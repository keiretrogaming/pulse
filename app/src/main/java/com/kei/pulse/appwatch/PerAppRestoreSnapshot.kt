package com.kei.pulse.appwatch

import com.kei.pulse.model.PerAppRestoreState

/**
 * Device-free construction of the state restored after a chain of directly-switched bound apps.
 *
 * The first app's config is deliberately not an input: a later app in the same bound-app chain can change
 * controls the first app did not, and the eventual exit must still restore their pre-chain values.
 */
internal fun captureInitialRestoreState(
    values: Map<Int, Int>,
    activeTierLabel: String?,
    readFanMode: () -> Int?,
    readRefreshRate: () -> Int?,
    governor: String?,
): PerAppRestoreState = PerAppRestoreState(
    values = values,
    appliedDisplayProfileId = com.kei.pulse.model.ProfileStateResolver.MANUAL_PROFILE_ID,
    activeTierLabel = activeTierLabel,
    fanMode = readFanMode(),
    refreshRateHz = readRefreshRate(),
    governor = governor,
)
