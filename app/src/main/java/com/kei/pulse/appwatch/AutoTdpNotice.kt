package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.PerAppConfig

/** Keeps every key/value setting together while Android wraps between complete setting groups. */
internal const val SETTING_NON_BREAKING_SPACE = '\u00a0'
internal const val SETTING_BREAKABLE_SPACE = ' '
internal const val SETTING_GROUP_WRAP_OPPORTUNITY = "  "
internal const val SETTING_GROUP_MARKER = '◆'
internal const val SETTING_KEY_VALUE_SEPARATOR = '›'
internal const val NOTICE_HEADING_SEPARATOR = ": "
internal const val AUTOTDP_NOTICE_LABEL = "AutoTDP"

data class AutoTdpNoticeSetting(
    val key: String,
    val value: String,
)

/** Structured content for an applied per-app AutoTDP notification. */
data class AutoTdpNoticeText(
    val appName: String,
    val settings: List<AutoTdpNoticeSetting>,
) {
    val compact: String
        get() = "$appName$NOTICE_HEADING_SEPARATOR$AUTOTDP_NOTICE_LABEL"

    val expanded: String
        get() = compact + settings.joinToString(separator = "") {
            "$SETTING_GROUP_WRAP_OPPORTUNITY$SETTING_GROUP_MARKER" +
                "$SETTING_NON_BREAKING_SPACE${it.displayKeepingTogether()}"
        }

    private fun AutoTdpNoticeSetting.displayKeepingTogether(): String =
        key.asNonBreakingSettingText() +
            SETTING_NON_BREAKING_SPACE +
            SETTING_KEY_VALUE_SEPARATOR +
            SETTING_NON_BREAKING_SPACE +
            value.asNonBreakingSettingText()

    /** A future multi-word key or value must remain part of its indivisible setting group. */
    private fun String.asNonBreakingSettingText(): String =
        replace(SETTING_BREAKABLE_SPACE, SETTING_NON_BREAKING_SPACE)
}

/** Builds the per-app AutoTDP switch notice, calling out only values that override global defaults. */
object AutoTdpNotice {
    fun text(
        appName: String,
        config: PerAppConfig,
        global: AppSettings,
        includeOverrides: Boolean = true,
    ): AutoTdpNoticeText {
        val overrides = mutableListOf<AutoTdpNoticeSetting>()

        if (includeOverrides) {
            config.fpsTarget
                ?.takeIf { it != global.autoTdpFpsTarget }
                ?.let { overrides += AutoTdpNoticeSetting("FPS target", PerAppConfig.fpsTargetLabel(it)) }
            config.aggressivePark
                ?.takeIf { it != global.autoTdpAggressivePark }
                ?.let { overrides += AutoTdpNoticeSetting("Aggressive park", if (it) "On" else "Off") }
            config.bias
                ?.takeIf { it != global.autoTdpBias }
                ?.let { overrides += AutoTdpNoticeSetting("Efficiency", it.label) }
        }

        // The compact template stays concise. BigTextStyle renders [settings] on one line when possible and
        // wraps only at the ordinary spaces before each diamond; each setting itself uses non-breaking spaces.
        return AutoTdpNoticeText(appName, overrides)
    }
}
