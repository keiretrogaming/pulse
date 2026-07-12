package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.CustomTuning
import com.kei.pulse.model.SideControlState
import com.kei.pulse.model.toSideControlState

/** Pure persistence snapshots for one Quick Access Power Target edit. */
internal data class QuickAccessPowerTargetPersistence(
    val liveSideControls: SideControlState,
    val customTuning: CustomTuning,
)

/**
 * Live controls start from current settings, while saved Custom controls start from their own snapshot.
 * A preset may have cleared the live GPU/floor/boost fields, which must not erase the values Custom restores.
 */
internal fun resolveQuickAccessPowerTargetPersistence(
    settings: AppSettings,
    customTuning: CustomTuning,
    percent: Int,
    enabled: Boolean,
): QuickAccessPowerTargetPersistence = QuickAccessPowerTargetPersistence(
    liveSideControls = settings.toSideControlState().copy(
        powerTargetEnabled = enabled,
        powerTargetPercent = percent,
    ),
    customTuning = customTuning.copy(
        sideControls = customTuning.sideControls.copy(
            powerTargetEnabled = enabled,
            powerTargetPercent = percent,
        ),
    ),
)
