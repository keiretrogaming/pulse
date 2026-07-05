package com.kei.pulse.data

import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.CpuPolicyInfo

/**
 * Parses a saved `PulseAutoTdp` logcat capture back into the inputs the AutoTDP controller saw, so a
 * real gaming session can be **re-run through the actual `AutoTuneController.step()`** off-device (see
 * [AutoTdpReplay]). This is the test-side half of the replay harness — the device emits the capture
 * (`ForegroundAppMonitorService.logAutoTdp`, `AUTO_DEBUG`), this turns it back into structured ticks.
 *
 * Two line kinds are consumed; everything else (logcat noise, `PERFLOCK-PROBE`, the periodic `mn/mx`
 * read-back text, blanks) is ignored:
 *  - the one-shot **`AUTOTDP-SESSION`** header — session config the per-tick line can't carry
 *    (`tgt`, `bias`, the Odin `wattCap` gate) plus the static cluster layout (`policies`);
 *  - each per-tick line (`tgt=… act=…`) — the 10 `step()` input scalars + the recorded action/caps.
 *
 * Robust to the logcat prefix (we match `key=value` tokens anywhere in the line) and to **older
 * captures missing `primePk`** (that input parses as null, exactly as `step()` treats an absent signal).
 *
 * Fidelity caveat: the log rounds values (fps %.1f, draw %.2f, temps int, tail %.0f), so replay runs on
 * rounded inputs ≈ live — faithful for decision-logic regression, NOT bit-exact.
 */
object AutoTdpLogParser {

    /** One recorded AutoTDP tick: the [AutoTuneController.step] inputs plus the recorded outputs. */
    data class TickRecord(
        val targetFps: Int,
        val fps: Float?,
        val jank: Int?,
        val worstFrameMs: Float?,
        val drawW: Float?,
        val cpuTempC: Int?,
        val gpuTempC: Int?,
        val cpuBusy: Int?,
        val gpuBusy: Int?,
        /** `primePk` — the PRIME-cluster peak = step()'s `cpuPeakPercent` (the park guard). */
        val cpuPeak: Int?,
        /** `cpuPk` — the ALL-core peak = step()'s `cpuCorePeakPercent` (the hot-thread detector). */
        val cpuCorePeak: Int?,
        val recordedAction: String,
        val recordedCaps: Map<Int, Int>,
    )

    /** Session-scoped config the per-tick line can't carry; needed to configure the replay controller. */
    data class SessionHeader(
        val targetFps: Int,
        val bias: AutoTdpBias,
        val wattCapAndSettle: Boolean,
        val policies: List<CpuPolicyInfo>,
    )

    data class ParsedCapture(val header: SessionHeader?, val ticks: List<TickRecord>)

    private val KV = Regex("""([A-Za-z]+)=(\S+)""")
    private val CAPS = Regex("""caps%\[([^\]]*)\]""")

    fun parse(text: String): ParsedCapture {
        var header: SessionHeader? = null
        val ticks = mutableListOf<TickRecord>()
        text.lineSequence().forEach { line ->
            when {
                "AUTOTDP-SESSION" in line -> header = parseHeader(line)
                "tgt=" in line && "act=" in line -> parseTick(line)?.let(ticks::add)
                else -> Unit // ignore logcat noise / PERFLOCK-PROBE / mn-mx text / blanks
            }
        }
        return ParsedCapture(header, ticks)
    }

    private fun parseHeader(line: String): SessionHeader {
        val kv = kv(line)
        return SessionHeader(
            targetFps = int(kv["tgt"]) ?: 0,
            bias = kv["bias"]?.let { runCatching { AutoTdpBias.valueOf(it) }.getOrNull() } ?: AutoTdpBias.EFFICIENT,
            wattCapAndSettle = (int(kv["wattCap"]) ?: 1) != 0,
            policies = parsePolicies(kv["policies"]),
        )
    }

    private fun parseTick(line: String): TickRecord? {
        val kv = kv(line)
        val target = int(kv["tgt"]) ?: return null
        val action = kv["act"] ?: return null
        return TickRecord(
            targetFps = target,
            fps = float(kv["fps"]),
            jank = int(kv["jank"]),
            worstFrameMs = float(kv["tail"]),
            drawW = float(kv["draw"]),
            cpuTempC = int(kv["cT"]),
            gpuTempC = int(kv["gT"]),
            cpuBusy = sentinel(kv["cpuB"]),
            gpuBusy = sentinel(kv["gpuB"]),
            cpuPeak = sentinel(kv["primePk"]),     // may be absent in older captures ⇒ null
            cpuCorePeak = sentinel(kv["cpuPk"]),
            recordedAction = action,
            recordedCaps = parseCaps(line),
        )
    }

    /** `id:max:cpu,cpu;id:max:cpu,cpu;…` → reconstructed policies (mirrors the AutoTuneControllerTest helper). */
    private fun parsePolicies(spec: String?): List<CpuPolicyInfo> =
        spec?.split(";")?.mapNotNull { token ->
            val parts = token.split(":")
            val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val max = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val cpus = parts.getOrNull(2)
                ?.split(",")?.mapNotNull(String::toIntOrNull)
                ?.takeIf { it.isNotEmpty() } ?: listOf(id)
            policyOf(id, max, cpus)
        } ?: emptyList()

    /** Build a controller-usable [CpuPolicyInfo]; only id / selectableMaxFreq / cpuIds drive `step()` (the
     *  other fields feed device-write paths the replay never calls), so they get safe placeholders. */
    fun policyOf(id: Int, maxFreq: Int, cpuIds: List<Int>): CpuPolicyInfo = CpuPolicyInfo(
        id = id,
        policyPath = "replay/policy$id",
        scalingMaxPath = "replay/policy$id/scaling_max_freq",
        currentMaxFreq = maxFreq,
        selectableMaxFreq = maxFreq,
        observedMaxFreq = maxFreq,
        minFreq = 300_000,
        supportedFrequencies = listOf(300_000, maxFreq),
        cpuIds = cpuIds,
    )

    private fun parseCaps(line: String): Map<Int, Int> =
        CAPS.find(line)?.groupValues?.get(1)
            ?.split(",")
            ?.mapNotNull { pair ->
                val kv = pair.split(":")
                val id = kv.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val pct = kv.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                id to pct
            }?.toMap() ?: emptyMap()

    private fun kv(line: String): Map<String, String> =
        KV.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }

    /** Strip the `ms`/`W` unit suffix; `-` (absent) and `null` map to null. */
    private fun float(s: String?): Float? =
        s?.removeSuffix("ms")?.removeSuffix("W")?.let { if (it == "-" || it == "null") null else it.toFloatOrNull() }

    private fun int(s: String?): Int? = s?.let { if (it == "-" || it == "null") null else it.toIntOrNull() }

    /** Busy/peak fields log `-1` for "no signal" — fold that to null so `step()` sees an absent input. */
    private fun sentinel(s: String?): Int? = int(s)?.takeIf { it != -1 }
}
