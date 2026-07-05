package com.kei.pulse.appwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AutoTDP global-default engagement policy: engage any non-neutral foreground EXCEPT a known benchmark
 * (tuning mid-benchmark trims clocks/governor and tanks the score — the device is what's being measured).
 */
class AutoTdpEngagementTest {

    @Test
    fun `games and ordinary apps engage`() {
        assertTrue(AutoTdpEngagement.shouldEngage("com.unknownworlds.subnautica", neutralForeground = false))
        assertTrue(AutoTdpEngagement.shouldEngage("app.gamenative", neutralForeground = false))
    }

    @Test
    fun `neutral foreground never engages`() {
        assertFalse(AutoTdpEngagement.shouldEngage("com.android.settings", neutralForeground = true))
        assertFalse(AutoTdpEngagement.shouldEngage(null, neutralForeground = false))
    }

    @Test
    fun `known benchmarks are excluded from the global default`() {
        for (pkg in listOf(
            "com.futuremark.dmandroid.application", // 3DMark
            "com.ul.benchmarks.wildlife",           // UL Wild Life
            "com.primatelabs.geekbench6",           // Geekbench 6
            "com.antutu.ABenchMark",                // AnTuTu
            "com.glbenchmark.glbenchmark27",        // GFXBench
        )) {
            assertTrue(pkg, AutoTdpEngagement.isBenchmark(pkg))
            assertFalse(pkg, AutoTdpEngagement.shouldEngage(pkg, neutralForeground = false))
        }
    }

    @Test
    fun `benchmark match is prefix-anchored, not substring`() {
        // A package merely CONTAINING a vendor string must not be excluded.
        assertFalse(AutoTdpEngagement.isBenchmark("com.somegame.com.primatelabs"))
        assertTrue(AutoTdpEngagement.shouldEngage("com.somegame.com.primatelabs", neutralForeground = false))
    }
}
