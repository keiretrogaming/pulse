package com.kei.pulse.appwatch

/**
 * Whether the AutoTDP GLOBAL default may engage a foreground package — extracted from the service so the
 * policy is pure and truth-table-tested (it used to be `!isNeutralForeground(pkg)`, which silently engaged
 * benchmarks: trimming clocks and forcing the Balanced governor mid-run tanked Wild Life / Geekbench scores
 * by ~8%, field-reported). A benchmark measures the DEVICE, so the global default stands aside.
 *
 * An EXPLICIT per-app binding still wins — a user who deliberately binds AutoTDP to a benchmark (e.g. to
 * measure PULSE itself) is honored; this gate only covers the engage-by-default path.
 */
object AutoTdpEngagement {

    /** Well-known benchmark vendors, matched by package prefix (conservative — only unambiguous ids). */
    private val BENCHMARK_PREFIXES = listOf(
        "com.futuremark.", // 3DMark / PCMark (legacy UL vendor id)
        "com.ul.",         // UL Solutions (3DMark's current vendor id)
        "com.primatelabs.", // Geekbench
        "com.antutu.",     // AnTuTu
        "com.glbenchmark.", // GFXBench
    )

    /** True when [pkg] is a known benchmark the global default must not tune. */
    fun isBenchmark(pkg: String?): Boolean {
        val p = pkg?.trim()?.lowercase() ?: return false
        return BENCHMARK_PREFIXES.any { p.startsWith(it) }
    }

    /** The engage-by-default decision: any non-neutral foreground EXCEPT a known benchmark. */
    fun shouldEngage(pkg: String?, neutralForeground: Boolean): Boolean =
        pkg != null && !neutralForeground && !isBenchmark(pkg)
}
