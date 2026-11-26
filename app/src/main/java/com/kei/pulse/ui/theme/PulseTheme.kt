package com.kei.pulse.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.kei.pulse.i18n.LocalPulseStrings
import com.kei.pulse.i18n.resolvePulseStrings
import com.kei.pulse.model.AppColorSource
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.PulseThemeId

/** The active theme id, so theme-aware visuals (e.g. the Ad Astra background) can branch on it. */
val LocalPulseThemeId = staticCompositionLocalOf { PulseThemeId.SIGNAL }

/**
 * Thermal "ignition" level for the Ad Astra background, 0f (icy / idle) → 1f (max ignition).
 * Driven by the active power tier so the disk's colour temperature tracks the selected profile.
 */
val LocalThermalHeat = compositionLocalOf { 0f }

/** Maps the active power tier to the background ignition level used by Ad Astra. */
fun heatForTier(tier: PowerTier): Float = when (tier) {
    PowerTier.POWER_SAVING -> 0f
    PowerTier.BALANCED -> 0.5f
    PowerTier.MAX -> 1f
    PowerTier.CUSTOM -> 0.25f // manual: load is unknown, so lean cool
}

/**
 * PULSE is a fixed dark "heads-up display" experience, so the scheme is always the
 * deep-space telemetry palette regardless of system light/dark. If the user has chosen
 * a CUSTOM_ACCENT in Settings we honor it for the primary signal color; otherwise the
 * default signal cyan is used. Surfaces stay near-black either way to preserve the HUD.
 */
private class PulsePalette(
    val void: Color,
    val deep: Color,
    val surface: Color,
    val surfaceHi: Color,
    val surfaceHi2: Color,
    val line: Color,
    val primary: Color,
    val primaryDeep: Color,
    val secondary: Color,
    val tertiary: Color,
    val tertiaryDeep: Color,
    val ink: Color,
    val inkDim: Color,
    val inkFaint: Color,
    val onAccent: Color,
)

private fun paletteFor(themeId: PulseThemeId): PulsePalette = when (themeId) {
    // Signal — cool cyan family. Secondary/tertiary are harmonised cyans (mint + sky)
    // instead of the old green/amber clash, so the whole app reads as one signal.
    PulseThemeId.SIGNAL -> PulsePalette(
        void = PulseVoid, deep = PulseDeep, surface = PulseSurface, surfaceHi = PulseSurfaceHi,
        surfaceHi2 = PulseSurfaceHi2, line = PulseLine,
        primary = Color(0xFF3DF0E0), primaryDeep = Color(0xFF08312F),
        secondary = Color(0xFF5BF0C8), tertiary = Color(0xFF57B6FF), tertiaryDeep = Color(0xFF0A2738),
        ink = PulseInk, inkDim = PulseInkDim, inkFaint = PulseInkFaint, onAccent = PulseOnSignal,
    )
    // Crimson — deep blood-red family (no pink, no orange/yellow accents).
    PulseThemeId.CRIMSON -> PulsePalette(
        void = Color(0xFF0A0506), deep = Color(0xFF110608), surface = Color(0xFF16080A),
        surfaceHi = Color(0xFF1F0C0F), surfaceHi2 = Color(0xFF2A1014), line = Color(0xFF3A161B),
        primary = Color(0xFFC9201A), primaryDeep = Color(0xFF340809),
        secondary = Color(0xFFE84334), tertiary = Color(0xFFFF7A52), tertiaryDeep = Color(0xFF36120B),
        ink = Color(0xFFF4E2DF), inkDim = Color(0xFFB98C88), inkFaint = Color(0xFF7A5651), onAccent = Color(0xFFFFF0F0),
    )
    // Cyberpunk — OLED pure black, neon cyan -> violet -> magenta arc (coherent neon sweep).
    PulseThemeId.CYBERPUNK -> PulsePalette(
        void = Color(0xFF000000), deep = Color(0xFF040406), surface = Color(0xFF08080C),
        surfaceHi = Color(0xFF0E0E14), surfaceHi2 = Color(0xFF15151F), line = Color(0xFF21212E),
        primary = Color(0xFF25E6FF), primaryDeep = Color(0xFF052730),
        secondary = Color(0xFFFF2BD6), tertiary = Color(0xFF9D6BFF), tertiaryDeep = Color(0xFF1A0E33),
        ink = Color(0xFFD6ECFF), inkDim = Color(0xFF7C90AE), inkFaint = Color(0xFF4A586E), onAccent = PulseOnSignal,
    )
    // Ronin — Sumi black, single crimson accent over cold katana-steel metallics.
    PulseThemeId.RONIN -> PulsePalette(
        void = Color(0xFF0D0D0C), deep = Color(0xFF111113), surface = Color(0xFF1A1A1E),
        surfaceHi = Color(0xFF222227), surfaceHi2 = Color(0xFF2B2B31), line = Color(0xFF35353D),
        primary = Color(0xFFC8102E), primaryDeep = Color(0xFF3A0810),
        secondary = Color(0xFF8A95A5), tertiary = Color(0xFFAEB9C7), tertiaryDeep = Color(0xFF24282E),
        ink = Color(0xFFF5F5F0), inkDim = Color(0xFF9AA0AB), inkFaint = Color(0xFF626875),
        onAccent = Color(0xFFF5F5F0),
    )
    // Ad Astra — indigo-violet "deep void" (not dead OLED black), icy starlight primary, a
    // periwinkle-nebula secondary, and solar-flare amber for GPU/thermal "ignition" under load.
    PulseThemeId.ADASTRA -> PulsePalette(
        void = Color(0xFF05050A), deep = Color(0xFF08060F), surface = Color(0xFF0C0A16),
        surfaceHi = Color(0xFF12101F), surfaceHi2 = Color(0xFF1A1628), line = Color(0xFF272036),
        primary = Color(0xFF84FFFF), primaryDeep = Color(0xFF07343A),
        secondary = Color(0xFF8C9EFF), tertiary = Color(0xFFFF8F00), tertiaryDeep = Color(0xFF2E1500),
        ink = Color(0xFFE6F6FF), inkDim = Color(0xFF8B93B8), inkFaint = Color(0xFF4D5273),
        onAccent = Color(0xFF04060A),
    )
}

private fun pulseColorScheme(settings: AppSettings): androidx.compose.material3.ColorScheme {
    val p = paletteFor(settings.themeId)
    return darkColorScheme(
        primary = if (settings.colorSource == AppColorSource.CUSTOM_ACCENT) {
            Color(settings.accentColor)
        } else {
            p.primary
        },
        onPrimary = p.onAccent,
        primaryContainer = p.primaryDeep,
        onPrimaryContainer = p.primary,
        secondary = p.secondary,
        onSecondary = p.onAccent,
        secondaryContainer = p.surfaceHi2,
        onSecondaryContainer = p.ink,
        tertiary = p.tertiary,
        onTertiary = p.onAccent,
        tertiaryContainer = p.tertiaryDeep,
        onTertiaryContainer = p.tertiary,
        background = p.void,
        onBackground = p.ink,
        surface = p.surface,
        onSurface = p.ink,
        surfaceVariant = p.surfaceHi,
        onSurfaceVariant = p.inkDim,
        surfaceContainerLowest = p.void,
        surfaceContainerLow = p.deep,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surfaceHi,
        surfaceContainerHighest = p.surfaceHi2,
        surfaceBright = p.surfaceHi2,
        surfaceDim = p.deep,
        surfaceTint = p.primary,
        inverseSurface = p.ink,
        inverseOnSurface = p.void,
        outline = p.line,
        outlineVariant = p.inkFaint,
        error = PulseRed,
        onError = PulseOnSignal,
        errorContainer = PulseRedDeep,
        onErrorContainer = PulseRed,
        scrim = Color(0xCC000000),
    )
}

@Composable
fun PulseTheme(
    settings: AppSettings = AppSettings(),
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = pulseColorScheme(settings)
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PulseTypography,
        shapes = PulseShapes,
    ) {
        val context = LocalContext.current
        val strings = remember(context, settings.appLanguage) {
            resolvePulseStrings(settings.appLanguage, context)
        }
        CompositionLocalProvider(
            LocalPulseThemeId provides settings.themeId,
            LocalPulseStrings provides strings,
        ) {
            content()
        }
    }
}
