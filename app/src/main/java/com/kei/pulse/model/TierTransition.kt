package com.kei.pulse.model

/**
 * Pure, device-free resolver for the side-control UI state after a power-tier transition.
 *
 * These are the knobs that [com.kei.pulse.ui.TunerViewModel.applyTier] historically set by
 * hand-copying a scatter of `MutableStateFlow` assignments — the exact place state-restoration bugs
 * slip past a green build: a transition that releases/clears device tuning but forgets to mirror it
 * in the "current values" UI, leaving the readout diverged from real sysfs. Centralizing the
 * contract here makes those omissions a single, unit-tested source of truth.
 *
 * Scope: the seven simple flag/value side-controls. The governor is restored via its own controller
 * path (it is not a plain flag), so it is intentionally not modeled here.
 */
data class SideControlState(
    val powerTargetEnabled: Boolean = false,
    val powerTargetPercent: Int = 100,
    val powerTargetCpuOnly: Boolean = false,
    val gpuLocked: Boolean = false,
    val gpuFloorPercent: Int = 0,
    val cpuFloorPercent: Int = 0,
    val primeCoreBoostLimited: Boolean = false,
) {
    companion object {
        /** All side-controls at their neutral defaults (nothing governing). */
        val CLEARED = SideControlState()
    }
}

object TierTransition {

    /**
     * Side-control state after a clean PRESET (Max / Balanced / Power Saving) applies. A preset is
     * governed by the tier, so it CLEARS Power Target, the GPU lock + floor, the CPU floor, and the
     * prime-boost limit (each is also RELEASED on the device by `applyTier`). Power Target's percent
     * and cpu-only flag are inert while disabled and are left as the user's last values (the next
     * Custom restore overwrites them).
     */
    fun afterPreset(current: SideControlState): SideControlState = current.copy(
        powerTargetEnabled = false,
        gpuLocked = false,
        gpuFloorPercent = 0,
        cpuFloorPercent = 0,
        primeCoreBoostLimited = false,
    )

    /**
     * Side-control state after switching to CUSTOM: restore every saved knob. A [CustomTuning] flag
     * with no mapping here is a restoration bug — the saved value would be silently dropped.
     */
    fun afterCustomRestore(saved: CustomTuning): SideControlState = saved.sideControls

    // --- Individual side-control toggles (the per-control interlink matrix) ---

    /**
     * After toggling the GPU clock lock. Locking CLEARS the GPU floor — both pin the GPU's min power level,
     * and the lock (pin-to-current) wins, so leaving a stale floor would diverge the UI from the device.
     * Unlocking leaves the floor as the user's value.
     */
    fun afterGpuLock(current: SideControlState, locked: Boolean): SideControlState =
        if (locked) current.copy(gpuLocked = true, gpuFloorPercent = 0) else current.copy(gpuLocked = false)

    /** After toggling Power Target on/off. The tier→Custom switch + cap apply stay in the ViewModel. */
    fun afterPowerTargetEnabled(current: SideControlState, enabled: Boolean): SideControlState =
        current.copy(powerTargetEnabled = enabled)

    /** After setting the CPU floor percentage (a single-control value; no sibling interlink). */
    fun afterCpuFloor(current: SideControlState, percent: Int): SideControlState =
        current.copy(cpuFloorPercent = percent)

    /**
     * After setting the GPU floor percentage. Note: this does NOT clear the GPU lock (current behavior is
     * asymmetric — lock clears floor, floor does not clear lock); encoded faithfully, not changed here.
     */
    fun afterGpuFloor(current: SideControlState, percent: Int): SideControlState =
        current.copy(gpuFloorPercent = percent)

    /** After toggling the prime-core boost limit (a single flag; no sibling interlink). */
    fun afterPrimeBoost(current: SideControlState, limited: Boolean): SideControlState =
        current.copy(primeCoreBoostLimited = limited)
}
