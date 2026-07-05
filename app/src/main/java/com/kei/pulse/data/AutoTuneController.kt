package com.kei.pulse.data

import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.root.PerformanceCommandBuilder
import com.kei.pulse.root.RootSupport
import kotlin.math.abs

/**
 * AutoTDP — holds a **user-set FPS target at the least power**, by regulating each clock domain against
 * live FPS *and* frame pacing:
 *
 *  - The panel is pinned to its **max refresh** (by the service) for latency; AutoTDP then trims clocks
 *    so the frame rate settles at [targetFps] instead of running flat-out.
 *  - **Per CPU cluster, prime/big first** (then performance, then efficiency), and the **GPU last**: the
 *    prime cluster is the biggest power hog and least often the limiter, so it gives up its slack first
 *    (often "for free", with no FPS cost), then the GPU eases the rate down toward the target.
 *  - **Above** the target (with headroom) it trims; **below** the target it raises the binding domain
 *    (GPU first); **in-band** it holds. A trim that worsens **frame pacing** (more ≥33 ms hitches) is
 *    undone and that domain frozen one notch above its edge — the smoothness rail.
 *  - **Max** target (0) means uncapped: keep the clocks open, only thermal trims.
 *  - **Steady-render gate:** trimming + power-split learning only happen once the app has been
 *    *continuously* rendering for a few steps; a static/bursty screen (no FPS) holds and resets the gate.
 *  - Periodic **re-probe** clears the frozen set; a **stall** restores clocks fast; a **thermal** read
 *    forces a trim regardless.
 *
 * It pairs with the Balanced CPU governor (forced by the service), which scales frequency to load *under*
 * the caps. Device writes go through [writeCaps]/[releaseCaps], which reuse [PerformanceCommandBuilder]
 * — the same path as the manual tiers — so the caps are written and then **locked read-only (`chmod
 * 444`)**; otherwise the vendor perf/thermal daemon silently stomps `scaling_max_freq` back to full
 * (and the GPU's `min_pwrlevel` must be widened first or the cap snaps back). Unit tests inject no-ops
 * so the decision logic can be driven with FPS/jank sequences.
 *
 * **Aggressive park** (opt-in, [aggressivePark]): on heavy emulators the vendor perf HAL floors the
 * prime cluster's min frequency during gaming, so a frequency cap can't lower it. When the frame rate
 * is comfortably above target with the prime already trimmed to its floor (i.e. the prime isn't the
 * limiter), AutoTDP **offlines the prime cores** to drop their draw to zero — and re-onlines them the
 * instant FPS dips, jank rises, or it overheats/stalls. A cooldown after a failed park stops flapping.
 */
class AutoTuneController(
    private val writeCaps: (List<CpuPolicyInfo>, Map<Int, Int>) -> Unit = Companion::applyCapsToDevice,
    private val releaseCaps: (List<CpuPolicyInfo>) -> Unit = Companion::releaseCapsToDevice,
    private val setCoresOnline: (List<Int>, Boolean) -> Unit = Companion::setCoresOnlineToDevice,
) {

    /** Per-policy cap as a percent of that policy's max (CPU clusters + the synthetic GPU policy). */
    private val capPercent = HashMap<Int, Int>()

    /** The per-SoC power model this session learns into (loaded/persisted by the service). */
    var powerModel = PowerModel()

    /** Frame-rate target (fps) to regulate to; `0` = Max (uncapped). Set by the service per session. */
    var targetFps: Int = 0

    /** Opt-in: offline the prime cores when they aren't the limiter (set by the service per session). */
    var aggressivePark: Boolean = false

    /** Efficiency↔smoothness lean (set by the service per session; scales the margin gate + harvest). */
    var bias: AutoTdpBias = AutoTdpBias.EFFICIENT

    /** Odin-only power tuning: the watt cap ([powerCeilingW]) + the prime-walled settle. Both assume the Odin's
     *  tight chassis envelope and vendor-FLOORED prime. The SD 8 Gen 2 (Thor/RP6) has a scaling prime and a
     *  different chassis, so the service turns this OFF there (see [appliesOdinPowerTuning]); AutoTDP then just
     *  chases the target. The thermal ceiling stays on as a universal safety backstop regardless. */
    var wattCapAndSettleEnabled: Boolean = true

    private var fpsEma = 0f
    private var jankEma = 0f
    private var jankAtLastTrim = 0f
    private var lastTrimPolicy: Int? = null // policy id of the last single trim (feedback + learning)
    private var wBeforeTrim: Float? = null // draw (W) just before the last single-domain trim
    private val edged = HashSet<Int>() // policies whose further trimming costs frames/smoothness
    private var reprobeCounter = 0
    private var gpuIdleStreak = 0 // consecutive steps the 3D GPU is idle (media) — drops its harvest floor
    private var steadyCount = 0 // consecutive steps with real, continuous rendering
    private var primeParked = false // prime cores currently offlined
    private var parkSteady = 0 // consecutive steps eligible to park (hysteresis)
    private var parkCooldown = 0 // steps to wait before re-parking after a park hurt FPS
    private var jankAtPark = 0f
    private var bottleneck = Bottleneck.NONE // which domain is the limiter this step (Bug 6)
    private var cpuPeakPct = -1 // busiest PRIME-cluster core %, this step (park guard; -1 = unknown)
    private var cpuCorePeakPct = -1 // busiest single core across ALL CPUs (hot-thread bottleneck; -1 = unknown)
    private var sessionSettled = false // game has rendered continuously long enough that the initial load is done
    private var notRenderingStreak = 0 // consecutive not-rendering steps (in-emulator game switch / load detection)
    private var parkHurtCount = 0 // times parking cost frames this session (grows the re-park cooldown)
    private var fpsBeforeTrim = 0f // smoothed fps just before the last single trim (fps-regret backoff)
    private var pendingRaise: Int? = null // domain raised on spec, awaiting an fps response (clock-probe)
    private var fpsBeforeRaise = 0f // smoothed fps when [pendingRaise] was set
    private val raiseStalled = HashSet<Int>() // domains where raising stopped moving fps (content-capped)
    private val raiseStallStreak = HashMap<Int, Int>() // consecutive no-response probes per domain (confirm gate)
    private var raiseStallCounter = 0 // ticks since [raiseStalled] last cleared (slow re-probe)
    // Learned per-domain floor (cap %): ratchets UP when a trim hurts so a domain settles at its knee
    // instead of being re-trimmed to CPU_MIN every reprobe (the perf-cluster oscillation). Decays slowly.
    private val domainFloor = HashMap<Int, Int>()
    private var floorDecayCounter = 0
    // Recovery lockout: a *sustained* dip/roughness means we've harvested into — or a scene change caused —
    // a real stutter. Stop harvesting, snap the caps back up to the last smooth point, and raise the limiter
    // (the saturated GPU first) until frames recover; then a learned-floor bump + a short cooldown prevent
    // an immediate relapse (the TRIM↔RAISE flip-flop that left fps at 42–55 for ~90 s in the field log).
    private var gpuBusyPct = -1 // raw GPU busy% (saturation at current clock) this step (GPU-first recovery)
    private var cpuBusyPct = -1 // aggregate CPU load% this step (not-clock-bound harvest signal)
    private var drawWEma = -1f // smoothed system draw (W) on battery; -1 = charging/unknown (power ceiling off)
    private var primeBoundStreak = 0 // consecutive prime-bound below-target ticks → latches "target unreachable"
    private var primeBoundReleaseStreak = 0 // consecutive reach-target / bottleneck-shift ticks → releases the latch
    private var primeBoundLatched = false // settle is LATCHED on; a lucky fps spike can't knock it loose into a chase
    private var powerCooldown = 0 // ticks left suppressing fps-chase raises after the power ceiling fired (anti-bounce)
    private var frameTimeTailMs = -1f // worst present-to-present (ms) this step; -1 = unknown (margin gate)
    private var tailEma = 0f // EMA of the frametime tail; the gate acts on THIS (not the raw worst) so an
    // isolated emulator spike doesn't trip it — only a sustained rough tail does. Smoothing + thresholds are
    // bias-scaled (see [gateParams]): EFFICIENT smooths hard + high thresholds (ignore jitter, harvest);
    // SMOOTH reacts fast + low thresholds (protect frames).
    private var marginOverStreak = 0 // consecutive holding-but-tail-over-budget steps (pre-raise confirm)
    private var troubleStreak = 0 // consecutive rough ticks (toward engaging the lockout)
    private var recoverStreak = 0 // consecutive smooth ticks while locked (toward releasing it)
    private var jankLocked = false // in recovery: harvesting suppressed, raising to recover
    private var harvestCooldown = 0 // ticks to keep harvesting suppressed after a recovery
    private val lastGoodCaps = HashMap<Int, Int>() // caps at the last smooth, in-band operating point
    private val lockRaised = HashSet<Int>() // domains raised during this lockout (floor-pinned on exit)

    /** Which clock domain is the bottleneck — protected from trimming, prioritized for raising. */
    private enum class Bottleneck { NONE, CPU, GPU }

    /** From the busy% signals: the saturated domain (≥ [BOUND_THRESHOLD]); the higher one wins a tie. */
    private fun computeBottleneck(cpuBusy: Int?, gpuBusy: Int?, cpuCorePeak: Int?): Bottleneck {
        val g = gpuBusy ?: -1
        val c = cpuBusy ?: -1
        val pk = cpuCorePeak ?: -1
        val gpuBound = g >= BOUND_THRESHOLD
        // CPU-bound on AGGREGATE load, or on a single hot core: one pegged game/emulator thread eats most of
        // a frame's budget but reads low on the 8-core aggregate (~18%), so without the per-core signal the
        // limiter is invisible and PULSE raises the GPU (the wrong domain) while the perf cluster starves.
        val cpuBound = c >= BOUND_THRESHOLD || (pk >= CPU_CORE_BOUND && pk - c >= CPU_CORE_GAP)
        return when {
            gpuBound && (!cpuBound || g >= c) -> Bottleneck.GPU
            cpuBound -> Bottleneck.CPU
            else -> Bottleneck.NONE
        }
    }

    /** True if [p] belongs to the current bottleneck domain (so it shouldn't be trimmed for power). */
    private fun isBottleneck(p: CpuPolicyInfo): Boolean = when (bottleneck) {
        Bottleneck.GPU -> p.isGpu
        Bottleneck.CPU -> !p.isGpu
        Bottleneck.NONE -> false
    }

    /** True while the prime cores are offlined (for the overlay readout). */
    val isPrimeParked: Boolean get() = primeParked

    /** True while the prime-walled settle is latched (fps target confirmed unreachable) — for the diagnostic log. */
    val isPrimeWalled: Boolean get() = primeBoundLatched

    /** How many domains are currently judged content-capped (raising them didn't move fps) — for the log. */
    val stalledDomainCount: Int get() = raiseStalled.size

    /** The current bottleneck domain, for the diagnostic log ("CPU"/"GPU"/"-"). */
    val bottleneckLabel: String
        get() = when (bottleneck) {
            Bottleneck.CPU -> "CPU"
            Bottleneck.GPU -> "GPU"
            Bottleneck.NONE -> "-"
        }

    /** Read-only view of the current caps (policy id → percent) for the overlay readout. */
    val caps: Map<Int, Int> get() = capPercent.toMap()

    fun capFor(policyId: Int): Int = capPercent[policyId] ?: 100

    /** 0..100 confidence in the learned CPU/GPU power split, for the overlay "learning…" line. */
    fun learnedPercent(): Int =
        if (powerModel.hasSplit()) 100
        else (powerModel.splitSamples * 100 / PowerModel.MIN_SPLIT_SAMPLES).coerceIn(0, 99)

    fun reset() {
        capPercent.clear()
        fpsEma = 0f
        jankEma = 0f
        jankAtLastTrim = 0f
        lastTrimPolicy = null
        wBeforeTrim = null
        edged.clear()
        raiseStalled.clear()
        raiseStallStreak.clear()
        raiseStallCounter = 0
        domainFloor.clear()
        floorDecayCounter = 0
        pendingRaise = null
        reprobeCounter = 0
        gpuIdleStreak = 0
        steadyCount = 0
        primeParked = false
        parkSteady = 0
        parkCooldown = 0
        parkHurtCount = 0
        sessionSettled = false
        notRenderingStreak = 0
        bottleneck = Bottleneck.NONE
        gpuBusyPct = -1
        cpuBusyPct = -1
        drawWEma = -1f
        primeBoundStreak = 0
        primeBoundReleaseStreak = 0
        primeBoundLatched = false
        powerCooldown = 0
        troubleStreak = 0
        recoverStreak = 0
        jankLocked = false
        harvestCooldown = 0
        frameTimeTailMs = -1f
        tailEma = 0f
        marginOverStreak = 0
        lastGoodCaps.clear()
        lockRaised.clear()
        // Note: powerModel is intentionally preserved (it carries across sessions/apps).
    }

    /**
     * Warm-start from a previous session's converged caps for this app (Step 5 memory): apply them as
     * the starting point so the loop only has to fine-tune, not re-discover from full clocks.
     */
    fun warmStart(policies: List<CpuPolicyInfo>, savedCaps: Map<Int, Int>) {
        if (savedCaps.isEmpty()) return
        for (p in policies) {
            val saved = savedCaps[p.id] ?: continue
            capPercent[p.id] = saved.coerceIn(minFor(p), 100)
        }
        if (capPercent.isNotEmpty()) writeCaps(policies, caps)
    }

    /**
     * Advance one step and apply the caps if they changed.
     *
     * @param fps smoothed source frame rate (null/0 ⇒ not rendering → gate resets, holds)
     * @param jankFrames ≥33 ms presents in the last window (frame-pacing roughness)
     * @param drawW live system draw (W) on battery, null while charging/unknown — across a single-domain
     *   trim its delta is folded into [powerModel] to learn this SoC's CPU/GPU power split.
     * @param cpuBusyPercent AGGREGATE CPU load (0..100) — the CPU bottleneck signal (so one busy thread
     *   doesn't shield the whole CPU from trimming)
     * @param gpuBusyPercent raw GPU busy% (0..100) — the GPU bottleneck signal. Together these decide
     *   which domain is the limiter so AutoTDP protects it and only trims the one with slack (Bug 6).
     * @param cpuPeakPercent busiest single CPU core (0..100) — used only as a guard so aggressive park
     *   won't offline a prime that's running a pegged render thread.
     * @param worstFrameMs worst present-to-present interval (ms) in the last window — the frametime TAIL.
     *   The average rate can still read on-target while the tail creeps toward the frame budget right before a
     *   scene-transition misses; this lets the controller pre-empt the stutter instead of only reacting (via
     *   the jank lockout) after frames already dropped.
     */
    fun step(
        policies: List<CpuPolicyInfo>,
        fps: Float?,
        jankFrames: Int?,
        drawW: Float?,
        cpuTempC: Int?,
        gpuTempC: Int?,
        cpuBusyPercent: Int? = null,
        gpuBusyPercent: Int? = null,
        cpuPeakPercent: Int? = null,
        cpuCorePeakPercent: Int? = null,
        worstFrameMs: Float? = null,
    ): Decision {
        // Attribute the previous single-domain trim's draw delta before this step changes anything.
        val prev = lastTrimPolicy
        val before = wBeforeTrim
        if (prev != null && before != null && drawW != null) {
            val isGpu = policies.firstOrNull { it.id == prev }?.isGpu == true
            val domain = if (isGpu) PowerModel.Domain.GPU else PowerModel.Domain.CPU
            powerModel = powerModel.observe(domain, before - drawW)
        }

        // Steady-render gate: a static/bursty screen (no FPS) never gets tuned or learned from.
        if (fps == null || fps <= 0f) {
            // A SUSTAINED render gap = a new game/content loading in the same emulator ⇒ re-arm the settle
            // gate ("relearn on launch"). A brief 1-2 tick fps-read miss stays below the threshold so it
            // doesn't needlessly re-arm mid-game.
            notRenderingStreak++
            onRenderingStopped(policies, sustained = notRenderingStreak >= SESSION_RESET_TICKS)
            return Decision(Action.HOLD, caps)
        }
        notRenderingStreak = 0
        steadyCount++
        // Park gate: don't offline the prime until the game has actually settled into continuous play. A
        // load renders erratically — frames stall and stutter, which resets steadyCount above — so it can
        // never reach this run length; real gameplay (frame-capped or uncapped) sails past it. Latched for
        // the session so a mid-game stutter can't re-trigger the caution and block re-parking.
        if (steadyCount >= PARK_SETTLE_TICKS) sessionSettled = true
        fpsEma = if (fpsEma <= 0f) fps else fpsEma * (1f - FPS_ALPHA) + fps * FPS_ALPHA
        jankEma = jankEma * (1f - JANK_ALPHA) + (jankFrames ?: 0).toFloat() * JANK_ALPHA
        cpuPeakPct = cpuPeakPercent ?: -1
        cpuCorePeakPct = cpuCorePeakPercent ?: -1
        cpuBusyPct = cpuBusyPercent ?: -1
        gpuBusyPct = gpuBusyPercent ?: -1
        frameTimeTailMs = worstFrameMs ?: -1f
        if (frameTimeTailMs > 0f) {
            val a = gateParams().alpha
            tailEma = if (tailEma <= 0f) frameTimeTailMs else tailEma * (1f - a) + frameTimeTailMs * a
        }
        val rawBottleneck = computeBottleneck(cpuBusyPercent, gpuBusyPercent, cpuCorePeakPercent)
        // Hysteresis on a hot-thread CPU bottleneck: the hot core's load fluctuates around the detect
        // threshold, and a momentary dip to NONE used to (mis)raise the GPU first — which, on an emulator
        // wave that no clock fixes, climbed the GPU to max for nothing (burned ~11 W / 50 °C). Hold CPU while
        // the core stays moderately busy AND the GPU isn't itself saturated, so the wave keeps feeding the
        // perf cluster instead of over-raising the GPU. A real GPU bottleneck (gpuBusy high) still overrides.
        bottleneck = if (rawBottleneck == Bottleneck.NONE && bottleneck == Bottleneck.CPU &&
            (cpuCorePeakPercent ?: -1) >= CPU_CORE_HOLD && (gpuBusyPercent ?: -1) < BOUND_THRESHOLD
        ) {
            Bottleneck.CPU
        } else {
            rawBottleneck
        }

        // Idle-GPU floor: during media the 3D GPU sits idle (video decode is fixed-function, so gpuBusy≈0),
        // yet the vendor daemon pins the clock to whatever ceiling we allow. Once the GPU has been idle for a
        // sustained stretch, let it harvest below the responsive gaming floor toward its true min (≈160 MHz).
        // The streak's hysteresis keeps games (gpuBusy fluctuates) on the higher floor; on the first sign of
        // real GPU work the floor — and the cap — snap back up so a heavy scene can still ramp quickly.
        policies.firstOrNull { it.isGpu }?.let { gpu ->
            val gpuIdle = (gpuBusyPercent ?: 100) <= GPU_IDLE_BUSY && bottleneck != Bottleneck.GPU
            gpuIdleStreak = if (gpuIdle) gpuIdleStreak + 1 else 0
            if (gpuIdleStreak == 0 && (capPercent[gpu.id] ?: 100) in 1 until GPU_MIN_PERCENT) {
                capPercent[gpu.id] = GPU_MIN_PERCENT // re-activation: restore the responsive floor at once
            }
        }

        // Smooth the system draw for the power ceiling: a single-tick spike must not trim, but a sustained
        // over-budget must. Stands down (-1) while charging/unknown so the ceiling can't act on a bad signal.
        drawWEma = when {
            drawW == null || drawW <= 0f -> -1f
            drawWEma < 0f -> drawW
            else -> drawWEma * (1f - DRAW_ALPHA) + drawW * DRAW_ALPHA
        }
        val steady = steadyCount >= STEADY_TICKS
        val action = decide(policies, fpsEma, jankEma, cpuTempC, gpuTempC, steady)
        wBeforeTrim = if (action == Action.TRIM) drawW else null
        if (action != Action.HOLD) writeCaps(policies, caps)
        handlePrimeParking(policies, fpsEma, jankEma, cpuTempC, gpuTempC, steady, action)
        return Decision(action, caps)
    }

    /**
     * Offline the prime cores when they're clearly not the limiter and let perf + GPU do the fine
     * frequency stepping. The prime can't be frequency-scaled below the vendor floor (~3.28 GHz) mid-game,
     * so offlining is the *only* way to cut its power — park it when AutoTDP has harvested its cap to the
     * floor, fps is holding the target, the prime cores themselves are idle, and it's cool/steady. This is
     * NOT gated on battery: AutoTDP means efficiency regardless of power source (parking also sheds heat).
     * Re-online the moment anything goes wrong; a cooldown after a park that hurt fps prevents flapping.
     */
    /**
     * The rendering-stopped safety block, shared by [step]'s not-rendering early return and the screen-off
     * pause ([pauseForScreenOff]). Safety: never hold the prime cores offline while nothing is rendering —
     * a prime parked just before rendering stopped (an in-emulator game switch, a load screen, a pause,
     * the display turning off) would otherwise stay offlined until the foreground PACKAGE changed — which
     * never happens inside a multi-game emulator — stranding controller/input threads on the dead cores.
     * Re-onlining is always the safe direction (parking re-arms once continuous rendering resumes).
     * [sustained] additionally re-arms the settle gate + drops the per-session park penalties so the next
     * render session re-learns its park decision from scratch.
     */
    private fun onRenderingStopped(policies: List<CpuPolicyInfo>, sustained: Boolean) {
        steadyCount = 0
        lastTrimPolicy = null
        wBeforeTrim = null
        bottleneck = Bottleneck.NONE
        if (primeParked) {
            setCoresOnline(primeCores(policies), true)
            primeParked = false
            parkSteady = 0
        }
        if (sustained) {
            sessionSettled = false
            parkCooldown = 0
            parkHurtCount = 0
        }
    }

    /**
     * Screen-off hand-off for the watcher's TOTAL-sleep gate: [step] stops being called entirely while the
     * display is off, so run the rendering-stopped safety NOW — re-online a parked prime (never leave cores
     * offline unattended) and re-arm the settle gate as a sustained gap would, so wake re-decides parking
     * from scratch. Session identity and the learned power model are untouched; stepping resumes on the
     * first screen-on tick.
     */
    fun pauseForScreenOff(policies: List<CpuPolicyInfo>) {
        notRenderingStreak = 0
        onRenderingStopped(policies, sustained = true)
    }

    private fun handlePrimeParking(
        policies: List<CpuPolicyInfo>,
        fps: Float,
        jank: Float,
        cpuTempC: Int?,
        gpuTempC: Int?,
        steady: Boolean,
        action: Action,
    ) {
        val cores = primeCores(policies)
        if (cores.isEmpty()) return
        val thermal = (cpuTempC ?: 0) >= THERMAL_C || (gpuTempC ?: 0) >= THERMAL_C
        val holding = fps >= targetFps * TARGET_HYST_LO // at/above target (works under a frame cap)
        if (primeParked) {
            val hurt = !holding // parking cost frames ⇒ the prime was needed after all
            val unpark = !aggressivePark || thermal || targetFps <= 0 ||
                hurt || jank > jankAtPark + JANK_TOL || action == Action.RAISE
            if (unpark) {
                setCoresOnline(cores, true)
                primeParked = false
                parkSteady = 0
                if (hurt) {
                    // A game that keeps needing the prime gets an ever-longer re-park cooldown, so the
                    // dip-on-unpark stops recurring (bursty emulators) instead of flapping every minute.
                    parkHurtCount++
                    parkCooldown = (PARK_COOLDOWN_TICKS * parkHurtCount).coerceAtMost(PARK_COOLDOWN_MAX)
                }
            }
            return
        }
        if (parkCooldown > 0) {
            parkCooldown--
            parkSteady = 0
            return
        }
        val prime = trimOrder(policies).firstOrNull { !it.isGpu }
        val primeAtFloor = prime != null && (capPercent[prime.id] ?: 100) <= minFor(prime)
        // Never park a prime whose own cores are still busy (cpuPeakPct = prime-cluster peak; unknown ⇒ allow).
        val primeNotPegged = cpuPeakPct < 0 || cpuPeakPct < PARK_CPU_PEAK_MAX
        // Don't park until the game has settled into continuous play (sessionSettled). During the initial
        // load the screen renders erratically — stalls and stutters — with the prime idle, which otherwise
        // looks parkable and only slows the load.
        val eligible = aggressivePark && steady && targetFps > 0 && !thermal && sessionSettled &&
            bottleneck != Bottleneck.CPU && primeNotPegged &&
            holding && jank <= PARK_JANK_MAX && primeAtFloor
        parkSteady = if (eligible) parkSteady + 1 else 0
        if (parkSteady >= PARK_TICKS) {
            setCoresOnline(cores, false)
            primeParked = true
            jankAtPark = jank
            parkSteady = 0
        }
    }

    /** The prime cluster's offlinable cores (its cpuIds, never cpu0). */
    private fun primeCores(policies: List<CpuPolicyInfo>): List<Int> =
        trimOrder(policies).firstOrNull { !it.isGpu }?.cpuIds?.filter { it != 0 }.orEmpty()

    /** Mutates the caps/edges and returns what it did. No device I/O here (that's [writeCaps]). */
    // Bottleneck awareness (Bug 6): which domain to trim/raise is decided by [bottleneck] (set in step
    // from live busy%). trimNext skips the bound domain; raiseNext prioritizes it. This keeps a GPU-bound
    // game's GPU up (no oscillation to the floor) and trims the CPU that actually has slack instead.
    private fun decide(
        policies: List<CpuPolicyInfo>,
        fps: Float,
        jank: Float,
        cpuTempC: Int?,
        gpuTempC: Int?,
        steady: Boolean,
    ): Action {
        if (++reprobeCounter >= REPROBE_TICKS) {
            reprobeCounter = 0
            edged.clear()
        }
        // Re-probe content-capped domains far less often than trim edges — clearing this every trim cycle
        // would re-probe a video every few seconds and waste power. Content does change though (a clip ends,
        // a cutscene starts), so re-test roughly once a minute.
        if (++raiseStallCounter >= RAISE_STALL_REPROBE_TICKS) {
            raiseStallCounter = 0
            raiseStalled.clear()
            // NB: keep raiseStallStreak across the reprobe. A domain that's still content-capped (video)
            // re-stalls on the very next no-response probe instead of paying the full confirm again, so the
            // reprobe stays a cheap 1-probe re-test; a domain whose content changed responds and resets.
        }
        // Slowly relax each learned floor by a notch so a domain re-tests whether the game's demand dropped
        // (a lighter scene). Far slower than the trim reprobe, so it doesn't re-introduce the bounce.
        if (++floorDecayCounter >= FLOOR_DECAY_TICKS) {
            floorDecayCounter = 0
            domainFloor.entries.forEach { e ->
                policies.firstOrNull { it.id == e.key }?.let { p ->
                    e.setValue((e.value - stepFor(p)).coerceAtLeast(minFor(p)))
                }
            }
        }
        if (harvestCooldown > 0) harvestCooldown--
        if (powerCooldown > 0) powerCooldown--
        // Prime-WALLED LATCH: track whether the fps target is structurally unreachable — below target, a single
        // core PEGGED (the vendor-floored prime gating each frame), and the GPU has headroom (so it's not GPU-
        // bound: raising the GPU wouldn't help). Key off the RAW pegged-core + GPU-headroom signals, NOT the
        // aggregate bottleneck label (it flickers CPU/GPU/NONE on this noisy signal and never let the latch
        // build). Engage after [PRIME_BOUND_CONFIRM] such ticks; RELEASE only after [PRIME_BOUND_RELEASE] ticks
        // of genuinely reaching the FULL target (a lone 58-fps near-miss must NOT release it — that flutter was
        // the bug). Once latched the settle below outranks the recovery lockout, which otherwise fires forever on
        // a chronically-rough prime-walled game (jankEma ≫ LOCK_JANK) and pumps heat raising clocks that can't
        // lift a prime-gated frame.
        val reachedTarget = targetFps <= 0 || fps >= targetFps
        val below = targetFps > 0 && fps < targetFps * TARGET_HYST_LO
        // Gated to Odin power tuning: the SD 8 Gen 2's prime SCALES, so it must keep chasing (raising) — never
        // settle. With the flag off, primeWalled is always false ⇒ the latch never engages and both settle
        // short-circuits below are skipped.
        // The core must be TRULY PEGGED (>= CPU_CORE_WALL ~90), not merely busy: a light game (Megabonk) whose
        // hottest core sits at 66–81 % is NOT prime-gated — raising its clocks DOES lift fps — so it must keep
        // chasing/holding 60. Only a near-pegged core (Stray's vendor-floored Box64 thread, 95–100) is the real
        // wall the settle exists for. CPU_CORE_BOUND (65) is the bottleneck-detection bar; the settle needs the
        // stricter "actually maxed" bar or it strangles a cool 5 W / 44 °C mid-range game into GPU oscillation.
        val primeWalled = wattCapAndSettleEnabled &&
            below && cpuCorePeakPct >= CPU_CORE_WALL && gpuBusyPct in 0 until BOUND_THRESHOLD
        if (primeWalled) {
            primeBoundStreak = (primeBoundStreak + 1).coerceAtMost(PRIME_BOUND_CONFIRM)
            if (primeBoundStreak >= PRIME_BOUND_CONFIRM) primeBoundLatched = true
            primeBoundReleaseStreak = 0
        } else if (reachedTarget) {
            primeBoundReleaseStreak++
            if (primeBoundReleaseStreak >= PRIME_BOUND_RELEASE) {
                primeBoundLatched = false
                primeBoundStreak = 0
            }
        } // else: below target but GPU-bound / flicker — preserve both streaks
        // Absolute thermal trip (last resort): shed heat ANY way, including the maybe-no-op prime — ignoring
        // edges AND learned floors (a floor pinned while chasing must not block the safety trim).
        if ((cpuTempC ?: 0) >= HARD_THERMAL_C || (gpuTempC ?: 0) >= HARD_THERMAL_C) {
            lastTrimPolicy = null
            return if (trimNext(policies, ignoreEdges = true, ignoreFloor = true) != null) Action.TRIM else Action.HOLD
        }
        // Bias thermal CEILING (cascade backstop): hold the chip at/under the ceiling by trimming the REAL heat
        // levers (perf+GPU, NOT the vendor-floored prime), OVERRIDING the fps target. Set ABOVE the prime floor
        // (EFFICIENT 82 / BALANCED 84 / SMOOTH 87) so the fan gets first crack at the heat and the GPU isn't
        // strangled below its floor. Normal games run cool and never hit it.
        val ceilingC = gateParams().ceilingC
        if ((cpuTempC ?: 0) >= ceilingC || (gpuTempC ?: 0) >= ceilingC) {
            lastTrimPolicy = null
            return if (thermalTrim(policies) != null) Action.TRIM else Action.HOLD
        }
        // Bias POWER ceiling (the PRIMARY lever for the Odin's tiny chassis ~13–14 W envelope): cap the CAUSE
        // of heat (watts) ahead of the lagging temp. Hold draw under the envelope by trimming perf+GPU (skip
        // the prime — its draw can't be capped). Acts on the SMOOTHED draw so a single-tick spike doesn't trim;
        // stands down while charging (drawWEma -1) — the thermal ceiling above is the backstop then.
        val powerCeilingW = gateParams().powerCeilingW
        if (wattCapAndSettleEnabled && drawWEma > 0f && drawWEma > powerCeilingW) {
            powerCooldown = POWER_COOLDOWN_TICKS // hold off chase-raises for a few ticks so we don't bounce back over
            lastTrimPolicy = null
            return if (thermalTrim(policies) != null) Action.TRIM else Action.HOLD
        }
        // LATCHED prime-walled settle — placed ABOVE the recover/lockout/raise machinery on purpose. On a
        // chronically-rough prime-walled game the recovery lockout (jankEma ≫ LOCK_JANK) would fire EVERY tick
        // and pump heat raising clocks that can't lift a prime-gated frame — the raise→heat→thermal-trim→cool→
        // raise oscillation. When we've confirmed the wall AND the GPU isn't currently the bottleneck, just hold
        // (harvest the idle GPU) and let the thermal/power ceilings above shed heat. If the GPU IS saturated this
        // tick (a GPU-heavy moment), fall through so the normal logic can raise the real bottleneck.
        if (primeBoundLatched && gpuBusyPct in 0 until BOUND_THRESHOLD) {
            return primeBoundSettle(policies, fps, jank, steady)
        }
        // Genuine stall (loading spike / very heavy scene): restore clocks fast and re-learn floors fresh.
        if (fps < RECOVER_FLOOR_FPS) {
            edged.clear()
            raiseStalled.clear()
            raiseStallStreak.clear()
            domainFloor.clear()
            pendingRaise = null
            lastTrimPolicy = null
            return if (raiseAll(policies)) Action.RAISE else Action.HOLD
        }
        // Max target = uncapped: keep the clocks open; only the thermal guard above ever trims.
        if (targetFps <= 0) {
            lastTrimPolicy = null
            return if (raiseAll(policies)) Action.RAISE else Action.HOLD
        }
        val lo = targetFps * TARGET_HYST_LO
        // Recovery lockout — the jank rail made *sustained*. One bad trim is caught by the single-undo rails
        // just below; a dip or roughness that persists for ≥ LOCK_ENTER_TICKS is a real stutter (we harvested
        // too far, or the scene got heavier). Then: stop harvesting, snap the caps up to the last smooth point
        // in one move ([enterLockout]), and raise the limiter — the saturated GPU first, the exact case a
        // hot-thread bn=CPU used to mis-serve by feeding the CPU while the GPU sat starved — until frames hold
        // for LOCK_RECOVER_TICKS. On release, pin the raised domains' floors and suppress harvesting for a
        // cooldown so the loop can't immediately dig back into the same stutter.
        // A real stutter shows up as frame-pacing *roughness* (uneven frame times), not merely a low rate.
        // Steady low-fps content — a 24 fps video under a 30 target — paces evenly (jank≈0), so it stays on
        // the harvest path and isn't mistaken for a clock-starved game. Triggering on fps-below-target alone
        // would conflate the two and pin clocks chasing an unreachable rate.
        val rough = jank >= LOCK_JANK
        if (jankLocked) {
            recoverStreak = if (rough) 0 else recoverStreak + 1
            if (recoverStreak < LOCK_RECOVER_TICKS) {
                lastTrimPolicy = null
                return if (recoverRaise(policies)) Action.RAISE else Action.HOLD
            }
            lockRaised.forEach { id -> capPercent[id]?.let { domainFloor[id] = maxOf(domainFloor[id] ?: 0, it) } }
            lockRaised.clear()
            jankLocked = false
            troubleStreak = 0
            harvestCooldown = LOCK_COOLDOWN_TICKS
            // fall through into the normal logic; the cooldown suppresses trims for the next few ticks
        } else {
            troubleStreak = if (rough) troubleStreak + 1 else 0
            if (troubleStreak >= LOCK_ENTER_TICKS) {
                enterLockout(policies)
                lastTrimPolicy = null
                return if (recoverRaise(policies)) Action.RAISE else Action.HOLD
            }
        }
        // Smoothness rail: if the last trim worsened frame pacing, undo it, freeze it, and raise its floor.
        val last = lastTrimPolicy
        if (last != null && jank > jankAtLastTrim + JANK_TOL) {
            raiseAndLearnFloor(policies, last)
            lastTrimPolicy = null
            return Action.RAISE
        }
        // FPS-regret rail: a trim that actually dropped frames (it WAS load-bearing) gets the same handling.
        if (last != null && fps < fpsBeforeTrim - fpsBeforeTrim * FPS_REGRET_FRAC) {
            raiseAndLearnFloor(policies, last)
            lastTrimPolicy = null
            return Action.RAISE
        }
        // At/above target (overshooting, or holding under the Game-Mode cap): harvest the slack. Hill-climb
        // — trim the next non-bottleneck domain a notch; the rails above back it off if it costs frames.
        if (steady && fps >= lo) {
            // Remember this smooth, in-band point so a later stutter can snap straight back to it.
            if (jank < LOCK_JANK) {
                lastGoodCaps.clear()
                lastGoodCaps.putAll(capPercent)
            }
            pendingRaise = null // climbed back into the band; any pending clock-probe is resolved
            // Frametime-margin early-warning: the AVERAGE rate still holds the target, but the worst-frame
            // TAIL is creeping toward the frame budget — a scene-transition is starting to miss before the
            // average collapses (and before jank accumulates enough to trip the recovery lockout). When a real
            // clock domain is saturated, PRE-RAISE it (predictive recovery; the lockout stays the backstop);
            // when nothing is saturated, raising can't help (emulator/IO overhead) so just stop harvesting.
            // Either way, never harvest deeper while the tail is tight.
            val gate = gateParams()
            val budgetMs = if (targetFps > 0) 1000f / targetFps else null
            val tail = tailEma.takeIf { it > 0f }
            val overBudget = budgetMs != null && tail != null && tail >= budgetMs * gate.overFrac
            val tailTight = budgetMs != null && tail != null && tail >= budgetMs * gate.nearFrac
            marginOverStreak = if (overBudget) marginOverStreak + 1 else 0
            if (marginOverStreak >= gate.confirm && bottleneck != Bottleneck.NONE) {
                marginOverStreak = 0
                lastTrimPolicy = null
                if (raiseNext(policies, raiseStalled) != null) return Action.RAISE
            }
            if (harvestCooldown == 0 && !tailTight) {
                val trimmed = trimNext(policies, ignoreEdges = false)
                if (trimmed != null) {
                    lastTrimPolicy = trimmed
                    jankAtLastTrim = jank
                    fpsBeforeTrim = fps
                    return Action.TRIM
                }
            }
        }
        // Below the target. Don't assume low fps means "needs more clock" — *probe* and watch the response.
        if (fps < lo) {
            // Resolve the last clock-probe: did raising that domain actually move fps?
            val probe = pendingRaise
            var probeHelped = false
            if (probe != null) {
                pendingRaise = null
                val needed = maxOf(RAISE_RESPONSE_FPS, fpsBeforeRaise * RAISE_RESPONSE_FRAC)
                if (fps <= fpsBeforeRaise + needed) {
                    // No response THIS probe — hand back the clock that did nothing (as before). But don't
                    // *latch* "content-capped" on a single bad probe: in a high-fps-variance game a wave can
                    // land on the probe and mask a real gain, which used to harvest into a stutter and stay
                    // stuck. Require the no-response SUSTAINED; any probe that moves fps resets the streak. A
                    // truly hard-capped video reads no response every time, so it still latches within a few.
                    trimOne(policies, probe)
                    lastTrimPolicy = null
                    val stall = (raiseStallStreak[probe] ?: 0) + 1
                    raiseStallStreak[probe] = stall
                    if (stall >= RAISE_STALL_CONFIRM) {
                        raiseStalled.add(probe)
                        raiseStallStreak.remove(probe)
                    }
                    return Action.TRIM
                } else {
                    raiseStallStreak.remove(probe) // it moved fps — reset; climb another notch below.
                    probeHelped = true
                }
            }
            // Prime-walled & target UNREACHABLE (pre-latch fallback): the GPU has headroom and a pegged core
            // gates the frame, so chasing only pumps heat. SETTLE structurally (the latch above handles it once
            // confirmed; this catches the first ticks if execution reaches here before the latch engages).
            if (primeWalled) return primeBoundSettle(policies, fps, jank, steady)
            // Not clock-bound AND hot: below target with NO domain saturated (every busy% below the
            // bottleneck line ⇒ each has idle headroom ⇒ none is clock-starved), no probe just moved fps, and
            // the SoC is warm (≥ THERMAL_SOFT_C, just under the hard 85 °C trip). Raising a non-bottleneck to
            // full clock can't lift the rate (the limit is emulator overhead / a frame cap) and only adds
            // heat, which throttles fps *further* — the field log's CPU pinned at 3.5 GHz / 90 °C while only
            // 42 % busy, fps stuck ~50. Harvest toward the floor to shed heat at no fps cost. (Cool-and-not-
            // bound is left to the probe/content-cap path below, which still climbs when raising DOES help.)
            val hot = (cpuTempC ?: 0) >= THERMAL_SOFT_C || (gpuTempC ?: 0) >= THERMAL_SOFT_C
            if (!probeHelped && hot && (cpuBusyPct >= 0 || gpuBusyPct >= 0)) {
                // Thermally bound below target: the last clock-probe didn't move fps and the SoC is warm.
                // Raising further just hits the 85 °C wall and throttles — or, on a maxed Box64 single thread /
                // vendor-floored prime, does nothing — which is Stray's 18 W / 91 °C chase. STOP pumping: harvest
                // the NON-bottleneck toward its hard floor (ignoreFloor — past any floor pinned while chasing) to
                // shed heat, and return here so we never reach the raise below — that's what breaks the pump cycle
                // while the SoC stays warm (no need to stall, which would unprotect the bottleneck). The pegged
                // CPU is left alone; the hard 85 °C guard trims it if heat keeps climbing. (This previously
                // required NO bottleneck, so a maxed-CPU emulated game — exactly Stray — was never caught.)
                pendingRaise = null
                if (steady && harvestCooldown == 0) {
                    val trimmed = trimNext(policies, ignoreEdges = false, ignoreFloor = true)
                    if (trimmed != null) {
                        lastTrimPolicy = trimmed
                        jankAtLastTrim = jank
                        fpsBeforeTrim = fps
                        return Action.TRIM
                    }
                }
                return Action.HOLD
            }
            // Don't raise INTO the power ceiling: within [POWER_RAISE_FRAC] of the watt envelope — OR during the
            // [powerCooldown] right after the cap fired — more clock just overheats for frames the chassis can't
            // sustain (the power rail above would trim it right back ⇒ oscillation). Hold at the budget. The
            // cooldown is the sticky part: it suppresses the chase even once the smoothed draw dips back under,
            // which is what stops the raise→spike→trim bounce. Safety raises (stutter recovery) above aren't gated.
            if (wattCapAndSettleEnabled && drawWEma > 0f &&
                (powerCooldown > 0 || drawWEma >= gateParams().powerCeilingW * POWER_RAISE_FRAC)) {
                lastTrimPolicy = null
                return Action.HOLD
            }
            val raised = raiseNext(policies, raiseStalled)
            if (raised != null) {
                edged.remove(raised)
                pendingRaise = raised
                fpsBeforeRaise = fps
                lastTrimPolicy = null
                return Action.RAISE
            }
            // Raising is exhausted (everything raiseable is maxed or already proven useless) yet fps is
            // still below target ⇒ content-/externally-capped, not clock-starved. Mark every domain
            // content-capped so the harvest can trim even a high-load one (e.g. a video-compositing GPU that
            // reads as the "bottleneck"), then harvest toward the floor. A trim that really costs frames hits
            // the fps-regret rail above and is undone — so a genuinely maxed-but-too-heavy game stays pinned.
            if (steady && harvestCooldown == 0) {
                policies.forEach { raiseStalled.add(it.id) }
                val trimmed = trimNext(policies, ignoreEdges = false)
                if (trimmed != null) {
                    lastTrimPolicy = trimmed
                    jankAtLastTrim = jank
                    fpsBeforeTrim = fps
                    return Action.TRIM
                }
            }
        }
        lastTrimPolicy = null
        return Action.HOLD
    }

    /**
     * Prime-bound settle: the target is unreachable because a single core (the vendor-floored prime) gates each
     * frame. Never raise into that wall; harvest the idle GPU toward saturation (no fps cost — fps is CPU-gated)
     * so it sheds watts/heat. Stops trimming once the GPU nears saturation ([GPU_SETTLE_BUSY]) so it never digs
     * into a stutter. The over-raised CPU clusters are brought down by the power ceiling, not here (trimming the
     * bn=CPU clusters directly is bottleneck-protected); this just stops the chase and harvests the wasted GPU.
     */
    private fun primeBoundSettle(policies: List<CpuPolicyInfo>, fps: Float, jank: Float, steady: Boolean): Action {
        pendingRaise = null
        if (steady && harvestCooldown == 0 && gpuBusyPct in 0 until GPU_SETTLE_BUSY) {
            val g = policies.firstOrNull { it.isGpu }
            if (g != null && (capPercent[g.id] ?: 100) > effectiveMin(g)) {
                trimOne(policies, g.id)
                lastTrimPolicy = g.id
                jankAtLastTrim = jank
                fpsBeforeTrim = fps
                return Action.TRIM
            }
        }
        lastTrimPolicy = null
        return Action.HOLD
    }

    /**
     * Trim the first still-trimmable policy in prime→efficiency→GPU order, **skipping the bottleneck
     * domain** (its load-gated floor is its current cap — don't starve the limiter). [ignoreEdges] (used
     * by the thermal guard) also ignores the bottleneck skip, since shedding heat outranks it. A domain
     * that's been *proven* content-capped ([raiseStalled] — raising it didn't move fps) is no longer
     * protected as a bottleneck: that's how a high-load-but-clock-insensitive GPU (video compositing) still
     * gets harvested. If trimming it really does cost frames, the fps-regret rail undoes and freezes it.
     *
     * NB: trim order is intentionally prime→perf→GPU, not watt-ranked. Two proven behaviors depend on it:
     * the prime must reach its floor FAST so it can *park* (its only real power lever — frequency-capping it
     * is futile mid-game), and the GPU stays *last* as an fps-safety hedge (it's the common bottleneck). A
     * pure watt-rank trims the GPU first (one big domain) and starves both. The learned CPU/GPU split is
     * used where it's safe — the battery/EST-PK estimate — not to reorder live trims.
     */
    private fun trimNext(
        policies: List<CpuPolicyInfo>,
        ignoreEdges: Boolean,
        ignoreFloor: Boolean = false,
    ): Int? {
        for (p in trimOrder(policies)) {
            val protectedBottleneck = isBottleneck(p) && p.id !in raiseStalled
            if (!ignoreEdges && (p.id in edged || protectedBottleneck)) continue
            val cur = capPercent[p.id] ?: 100
            // [ignoreFloor] drops to the HARD min, bypassing the learned [domainFloor] — used when shedding
            // heat outranks the learned knee (the thermal guard / thermal-bound harvest). Otherwise the floors
            // pinned while chasing an unreachable target would block the safety trim (the Stray 91°C runaway).
            val min = if (ignoreFloor) minFor(p) else effectiveMin(p)
            if (cur > min) {
                capPercent[p.id] = (cur - stepFor(p)).coerceAtLeast(min)
                return p.id
            }
        }
        return null
    }

    /** Raise the first still-capped policy, **bottleneck domain first**, skipping [skip] (stalled probes). */
    private fun raiseNext(policies: List<CpuPolicyInfo>, skip: Set<Int> = emptySet()): Int? {
        for (p in raiseOrder(policies)) {
            if (p.id in skip) continue
            val cur = capPercent[p.id] ?: 100
            if (cur < 100) {
                capPercent[p.id] = (cur + stepFor(p)).coerceAtMost(100)
                return p.id
            }
        }
        return null
    }

    /** Trim a specific policy one notch (used to hand back a clock-probe that didn't move fps). */
    private fun trimOne(policies: List<CpuPolicyInfo>, id: Int) {
        val p = policies.firstOrNull { it.id == id } ?: return
        capPercent[id] = ((capPercent[id] ?: 100) - stepFor(p)).coerceAtLeast(effectiveMin(p))
    }

    /** Raise priority: the bottleneck domain first; otherwise GPU→efficiency→prime (reverse trim order). */
    private fun raiseOrder(policies: List<CpuPolicyInfo>): List<CpuPolicyInfo> {
        val cpus = trimOrder(policies).filterNot { it.isGpu } // prime → efficiency
        val gpu = policies.filter { it.isGpu }
        return when (bottleneck) {
            Bottleneck.GPU -> gpu + cpus.asReversed()
            Bottleneck.CPU -> cpus + gpu
            Bottleneck.NONE -> trimOrder(policies).asReversed()
        }
    }

    private fun raiseOne(policies: List<CpuPolicyInfo>, id: Int) {
        val p = policies.firstOrNull { it.id == id } ?: return
        capPercent[id] = ((capPercent[id] ?: 100) + stepFor(p)).coerceAtMost(100)
    }

    /**
     * Undo a hurtful trim of [id] (freeze + raise a notch) AND pin its **learned floor** at the recovered
     * level, so future trims/reprobes won't take it back below its knee — this is what stops the perf
     * cluster bouncing to CPU_MIN every reprobe on a game that needs the clock in its heavy moments.
     */
    private fun raiseAndLearnFloor(policies: List<CpuPolicyInfo>, id: Int) {
        edged.add(id)
        raiseOne(policies, id)
        val recovered = capPercent[id] ?: return
        domainFloor[id] = maxOf(domainFloor[id] ?: 0, recovered)
    }

    /**
     * Climb out of a sustained stutter by raising the limiter — the **saturated GPU first** (when gpuBusy is
     * pegged), exactly the case a hot-thread bn=CPU used to mis-serve by feeding the CPU while the GPU sat
     * starved; otherwise the normal bottleneck-first order. Content-cap latches are ignored here (a stutter
     * means they may be stale). Records what it raised so the floor can be pinned on recovery; returns false
     * once everything is already maxed (a genuinely too-heavy scene — hold at full clocks until it eases).
     */
    private fun recoverRaise(policies: List<CpuPolicyInfo>): Boolean {
        val gpu = policies.firstOrNull { it.isGpu }
        if (gpu != null && gpuBusyPct >= BOUND_THRESHOLD && (capPercent[gpu.id] ?: 100) < 100) {
            raiseOne(policies, gpu.id)
            lockRaised.add(gpu.id)
            return true
        }
        val raised = raiseNext(policies)
        if (raised != null) {
            lockRaised.add(raised)
            return true
        }
        return false
    }

    /**
     * Engage the recovery lockout: **snap the caps up to the last smooth operating point** in one move
     * (undoes a multi-notch over-harvest that crept into a stutter; never snaps *down*, since a heavier
     * scene needs more, not less — [recoverRaise] climbs from there). A stutter invalidates the harvest
     * assumptions, so edges, content-cap latches and any pending probe are cleared too.
     */
    private fun enterLockout(policies: List<CpuPolicyInfo>) {
        jankLocked = true
        recoverStreak = 0
        lockRaised.clear()
        for (p in policies) {
            val good = lastGoodCaps[p.id] ?: continue
            if (good > (capPercent[p.id] ?: 100)) {
                capPercent[p.id] = good
                lockRaised.add(p.id)
            }
        }
        edged.clear()
        raiseStalled.clear()
        raiseStallStreak.clear()
        pendingRaise = null
    }

    private fun raiseAll(policies: List<CpuPolicyInfo>): Boolean {
        var changed = false
        for (p in policies) {
            val cur = capPercent[p.id] ?: 100
            val raised = (cur + stepFor(p)).coerceAtMost(100)
            if (raised != cur) {
                capPercent[p.id] = raised
                changed = true
            }
        }
        return changed
    }

    /** CPU clusters prime(highest max freq)→efficiency, then the GPU. */
    private fun trimOrder(policies: List<CpuPolicyInfo>): List<CpuPolicyInfo> =
        policies.sortedWith(compareBy({ it.isGpu }, { -it.selectableMaxFreq }))

    private fun minFor(p: CpuPolicyInfo): Int = when {
        !p.isGpu -> CPU_MIN_PERCENT
        gpuIdleStreak >= GPU_IDLE_TICKS -> GPU_IDLE_MIN_PERCENT // sustained idle (media): harvest to ~160 MHz
        else -> GPU_MIN_PERCENT
    }
    private fun stepFor(p: CpuPolicyInfo): Int = if (p.isGpu) GPU_STEP else CPU_STEP

    /** Per-[bias] tuning: margin-gate (tail-EMA α + suppress/pre-raise thresholds × budget + confirm) AND the
     *  thermal CEILING (°C) AutoTDP holds by trimming perf+GPU, overriding the fps target. EFFICIENT smooths
     *  hard + high gate thresholds + a LOW (quiet) ceiling; SMOOTH the reverse (responsive + a higher ceiling). */
    internal data class GateParams(
        val alpha: Float,
        val nearFrac: Float,
        val overFrac: Float,
        val confirm: Int,
        val ceilingC: Int,
        val powerCeilingW: Float,
    )
    private fun gateParams(): GateParams = gateParamsFor(bias)

    /** [gateParams] for an explicit [b] — exposed (internal, no behavior change) so unit tests can pin the
     *  per-bias values AND the EFFICIENT→SMOOTH monotonic gradient without driving a full session. Pure: reads
     *  nothing but [b]. The live decision path always goes through [gateParams] (= `gateParamsFor(bias)`). */
    internal fun gateParamsFor(b: AutoTdpBias): GateParams = when (b) {
        // ceilingC sits ABOVE the chip's uncoolable prime floor (~80 °C on the Odin under heavy load) so the
        // trim is REACHABLE — a ceiling below the floor (the old EFFICIENT 74) saturates: it strangles the GPU
        // to its floor forever (Stray 60→42 fps) for ~0 thermal/power gain. Above the floor the ceiling is a
        // genuine cascade backstop: the fan (target ≤ ceiling − [AUTOTDP_FAN_CASCADE_GAP_C]) takes the first
        // bite, clocks only trim when it can't hold. Band width (floor→ceiling) = the bias's noise↔fps trade.
        // powerCeilingW (the sustained draw each mode holds) is the PRIMARY lever — see [powerCeilingW]; over
        // the chassis envelope, heat outruns cooling regardless of fan. ceilingC is the thermal backstop.
        AutoTdpBias.EFFICIENT -> GateParams(alpha = 0.30f, nearFrac = 1.9f, overFrac = 2.2f, confirm = 3, ceilingC = 82, powerCeilingW = powerCeilingW(b))
        AutoTdpBias.BALANCED -> GateParams(alpha = 0.45f, nearFrac = 1.6f, overFrac = 1.9f, confirm = 2, ceilingC = 84, powerCeilingW = powerCeilingW(b))
        AutoTdpBias.SMOOTH -> GateParams(alpha = 0.60f, nearFrac = 1.3f, overFrac = 1.6f, confirm = 2, ceilingC = 87, powerCeilingW = powerCeilingW(b))
    }

    /** The active bias's thermal-trim ceiling (°C). The fan loop reads this to target a temp just below it so
     *  the fan takes the first bite of the heat (see [autoTdpFanTargetC]). */
    fun thermalCeilingC(): Int = gateParams().ceilingC

    /**
     * Trim a REAL heat-shedding domain (perf/efficiency CPU or GPU) to hold the thermal ceiling, **skipping the
     * prime** — its cap is a no-op on SoCs that vendor-floor it (the Odin's prime stays ~3.5 GHz however low we
     * cap it), so "trimming" it sheds no heat (that left Stray riding 97 °C while the guard wasted trims on the
     * floored prime). Drops to the hard floor (ignores learned floors) and ignores bottleneck protection — the
     * ceiling overrides the fps target on purpose.
     */
    private fun thermalTrim(policies: List<CpuPolicyInfo>): Int? {
        val primeId = trimOrder(policies).firstOrNull { !it.isGpu }?.id
        for (p in trimOrder(policies)) {
            if (!p.isGpu && p.id == primeId) continue
            val cur = capPercent[p.id] ?: 100
            val min = minFor(p)
            if (cur > min) {
                capPercent[p.id] = (cur - stepFor(p)).coerceAtLeast(min)
                return p.id
            }
        }
        return null
    }

    /** The floor a trim may reach: the hard min, raised by any **learned** floor for this domain (its knee). */
    private fun effectiveMin(p: CpuPolicyInfo): Int = maxOf(minFor(p), domainFloor[p.id] ?: 0)

    /** Reopen caps to full, re-online the prime cores, and restore stock writability for the restore. */
    fun release(policies: List<CpuPolicyInfo>) {
        // Defense in depth: re-online the prime cluster UNCONDITIONALLY (not gated on the primeParked
        // flag) so a stale/false flag can never strand cores offline. Onlining already-online cores is a
        // harmless no-op. runCatching so a failed write can't skip the cap-release + reset below.
        val cores = primeCores(policies)
        if (cores.isNotEmpty()) runCatching { setCoresOnline(cores, true) }
        primeParked = false
        for (p in policies) capPercent[p.id] = 100
        if (policies.isNotEmpty()) releaseCaps(policies)
        reset()
    }

    data class Decision(val action: Action, val caps: Map<Int, Int>)

    enum class Action { RAISE, TRIM, HOLD }

    companion object {
        /** Cascade gap (°C): during AutoTDP the Custom fan targets [thermalCeilingC] − this, so the fan ramps
         *  up BEFORE the clock-trim ceiling engages — it buys back GPU clocks (more fps) by spending noise on a
         *  hot AAA game, instead of the clocks throttling first. See [autoTdpFanTargetC]. */
        const val AUTOTDP_FAN_CASCADE_GAP_C = 2

        /** The fan's effective target temp while AutoTDP runs: the user's manual target, but never above the
         *  trim ceiling minus the cascade gap — guarantees the fan takes the first bite regardless of how lazy
         *  the user's manual fan target is, so the GPU isn't strangled while the fan sleeps. */
        fun autoTdpFanTargetC(userTargetC: Int, ceilingC: Int): Int =
            minOf(userTargetC, ceilingC - AUTOTDP_FAN_CASCADE_GAP_C)

        /** The sustained power ceiling (W) each AutoTDP bias holds — the PRIMARY heat lever (the Odin's chassis
         *  only dissipates ~13–14 W). Single source of truth: the controller's [gateParams] AND the UI read
         *  this so the mode descriptions can state the TDP cap. Odin-tuned; on-device adjustable. */
        fun powerCeilingW(bias: AutoTdpBias): Float = when (bias) {
            AutoTdpBias.EFFICIENT -> 11f
            AutoTdpBias.BALANCED -> 12.5f
            AutoTdpBias.SMOOTH -> 14f
        }

        /** True only for the Odin (CQ8725S) — the device the watt cap + prime-walled settle are tuned for (tight
         *  chassis, vendor-floored prime). The SD 8 Gen 2 (QCS8550, Thor/RP6) and any unknown SoC return false,
         *  so they get plain chase-and-harvest. The UI also uses this to hide the per-mode watt labels there.
         *  Delegates to the [com.kei.pulse.model.DeviceProfiles] invariants table. */
        fun appliesOdinPowerTuning(socModel: String?): Boolean =
            com.kei.pulse.model.DeviceProfiles.forSoc(socModel).appliesOdinPowerTuning

        private const val DRAW_ALPHA = 0.4f // EMA weight on live draw for the power ceiling (smooth out spikes)
        private const val POWER_RAISE_FRAC = 0.95f // don't raise clocks once draw is within 5% of the ceiling
        private const val POWER_COOLDOWN_TICKS = 4 // suppress chase-raises this many ticks after the cap fired
        private const val PRIME_BOUND_CONFIRM = 3 // prime-bound below-target ticks before the settle latches on
        private const val PRIME_BOUND_RELEASE = 3 // reach-target/shift ticks before it releases (engage-fast/release-slow)

        const val CPU_MIN_PERCENT = 35
        const val GPU_MIN_PERCENT = 40 // responsive gaming floor (keeps ramp headroom)
        private const val GPU_IDLE_MIN_PERCENT = 15 // sustained-idle GPU (media): harvest to its true min (~160 MHz)
        private const val GPU_IDLE_BUSY = 10 // gpuBusy% at/under which the 3D GPU counts as idle
        private const val GPU_IDLE_TICKS = 8 // sustained idle steps (~16 s) before dropping to the idle floor
        private const val CPU_STEP = 5
        private const val GPU_STEP = 5
        private const val TARGET_HYST_HI = 1.08f // trim only above target × this (deadband upper)
        private const val TARGET_HYST_LO = 0.97f // raise only below target × this (deadband lower)
        private const val BOUND_THRESHOLD = 85 // busy% at/above which a domain is the bottleneck (aggregate)
        private const val GPU_SETTLE_BUSY = 80 // prime-bound settle: harvest the idle GPU only while busy% is
        // under this (leaves headroom below the 85 saturation line so the trim never makes the GPU the limiter)
        private const val CPU_CORE_BOUND = 65 // a single core this busy can be the limiter (hot game thread)
        // The prime-WALLED settle needs a STRICTER bar than CPU_CORE_BOUND: only a near-pegged core (the vendor-
        // floored prime / a maxed Box64 thread — Stray reads 95–100) is a true wall where chasing 60 is futile.
        // A merely-busy core (Megabonk 66–81) still lifts fps when given clock, so it must NOT settle (that mis-
        // fire harvested the GPU and oscillated a cool 5 W / 44 °C game — the "AutoTDP got worse" regression).
        private const val CPU_CORE_WALL = 90 // a core must be ~pegged to count as the unreachable prime wall
        private const val CPU_CORE_GAP = 30 // …but only if it's this far above the aggregate (one hot thread,
        // not balanced multi-core load the aggregate already covers)
        private const val CPU_CORE_HOLD = 55 // once CPU-bound, hold it until the hot core drops below this
        // (hysteresis below CPU_CORE_BOUND so a fluctuating hot thread doesn't flicker to NONE and raise the GPU)
        private const val RAISE_RESPONSE_FRAC = 0.02f // fps rise (vs fps) for a clock-probe to count as helping
        private const val RAISE_RESPONSE_FPS = 1.5f // …with this absolute floor so low-fps jitter isn't "help"
        private const val FPS_REGRET_FRAC = 0.05f // fps dropping ≥5% after a trim means it cost frames → undo
        private const val RAISE_STALL_REPROBE_TICKS = 30 // re-probe content-capped domains ~every 60s
        private const val RAISE_STALL_CONFIRM = 3 // consecutive no-response probes before a domain is content-capped
        private const val FLOOR_DECAY_TICKS = 30 // relax a learned per-domain floor a notch ~every 60s
        private const val JANK_TOL = 2f // extra ≥33 ms hitches/window that mean a trim hurt pacing
        private const val RECOVER_FLOOR_FPS = 20f
        private const val LOCK_JANK = 3f // jankEma at/above which frame pacing counts as rough (recovery trigger)
        private const val LOCK_ENTER_TICKS = 2 // consecutive rough ticks before recovery engages (sustained dip)
        private const val LOCK_RECOVER_TICKS = 3 // consecutive smooth ticks before recovery releases
        private const val LOCK_COOLDOWN_TICKS = 4 // harvesting stays suppressed this many ticks after recovery
        // Frametime-margin early-warning gate, scaled by the efficiency↔smoothness bias. Acts on the tail EMA
        // (× frame budget), not the raw worst frame, so isolated emulator jitter doesn't trip it. Tuned
        // starting values — on-device adjustable like the fan gains:
        //  - EFFICIENT: smooth the tail hard (low alpha) + high thresholds + long confirm → ignore jitter,
        //    keep harvesting; only a sustained rough tail acts.
        //  - SMOOTH: react fast (high alpha) + low thresholds → protect frames aggressively (≈ the prior gate).
        //  - BALANCED: between. All stay below the 33 ms hard-jank line so we act BEFORE the lockout would.
        private const val THERMAL_C = 85 // park-unpark thermal threshold (handlePrimeParking)
        private const val HARD_THERMAL_C = 90 // absolute trip — trim anything (incl the floored prime); above any bias ceiling
        private const val THERMAL_SOFT_C = 80 // warm (below the hard 85 trip): stop raising a non-bottleneck
        // domain to pre-empt the throttle, instead of pumping heat in chasing an fps the clocks can't reach
        private const val REPROBE_TICKS = 8 // re-test trimming roughly every ~16s (step runs ~2s)
        private const val STEADY_TICKS = 2 // continuous rendering required before trimming for power
        private const val FPS_ALPHA = 0.6f
        private const val JANK_ALPHA = 0.5f
        // Aggressive park (prime core offlining).
        private const val PARK_JANK_MAX = 1f // …and pacing is clean
        private const val PARK_TICKS = 3 // …sustained for this many steps (hysteresis, ~6s)
        private const val PARK_COOLDOWN_TICKS = 30 // base wait after a park that hurt fps (~60s)
        private const val PARK_COOLDOWN_MAX = 300 // cap the growing re-park cooldown (~10 min)
        private const val PARK_CPU_PEAK_MAX = 90 // don't park if any core is busier than this
        private const val PARK_SETTLE_TICKS = 10 // continuous rendered frames before the first park (load done)
        private const val SESSION_RESET_TICKS = 4 // sustained not-rendering steps (~8s) ⇒ treat as a new in-emulator game: re-arm the settle gate

        /**
         * Apply [caps] (policy id → percent) via [PerformanceCommandBuilder] so the writes are locked
         * read-only — the only way they survive the vendor perf daemon (CPU `scaling_max_freq` + GPU
         * `min_pwrlevel`/`max_pwrlevel`). Percent is turned into a real OPP per policy first.
         *
         * Only the **prime** cluster's `scaling_min` is lowered (see [primePolicyId]). The vendor floors the
         * prime's min ~3 GHz during gaming, so without this the prime cap is rejected (max < min) and it
         * never drops — this is what the old "working" build did for the prime. The *perf* cluster's min is
         * deliberately left alone: writing it wakes the HAL and stomps the perf cap back up (the regression
         * we fixed). Net: prime drops AND perf keeps biting.
         */
        fun applyCapsToDevice(policies: List<CpuPolicyInfo>, caps: Map<Int, Int>) {
            if (policies.isEmpty()) return
            runScript(
                PerformanceCommandBuilder().buildApplyScript(
                    policies,
                    capFreqs(policies, caps),
                    isReset = false,
                    lowerMinPolicyIds = setOfNotNull(primePolicyId(policies)),
                ),
            )
        }

        /**
         * Re-assert an explicit, already-resolved locked cap (policy id → kHz; a GPU value is a kHz the builder
         * maps to a pwrlevel) — used to HOLD a per-app Custom/tier binding's caps against the vendor daemon, the
         * same lock + prime-only `scaling_min` lower as [applyCapsToDevice]. [policies] MUST be the full detected
         * set so the prime is identified correctly; only ids present in [freqs] are written, so an uncapped
         * cluster (incl. an uncapped prime, whose min we then never touch) is left completely alone.
         */
        fun applyFreqsToDevice(policies: List<CpuPolicyInfo>, freqs: Map<Int, Int>) {
            if (policies.isEmpty() || freqs.isEmpty()) return
            runScript(
                PerformanceCommandBuilder().buildApplyScript(
                    policies,
                    freqs,
                    isReset = false,
                    lowerMinPolicyIds = setOfNotNull(primePolicyId(policies)),
                ),
            )
        }

        /**
         * Re-assert the NON-PRIME CPU clusters' caps (`scaling_max`, 444-locked) WITHOUT lowering their
         * `scaling_min` (writing a perf cluster's min wakes the HAL and stomps its max — the brief's #1 rule)
         * and WITHOUT touching the prime. Called every tick so the perf cap holds even while the prime is
         * PARKED: the per-tick prime re-assert is skipped when parked, and the vendor then stomps perf's
         * `scaling_max` back to full → the CPU runs free → heat → loud fan. Only writes clusters capped < 100%.
         */
        fun applyNonPrimeCpuCaps(policies: List<CpuPolicyInfo>, caps: Map<Int, Int>) {
            if (policies.isEmpty()) return
            val primeId = primePolicyId(policies)
            val freqs = policies
                .filterNot { it.isGpu }
                .filter { it.id != primeId && (caps[it.id] ?: 100) < 100 }
                .associate { p ->
                    val target = p.selectableMaxFreq * (caps[p.id] ?: 100) / 100
                    p.id to (p.supportedFrequencies.minByOrNull { abs(it - target) } ?: p.selectableMaxFreq)
                }
            if (freqs.isEmpty()) return
            runScript(
                PerformanceCommandBuilder().buildApplyScript(
                    policies, freqs, isReset = false, lowerMinPolicyIds = emptySet(),
                ),
            )
        }

        /**
         * Reopen every policy to its max and restore stock writability (`644`) so the firmware takes over.
         * Rewriting *every* CPU `scaling_min` writable (`644`) clears any stale read-only min lock and hands
         * min control back to the HAL.
         */
        fun releaseCapsToDevice(policies: List<CpuPolicyInfo>) {
            if (policies.isEmpty()) return
            val full = policies.associate { it.id to it.selectableMaxFreq }
            val allCpuIds = policies.filterNot { it.isGpu }.map { it.id }.toSet()
            runScript(
                PerformanceCommandBuilder().buildApplyScript(
                    policies, full, isReset = true, lowerMinPolicyIds = allCpuIds,
                ),
            )
        }

        /** The prime cluster = the CPU policy with the highest max frequency (its min is the floored one). */
        private fun primePolicyId(policies: List<CpuPolicyInfo>): Int? =
            policies.filterNot { it.isGpu }.maxByOrNull { it.selectableMaxFreq }?.id

        /** Percent caps → a concrete supported frequency per policy (CPU kHz / GPU kHz; builder maps GPU→level). */
        private fun capFreqs(policies: List<CpuPolicyInfo>, caps: Map<Int, Int>): Map<Int, Int> =
            policies.associate { p ->
                val target = p.selectableMaxFreq * (caps[p.id] ?: 100) / 100
                p.id to (p.supportedFrequencies.minByOrNull { abs(it - target) } ?: p.selectableMaxFreq)
            }

        private fun runScript(script: String) {
            val cmd = script.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .joinToString("; ")
            if (cmd.isNotEmpty()) RootSupport.runRootCommand(cmd)
        }

        /** Offline (`online=false`) or online the given CPU cores via their `cpuN/online` node. */
        fun setCoresOnlineToDevice(cores: List<Int>, online: Boolean) {
            if (cores.isEmpty()) return
            val v = if (online) 1 else 0
            val cmd = cores.joinToString("; ") {
                val path = "/sys/devices/system/cpu/cpu$it/online"
                "chmod 666 $path 2>/dev/null; echo $v > $path"
            }
            RootSupport.runRootCommand(cmd)
        }
    }
}
