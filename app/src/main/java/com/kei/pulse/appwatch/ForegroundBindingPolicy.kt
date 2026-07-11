package com.kei.pulse.appwatch

import com.kei.pulse.model.PerAppConfig

/** One authoritative classification of why PULSE should (or should not) bind the foreground package. */
internal enum class ForegroundBindingKind {
    NONE,
    EXPLICIT_AUTOTDP,
    EXPLICIT_CONFIG,
    GLOBAL_AUTOTDP,
}

internal fun resolveForegroundBinding(
    config: PerAppConfig?,
    globalAutoTdpEnabled: Boolean,
    eligibleForGlobalAutoTdp: Boolean,
): ForegroundBindingKind = when {
    config != null && PerAppConfig.isAuto(config.profileBinding) ->
        ForegroundBindingKind.EXPLICIT_AUTOTDP
    config != null && PerAppConfig.isAutoOff(config.profileBinding) &&
        config.fanMode == null && config.refreshRateHz == null ->
        ForegroundBindingKind.NONE
    config != null ->
        ForegroundBindingKind.EXPLICIT_CONFIG
    globalAutoTdpEnabled && eligibleForGlobalAutoTdp ->
        ForegroundBindingKind.GLOBAL_AUTOTDP
    else -> ForegroundBindingKind.NONE
}
