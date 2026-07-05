package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierTransitionTest {

    /** A fully-engaged Custom side-control state (every knob non-default) to prove clears/restores bite. */
    private val engaged = SideControlState(
        powerTargetEnabled = true,
        powerTargetPercent = 65,
        powerTargetCpuOnly = true,
        gpuLocked = true,
        gpuFloorPercent = 40,
        cpuFloorPercent = 30,
        primeCoreBoostLimited = true,
    )

    @Test
    fun `preset clears every governing side-control`() {
        val after = TierTransition.afterPreset(engaged)
        assertFalse("Power Target must clear on a preset", after.powerTargetEnabled)
        assertFalse("GPU lock must clear on a preset", after.gpuLocked)
        assertEquals("GPU floor must release on a preset", 0, after.gpuFloorPercent)
        assertEquals("CPU floor must release on a preset", 0, after.cpuFloorPercent)
        // The bit applyTier historically forgot (open Known Bug): a preset must release the
        // prime-boost limit too, or the toggle stays lit while the device cap is the preset's.
        assertFalse("Prime-boost limit must clear on a preset", after.primeCoreBoostLimited)
    }

    @Test
    fun `preset leaves Power Target percent and cpu-only untouched`() {
        // Faithful to current behavior: only the enabled flag is cleared; the inert percent / cpu-only
        // are left as-is (overwritten on the next Custom restore). Pins this so a future edit doesn't
        // silently start zeroing them.
        val after = TierTransition.afterPreset(engaged)
        assertEquals(65, after.powerTargetPercent)
        assertTrue(after.powerTargetCpuOnly)
    }

    @Test
    fun `preset from an already-clear state is a no-op`() {
        assertEquals(SideControlState.CLEARED, TierTransition.afterPreset(SideControlState.CLEARED))
    }

    @Test
    fun `custom restore copies every saved knob`() {
        val saved = CustomTuning(
            powerTargetEnabled = true,
            powerTargetPercent = 72,
            powerTargetCpuOnly = true,
            gpuLocked = true,
            gpuFloorPercent = 55,
            cpuFloorPercent = 25,
            primeCoreBoostLimited = true,
            governorLabel = "Performance",
        )
        val after = TierTransition.afterCustomRestore(saved)
        assertEquals(saved.powerTargetEnabled, after.powerTargetEnabled)
        assertEquals(saved.powerTargetPercent, after.powerTargetPercent)
        assertEquals(saved.powerTargetCpuOnly, after.powerTargetCpuOnly)
        assertEquals(saved.gpuLocked, after.gpuLocked)
        assertEquals(saved.gpuFloorPercent, after.gpuFloorPercent)
        assertEquals(saved.cpuFloorPercent, after.cpuFloorPercent)
        assertEquals(saved.primeCoreBoostLimited, after.primeCoreBoostLimited)
    }

    @Test
    fun `custom restore of default tuning yields the cleared state`() {
        assertEquals(SideControlState.CLEARED, TierTransition.afterCustomRestore(CustomTuning()))
    }

    @Test
    fun `locking the GPU clears the GPU floor`() {
        // The interlink rule that lives implicitly in setGpuLocked: a lock pins the GPU min, so a stale floor
        // would diverge the UI from the device. (RED until afterGpuLock clears the floor.)
        val after = TierTransition.afterGpuLock(engaged, locked = true)
        assertTrue(after.gpuLocked)
        assertEquals("locking must clear the GPU floor", 0, after.gpuFloorPercent)
    }

    @Test
    fun `unlocking the GPU leaves the floor untouched`() {
        val after = TierTransition.afterGpuLock(engaged, locked = false)
        assertFalse(after.gpuLocked)
        assertEquals(40, after.gpuFloorPercent) // unlock doesn't touch the floor
    }

    @Test
    fun `single-control toggles set only their own field`() {
        assertEquals(false, TierTransition.afterPowerTargetEnabled(engaged, false).powerTargetEnabled)
        assertEquals(15, TierTransition.afterCpuFloor(engaged, 15).cpuFloorPercent)
        assertEquals(25, TierTransition.afterGpuFloor(engaged, 25).gpuFloorPercent)
        assertEquals(false, TierTransition.afterPrimeBoost(engaged, false).primeCoreBoostLimited)
        // afterGpuFloor must NOT clear the lock (faithful to the asymmetric current behavior).
        assertTrue("setting a GPU floor leaves the lock as-is", TierTransition.afterGpuFloor(engaged, 25).gpuLocked)
        // A single-control toggle leaves the other six fields exactly as they were.
        assertEquals(engaged.copy(cpuFloorPercent = 15), TierTransition.afterCpuFloor(engaged, 15))
    }
}
