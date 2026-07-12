package com.kei.pulse.model

/**
 * Snapshot of the user's Custom-mode tuning knobs, persisted separately from the live
 * [AppSettings] so that applying a preset (which clears the live Power Target / GPU lock for
 * the duration the preset governs) cannot erase what the user had set up in Custom. Restored
 * when the user cycles back to Custom from a preset.
 */
data class CustomTuning(
    val sideControls: SideControlState = SideControlState(),
    /** GovernorController.OPTIONS label the user last chose while in Custom; null = leave alone. */
    val governorLabel: String? = null,
)
