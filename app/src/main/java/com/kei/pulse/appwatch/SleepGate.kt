package com.kei.pulse.appwatch

/**
 * TOTAL-sleep gate for the watcher's poll loop (the 1.19.6 battery fix). While the screen is off PULSE does
 * ABSOLUTELY nothing per tick — no UsageStats query, no fan math, no temp reads, no RGB writes, no cap
 * re-asserts; the 1 s timer keeps counting (a bare delay holds no wakelock — a suspended SoC sleeps through
 * it) so wake-up latency is ≤1 tick. The vendor's own fan/thermal regulation covers the screen-off window:
 * the WENT_OFF transition hands the fan back to a vendor profile (never leaves fan_mode=6 unattended), darkens
 * the info-LED, and re-onlines a parked prime before stepping pauses.
 *
 * Pure + trivial by design: the tests pin the "asleep = do nothing" contract so no future edit can sneak
 * per-tick work back into the SKIP state unnoticed.
 */
object SleepGate {

    enum class TickWork { FULL, SKIP }

    enum class Transition { NONE, WENT_OFF, WENT_ON }

    /** What this tick is allowed to do. Screen on → everything; screen off → nothing at all. */
    fun tickWork(screenOn: Boolean): TickWork = if (screenOn) TickWork.FULL else TickWork.SKIP

    /** Edge detection for the one-shots (fan release / RGB dark / prime un-park) and the wake resume. */
    fun transition(prevOn: Boolean, nowOn: Boolean): Transition = when {
        prevOn && !nowOn -> Transition.WENT_OFF
        !prevOn && nowOn -> Transition.WENT_ON
        else -> Transition.NONE
    }
}
