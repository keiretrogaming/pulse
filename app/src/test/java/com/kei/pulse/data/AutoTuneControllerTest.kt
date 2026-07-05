package com.kei.pulse.data

import com.kei.pulse.data.AutoTuneController.Action
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.CpuPolicyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour coverage for the target-FPS AutoTDP controller. A no-op cap writer is injected so
 * [AutoTuneController.step] does no device I/O — we drive it with FPS/jank sequences against a
 * [AutoTuneController.targetFps] and assert per-policy cap movements via [AutoTuneController.caps].
 *
 * (The harness feeds a fixed FPS, so trimming doesn't change the fed value — sustained high FPS keeps
 * trimming, sustained low FPS keeps raising, which is exactly what we want to assert.)
 */
class AutoTuneControllerTest {

    private val prime = cpu(2, 3_300_000)
    private val perf = cpu(1, 2_800_000)
    private val eff = cpu(0, 2_000_000)
    private val gpu = gpu()
    private val policies = listOf(eff, gpu, prime, perf) // unordered on purpose

    private fun newController(target: Int = 60) =
        AutoTuneController(writeCaps = { _, _ -> }, releaseCaps = { _ -> }, setCoresOnline = { _, _ -> })
            .apply { targetFps = target }

    private fun AutoTuneController.feed(
        fps: Float,
        jank: Int = 0,
        drawW: Float? = null,
        cpuTemp: Int? = 50,
        gpuTemp: Int? = 50,
        cpuBusy: Int? = null,
        gpuBusy: Int? = null,
        cpuPeak: Int? = null,
        cpuCorePeak: Int? = null,
        worstFrameMs: Float? = null,
    ) = step(policies, fps, jank, drawW, cpuTemp, gpuTemp, cpuBusy, gpuBusy, cpuPeak, cpuCorePeak, worstFrameMs)

    @Test
    fun steadyGateHoldsThenTrimsAboveTarget() {
        val c = newController(60)
        assertEquals("warm-up step holds", Action.HOLD, c.feed(90f).action)
        assertEquals(100, c.capFor(prime.id))
        assertEquals("second steady step trims (90 well above 60)", Action.TRIM, c.feed(90f).action)
        assertTrue(c.capFor(prime.id) < 100)
    }

    @Test
    fun trimsPrimeClusterFirst() {
        val c = newController(60)
        c.feed(90f) // warm-up
        c.feed(90f)
        assertTrue("prime drops first", c.capFor(prime.id) < 100)
        assertEquals("performance untouched", 100, c.capFor(perf.id))
        assertEquals("GPU untouched (trimmed last)", 100, c.capFor(gpu.id))
    }

    @Test
    fun raisesClocksWhenBelowTarget() {
        val c = newController(60)
        repeat(5) { c.feed(90f) } // trim the prime cluster down
        val trimmed = c.capFor(prime.id)
        assertTrue(trimmed < 100)
        val r = c.feed(30f) // now well below target ⇒ restore clocks
        assertEquals(Action.RAISE, r.action)
        assertTrue("clocks given back", c.capFor(prime.id) > trimmed)
    }

    @Test
    fun maxTargetNeverTrimsForPower() {
        val c = newController(0) // 0 = Max (uncapped)
        repeat(20) { c.feed(200f) }
        assertEquals("Max target keeps clocks open", 100, c.capFor(prime.id))
        assertEquals(100, c.capFor(gpu.id))
    }

    @Test
    fun backsOffWhenTrimWorsensFramePacing() {
        val c = newController(60)
        c.feed(90f, jank = 0); c.feed(90f, jank = 0) // warm-up + trim prime, pacing baseline ~0
        val rough = c.feed(90f, jank = 8) // FPS still high but hitches spike ⇒ trim hurt smoothness
        assertEquals(Action.RAISE, rough.action)
        assertEquals("prime restored on rough pacing", 100, c.capFor(prime.id))
    }

    @Test
    fun marginGateSuppressesHarvestWhenTheFrametimeTailIsTight() {
        // SMOOTH bias = the responsive gate. Holding 90 over a 60 target would normally TRIM, but the tail is
        // creeping toward the 16.7 ms budget — don't harvest deeper into it; the jank lockout only fires AFTER
        // frames actually drop, this catches it earlier.
        val c = newController(60).apply { bias = AutoTdpBias.SMOOTH }
        c.feed(90f) // warm-up
        val r = c.feed(90f, worstFrameMs = 26f)
        assertEquals("don't harvest into a creeping tail", Action.HOLD, r.action)
        assertEquals("prime left alone", 100, c.capFor(prime.id))
    }

    @Test
    fun marginGatePreRaisesTheClockLimiterOnASustainedOverBudgetTail() {
        // GPU already harvested down and it's the bottleneck; the average still holds 90 but the tail is over
        // budget (30 ms ≈ 1.8×) for two steps. Pre-raise the GPU BEFORE the average collapses (predictive),
        // instead of waiting for the jank lockout to clean up after the stutter is visible.
        val c = newController(60).apply { bias = AutoTdpBias.SMOOTH }
        c.warmStart(policies, mapOf(gpu.id to 60))
        c.feed(90f, gpuBusy = 90, worstFrameMs = 30f) // warm-up (not steady yet)
        c.feed(90f, gpuBusy = 90, worstFrameMs = 30f) // steady; over-budget streak = 1 (still suppresses)
        val r = c.feed(90f, gpuBusy = 90, worstFrameMs = 30f) // streak = 2 ⇒ pre-raise the limiter
        assertEquals(Action.RAISE, r.action)
        assertTrue("GPU pre-raised before the average dropped", c.capFor(gpu.id) > 60)
    }

    @Test
    fun marginGateDoesNotPreRaiseWhenNoClockDomainIsSaturated() {
        // Tail over budget but NOTHING is the bottleneck ⇒ raising a clock can't fix it (emulator/IO overhead).
        // Suppress harvest (don't dig in) but DON'T pump clocks chasing an unreachable rate.
        val c = newController(60).apply { bias = AutoTdpBias.SMOOTH }
        c.warmStart(policies, mapOf(gpu.id to 60))
        repeat(4) { c.feed(90f, worstFrameMs = 30f) } // no busy% ⇒ bottleneck NONE
        assertEquals("no clock pumped when nothing is saturated", 60, c.capFor(gpu.id))
    }

    @Test
    fun efficientBiasDoesNotSuppressHarvestOnAnIsolatedTailSpike() {
        // EFFICIENT (default): GameNative throws isolated 60 ms spikes amid a 16 ms cadence. The tail EMA
        // barely moves for ONE spike, so the gate stays open and we keep harvesting — only a *sustained* rough
        // tail should act. (Contrast the SMOOTH responsive gate above, which suppresses on the same spike.)
        val c = newController(60).apply { bias = AutoTdpBias.EFFICIENT }
        c.feed(90f, worstFrameMs = 16f) // warm-up; EMA seeds ~16
        c.feed(90f, worstFrameMs = 16f) // steady → TRIM
        c.feed(90f, worstFrameMs = 16f) // TRIM
        val spike = c.feed(90f, worstFrameMs = 60f) // one isolated spike — must NOT suppress
        assertEquals("isolated jitter doesn't stop the harvest in EFFICIENT", Action.TRIM, spike.action)
    }

    @Test
    fun smoothBiasSuppressesHarvestOnTheSameIsolatedSpike() {
        // Same isolated 60 ms spike, SMOOTH bias = responsive: it DOES suppress (the efficiency↔smoothness
        // knob actually changes behavior).
        val c = newController(60).apply { bias = AutoTdpBias.SMOOTH }
        c.feed(90f, worstFrameMs = 16f)
        c.feed(90f, worstFrameMs = 16f)
        c.feed(90f, worstFrameMs = 16f)
        val spike = c.feed(90f, worstFrameMs = 60f)
        assertEquals("SMOOTH protects on a single spike", Action.HOLD, spike.action)
    }

    @Test
    fun efficientBiasStillPreRaisesOnASustainedRoughTail() {
        // A *sustained* rough tail (not a one-off) does still climb the EMA past EFFICIENT's threshold and
        // pre-raise the saturated limiter — the safety rail survives the looser gate.
        val c = newController(60).apply { bias = AutoTdpBias.EFFICIENT }
        c.warmStart(policies, mapOf(gpu.id to 60))
        val actions = (1..6).map { c.feed(90f, gpuBusy = 90, worstFrameMs = 60f) }
        assertTrue("sustained rough tail eventually pre-raises", actions.any { it.action == Action.RAISE })
        assertTrue("GPU pre-raised", c.capFor(gpu.id) > 60)
    }

    @Test
    fun thermalBoundHarvestsTheIdleGpuInsteadOfPumpingAnUnreachableCpuBoundTarget() {
        // Stray: emulated game pegged on one Box64 thread (cpuCorePeak ~99) that can't reach 60, SoC warm
        // (82°C, below the 85 hard trip), GPU NOT the bottleneck (70%). The old rule (fire only when there's
        // no bottleneck) kept RAISING — chasing 60 into the thermal wall (18W/91°C). Now it stops pumping and
        // harvests the idle GPU to settle cool, accepting the achievable rate.
        // SMOOTH bias (ceiling 87 °C) so 82 °C stays below the ceiling and exercises the below-target harvest,
        // not the thermal-ceiling rail (which would also trim the perf cluster).
        val c = newController(60).apply { bias = AutoTdpBias.SMOOTH }
        c.warmStart(policies, mapOf(gpu.id to 100)) // GPU pinned at max
        var last = c.feed(43f, cpuTemp = 82, gpuTemp = 70, cpuBusy = 20, gpuBusy = 70, cpuCorePeak = 99)
        repeat(6) { last = c.feed(43f, cpuTemp = 82, gpuTemp = 70, cpuBusy = 20, gpuBusy = 70, cpuCorePeak = 99) }
        assertTrue("idle GPU harvested to shed heat", c.capFor(gpu.id) < 100)
        assertTrue("never pumps clocks chasing the unreachable target", last.action != Action.RAISE)
        assertEquals("the pegged CPU (the bottleneck) is left alone", 100, c.capFor(prime.id))
    }

    @Test
    fun thermalCeilingTrimsPerfAndGpuNotTheFlooredPrimeOnAHeavyButReachableGame() {
        // Stray HITS 60 but at 18 W/97 °C. The ceiling must trim the REAL heat levers (perf+GPU) — NOT the
        // vendor-floored prime (a no-op heat-wise) — to hold the temp, overriding the fps target.
        val c = newController(60).apply { bias = AutoTdpBias.EFFICIENT } // ceiling 82 °C
        var last = c.feed(90f, cpuTemp = 85, gpuTemp = 80, gpuBusy = 90) // hitting target but over the ceiling
        repeat(4) { last = c.feed(90f, cpuTemp = 85, gpuTemp = 80, gpuBusy = 90) }
        assertEquals("the ceiling trims to hold the temp", Action.TRIM, last.action)
        assertTrue("perf and/or GPU trimmed", c.capFor(perf.id) < 100 || c.capFor(gpu.id) < 100)
        assertEquals("the vendor-floored prime is left alone (capping it sheds no heat)", 100, c.capFor(prime.id))
    }

    @Test
    fun thermalCeilingSitsAboveThePrimeFloorForEveryBias() {
        // The bug: EFFICIENT's old 74 °C ceiling was BELOW the Odin's ~80 °C uncoolable prime floor, so the
        // ceiling rail strangled the GPU to its hard floor forever (Stray 60→42 fps) for ~0 thermal/power gain.
        // Every bias's ceiling must clear the floor so the trim is reachable and the fan gets first crack.
        assertEquals(82, newController(60).apply { bias = AutoTdpBias.EFFICIENT }.thermalCeilingC())
        assertEquals(84, newController(60).apply { bias = AutoTdpBias.BALANCED }.thermalCeilingC())
        assertEquals(87, newController(60).apply { bias = AutoTdpBias.SMOOTH }.thermalCeilingC())
        AutoTdpBias.values().forEach {
            assertTrue("$it ceiling must clear the ~80 °C prime floor",
                newController(60).apply { bias = it }.thermalCeilingC() > 80)
        }
    }

    @Test
    fun biasGradientPinsEveryKnobAndStaysMonotonicEfficientToSmooth() {
        // The three biases ARE six hand-tuned numbers ([gateParamsFor]) + the watt cap ([powerCeilingW]); every
        // other knob in the controller is bias-independent. This pins each value AND the EFFICIENT→SMOOTH
        // ordering, so a future retune can't silently invert the lever (e.g. make BALANCED run hotter/hungrier
        // than SMOOTH) even if it changes the exact numbers — the lever's whole meaning is the monotonic gradient.
        val e = newController().gateParamsFor(AutoTdpBias.EFFICIENT)
        val b = newController().gateParamsFor(AutoTdpBias.BALANCED)
        val s = newController().gateParamsFor(AutoTdpBias.SMOOTH)

        // --- Exact values (on-device-tuned starting points; change deliberately, never by accident). ---
        // Watt cap — the PRIMARY Odin lever; companion is the single source of truth (also shown in the UI chips).
        assertEquals(11f, AutoTuneController.powerCeilingW(AutoTdpBias.EFFICIENT), 0f)
        assertEquals(12.5f, AutoTuneController.powerCeilingW(AutoTdpBias.BALANCED), 0f)
        assertEquals(14f, AutoTuneController.powerCeilingW(AutoTdpBias.SMOOTH), 0f)
        // …and the gate carries the same watt cap it will enforce.
        assertEquals(11f, e.powerCeilingW, 0f)
        assertEquals(12.5f, b.powerCeilingW, 0f)
        assertEquals(14f, s.powerCeilingW, 0f)
        // Thermal ceiling.
        assertEquals(82, e.ceilingC); assertEquals(84, b.ceilingC); assertEquals(87, s.ceilingC)
        // Margin gate: tail-EMA alpha, near/over budget-fracs, pre-raise confirm.
        assertEquals(0.30f, e.alpha, 0f); assertEquals(0.45f, b.alpha, 0f); assertEquals(0.60f, s.alpha, 0f)
        assertEquals(1.9f, e.nearFrac, 0f); assertEquals(1.6f, b.nearFrac, 0f); assertEquals(1.3f, s.nearFrac, 0f)
        assertEquals(2.2f, e.overFrac, 0f); assertEquals(1.9f, b.overFrac, 0f); assertEquals(1.6f, s.overFrac, 0f)
        assertEquals(3, e.confirm); assertEquals(2, b.confirm); assertEquals(2, s.confirm)

        // --- The invariant (intent): protectiveness rises EFFICIENT → BALANCED → SMOOTH. ---
        // More headroom (ASCENDING): watts, thermal ceiling, gate reaction speed (alpha).
        assertTrue("powerCeilingW asc", e.powerCeilingW < b.powerCeilingW && b.powerCeilingW < s.powerCeilingW)
        assertTrue("ceilingC asc", e.ceilingC < b.ceilingC && b.ceilingC < s.ceilingC)
        assertTrue("alpha asc", e.alpha < b.alpha && b.alpha < s.alpha)
        // Trips sooner / protects frames harder (DESCENDING): the × budget thresholds and the pre-raise debounce.
        assertTrue("nearFrac desc", e.nearFrac > b.nearFrac && b.nearFrac > s.nearFrac)
        assertTrue("overFrac desc", e.overFrac > b.overFrac && b.overFrac > s.overFrac)
        assertTrue("confirm non-increasing", e.confirm >= b.confirm && b.confirm >= s.confirm)
        // Within each bias the suppress-harvest band sits below the pre-raise trigger.
        listOf(e, b, s).forEach { assertTrue("nearFrac < overFrac for $it", it.nearFrac < it.overFrac) }

        // The accessor reflects the LIVE gate the controller decides with — not a parallel lookup table.
        val live = newController().apply { bias = AutoTdpBias.BALANCED }
        assertEquals(b, live.gateParamsFor(live.bias))
        assertEquals("thermalCeilingC() agrees with the gate", b.ceilingC, live.thermalCeilingC())
    }

    @Test
    fun autoTdpFanTargetCascadesBelowTheCeilingSoTheFanBitesFirst() {
        val gap = AutoTuneController.AUTOTDP_FAN_CASCADE_GAP_C
        // The user's manual target is honored when it already clears the gap below the ceiling…
        assertEquals(80, AutoTuneController.autoTdpFanTargetC(userTargetC = 80, ceilingC = 82))
        assertEquals(70, AutoTuneController.autoTdpFanTargetC(userTargetC = 70, ceilingC = 87))
        // …but a lazy (too-high) manual target is clamped below the ceiling so the fan can't sleep through the
        // heat while the clocks throttle (the whole point of running the Custom fan during AutoTDP).
        assertEquals(82 - gap, AutoTuneController.autoTdpFanTargetC(userTargetC = 90, ceilingC = 82))
        assertEquals(87 - gap, AutoTuneController.autoTdpFanTargetC(userTargetC = 90, ceilingC = 87))
        // Invariant: the fan target is always at least the cascade gap below the trim ceiling, every bias.
        AutoTdpBias.values().forEach {
            val ceil = newController(60).apply { bias = it }.thermalCeilingC()
            assertTrue("fan target stays a gap below the trim ceiling for $it",
                AutoTuneController.autoTdpFanTargetC(99, ceil) <= ceil - gap)
        }
    }

    @Test
    fun powerCeilingTrimsWhenDrawExceedsTheEnvelopeSkippingThePrime() {
        // The Odin chassis can't dissipate the watts — power is the PRIMARY lever. EFFICIENT holds 11 W; feed
        // 16 W → trim the real heat levers (perf+GPU), NOT the vendor-floored prime (capping it sheds no
        // watts), overriding the fps target even though the game is below it.
        val c = newController(60).apply { bias = AutoTdpBias.EFFICIENT }
        var last = c.feed(50f, drawW = 16f, gpuBusy = 70)
        repeat(4) { last = c.feed(50f, drawW = 16f, gpuBusy = 70) }
        assertEquals("over the watt envelope ⇒ trim", Action.TRIM, last.action)
        assertTrue("perf and/or GPU trimmed to shed watts", c.capFor(perf.id) < 100 || c.capFor(gpu.id) < 100)
        assertEquals("the vendor-floored prime is left alone (capping it sheds no watts)", 100, c.capFor(prime.id))
    }

    @Test
    fun powerCeilingStandsDownWhileCharging() {
        // drawW null (charging/unknown) ⇒ the power ceiling can't act on a bad signal; the thermal ceiling is
        // the backstop. A below-target game should still chase (raise), not be power-trimmed.
        val c = newController(60).apply { bias = AutoTdpBias.EFFICIENT }
        repeat(5) { c.feed(90f) } // trim down first so a raise is possible
        val r = c.feed(40f, drawW = null, gpuBusy = 70)
        assertEquals("no power signal ⇒ not power-trimmed; normal chase", Action.RAISE, r.action)
    }

    @Test
    fun doesNotRaiseIntoThePowerCeiling() {
        // Just under the envelope (EFFICIENT 11 W, draw 10.7 W ≈ 97 % of the ceiling), a below-target game must
        // NOT raise — more clock would push over the envelope and overheat (the power rail would trim it right
        // back ⇒ oscillation). Hold at the budget.
        val c = newController(60).apply { bias = AutoTdpBias.EFFICIENT }
        repeat(5) { c.feed(90f) } // trim down so a raise would otherwise be possible
        var last = c.feed(40f, drawW = 10.7f, gpuBusy = 70)
        repeat(3) { last = c.feed(40f, drawW = 10.7f, gpuBusy = 70) }
        assertEquals("within 5 % of the watt ceiling ⇒ hold, don't raise into it", Action.HOLD, last.action)
    }

    @Test
    fun settlesInsteadOfChasingWhenPrimeBoundWithGpuHeadroom() {
        // Stray on the Odin: one pegged core (the vendor-floored prime — can't clock higher or cool) gates each
        // frame, the GPU has headroom (75% busy, not saturated), fps below target. Chasing 60 here just pumps
        // the GPU into heat for frames that can't come. The controller must STOP raising and harvest the over-
        // provisioned GPU — NOT pump clocks, NOT trim the pegged prime. Crucially the SoC is COOL (70 °C) so the
        // old thermal-only harvest wouldn't catch it — this proves the settle is STRUCTURAL, not thermal.
        val c = newController(60)
        var last = c.feed(50f, cpuBusy = 45, gpuBusy = 75, cpuCorePeak = 95, cpuTemp = 70, gpuTemp = 65)
        repeat(4) { last = c.feed(50f, cpuBusy = 45, gpuBusy = 75, cpuCorePeak = 95, cpuTemp = 70, gpuTemp = 65) }
        assertTrue("never pumps clocks into the CPU wall", last.action != Action.RAISE)
        assertTrue("harvests the over-provisioned GPU", c.capFor(gpu.id) < 100)
        assertEquals("the pegged prime is left alone (can't be raised or cooled)", 100, c.capFor(prime.id))
        assertEquals("the perf cluster isn't trimmed by the settle", 100, c.capFor(perf.id))
    }

    @Test
    fun settleHoldsOnceTheGpuNearsSaturationInsteadOfRubberBanding() {
        // Once the GPU is harvested to ~saturation (busy ≥ the settle target) the settle must HOLD — neither
        // trim further (digging into a stutter — the rubber-band we're killing) nor raise into the CPU wall.
        val c = newController(60)
        var last = c.feed(50f, cpuBusy = 45, gpuBusy = 82, cpuCorePeak = 95, cpuTemp = 70, gpuTemp = 65)
        repeat(4) { last = c.feed(50f, cpuBusy = 45, gpuBusy = 82, cpuCorePeak = 95, cpuTemp = 70, gpuTemp = 65) }
        assertEquals("GPU already near saturation ⇒ settle holds", Action.HOLD, last.action)
        assertEquals("GPU not trimmed past saturation (would cost fps)", 100, c.capFor(gpu.id))
        assertEquals("prime untouched", 100, c.capFor(prime.id))
    }

    @Test
    fun doesNotSettleWhenTheGpuIsTheBottleneck() {
        // Guard: a genuinely GPU-bound below-target game (GPU saturated, no hot CPU core) must NOT hit the
        // prime-bound settle — it should still probe/raise the GPU. The settle is only for the prime wall.
        val c = newController(60)
        c.warmStart(policies, mapOf(gpu.id to 60)) // GPU below max so a raise is possible
        val r = c.feed(45f, cpuBusy = 30, gpuBusy = 92, cpuCorePeak = 40, cpuTemp = 70, gpuTemp = 70)
        assertEquals("GPU-bound below target still raises to chase, doesn't settle", Action.RAISE, r.action)
        assertTrue("the saturated GPU is given clock, not harvested", c.capFor(gpu.id) > 60)
    }

    @Test
    fun doesNotSettleWhenThePrimeCoreIsBusyButNotPegged() {
        // Megabonk regression: a light, COOL game (44 °C, ~5 W) whose hottest core sits at ~80 % — BUSY but NOT
        // pegged — with GPU headroom and fps below 60. Raising its clocks DOES lift fps, so it must keep CHASING,
        // not engage the prime-bound settle (which would harvest the GPU and oscillate — the wobble the user hit).
        // The settle is only for a genuinely pegged core (Stray 95–100, the vendor-floored prime wall); a merely-
        // busy 66–81 % core was mistaken for that wall (CPU_CORE_BOUND 65) and strangled cool mid-range games.
        val c = newController(60)
        c.warmStart(policies, mapOf(gpu.id to 60)) // GPU below max so a chase-raise is observable
        val r = c.feed(50f, cpuBusy = 20, gpuBusy = 75, cpuCorePeak = 80, cpuTemp = 44, gpuTemp = 42)
        assertEquals("a busy-but-not-pegged cool game chases the target, doesn't settle", Action.RAISE, r.action)
        assertTrue("the GPU is given clock to chase, not harvested by a false settle", c.capFor(gpu.id) >= 60)
    }

    @Test
    fun powerCooldownSuppressesRaiseAfterGoingOverBudget() {
        // After draw exceeds the cap, the chase-raise stays suppressed for a few ticks even once the smoothed
        // draw dips back under — that stickiness is what stops the raise→spike→trim bounce (signal noise can't
        // re-open the raise the instant the EMA dips).
        val c = newController(60).apply { bias = AutoTdpBias.EFFICIENT } // 11 W cap
        c.feed(50f, drawW = 18f, gpuBusy = 70) // over budget ⇒ trim + arm the cooldown
        val actions = (1..3).map { c.feed(45f, drawW = 6f, gpuBusy = 70).action }
        assertTrue("no chase-raise during the power cooldown window", actions.none { it == Action.RAISE })
    }

    @Test
    fun latchedSettleSurvivesAnFpsSpike() {
        // Stray's fps swings 35→60→35. Once the prime-bound settle has LATCHED, a lone lucky 60-fps tick must
        // NOT knock it loose into a chase — that flutter was exactly what defeated the un-latched settle.
        val c = newController(60)
        repeat(5) { c.feed(48f, cpuBusy = 45, gpuBusy = 82, cpuCorePeak = 95, cpuTemp = 75, gpuTemp = 68) }
        c.feed(60f, cpuBusy = 45, gpuBusy = 82, cpuCorePeak = 95, cpuTemp = 75, gpuTemp = 68) // lone spike to target
        val r = c.feed(48f, cpuBusy = 45, gpuBusy = 82, cpuCorePeak = 95, cpuTemp = 75, gpuTemp = 68)
        assertEquals("latched settle holds through a lone spike, doesn't chase", Action.HOLD, r.action)
    }

    @Test
    fun latchedSettleOutranksTheRecoveryLockoutOnAChronicallyRoughPrimeWall() {
        // Stray: prime-walled at ~35 fps with chronically rough pacing (jankEma ≫ LOCK_JANK). The recovery
        // lockout would otherwise fire EVERY tick and pump heat raising clocks that can't lift a prime-gated
        // frame (the raise→heat→thermal-trim→cool→raise oscillation). Once latched, the settle outranks it.
        val c = newController(60)
        var last = c.feed(35f, jank = 28, cpuBusy = 42, gpuBusy = 75, cpuCorePeak = 99, cpuTemp = 80, gpuTemp = 66)
        repeat(6) { last = c.feed(35f, jank = 28, cpuBusy = 42, gpuBusy = 75, cpuCorePeak = 99, cpuTemp = 80, gpuTemp = 66) }
        assertTrue("latched prime-wall settle outranks the lockout — no chase-raise", last.action != Action.RAISE)
        assertEquals("the pegged prime is never trimmed (no-op for heat)", 100, c.capFor(prime.id))
    }

    @Test
    fun odinPowerTuningAppliesOnlyToTheOdinSoc() {
        // The watt cap + prime-walled settle are Odin (CQ8725S) chassis/floored-prime tuning. They must NOT
        // apply to the SD 8 Gen 2 (QCS8550 — Thor/RP6), whose prime scales and chassis differs.
        assertTrue(AutoTuneController.appliesOdinPowerTuning("CQ8725S"))
        assertTrue("case-insensitive", AutoTuneController.appliesOdinPowerTuning("cq8725s"))
        assertFalse("SD 8 Gen 2 (Thor / RP6)", AutoTuneController.appliesOdinPowerTuning("QCS8550"))
        assertFalse("unknown/null SoC", AutoTuneController.appliesOdinPowerTuning(null))
    }

    @Test
    fun sdGen2DoesNotApplyThePowerCeiling() {
        // With Odin power tuning OFF (the SD 8 Gen 2 path), even well over the watt envelope it must NOT
        // power-trim — it chases the target. (Contrast: powerCeilingTrimsWhenDrawExceeds, where it's ON.)
        val c = newController(60).apply { bias = AutoTdpBias.EFFICIENT; wattCapAndSettleEnabled = false }
        c.warmStart(policies, mapOf(prime.id to 60, perf.id to 60, gpu.id to 60)) // room to raise
        val r = c.feed(45f, drawW = 18f, gpuBusy = 70)
        assertEquals("watt cap disabled ⇒ raise/chase, never power-trim", Action.RAISE, r.action)
    }

    @Test
    fun sdGen2DoesNotEngageThePrimeWalledSettle() {
        // The SD 8 Gen 2 prime SCALES (595→3187 MHz), so the settle must never latch and hold it down.
        val c = newController(60).apply { wattCapAndSettleEnabled = false }
        repeat(6) { c.feed(48f, cpuBusy = 45, gpuBusy = 75, cpuCorePeak = 95, cpuTemp = 70, gpuTemp = 65) }
        assertFalse("settle never latches on the SD 8 Gen 2", c.isPrimeWalled)
    }

    @Test
    fun nullFpsResetsTheGate() {
        val c = newController(60)
        c.feed(90f) // steady count = 1
        val paused = c.step(policies, fps = null, jankFrames = null, drawW = null, cpuTempC = 50, gpuTempC = 50)
        assertEquals(Action.HOLD, paused.action)
        assertEquals("next single step is a warm-up again, not a trim", Action.HOLD, c.feed(90f).action)
    }

    @Test
    fun thermalForcesATrimEvenBelowTheGate() {
        val c = newController(60)
        val hot = c.feed(90f, cpuTemp = 92) // first step, gate unsatisfied, but heat must trim
        assertEquals(Action.TRIM, hot.action)
        assertTrue(c.capFor(prime.id) < 100)
    }

    @Test
    fun recoversAfterASustainedStall() {
        val c = newController(60)
        repeat(10) { c.feed(90f) }
        val trimmed = c.capFor(prime.id)
        assertTrue(trimmed < 100)
        repeat(4) { c.feed(5f) } // sustained stall ⇒ restore clocks
        assertTrue("clocks recovered upward", c.capFor(prime.id) > trimmed)
    }

    @Test
    fun holdsWhenFpsUnknown() {
        val c = newController(60)
        val d = c.step(policies, fps = null, jankFrames = null, drawW = null, cpuTempC = 50, gpuTempC = 50)
        assertEquals(Action.HOLD, d.action)
        assertEquals(100, c.capFor(prime.id))
    }

    @Test
    fun learnsCpuPowerFromTheDrawDeltaAcrossACpuTrim() {
        val c = newController(60)
        c.feed(90f, drawW = 6.0f) // warm-up
        c.feed(90f, drawW = 6.0f) // trims the prime CPU cluster; baseline draw 6.0 W
        c.feed(90f, drawW = 5.4f) // CPU trim cost 0.6 W ⇒ attributed to CPU
        assertEquals(1, c.powerModel.cpuSamples)
        assertEquals(0, c.powerModel.gpuSamples)
        assertTrue(c.powerModel.cpuWattsSum > 0.5f)
    }

    @Test
    fun neverTrimsBelowFloors() {
        val c = newController(60)
        repeat(80) { c.feed(90f) } // FPS stays above target ⇒ everything trims to its floor and stops
        assertEquals(AutoTuneController.CPU_MIN_PERCENT, c.capFor(prime.id))
        assertTrue(c.capFor(perf.id) >= AutoTuneController.CPU_MIN_PERCENT)
        assertTrue(c.capFor(gpu.id) >= AutoTuneController.GPU_MIN_PERCENT)
    }

    @Test
    fun gpuBoundKeepsGpuUpAndTrimsCpu() {
        val c = newController(60)
        repeat(40) { c.feed(80f, gpuBusy = 95, cpuBusy = 30) } // overshoot, GPU is the bottleneck
        assertEquals("GPU never trimmed when it's the bottleneck", 100, c.capFor(gpu.id))
        assertEquals("CPU trimmed instead", AutoTuneController.CPU_MIN_PERCENT, c.capFor(prime.id))
    }

    @Test
    fun cpuBoundKeepsCpuUpAndTrimsGpu() {
        val c = newController(60)
        repeat(40) { c.feed(80f, cpuBusy = 95, gpuBusy = 30) } // overshoot, CPU is the bottleneck
        assertEquals("prime never trimmed when the CPU is the bottleneck", 100, c.capFor(prime.id))
        assertEquals("GPU trimmed instead", AutoTuneController.GPU_MIN_PERCENT, c.capFor(gpu.id))
    }

    @Test
    fun gpuBoundRaisesGpuFirstWhenBelowTarget() {
        val c = newController(60)
        repeat(60) { c.feed(80f) } // no load signal ⇒ trims everything (incl. GPU) to its floor
        val gpuTrimmed = c.capFor(gpu.id)
        assertTrue(gpuTrimmed < 100)
        val r = c.feed(40f, gpuBusy = 95, cpuBusy = 20) // below target, GPU-bound ⇒ raise GPU first
        assertEquals(Action.RAISE, r.action)
        assertTrue("GPU given its clocks back first", c.capFor(gpu.id) > gpuTrimmed)
    }

    @Test
    fun lowFpsWhileIdleHarvestsInsteadOfChasing() {
        // Bug 3: video at 30 fps under a 60 target with everything idle isn't clock-bound — don't pin
        // clocks chasing an unreachable 60; harvest toward the floor instead (no thrash).
        val c = newController(60)
        c.feed(30f, cpuBusy = 10, gpuBusy = 10, cpuPeak = 40) // warm-up
        val d = c.feed(30f, cpuBusy = 10, gpuBusy = 10, cpuPeak = 40)
        assertEquals(Action.TRIM, d.action)
        assertTrue("clocks harvested down, not raised", c.capFor(prime.id) < 100)
    }

    @Test
    fun mediaHarvestsClocksInsteadOfChasing() {
        // S-tier Bug 3: a steady 24 fps under a 30 target where raising never helps (video) must stop
        // pinning clocks and harvest them down — not sit at the ceiling chasing an unreachable 30. The GPU
        // reads as the bottleneck (video compositing, gpuBusy 88) yet must STILL be harvested once proven
        // content-capped — that's the case that kept it pinned at ~1 GHz before.
        val c = newController(30)
        // Warm-start at converged-low caps, as a real media session resumes (it doesn't cold-start at 100%).
        c.warmStart(policies, mapOf(prime.id to 50, perf.id to 50, eff.id to 50, gpu.id to 70))
        repeat(60) { c.feed(24f, cpuBusy = 12, gpuBusy = 88, cpuPeak = 55) }
        assertTrue("CPU not pinned (harvested)", c.capFor(prime.id) < 60)
        assertTrue("GPU not pinned despite high load (harvested)", c.capFor(gpu.id) < 75)
    }

    @Test
    fun idleGpuHarvestsBelowTheGamingFloorForMedia() {
        // Batch 4: during video the 3D GPU is idle (decode is fixed-function ⇒ gpuBusy≈0). Once it's been
        // idle a sustained stretch, the harvest floor drops below the 40% gaming floor toward the GPU's true
        // minimum instead of leaving it pinned at ~443 MHz — the daemon only pins it to the ceiling we allow.
        val c = newController(30)
        // Warm-start at the converged operating point a real media session resumes from (CPUs + GPU already
        // at their gaming floors — the log showed caps%[-100:40] from the first tick), so the test exercises
        // the idle-GPU harvest itself, not the one-time cold descent through the CPU clusters.
        c.warmStart(
            policies,
            mapOf(prime.id to 35, perf.id to 35, eff.id to 35, gpu.id to AutoTuneController.GPU_MIN_PERCENT),
        )
        repeat(25) { c.feed(25f, cpuBusy = 3, gpuBusy = 0, cpuPeak = 35) }
        assertTrue(
            "idle GPU harvested below the gaming floor, got ${c.capFor(gpu.id)}",
            c.capFor(gpu.id) < AutoTuneController.GPU_MIN_PERCENT,
        )
    }

    @Test
    fun idleGpuFloorSnapsBackWhenGpuWorkReturns() {
        // The idle floor must not strand a game: the instant real GPU work returns (bottleneck = GPU), the
        // responsive 40% floor is restored at once so a heavy scene can ramp without a long climb from ~160.
        val c = newController(30)
        c.warmStart(
            policies,
            mapOf(prime.id to 35, perf.id to 35, eff.id to 35, gpu.id to AutoTuneController.GPU_MIN_PERCENT),
        )
        repeat(25) { c.feed(25f, cpuBusy = 3, gpuBusy = 0, cpuPeak = 35) } // media: GPU harvested low
        assertTrue("harvested below gaming floor first", c.capFor(gpu.id) < AutoTuneController.GPU_MIN_PERCENT)
        c.feed(25f, cpuBusy = 3, gpuBusy = 95, cpuPeak = 35) // GPU work returns
        assertTrue(
            "responsive floor restored on re-activation",
            c.capFor(gpu.id) >= AutoTuneController.GPU_MIN_PERCENT,
        )
    }

    @Test
    fun aSingleHotCoreIsDetectedAsTheCpuBottleneck() {
        // Hot-thread fix: a pegged game/emulator thread (peak core 80%) reads low on the 8-core aggregate
        // (18%), so the old aggregate-only detector saw no bottleneck and raised the GPU (wrong domain).
        // The single hot core now flags CPU so the perf cluster gets fed instead.
        val c = newController(60)
        c.feed(45f, cpuBusy = 18, gpuBusy = 60, cpuPeak = 50, cpuCorePeak = 80)
        c.feed(45f, cpuBusy = 18, gpuBusy = 60, cpuPeak = 50, cpuCorePeak = 80)
        assertEquals("a single pegged core is the CPU bottleneck", "CPU", c.bottleneckLabel)
    }

    @Test
    fun balancedCpuLoadIsNotMistakenForAHotThread() {
        // The hot-thread clause needs a big peak-vs-aggregate GAP. Evenly-loaded cores (peak close to the
        // aggregate) aren't a single-thread bottleneck — the aggregate already covers that case.
        val c = newController(60)
        c.feed(45f, cpuBusy = 55, gpuBusy = 60, cpuPeak = 60, cpuCorePeak = 62)
        c.feed(45f, cpuBusy = 55, gpuBusy = 60, cpuPeak = 60, cpuCorePeak = 62)
        assertEquals("balanced load isn't flagged as a single hot core", "-", c.bottleneckLabel)
    }

    @Test
    fun hotThreadBottleneckHoldsThroughFluctuationInsteadOfFlickeringToGpu() {
        // The emulator-wave fix: a hot thread's peak core fluctuates around the detect threshold. Once
        // CPU-bound, a dip below CPU_CORE_BOUND (but above the hold) must KEEP bn=CPU — otherwise it flickers
        // to NONE and mis-raises the GPU to max on a wave no clock fixes (the 11 W / 50 °C waste).
        val c = newController(60)
        c.feed(45f, cpuBusy = 20, gpuBusy = 70, cpuPeak = 50, cpuCorePeak = 78) // hot thread → CPU-bound
        assertEquals("CPU-bound on the hot core", "CPU", c.bottleneckLabel)
        c.feed(45f, cpuBusy = 20, gpuBusy = 70, cpuPeak = 50, cpuCorePeak = 58) // dips below 65 but ≥ 55
        assertEquals("held CPU through the fluctuation, not flickered to NONE", "CPU", c.bottleneckLabel)
        c.feed(45f, cpuBusy = 20, gpuBusy = 70, cpuPeak = 50, cpuCorePeak = 40) // drops well below the hold
        assertEquals("released once the core is genuinely idle", "-", c.bottleneckLabel)
    }

    @Test
    fun aSaturatedGpuStillOverridesTheHotThreadHold() {
        // The hold must not shield a real GPU bottleneck: if the GPU saturates while we were CPU-bound, switch.
        val c = newController(60)
        c.feed(45f, cpuBusy = 20, gpuBusy = 70, cpuPeak = 50, cpuCorePeak = 78) // CPU-bound
        assertEquals("CPU", c.bottleneckLabel)
        c.feed(45f, cpuBusy = 20, gpuBusy = 92, cpuPeak = 50, cpuCorePeak = 58) // GPU now saturated
        assertEquals("a saturated GPU overrides the CPU hold", "GPU", c.bottleneckLabel)
    }

    @Test
    fun aSingleWaveMaskedProbeDoesNotLatchContentCapped() {
        // Over-harvest fix: one probe that didn't move fps (a wave masking the gain) must NOT mark a domain
        // content-capped — a single unlucky probe used to harvest a whole game into a stutter and stay stuck.
        // Only a SUSTAINED no-response (RAISE_STALL_CONFIRM in a row) latches (the media test covers that path).
        val c = newController(60)
        repeat(10) { c.feed(90f, cpuBusy = 20, gpuBusy = 90, cpuPeak = 70) } // harvest down to create raise room
        // Drop below target so it probes, then resolve the probe with a flat (wave) reading — one no-response.
        c.feed(40f, cpuBusy = 20, gpuBusy = 90, cpuPeak = 70)
        c.feed(40f, cpuBusy = 20, gpuBusy = 90, cpuPeak = 70)
        assertEquals("no domain latched on a single no-response probe", 0, c.stalledDomainCount)
    }

    @Test
    fun belowTargetClimbsWhenClocksActuallyHelp() {
        // Clock-bound game: fps tracks the prime cap, so each probe-raise genuinely lifts fps and the
        // controller climbs back up — the opposite of the media case (this is the clock-response feedback).
        val c = newController(60)
        repeat(8) { c.feed(90f) } // trim the prime down first to create raise headroom
        val floored = c.capFor(prime.id)
        assertTrue("prime trimmed first", floored < 100)
        // 80 fps achievable at full prime ⇒ fps responds to the cap. Feed that response for a while.
        repeat(30) { c.feed(80f * c.capFor(prime.id) / 100f, cpuBusy = 25, gpuBusy = 25, cpuPeak = 90) }
        assertTrue("prime climbed back as raising actually helped", c.capFor(prime.id) > floored)
    }

    @Test
    fun holdingAtTargetTrimsTheSlackDomainNotTheBusyOne() {
        // Simulates a Game-Mode-capped game pinned at 60 with the CPU busy (bottleneck) and GPU slack.
        val c = newController(60)
        c.feed(60f, cpuBusy = 95, gpuBusy = 20) // warm-up
        val d = c.feed(60f, cpuBusy = 95, gpuBusy = 20)
        assertEquals(Action.TRIM, d.action)
        assertEquals("CPU (busy/bottleneck) untouched", 100, c.capFor(prime.id))
        assertTrue("GPU (slack) trimmed while holding the cap", c.capFor(gpu.id) < 100)
    }

    @Test
    fun holdingTrimsTheNonBottleneckDomainEvenWhenBusy() {
        // Both domains busy and pinned at the cap: the bottleneck (GPU wins the tie) is protected, but the
        // other domain is still probed down — the hill-climb backs off later if it actually costs frames.
        val c = newController(60)
        c.feed(60f, cpuBusy = 90, gpuBusy = 90)
        val d = c.feed(60f, cpuBusy = 90, gpuBusy = 90)
        assertEquals(Action.TRIM, d.action)
        assertEquals("GPU (bottleneck) protected", 100, c.capFor(gpu.id))
        assertTrue("CPU probed down by the hill-climb", c.capFor(prime.id) < 100)
    }

    @Test
    fun holdingTrimsViaHillClimbWhenLoadUnknown() {
        // No busy% (read failed): fall back to the pure hill-climb — trim a notch while holding and let the
        // below-target raise / smoothness rail back it off if it hurts. This is what unstuck the CPU.
        val c = newController(60)
        c.feed(62f)
        assertEquals(Action.TRIM, c.feed(62f).action)
        assertTrue(c.capFor(prime.id) < 100)
    }

    @Test
    fun aggressiveParkOfflinesPrimeWhenHoldingThenReonlinesOnDip() {
        val events = mutableListOf<Pair<List<Int>, Boolean>>()
        val c = AutoTuneController(
            writeCaps = { _, _ -> },
            releaseCaps = { _ -> },
            setCoresOnline = { cores, online -> events += cores to online },
        ).apply { targetFps = 60; aggressivePark = true }
        // Hold comfortably above target on battery ⇒ prime trims to its floor, then gets offlined.
        repeat(40) { c.feed(70f, drawW = 5f) }
        assertTrue("prime cores offlined", events.contains(listOf(prime.id) to false))
        assertTrue("parked flag set", c.isPrimeParked)
        // FPS dips below target ⇒ the prime is needed back immediately.
        c.feed(50f, drawW = 5f)
        assertTrue("prime cores re-onlined", events.contains(listOf(prime.id) to true))
        assertTrue("unparked", !c.isPrimeParked)
    }

    @Test
    fun parkedPrimeIsReonlinedTheInstantRenderingStops() {
        // THE core regression test for the stranded-cores bug: if the prime is parked and rendering then
        // stops (fps→null, e.g. an in-emulator game switch / load / pause), the not-rendering early return
        // skips handlePrimeParking — the ONLY place that re-onlines parked cores — so without the safety
        // unpark the cores stay offlined indefinitely (the foreground package never changes inside a
        // multi-game emulator, so release() is never called either), stranding controller/input threads.
        val events = mutableListOf<Pair<List<Int>, Boolean>>()
        val c = AutoTuneController(
            writeCaps = { _, _ -> },
            releaseCaps = { _ -> },
            setCoresOnline = { cores, online -> events += cores to online },
        ).apply { targetFps = 60; aggressivePark = true }
        repeat(40) { c.feed(70f, drawW = 5f) } // hold above target ⇒ prime trims to floor, then parks
        assertTrue("prime cores offlined", events.contains(listOf(prime.id) to false))
        assertTrue("parked flag set", c.isPrimeParked)
        events.clear()
        // Rendering stops — ONE not-rendering step must bring the prime back online immediately.
        c.step(policies, fps = null, jankFrames = null, drawW = null, cpuTempC = 50, gpuTempC = 50)
        assertTrue("prime cores re-onlined the instant rendering stopped", events.contains(listOf(prime.id) to true))
        assertTrue("no longer parked", !c.isPrimeParked)
    }

    @Test
    fun aSustainedRenderGapReArmsTheSettleGateSoTheNextGameMustReSettle() {
        // Fix B: a sustained render gap (SESSION_RESET_TICKS not-rendering steps) inside the same emulator =
        // a new game/content loading. The settle gate must re-arm so the next render session must prove itself
        // (re-accumulate PARK_SETTLE_TICKS=10 of continuous frames, then PARK_TICKS=3 of eligibility) before
        // it can park again — each game re-decides parking from scratch instead of re-parking instantly.
        val events = mutableListOf<Pair<List<Int>, Boolean>>()
        val c = AutoTuneController(
            writeCaps = { _, _ -> },
            releaseCaps = { _ -> },
            setCoresOnline = { cores, online -> events += cores to online },
        ).apply { targetFps = 60; aggressivePark = true }
        repeat(40) { c.feed(70f, drawW = 5f) } // park the prime
        assertTrue("prime parked", c.isPrimeParked)
        // SESSION_RESET_TICKS (4) consecutive not-rendering steps ⇒ a new in-emulator game is loading.
        repeat(4) { c.step(policies, fps = null, jankFrames = null, drawW = null, cpuTempC = 50, gpuTempC = 50) }
        assertTrue("the render gap re-onlined the prime", !c.isPrimeParked)
        events.clear()
        // A SHORT continuous run (fewer than PARK_SETTLE_TICKS=10) must NOT re-park: the settle gate was
        // re-armed, so the new game hasn't proven continuous rendering yet.
        repeat(5) { c.feed(70f, drawW = 5f) }
        assertTrue("does not re-park before re-settling", !events.contains(listOf(prime.id) to false))
        assertTrue("still unparked after the short run", !c.isPrimeParked)
        // A LONG continuous run (well past PARK_SETTLE_TICKS + PARK_TICKS) lets the new game settle and park.
        repeat(40) { c.feed(70f, drawW = 5f) }
        assertTrue("re-parks once the new game has re-settled", events.contains(listOf(prime.id) to false))
    }

    @Test
    fun aBriefOneTickRenderMissDoesNotStrandTheParkedPrime() {
        // A brief 1-tick fps-read miss must (a) re-online the parked prime (the safety unpark — cores never
        // stay stranded) but (b) NOT re-arm the settle gate (1 < SESSION_RESET_TICKS=4), so sessionSettled
        // stays latched and the prime can re-park quickly once rendering resumes — a momentary read glitch
        // mustn't force a full re-settle mid-game.
        val events = mutableListOf<Pair<List<Int>, Boolean>>()
        val c = AutoTuneController(
            writeCaps = { _, _ -> },
            releaseCaps = { _ -> },
            setCoresOnline = { cores, online -> events += cores to online },
        ).apply { targetFps = 60; aggressivePark = true }
        repeat(40) { c.feed(70f, drawW = 5f) } // park the prime
        assertTrue("prime parked", c.isPrimeParked)
        events.clear()
        // ONE null tick: the key guarantee is the cores come back online.
        c.step(policies, fps = null, jankFrames = null, drawW = null, cpuTempC = 50, gpuTempC = 50)
        assertTrue("brief miss still re-onlines the prime", events.contains(listOf(prime.id) to true))
        assertTrue("unparked after the brief miss", !c.isPrimeParked)
        events.clear()
        // Because sessionSettled stayed latched (1 tick < SESSION_RESET_TICKS), resumed rendering re-parks
        // after only PARK_TICKS (3) of eligibility — no need to re-prove PARK_SETTLE_TICKS of continuous frames.
        repeat(8) { c.feed(70f, drawW = 5f) }
        assertTrue("re-parks quickly because the settle gate stayed latched", events.contains(listOf(prime.id) to false))
    }

    @Test
    fun aHurtfulTrimRaisesTheLearnedFloorSoItIsntReTrimmedBelowTheKnee() {
        // The fix for the perf-cluster oscillation: when a trim drops frames, that domain's floor ratchets
        // up to the recovered level, so it isn't re-trimmed back to CPU_MIN on the next reprobe.
        val c = newController(60)
        repeat(4) { c.feed(80f) } // overshoot ⇒ trims the prime down a few notches
        assertTrue(c.capFor(prime.id) < 100)
        c.feed(40f) // a sharp drop right after a trim ⇒ the fps-regret rail fires and learns the floor
        val learnedFloor = c.capFor(prime.id)
        // Many more overshoot ticks: the prime must hold at/above the learned floor, not bounce to CPU_MIN.
        repeat(20) { c.feed(80f) }
        assertTrue(
            "prime held its learned floor instead of bouncing (floor=$learnedFloor, now=${c.capFor(prime.id)})",
            c.capFor(prime.id) >= learnedFloor,
        )
    }

    @Test
    fun parksUnderFrameCapWithoutOvershootOrBattery() {
        // The real-device case: a Game-Mode-capped game pinned at exactly 60 (no overshoot) AND plugged in
        // (drawW = null). The prime must STILL park once its cap is harvested to the floor — offlining is
        // the only way to cut prime power (it's vendor-floored mid-game) and it isn't gated on battery.
        val events = mutableListOf<Pair<List<Int>, Boolean>>()
        val c = AutoTuneController(
            writeCaps = { _, _ -> },
            releaseCaps = { _ -> },
            setCoresOnline = { cores, online -> events += cores to online },
        ).apply { targetFps = 60; aggressivePark = true }
        repeat(40) { c.feed(60f, cpuBusy = 8, gpuBusy = 10, cpuPeak = 40) } // capped at target, idle prime
        assertTrue("prime parked under a frame cap, plugged in", events.contains(listOf(prime.id) to false))
        assertTrue(c.isPrimeParked)
    }

    @Test
    fun doesNotParkDuringErraticLoadThenParksOnceFramesAreContinuous() {
        // The "park during loading" fix: a load renders erratically — frames stall and stutter — so the
        // prime is never offlined mid-load (which would only slow it). Parking arms only once the game has
        // rendered continuously for a sustained run (real gameplay, capped or not).
        val events = mutableListOf<Pair<List<Int>, Boolean>>()
        val c = AutoTuneController(
            writeCaps = { _, _ -> },
            releaseCaps = { _ -> },
            setCoresOnline = { cores, online -> events += cores to online },
        ).apply { targetFps = 60; aggressivePark = true }
        // Prime already at its floor (warm-start) so it'd be park-eligible immediately but for the gate.
        c.warmStart(policies, mapOf(prime.id to AutoTuneController.CPU_MIN_PERCENT))
        // A loading screen: each rendered frame is followed by a stall (no frames), which resets the
        // continuous-render streak — exactly the bouncing fps=-/spike pattern seen in the GameNative logs.
        repeat(20) {
            c.feed(110f, cpuBusy = 8, gpuBusy = 10, cpuPeak = 40)
            c.step(policies, fps = null, jankFrames = null, drawW = null, cpuTempC = 50, gpuTempC = 50)
        }
        assertTrue("never parks during an erratic load", events.isEmpty())
        // Game now renders continuously at its cap — the streak builds and parking arms.
        repeat(20) { c.feed(60f, cpuBusy = 8, gpuBusy = 10, cpuPeak = 40) }
        assertTrue("parks once frames are continuous", events.contains(listOf(prime.id) to false))
    }

    @Test
    fun doesNotParkWhenAggressiveParkOff() {
        val events = mutableListOf<Pair<List<Int>, Boolean>>()
        val c = AutoTuneController(
            writeCaps = { _, _ -> },
            releaseCaps = { _ -> },
            setCoresOnline = { cores, online -> events += cores to online },
        ).apply { targetFps = 60 } // aggressivePark defaults off
        repeat(40) { c.feed(70f, drawW = 5f) }
        assertTrue("no cores touched", events.isEmpty())
        assertTrue(!c.isPrimeParked)
    }

    @Test
    fun sustainedJankTriggersGpuFirstRecoveryNotMoreHarvest() {
        // The recovery lockout: a scene spike that judders (sustained jank) with the GPU pegged must STOP
        // harvesting and raise the GPU back — the field-log misfire where a hot CPU thread stole the raise
        // while the GPU sat starved at its floor and frames stuttered at 42–55 for ~90 s.
        val c = newController(60)
        repeat(60) { c.feed(80f) } // smooth overshoot, no load ⇒ everything harvested toward its floor
        val gpuLow = c.capFor(gpu.id)
        assertTrue("GPU harvested down first", gpuLow < 100)
        c.feed(52f, jank = 10, gpuBusy = 95, cpuBusy = 25) // 1st rough tick (arming)
        val locked = c.feed(52f, jank = 10, gpuBusy = 95, cpuBusy = 25) // 2nd ⇒ recovery engages
        assertEquals("recovery raises, never trims into the stutter", Action.RAISE, locked.action)
        assertTrue("the pegged GPU is given its clocks back", c.capFor(gpu.id) > gpuLow)
    }

    @Test
    fun steadyLowFpsWithoutJankIsNotTreatedAsAStutter() {
        // The guard that fixes media: a low rate with clean pacing (a 30 fps video under 60) must NOT trip
        // the recovery lockout — it stays on the harvest/content-cap path, never pins clocks chasing 60.
        val c = newController(60)
        c.feed(30f, cpuBusy = 12, gpuBusy = 88, jank = 0) // warm-up
        val d = c.feed(30f, cpuBusy = 12, gpuBusy = 88, jank = 0)
        assertEquals("no recovery raise on smooth low fps", Action.TRIM, d.action)
        assertTrue("clocks harvested, not pinned", c.capFor(prime.id) < 100)
    }

    @Test
    fun doesNotInstantlyHarvestBackAfterRecovering() {
        // The anti-relapse half: once frames recover, a cooldown holds the clocks for a few ticks instead of
        // immediately trimming back into the same stutter (the TRIM↔RAISE flip-flop seen in the field log).
        val c = newController(60)
        repeat(40) { c.feed(80f) } // harvest down to a low operating point
        repeat(3) { c.feed(50f, jank = 12, gpuBusy = 95, cpuBusy = 25) } // sustained stutter ⇒ locked
        // Smooth, well above target again: it would normally trim at once — but recovery-hold + cooldown
        // must suppress harvesting for several ticks first.
        repeat(5) {
            assertTrue("no instant re-harvest", c.feed(80f, jank = 0, gpuBusy = 30).action != Action.TRIM)
        }
        // After the cooldown lapses, harvesting resumes.
        var resumed = false
        repeat(10) { if (c.feed(80f, jank = 0, gpuBusy = 30).action == Action.TRIM) resumed = true }
        assertTrue("harvesting resumes once the cooldown ends", resumed)
    }

    @Test
    fun belowTargetWhenHotAndNotClockBoundHarvestsInsteadOfRaising() {
        // The thermal-cycle fix: below target with NOTHING saturated (bn=NONE) and the SoC warm (just under
        // the hard 85 °C trip) — raising a non-bottleneck can't lift fps (the limit is emulation/cap) and only
        // adds heat, which throttles fps further. It must harvest/hold, never RAISE, even though it's below
        // target. (Cool + not-bound still climbs via the probe path — see belowTargetClimbsWhenClocksActuallyHelp.)
        val c = newController(60)
        repeat(40) { c.feed(80f, cpuBusy = 30, gpuBusy = 30, cpuPeak = 40) } // harvest down, cool, none saturated
        // The abrupt 80→50 drop trips the fps-regret rail once (it undoes a possibly-hurtful trim); let it
        // settle, then the warm + not-clock-bound state must stop raising entirely.
        repeat(6) { c.feed(50f, jank = 0, cpuBusy = 40, gpuBusy = 35, cpuTemp = 82, cpuPeak = 45) }
        repeat(4) {
            val d = c.feed(50f, jank = 0, cpuBusy = 40, gpuBusy = 35, cpuTemp = 82, cpuPeak = 45)
            assertTrue("warm + not clock-bound must not raise into the heat", d.action != Action.RAISE)
        }
    }

    @Test
    fun belowTargetWhenCoolStillClimbsEvenWithoutASaturatedDomain() {
        // The guard on the thermal fix: when COOL, a below-target game must still be able to climb if raising
        // helps — the heat trigger must not suppress legitimate recovery on a cool SoC.
        val c = newController(60)
        repeat(8) { c.feed(90f) } // trim down to create headroom (cool, default 50 °C)
        val floored = c.capFor(prime.id)
        repeat(30) { c.feed(80f * c.capFor(prime.id) / 100f, cpuBusy = 25, gpuBusy = 25, cpuPeak = 90) }
        assertTrue("cool game still climbs when raising helps", c.capFor(prime.id) > floored)
    }

    @Test
    fun warmStartAppliesSavedCapsClampedToFloors() {
        val c = newController(60)
        c.warmStart(policies, mapOf(prime.id to 60, gpu.id to 10 /* below floor */))
        assertEquals(60, c.capFor(prime.id))
        assertEquals(AutoTuneController.GPU_MIN_PERCENT, c.capFor(gpu.id))
    }

    @Test
    fun releaseAlwaysReonlinesThePrimeEvenWhenNeverParked() {
        // Defense in depth (anti-stranding): release() must re-online the prime cluster unconditionally,
        // not gated on the primeParked flag, so a stale/false flag (or a parked state release didn't know
        // about) can never leave cores offline. Onlining already-online cores is a harmless no-op.
        val events = mutableListOf<Pair<List<Int>, Boolean>>()
        val c = AutoTuneController(
            writeCaps = { _, _ -> },
            releaseCaps = { _ -> },
            setCoresOnline = { cores, online -> events += cores to online },
        ).apply { targetFps = 60 } // aggressivePark defaults off ⇒ nothing ever parks
        repeat(5) { c.feed(70f) }
        assertTrue("nothing parked while aggressivePark is off", events.isEmpty())
        c.release(policies)
        assertTrue(
            "release re-onlines the prime cluster unconditionally",
            events.contains(listOf(prime.id) to true),
        )
    }

    private fun cpu(id: Int, maxKHz: Int) = CpuPolicyInfo(
        id = id,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy$id",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy$id/scaling_max_freq",
        currentMaxFreq = maxKHz,
        selectableMaxFreq = maxKHz,
        observedMaxFreq = maxKHz,
        minFreq = 300_000,
        supportedFrequencies = listOf(300_000, maxKHz),
        cpuIds = listOf(id),
    )

    private fun gpu() = CpuPolicyInfo(
        id = CpuPolicyInfo.GPU_POLICY_ID,
        policyPath = "/sys/class/kgsl/kgsl-3d0",
        scalingMaxPath = "/sys/class/kgsl/kgsl-3d0/max_pwrlevel",
        currentMaxFreq = 800_000,
        selectableMaxFreq = 800_000,
        observedMaxFreq = 800_000,
        minFreq = 200_000,
        supportedFrequencies = listOf(200_000, 800_000),
        cpuIds = listOf(0),
    )
}
