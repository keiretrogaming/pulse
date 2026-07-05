package com.kei.pulse.appwatch

/**
 * Pure decision for the one-time "let PULSE ignore battery optimization" system prompt.
 *
 * PULSE keeps a persistent foreground watcher alive (global Fan/RGB, AutoTDP, OSD). With battery
 * optimization ON, the OS throttles that service in Doze and is quicker to reclaim it under memory
 * pressure — so when the master switch is on we ask the user, once, to exempt PULSE. (Nothing can
 * survive an explicit force-stop / recents-swipe; this only addresses Doze + background reclaim.)
 */
object BatteryExemptionPrompt {

    /**
     * Show the exemption prompt only when PULSE is meant to run ([masterEnabled]), it isn't already
     * exempt ([isExempt]), and we haven't already asked ([alreadyAsked]) — so it never nags.
     */
    fun shouldPrompt(masterEnabled: Boolean, isExempt: Boolean, alreadyAsked: Boolean): Boolean =
        masterEnabled && !isExempt && !alreadyAsked
}
