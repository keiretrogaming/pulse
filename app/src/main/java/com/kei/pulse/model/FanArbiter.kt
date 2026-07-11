package com.kei.pulse.model

import com.kei.pulse.data.FanController

/** What the fan reconcile should do this tick — returned by [FanArbiter.decide], executed by the service. */
sealed interface FanAction {
    /** Nothing to do (held / AutoTDP owns it / latched release / unreadable mode). */
    data object None : FanAction

    /** Drive the PULSE Custom fan loop (PI / curve via the gpio5_pwm2 duty). */
    data object RunCustomLoop : FanAction

    /** Write this vendor `fan_mode` (mode change or QS-tile drift correction). */
    data class SetVendorMode(val mode: Int) : FanAction

    /** Managed→unmanaged release: hand the fan to [mode] ONCE (the service latches so a user's later
     *  deliberate tile choice is never fought). */
    data class ReleaseToVendor(val mode: Int) : FanAction
}

/**
 * The fan arbitration as a pure, device-free resolver — the decision half of the service's
 * `reassertManagedFan` (the service gathers inputs, executes the returned [FanAction], and owns the
 * release latch + notify state). Extracted because this was the last big imperative fan decision, and its
 * release path silently assumed the fan was already at the vendor default: `stopCustomFan(restoreVendor=true)`
 * only restored anything when a Custom loop had been running, so turning the global Fan card OFF after
 * managing a vendor mode left that mode stuck forever. [FanArbiterTest] pins the full truth table.
 *
 * Read-cost contract: [decide] invokes [readLiveMode] (one root `settings get`) ONLY when the decision needs
 * the live mode — never for AutoTDP-owned, Custom-loop, or latched-release ticks. The fan reconcile shares
 * the process-wide PServer lock with the AutoTDP cap re-asserts, so needless reads are a regression class.
 */
object FanArbiter {

    /** The foreground app's fan override wins over the global Fan-card setting. */
    fun desiredMode(boundFanMode: Int?, managedFanMode: Int?): Int? =
        boundFanMode ?: managedFanMode

    /**
     * @param autoTdpActive an AutoTDP session owns the clocks (and set vendor Smart at session start);
     *   the fan only cascades the user's Custom loop, never other managed modes.
     * @param boundFanMode the foreground app's per-app fan (wins over the global).
     * @param managedFanMode the global Fan-card mode (null = PULSE doesn't manage the fan).
     * @param customFanSupported the gpio5_pwm2 duty node exists (cached by the service).
     * @param releaseLatched the managed→unmanaged release already ran; stay hands-off until managed again.
     * @param releaseMode the mode PULSE hands the fan to on release (vendor Smart everywhere today —
     *   the adaptive, thermally safe mode the rest of PULSE also restores).
     * @param readLiveMode lazily reads the live `fan_mode` (invoked at most once, only when needed).
     */
    fun decide(
        autoTdpActive: Boolean,
        boundFanMode: Int?,
        managedFanMode: Int?,
        customFanSupported: Boolean,
        releaseLatched: Boolean,
        releaseMode: Int,
        readLiveMode: () -> Int?,
    ): FanAction {
        val desired = desiredMode(boundFanMode, managedFanMode)
        // AutoTDP owns the fan: cascade the user's Custom loop if chosen + supported, else stand down
        // (startAutoTdp already forced vendor Smart for non-Custom).
        if (autoTdpActive) {
            return if (desired == FanController.CUSTOM && customFanSupported) {
                FanAction.RunCustomLoop
            } else {
                FanAction.None
            }
        }
        // The fan PULSE wants: a foreground app's per-app fan takes priority, else the global Fan-card mode.
        if (desired == null) {
            // Unmanaged. On the release EDGE (not latched), normalize the fan to [releaseMode] if it was left
            // somewhere else (a managed vendor mode, or Custom's manual passthrough). Once latched, PULSE is
            // hands-off — a mode the user then sets via the system tile is theirs.
            if (releaseLatched) return FanAction.None
            val live = readLiveMode() ?: return FanAction.None
            return if (live != releaseMode) FanAction.ReleaseToVendor(releaseMode) else FanAction.None
        }
        // Custom: run the loop where the PWM node exists; else fall back to Smart (never sit on a phantom mode).
        val effective = if (desired == FanController.CUSTOM) {
            if (customFanSupported) return FanAction.RunCustomLoop
            FanController.SMART
        } else {
            desired
        }
        // Managed vendor mode: write only on drift (the QS-tile fight); an unreadable mode skips the tick.
        val live = readLiveMode() ?: return FanAction.None
        return if (live != effective) FanAction.SetVendorMode(effective) else FanAction.None
    }
}
