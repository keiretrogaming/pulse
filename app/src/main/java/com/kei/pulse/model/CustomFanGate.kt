package com.kei.pulse.model

/**
 * Decides whether PULSE should ACTIVELY drive the Custom fan (vendor manual passthrough, `fan_mode=6`) or hand
 * the fan back to the vendor's quiet Smart curve.
 *
 * Why this exists: in manual passthrough the vendor stops regulating the fan, so if PULSE's process dies (a
 * force-stop SIGKILL, an LMK kill, the boot gap) the duty is left stranded at the vendor's ~50% mode-init reset
 * — i.e. the fan runs LOUD with no one to pin it back down. That bites hardest at IDLE (home screen), which is
 * exactly where the user force-stops / reboots.
 *
 * The fix: hand the fan to vendor Smart only when the chip is **cool** — below [engageTempC] AND the closed
 * loop only wants the floor. There, vendor Smart is just as quiet as PULSE's floor, so handing off doesn't make
 * the fan louder, and a dead PULSE leaves the fan quiet + vendor-regulated. We engage Custom the instant the
 * chip warms past the threshold OR the curve/PI wants active cooling, and release back to Smart only after
 * [RELEASE_TICKS] sustained cool ticks (engage-fast / release-slow ⇒ no flapping). So PULSE's quiet control
 * under load is fully preserved — only true idle is handed off.
 *
 * Applied to the global Custom fan only — NOT during AutoTDP, where the chip is under game load and the
 * fan↔clock cascade wants the fan ready to spin up.
 */
data class CustomFanState(val active: Boolean = false, val idleTicks: Int = 0)

object CustomFanGate {
    /** SoC temp (°C) below which the chip is "idle/cool" — vendor Smart is quiet here too, so it's safe AND
     *  quiet to hand the fan off. Above it, PULSE drives Custom (its control matters). On-device tunable. */
    const val ENGAGE_TEMP_C = 50

    /** Sustained cool+at-floor ticks before releasing manual passthrough back to vendor Smart (~one poll each). */
    const val RELEASE_TICKS = 5

    fun next(
        prev: CustomFanState,
        tempC: Int,
        targetPercent: Int,
        floorPercent: Int,
        engageTempC: Int = ENGAGE_TEMP_C,
    ): CustomFanState {
        val idle = tempC < engageTempC && targetPercent <= floorPercent
        return if (!idle) {
            CustomFanState(active = true, idleTicks = 0) // warm or active cooling wanted → drive Custom
        } else {
            val ticks = prev.idleTicks + 1
            CustomFanState(active = prev.active && ticks < RELEASE_TICKS, idleTicks = ticks)
        }
    }
}
