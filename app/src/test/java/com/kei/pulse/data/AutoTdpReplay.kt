package com.kei.pulse.data

import com.kei.pulse.data.AutoTdpLogParser.ParsedCapture
import com.kei.pulse.data.AutoTdpLogParser.TickRecord

/**
 * Re-runs a parsed `PulseAutoTdp` capture through the **real** [AutoTuneController.step] off-device, with
 * no-op device writers (exactly as `AutoTuneControllerTest` drives it). This turns a 20-minute physical
 * tuning test into a millisecond JVM re-run: feed a real session's tick stream into the *current*
 * controller code and see the action/cap trajectory it now produces, and where it diverges from what the
 * device recorded.
 *
 * **Open-loop only.** This answers *"does my change alter which action/caps the controller produces on this
 * real session?"* (decision-logic regression). It does NOT predict the new policy's resulting fps/temp/power
 * — those would be the device's closed-loop response to different actions, which isn't in the recording.
 * Never read a replayed trajectory as an fps prediction.
 *
 * Caveats on the recorded-vs-replayed comparison: the replay is **seeded from the recorded opening caps** so it
 * starts where the recording did, leaving a **one-tick offset** (the logged caps are post-decision, so tick 0 is
 * seeded with its own result) and the **rounded** logged inputs as the residual sources of minor divergence.
 * That's why the divergence report is a human-read diagnostic, while the deterministic golden-regression test
 * asserts the replayed trajectory against a checked-in expected trajectory (replay-vs-expected, not
 * replay-vs-recorded).
 */
object AutoTdpReplay {

    /** One replayed tick: what the device recorded vs what the current controller code now produces. */
    data class StepOutcome(
        val index: Int,
        val recordedAction: String,
        val replayedAction: String,
        val recordedCaps: Map<Int, Int>,
        val replayedCaps: Map<Int, Int>,
    ) {
        val actionDiverged: Boolean get() = recordedAction != replayedAction

        /** Caps differ on any policy the recording reported (compared via the controller's 100% default). */
        val capsDiverged: Boolean get() = recordedCaps.any { (id, pct) -> replayedCaps[id] != pct }
    }

    data class Result(val outcomes: List<StepOutcome>) {
        val replayedActions: List<String> get() = outcomes.map { it.replayedAction }
        val actionDivergences: List<StepOutcome> get() = outcomes.filter { it.actionDiverged }
        val capDivergences: List<StepOutcome> get() = outcomes.filter { it.capsDiverged }

        fun summary(): String = buildString {
            appendLine("replayed ${outcomes.size} ticks")
            appendLine("action divergences vs recorded: ${actionDivergences.size}")
            appendLine("cap divergences vs recorded:    ${capDivergences.size}")
            actionDivergences.take(20).forEach {
                appendLine("  tick ${it.index}: recorded=${it.recordedAction} replayed=${it.replayedAction}")
            }
        }
    }

    /** Replay a parsed capture; requires the one-shot `AUTOTDP-SESSION` header (controller config + policies). */
    fun replay(capture: ParsedCapture): Result {
        val header = requireNotNull(capture.header) {
            "capture has no AUTOTDP-SESSION header — re-capture with a build that emits it (AUTO_DEBUG on)"
        }
        val controller = AutoTuneController(
            writeCaps = { _, _ -> },
            releaseCaps = { _ -> },
            setCoresOnline = { _, _ -> },
        ).apply {
            targetFps = header.targetFps
            bias = header.bias
            wattCapAndSettleEnabled = header.wattCapAndSettle
        }
        // Seed from the recorded opening caps so the replay starts where the recording did — otherwise a
        // session warm-started to TRIMMED caps and RAISING back replays degenerately (a fresh controller at
        // MAX caps has nothing to raise into ⇒ all-HOLD). One-tick offset: the logged caps are POST-decision,
        // so tick 0 is seeded with its own result — acceptable for decision-regression.
        capture.ticks.firstOrNull()?.let { controller.warmStart(header.policies, it.recordedCaps) }
        val outcomes = capture.ticks.mapIndexed { i, t ->
            val action = stepOf(controller, header.policies, t).action.name
            val replayedCaps = header.policies.associate { it.id to controller.capFor(it.id) }
            StepOutcome(i, t.recordedAction, action, t.recordedCaps, replayedCaps)
        }
        return Result(outcomes)
    }

    private fun stepOf(c: AutoTuneController, policies: List<com.kei.pulse.model.CpuPolicyInfo>, t: TickRecord) =
        c.step(
            policies = policies,
            fps = t.fps,
            jankFrames = t.jank,
            drawW = t.drawW,
            cpuTempC = t.cpuTempC,
            gpuTempC = t.gpuTempC,
            cpuBusyPercent = t.cpuBusy,
            gpuBusyPercent = t.gpuBusy,
            cpuPeakPercent = t.cpuPeak,
            cpuCorePeakPercent = t.cpuCorePeak,
            worstFrameMs = t.worstFrameMs,
        )

    fun replay(text: String): Result = replay(AutoTdpLogParser.parse(text))
}
