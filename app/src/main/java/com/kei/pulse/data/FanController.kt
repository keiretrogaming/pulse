package com.kei.pulse.data

import com.kei.pulse.model.FanCurve
import com.kei.pulse.root.RootSupport

/**
 * Fan control for the AYN Odin family via the stock `Settings.System` keys that the
 * device's own fan controller (com.odin.settings) reads. We do NOT write raw PWM — we set
 * the same fan *mode* the stock app sets, so the stock thermal-safety curve stays in
 * charge. Confirmed on Odin 3: `fan_mode=4` is Smart (the stock default).
 *
 * Values are written through PServer as root via `settings put`.
 */
class FanController {

    fun readMode(): Int? =
        RootSupport.runRootCommand("settings get system fan_mode")?.trim()?.toIntOrNull()

    /**
     * Ensure the vendor fan controller is in manual passthrough ([CUSTOM]) so it stops regulating the duty.
     * Read-first so we only write on drift (no spurious settings-change broadcasts) — a cheap re-assert vs the
     * QS fan tile. Returns true if manual mode is (now) active.
     *
     * On the Odin the very ACT of writing `fan_mode=6` resets the duty node to a ~50% mode-init default
     * (verified on-device: write fan_mode=6 → duty jumps to 25000), so the fan audibly revs up before our
     * next PWM write lands. Pass [reassertDuty] to pin the intended duty in the SAME root command as the mode
     * write — the trailing echo overwrites the 50% default before it can spin the fan up. Harmless where the
     * firmware doesn't reset (RP6/Thor): it just re-writes the value the duty already holds.
     */
    fun ensureManualMode(reassertDuty: Int? = null): Boolean {
        val before = readMode()
        if (before == CUSTOM) return true
        val cmd = buildString {
            append("settings put system fan_mode $CUSTOM")
            if (reassertDuty != null) append("; echo $reassertDuty > $FAN_DUTY_PATH")
        }
        RootSupport.runRootCommand(cmd)
        val after = readMode()
        android.util.Log.d("PulseFan", "ensureManualMode before=$before after=$after pinnedDuty=$reassertDuty ok=${after == CUSTOM}")
        return after == CUSTOM
    }

    fun setMode(mode: Int): Boolean {
        // Custom: put the vendor fan controller into MANUAL passthrough (fan_mode=CUSTOM) so it STOPS
        // auto-regulating the gpio5_pwm2 duty. Otherwise its Smart/auto curve re-pins the duty faster than we
        // re-assert (~200ms) and the fan audibly oscillates. Confirmed on the Odin (RP6/Thor share the firmware):
        // writing fan_mode=6 makes a written duty stick; PULSE then owns the duty via FanCurveController. The
        // service restores SMART when Custom is left or PULSE stops, so the fan is never stranded without
        // vendor thermal regulation.
        // Entering Custom: pin the quiet floor duty atomically with the fan_mode=6 write (writing fan_mode=6
        // resets the Odin's duty to ~50%). The running service then slews from the floor toward the curve.
        if (mode == CUSTOM) {
            val period = FanCurveController.readPeriodFromDevice() ?: FanCurveController.DEFAULT_PERIOD
            return ensureManualMode(FanCurve.percentToDuty(FanCurve.MIN_PERCENT, period))
        }
        // The stock controller caches the active mode and won't re-apply when set to the
        // same direction it already cached. Bounce through a different real mode first to
        // force a reload, then set the target. Reaching Smart bounces through SILENT (low),
        // never SPORT (high), so restoring Smart on game-exit dips quietly instead of revving.
        val bounce = bounceModeFor(mode)
        RootSupport.runRootCommand(
            "settings put system fan_mode $bounce; " +
                "settings put system fan_mode $mode; " +
                "settings put system is_quick_set_performance_and_fan_enable 1; " +
                "settings get system fan_mode",
        )
        val readBack = RootSupport.runRootCommand("settings get system fan_mode")
            ?.trim()?.toIntOrNull()
        android.util.Log.d("PulseFan", "setMode($mode) bounce=$bounce readBack=$readBack ok=${readBack == mode}")
        return readBack == mode
    }

    companion object {
        /** Smart is the confirmed stock default and the safe fallback. */
        const val SMART = 4
        const val SPORT = 5
        /** Silent (low fan) — the quiet bounce mode when re-applying Smart so the fan dips instead of revving. */
        const val SILENT = 1

        /**
         * The intermediate mode [setMode] bounces through to force the stock controller to reload [target]
         * (it caches the active mode and won't re-apply the same one). Must differ from [target]. Reaching
         * SMART routes through SILENT (low fan) rather than SPORT (high fan), so handing the fan back to Smart
         * — e.g. when AutoTDP restores it on game-exit — dips quietly instead of audibly revving the fan.
         */
        fun bounceModeFor(target: Int): Int = if (target == SMART) SILENT else SMART

        /**
         * PULSE-driven custom fan curve (Odin 3 only). Not a stock fan_mode — when selected, the service
         * drives the fan via [FanCurveController] (re-asserting the gpio5_pwm2/duty PWM node) instead of
         * writing fan_mode. Picked a value outside the stock 1/4/5 range so it can't collide.
         */
        const val CUSTOM = 6

        // The Odin 3's fan is a MAX31760 driven via this vendor PWM node (NOT the stock Settings keys):
        //   duty (0..period, world-writable) = fan speed; period (=50000); speed = RPM tach (read).
        // Writable on the Odin; absent on Thor/RP6 (different fan path) — gate on customFanAvailable.
        const val FAN_DUTY_PATH = "/sys/class/gpio5_pwm2/duty"
        const val FAN_PERIOD_PATH = "/sys/class/gpio5_pwm2/period"
        const val FAN_SPEED_PATH = "/sys/class/gpio5_pwm2/speed"

        // Confirmed on Odin 3 via the device's own fan toggle: Silent=1, Smart=4, Sport=5; Custom is ours.
        // The fan card shows the live fan_mode number, so any mismatch is self-revealing.
        val MODES: List<FanMode> = listOf(
            FanMode(SILENT, "Silent"),
            FanMode(SMART, "Smart"),
            FanMode(SPORT, "Sport"),
            FanMode(CUSTOM, "Custom"),
        )

        fun labelFor(mode: Int?): String =
            MODES.firstOrNull { it.value == mode }?.label ?: mode?.let { "Mode $it" } ?: "—"

        /**
         * True wherever the firmware exposes the writable gpio5_pwm2 fan PWM node — confirmed on the Odin 3,
         * Retroid Pocket 6, and AYN Thor (all expose `/sys/class/gpio5_pwm2/duty` 0..50000 + a `speed` RPM
         * tach; identical interface). Self-gates off on any device without the node.
         */
        fun customFanAvailable(): Boolean {
            val v = RootSupport.runRootCommand("cat $FAN_DUTY_PATH 2>/dev/null")?.trim()
            return !v.isNullOrEmpty() && v.toIntOrNull() != null
        }
    }
}

data class FanMode(val value: Int, val label: String)
