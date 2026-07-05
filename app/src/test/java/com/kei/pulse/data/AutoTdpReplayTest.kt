package com.kei.pulse.data

import com.kei.pulse.model.AutoTdpBias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Verifies the AutoTDP replay harness: the parser turns a `PulseAutoTdp` capture back into structured
 * ticks, and the replay re-runs them through the real [AutoTuneController] producing the same trajectory a
 * directly-driven controller would (and flagging where the recorded run differs).
 */
class AutoTdpReplayTest {

    private val header =
        "AUTOTDP-SESSION tgt=60 bias=EFFICIENT wattCap=1 " +
            "policies=2:3300000:6,7;1:2800000:2,3,4,5;0:2000000:0,1;-100:800000:0"

    @Test
    fun parsesSessionHeader() {
        val h = AutoTdpLogParser.parse(header).header!!
        assertEquals(60, h.targetFps)
        assertEquals(AutoTdpBias.EFFICIENT, h.bias)
        assertTrue("wattCap=1 ⇒ Odin power tuning on", h.wattCapAndSettle)
        assertEquals("4 clusters incl. the GPU", 4, h.policies.size)
        val prime = h.policies.maxByOrNull { it.selectableMaxFreq }!!
        assertEquals(2, prime.id)
        assertEquals(listOf(6, 7), prime.cpuIds)
        assertTrue("policy -100 is the GPU", h.policies.first { it.id == -100 }.isGpu)
        assertFalse("a CPU cluster is not the GPU", prime.isGpu)
    }

    @Test
    fun parsesAllTickInputsAndCaps() {
        val line = "tgt=60 fps=58.4 jank=2 tail=24ms act=TRIM bn=CPU cpuB=41 cpuPk=88 primePk=72 io=0 " +
            "gpuB=63 gpuL=30 capped=1 prk=0 stl=1 draw=12.34W cT=78 gT=74 " +
            "caps%[2:55,1:80,0:90,-100:45] curMHz[2:1689] gpu=389 lrn=100"
        val t = AutoTdpLogParser.parse(line).ticks.single()
        assertEquals(60, t.targetFps)
        assertEquals(58.4f, t.fps!!, 0.001f)
        assertEquals(2, t.jank)
        assertEquals(24f, t.worstFrameMs!!, 0.001f)
        assertEquals(12.34f, t.drawW!!, 0.001f)
        assertEquals(78, t.cpuTempC)
        assertEquals(74, t.gpuTempC)
        assertEquals(41, t.cpuBusy)
        assertEquals(63, t.gpuBusy)
        assertEquals("cpuPk = all-core peak ⇒ cpuCorePeak", 88, t.cpuCorePeak)
        assertEquals("primePk = prime-cluster peak ⇒ cpuPeak", 72, t.cpuPeak)
        assertEquals("TRIM", t.recordedAction)
        assertEquals(mapOf(2 to 55, 1 to 80, 0 to 90, -100 to 45), t.recordedCaps)
    }

    @Test
    fun foldsAbsentSignalSentinelsToNull() {
        val line = "tgt=60 fps=- jank=0 tail=-ms act=HOLD cpuB=-1 cpuPk=-1 primePk=-1 gpuB=-1 " +
            "draw=-W cT=50 gT=50 caps%[2:100]"
        val t = AutoTdpLogParser.parse(line).ticks.single()
        assertNull(t.fps)
        assertNull(t.worstFrameMs)
        assertNull(t.drawW)
        assertNull("cpuB=-1 ⇒ no signal", t.cpuBusy)
        assertNull(t.gpuBusy)
        assertNull(t.cpuPeak)
        assertNull(t.cpuCorePeak)
    }

    @Test
    fun toleratesOlderCaptureMissingPrimePk() {
        // A pre-replay capture has no primePk= field; it must parse with cpuPeak null and still replay.
        val line = "tgt=60 fps=90.0 jank=0 tail=11ms act=HOLD cpuB=20 cpuPk=70 gpuB=50 draw=6.0W " +
            "cT=50 gT=50 caps%[2:100,1:100,0:100,-100:100]"
        val t = AutoTdpLogParser.parse(line).ticks.single()
        assertNull("missing primePk ⇒ cpuPeak null", t.cpuPeak)
        assertEquals(70, t.cpuCorePeak)
    }

    @Test
    fun ignoresLogcatPrefixAndNoiseLines() {
        val capture = """
            06-27 12:34:56.700  1234  5678 D PulseAutoTdp: $header
            06-27 12:34:58.700  1234  5678 D PulseAutoTdp: tgt=60 fps=90.0 jank=0 tail=11ms act=HOLD cpuB=-1 cpuPk=-1 primePk=-1 gpuB=-1 draw=-W cT=50 gT=50 caps%[2:100,1:100,0:100,-100:100]
            06-27 12:34:59.000  1234  5678 D PulseAutoTdp: PERFLOCK-PROBE prime=policy2 cpus=[6, 7]
              /sys/module/msm_performance/parameters/cpu_min_freq = 0

        """.trimIndent()
        val parsed = AutoTdpLogParser.parse(capture)
        assertEquals(60, parsed.header!!.targetFps)
        assertEquals("only the real tick line is a tick", 1, parsed.ticks.size)
    }

    @Test
    fun replayRecomputesActionsThroughTheRealControllerAndFlagsDivergence() {
        // Two steady fps=90 ticks over a 60 target. The recorded actions here are deliberately BOTH "HOLD"
        // (a stand-in for old/buggy behavior); the real controller warms up (HOLD) then TRIMs the second tick.
        // The replay must recompute HOLD→TRIM and flag the second tick as a divergence vs the recording.
        val tick = "tgt=60 fps=90.0 jank=0 tail=11ms act=HOLD cpuB=-1 cpuPk=-1 primePk=-1 gpuB=-1 draw=-W " +
            "cT=50 gT=50 caps%[2:100,1:100,0:100,-100:100]"
        val result = AutoTdpReplay.replay("$header\n$tick\n$tick")
        assertEquals(listOf("HOLD", "TRIM"), result.replayedActions)
        assertEquals("the second tick diverges from the recorded HOLD", 1, result.actionDivergences.size)
        assertEquals(1, result.actionDivergences.single().index)
    }

    @Test
    fun replayRequiresASessionHeader() {
        val tick = "tgt=60 fps=90.0 jank=0 tail=11ms act=HOLD cpuB=-1 cpuPk=-1 primePk=-1 gpuB=-1 draw=-W " +
            "cT=50 gT=50 caps%[2:100]"
        val ex = runCatching { AutoTdpReplay.replay(tick) }.exceptionOrNull()
        assertTrue("missing header is a clear error", ex is IllegalArgumentException)
    }

    /**
     * Ad-hoc entry point: replay a real shared capture and print the divergence report. Skipped unless a
     * path is provided, e.g.
     * `gradlew testDebugUnitTest --tests "*AutoTdpReplayTest.adHoc*" -Dreplay.logcat=C:/path/session.logcat`.
     */
    @Test
    fun adHocReplayOfASharedCapture() {
        val path = System.getProperty("replay.logcat")
        assumeTrue("set -Dreplay.logcat=<path> to replay a real capture", path != null)
        val result = AutoTdpReplay.replay(java.io.File(path!!).readText())
        println(result.summary())
        println("replayed trajectory: " + result.replayedActions.joinToString(","))
        assertTrue("capture contained no AutoTDP ticks", result.outcomes.isNotEmpty())
    }
}
