package com.kei.pulse.data

import com.kei.pulse.model.AutoTdpBias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden replay fixture: a real Megabonk session on the Odin 3 (AutoTDP 60, EFFICIENT). Megabonk is the
 * lighter/cooler case (vs the prime-walled Stray fixture), so its recorded actions vary (RAISE/HOLD/TRIM) —
 * a more discriminating regression trajectory than Stray's converged all-HOLD.
 *
 * Fixture: `app/src/test/resources/autotdp/megabonk_odin3_efficient60.logcat`.
 */
class MegabonkOdin3FixtureTest {

    private fun load(): String =
        javaClass.getResourceAsStream("/autotdp/megabonk_odin3_efficient60.logcat")!!.bufferedReader().readText()

    @Test
    fun fixtureParsesWithHeaderAndTicks() {
        val cap = AutoTdpLogParser.parse(load())
        val h = cap.header
        assertNotNull("fixture must carry an AUTOTDP-SESSION header", h)
        h!!
        assertEquals(60, h.targetFps)
        assertEquals(AutoTdpBias.EFFICIENT, h.bias)
        assertTrue("Odin (CQ8725S) ⇒ watt cap on", h.wattCapAndSettle)
        assertEquals("prime cluster is policy 6", 6, h.policies.maxByOrNull { it.selectableMaxFreq }!!.id)
        assertTrue("a real session has many ticks", cap.ticks.size >= 25)
    }

    @Test
    fun replayedTrajectoryIsStable() {
        val result = AutoTdpReplay.replay(load())
        // GOLDEN trajectory: seeded from the recorded opening caps, Megabonk (light, ~60) HARVESTS down (TRIM),
        // settles (HOLD), and CHASES back up (RAISE) on the heavy swarm moments — exercising all three paths,
        // unlike Stray's converged HOLD. A controller change that alters these decisions changes this list ⇒
        // the test fails for review.
        val expected = listOf("HOLD") + List(18) { "TRIM" } + List(5) { "HOLD" } +
            List(2) { "TRIM" } + List(2) { "HOLD" } + List(3) { "RAISE" }
        assertEquals(expected, result.replayedActions)
        // This is the deliberately VARIED fixture — it must cover both harvest and chase.
        assertTrue("exercises harvest (TRIM)", result.replayedActions.contains("TRIM"))
        assertTrue("exercises chase (RAISE)", result.replayedActions.contains("RAISE"))
    }
}
