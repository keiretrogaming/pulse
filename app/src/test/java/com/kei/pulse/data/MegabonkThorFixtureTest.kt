package com.kei.pulse.data

import com.kei.pulse.model.AutoTdpBias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden replay fixture: a real Megabonk session on the **AYN Thor** (SD 8 Gen 2 / QCS8550, AutoTDP 60
 * EFFICIENT). Cross-device coverage — the Thor runs the **non-Odin path** (`wattCap=0`: no power ceiling,
 * no prime-walled settle, and a prime cluster that scales), so its trajectory differs from the Odin fixtures.
 *
 * Fixture: `app/src/test/resources/autotdp/megabonk_thor_efficient60.logcat`.
 */
class MegabonkThorFixtureTest {

    private fun load(): String =
        javaClass.getResourceAsStream("/autotdp/megabonk_thor_efficient60.logcat")!!.bufferedReader().readText()

    @Test
    fun fixtureParsesWithHeaderAndTicks() {
        val cap = AutoTdpLogParser.parse(load())
        val h = cap.header
        assertNotNull("fixture must carry an AUTOTDP-SESSION header", h)
        h!!
        assertEquals(60, h.targetFps)
        assertEquals(AutoTdpBias.EFFICIENT, h.bias)
        assertFalse("SD 8 Gen 2 (QCS8550) ⇒ watt cap / prime-walled settle OFF", h.wattCapAndSettle)
        assertEquals("Thor 3-cluster CPU + GPU", 4, h.policies.size)
        assertEquals("prime cluster is policy 7 (highest max freq)", 7, h.policies.maxByOrNull { it.selectableMaxFreq }!!.id)
        assertTrue("a real session has many ticks", cap.ticks.size >= 20)
    }

    @Test
    fun replayedTrajectoryIsStable() {
        val result = AutoTdpReplay.replay(load())
        // GOLDEN trajectory: seeded from the recorded opening caps, the Thor (non-Odin path, no power ceiling)
        // CHASES (RAISE) from the trimmed start, settles (HOLD), harvests (TRIM), then chases again — the
        // richest fixture, covering all three actions on the non-Odin code path. A controller change that
        // alters these decisions changes this list ⇒ the test fails for review.
        val expected = listOf("HOLD") + List(9) { "RAISE" } + List(4) { "HOLD" } +
            List(8) { "TRIM" } + List(6) { "RAISE" }
        assertEquals(expected, result.replayedActions)
        // All three action kinds appear — the richest of the fixtures, and on the non-Odin code path.
        assertTrue("covers HOLD/TRIM/RAISE", result.replayedActions.toSet() == setOf("HOLD", "TRIM", "RAISE"))
    }
}
