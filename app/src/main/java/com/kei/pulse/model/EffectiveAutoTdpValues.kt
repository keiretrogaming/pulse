package com.kei.pulse.model

/** Per-app-over-global AutoTDP values before device-specific target validation and enforcement. */
data class EffectiveAutoTdpValues(
    val fpsTarget: Int,
    val aggressivePark: Boolean,
    val bias: AutoTdpBias,
)

fun resolveEffectiveAutoTdpValues(
    config: PerAppConfig?,
    global: AppSettings,
): EffectiveAutoTdpValues = EffectiveAutoTdpValues(
    fpsTarget = config?.fpsTarget ?: global.autoTdpFpsTarget,
    aggressivePark = config?.aggressivePark ?: global.autoTdpAggressivePark,
    bias = AutoTdpBias.resolve(config?.bias, global.autoTdpBias),
)
