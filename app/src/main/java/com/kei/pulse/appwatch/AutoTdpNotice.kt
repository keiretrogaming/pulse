package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.PerAppConfig

/** Compact and expanded notification text for an applied per-app AutoTDP profile. */
data class AutoTdpNoticeText(
    val compact: String,
    val expanded: String,
)

/** Builds the per-app AutoTDP switch notice, calling out only values that override global defaults. */
object AutoTdpNotice {
    fun text(
        appName: String,
        config: PerAppConfig,
        global: AppSettings,
        includeOverrides: Boolean = true,
    ): AutoTdpNoticeText {
        val overrides = mutableListOf<Override>()

        if (includeOverrides) {
            config.fpsTarget
                ?.takeIf { it != global.autoTdpFpsTarget }
                ?.let { overrides += Override("FPS target", PerAppConfig.fpsTargetLabel(it)) }
            config.aggressivePark
                ?.takeIf { it != global.autoTdpAggressivePark }
                ?.let { overrides += Override("Aggressive park", if (it) "On" else "Off") }
            config.bias
                ?.takeIf { it != global.autoTdpBias }
                ?.let { overrides += Override("Efficiency", it.label) }
        }

        val heading = "$appName: AutoTDP"
        return AutoTdpNoticeText(
            // The system owns the collapsed notification height and may ellipsize a long content line.
            // Keep that template concise; the complete detail lives in the wrapping BigTextStyle below.
            compact = heading,
            // Let BigTextStyle keep this on one line when it fits. The ordinary spaces before each diamond
            // are safe wrap points; everything from the diamond through its key/value uses non-breaking
            // spaces, so Android can reflow for the real screen width without splitting a setting.
            expanded = heading + overrides.joinToString(separator = "") {
                "  ◆\u00a0${keepTogether(it.display())}"
            },
        )
    }

    private fun keepTogether(setting: String): String = setting.replace(' ', '\u00a0')

    private data class Override(val key: String, val value: String) {
        fun display(): String = "$key › $value"
    }
}
