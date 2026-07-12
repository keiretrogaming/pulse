package com.kei.pulse.data

import com.kei.pulse.model.SideControlState
import org.junit.Assert.assertNotNull
import org.junit.Test

class SettingsStorageApiTest {
    @Test
    fun `tuning persistence accepts one canonical snapshot plus tier`() {
        val persist: suspend SettingsStorage.(SideControlState, String) -> Unit =
            SettingsStorage::persistTuningState

        assertNotNull(persist)
    }
}
