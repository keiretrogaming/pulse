package com.kei.pulse.appwatch

/**
 * Pure classification of whether a foreground package is a "neutral" system surface — one PULSE never
 * tunes and never draws the OSD over (PULSE itself, systemui, any Settings app, a home launcher, an IME).
 *
 * Extracted from [ForegroundAppMonitorService.isNeutralForeground] so the package-name rules are unit
 * testable (the service method also short-circuits on the stateful `uiInForeground`, which stays there).
 * This is the guard behind two leaks fixed historically: the OSD popping up / the global-default AutoTDP
 * binding over a Settings screen instead of treating it as Android UI.
 */
object ForegroundClassifier {

    /**
     * A Settings surface. Covers AOSP Settings and its search activity (`com.android.settings`,
     * `com.android.settings.intelligence`) AND the **vendor** Settings apps the AYN/Retroid devices ship,
     * which are NOT under the AOSP prefix — the Odin's is `com.odin.settings` (class `com.ro.settings.*`),
     * and the sibling devices use the same `*.settings` vendor convention. Matching the `.settings` suffix
     * neutralizes all of them without needing each device's exact package hard-coded.
     */
    fun isSettingsPackage(pkg: String): Boolean =
        pkg.startsWith("com.android.settings") || pkg.endsWith(".settings")

    /**
     * True if [pkg] is a neutral system surface given the dynamically-resolved [homePackages] / [imePackages]
     * sets and the app's own [selfPackage]. [systemUiPackages] is the static shell set (systemui, etc.).
     */
    fun isNeutralPackage(
        pkg: String,
        selfPackage: String,
        systemUiPackages: Set<String>,
        homePackages: Set<String>,
        imePackages: Set<String>,
    ): Boolean =
        pkg == selfPackage ||
            pkg in systemUiPackages ||
            isSettingsPackage(pkg) ||
            pkg in homePackages ||
            pkg in imePackages
}
