package com.kei.pulse.appwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Truth table for the battery-optimization-exemption prompt gate. */
class BatteryExemptionPromptTest {

    @Test fun promptsWhenEnabledNotExemptNotAsked() =
        assertTrue(BatteryExemptionPrompt.shouldPrompt(masterEnabled = true, isExempt = false, alreadyAsked = false))

    @Test fun neverPromptsWhenMasterOff() =
        assertFalse(BatteryExemptionPrompt.shouldPrompt(masterEnabled = false, isExempt = false, alreadyAsked = false))

    @Test fun neverPromptsWhenAlreadyExempt() =
        assertFalse(BatteryExemptionPrompt.shouldPrompt(masterEnabled = true, isExempt = true, alreadyAsked = false))

    @Test fun neverPromptsWhenAlreadyAsked() =
        assertFalse(BatteryExemptionPrompt.shouldPrompt(masterEnabled = true, isExempt = false, alreadyAsked = true))

    @Test fun exemptOutranksNotAsked() =
        assertFalse(BatteryExemptionPrompt.shouldPrompt(masterEnabled = true, isExempt = true, alreadyAsked = true))
}
