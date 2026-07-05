package com.kei.pulse.appwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for [ForegroundClassifier] — the neutral-foreground guard behind "the OSD must not pop up
 * over Settings" and "the global-default AutoTDP must not bind to a Settings screen".
 */
class ForegroundClassifierTest {

    private val self = "com.kei.pulse"
    private val systemUi = setOf("com.android.systemui", "com.android.settings")
    private val home = setOf("com.android.launcher3")
    private val ime = setOf("com.google.android.inputmethod.latin")

    private fun neutral(pkg: String) =
        ForegroundClassifier.isNeutralPackage(pkg, self, systemUi, home, ime)

    // --- Settings surfaces -------------------------------------------------------------------------

    @Test fun aospSettingsIsNeutral() = assertTrue(neutral("com.android.settings"))

    @Test fun aospSettingsSearchIsNeutral() = assertTrue(neutral("com.android.settings.intelligence"))

    /** The regression this commit fixes: the Odin's VENDOR settings app is `com.odin.settings`, not the
     *  AOSP package, so it slipped past the old `startsWith("com.android.settings")`-only guard and the
     *  OSD popped up over the device Settings menu. */
    @Test fun odinVendorSettingsIsNeutral() = assertTrue(neutral("com.odin.settings"))

    @Test fun siblingVendorSettingsIsNeutral() {
        // Same `*.settings` vendor convention on the other AYN/Retroid devices.
        assertTrue(neutral("com.thor.settings"))
        assertTrue(neutral("com.retroid.settings"))
    }

    @Test fun isSettingsPackageMatchesPrefixAndSuffix() {
        assertTrue(ForegroundClassifier.isSettingsPackage("com.android.settings"))
        assertTrue(ForegroundClassifier.isSettingsPackage("com.android.settings.intelligence"))
        assertTrue(ForegroundClassifier.isSettingsPackage("com.odin.settings"))
        assertFalse(ForegroundClassifier.isSettingsPackage("app.gamenative"))
    }

    // --- Other neutral surfaces --------------------------------------------------------------------

    @Test fun selfIsNeutral() = assertTrue(neutral(self))

    @Test fun systemUiIsNeutral() = assertTrue(neutral("com.android.systemui"))

    @Test fun homeLauncherIsNeutral() = assertTrue(neutral("com.android.launcher3"))

    @Test fun imeIsNeutral() = assertTrue(neutral("com.google.android.inputmethod.latin"))

    // --- Real apps/games are NOT neutral (still tuned + still get the OSD) --------------------------

    @Test fun gameIsNotNeutral() = assertFalse(neutral("app.gamenative"))

    @Test fun namedGameIsNotNeutral() = assertFalse(neutral("com.unknownworlds.subnautica"))
}
