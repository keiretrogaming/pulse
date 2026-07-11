package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.PerAppConfig

/** The complete effective AutoTDP policy after applying per-app-over-global precedence and device limits. */
internal data class AutoTdpEffectiveSettings(
    val targetFps: Int,
    val aggressivePark: Boolean,
    val bias: AutoTdpBias,
    val frameRatePath: FrameRatePath,
) {
    enum class FrameRatePath { GAME_MODE_CAP, REFRESH_RATE }
}

internal fun resolveAutoTdpSettings(
    config: PerAppConfig?,
    global: AppSettings,
    soc: String?,
    maxRefreshRate: Int,
): AutoTdpEffectiveSettings {
    val target = PerAppConfig.snapFpsTarget(
        config?.fpsTarget ?: global.autoTdpFpsTarget,
        PerAppConfig.fpsTargetsFor(soc),
    )
    return AutoTdpEffectiveSettings(
        targetFps = target,
        aggressivePark = config?.aggressivePark ?: global.autoTdpAggressivePark,
        bias = AutoTdpBias.resolve(config?.bias, global.autoTdpBias),
        frameRatePath = if (PerAppConfig.useGameModeCap(soc, target, maxRefreshRate)) {
            AutoTdpEffectiveSettings.FrameRatePath.GAME_MODE_CAP
        } else {
            AutoTdpEffectiveSettings.FrameRatePath.REFRESH_RATE
        },
    )
}
