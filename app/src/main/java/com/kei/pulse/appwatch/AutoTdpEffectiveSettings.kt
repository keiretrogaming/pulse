package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.resolveEffectiveAutoTdpValues

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
    val values = resolveEffectiveAutoTdpValues(config, global)
    val target = PerAppConfig.snapFpsTarget(
        values.fpsTarget,
        PerAppConfig.fpsTargetsFor(soc),
    )
    return AutoTdpEffectiveSettings(
        targetFps = target,
        aggressivePark = values.aggressivePark,
        bias = values.bias,
        frameRatePath = if (PerAppConfig.useGameModeCap(soc, target, maxRefreshRate)) {
            AutoTdpEffectiveSettings.FrameRatePath.GAME_MODE_CAP
        } else {
            AutoTdpEffectiveSettings.FrameRatePath.REFRESH_RATE
        },
    )
}
