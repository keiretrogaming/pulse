package com.kei.pulse.model

/** One authoritative classification of which performance binding governs a foreground package. */
enum class AutoTdpBindingKind(val autoTdpEnabled: Boolean) {
    NONE(false),
    EXPLICIT_AUTOTDP(true),
    EXPLICIT_CONFIG(false),
    GLOBAL_AUTOTDP(true),
}

fun resolveAutoTdpBinding(
    config: PerAppConfig?,
    globalAutoTdpEnabled: Boolean,
    eligibleForGlobalAutoTdp: Boolean,
): AutoTdpBindingKind = when {
    config != null && PerAppConfig.isAuto(config.profileBinding) ->
        AutoTdpBindingKind.EXPLICIT_AUTOTDP
    config != null && PerAppConfig.isAutoOff(config.profileBinding) ->
        if (config.fanMode != null || config.refreshRateHz != null) {
            AutoTdpBindingKind.EXPLICIT_CONFIG
        } else {
            AutoTdpBindingKind.NONE
        }
    config?.profileBinding != null ->
        AutoTdpBindingKind.EXPLICIT_CONFIG
    globalAutoTdpEnabled && eligibleForGlobalAutoTdp ->
        AutoTdpBindingKind.GLOBAL_AUTOTDP
    config != null ->
        AutoTdpBindingKind.EXPLICIT_CONFIG
    else -> AutoTdpBindingKind.NONE
}
