package com.kei.pulse.data

import android.util.Log
import com.kei.pulse.root.RootSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Drives the Quick Access bar's controller-combo trigger via a root-shell `getevent` reader (PServer).
 *
 * Two detection modes:
 *  - **STREAM (preferred)**: a persistent detached root `getevent` pipeline appends EV_KEY lines to a file;
 *    the consumer polls it with ONE short lock-held read+truncate every [STREAM_POLL_MS]. Capture is LOSSLESS
 *    (no blind gap), so a chord can never be missed and the cross-poll held-set in
 *    [InputComboParser.advanceHeld] is correct. The lock is also held far less than the windowed mode
 *    (~tens of ms per poll vs a full 1 s window), so the fan/AutoTDP re-asserts get MORE turns, not fewer.
 *  - **WINDOWED (fallback)**: the original bounded `timeout 1 getevent` windows with the [DETECT_GAP_MS]
 *    lock-free gap. Used when the producer can't start/survive (PServer absent, old firmware quirk). Its gap
 *    makes it blind ~37% of the time — that was the "combo sometimes doesn't open / opens late" bug.
 *
 * Which mode engaged is logged under `PulseCombo` — that's the on-device verification channel (PServer is
 * SELinux-hidden from the adb shell uid, so the stream's survival can only be proven by this runtime check).
 */
class InputComboWatcher(
    /** Short root command → first stdout line (the PServer contract). Injected for tests. */
    private val runCommand: (String) -> String? = RootSupport::runRootCommand,
    /**
     * Script-file runner (length-agnostic; needed for the multi-line producer script — the PServer inline
     * length gotcha). Null (e.g. the Settings capture flow) disables stream mode entirely.
     */
    private val runScript: ((String) -> String?)? = null,
    /**
     * True while a combo would actually DO something (the bar is showable). Drives the poll cadence: fast
     * in-game, slow while idle/neutral — capture is lossless, so slow polling misses nothing, it just reads
     * (and discards, via the service's action gate) the events later. Keeps the idle root-command traffic
     * near zero without ever disarming.
     */
    private val fastPoll: () -> Boolean = { true },
) {

    /** One ~1 s window of button events, or empty if root/getevent is unavailable. */
    private fun readWindow(): List<InputComboParser.KeyEvent> {
        val raw = runCommand(WINDOW_CMD) ?: return emptyList()
        return raw.split('~').mapNotNull { InputComboParser.parseEvent(it) }
    }

    /**
     * Capture the next combo the user holds. Returns the largest simultaneous held-set seen across a few
     * windows (or empty if nothing was pressed in time). Runs off the main thread. Stays windowed — capture
     * happens in the Settings UI where a 1 s window is fine and no producer lifecycle is wanted.
     */
    suspend fun captureNext(windows: Int = CAPTURE_WINDOWS): Set<String> = withContext(Dispatchers.IO) {
        var held = emptySet<String>()
        var best = emptySet<String>()
        repeat(windows) {
            for (e in readWindow()) {
                held = InputComboParser.applyEvent(held, e)
                if (held.size > best.size) best = held
            }
            if (best.size >= 2) return@withContext best // a real (2+ button) combo captured
        }
        best
    }

    /**
     * Detect loop: stream mode when the producer starts and survives, else the windowed fallback. Runs until
     * the calling coroutine is cancelled; a no-op for an empty combo. The producer is killed on cancellation
     * and on stream collapse (finally), and each producer (re)start kills any stale instance via the pidfile —
     * covering the package-update orphan (the process survives PULSE's death; it is root and detached).
     */
    suspend fun detect(combo: Set<String>, onTrigger: () -> Unit) = withContext(Dispatchers.IO) {
        if (combo.isEmpty()) return@withContext
        if (runScript != null && startStream()) {
            try {
                streamLoop(combo, onTrigger) // returns only if the stream collapsed (producer kept dying)
            } finally {
                stopStream()
            }
            Log.w(TAG, "capture stream collapsed — falling back to windowed detection")
        }
        windowedLoop(combo, onTrigger)
    }

    /** Start the detached producer and verify it survived PServer's command returning. */
    private suspend fun startStream(): Boolean {
        val reply = runScript?.invoke(PRODUCER_SCRIPT)
        delay(STREAM_VERIFY_MS)
        val alive = InputComboParser.parseStreamRead(runCommand(READ_CMD)).alive
        if (alive) {
            Log.i(TAG, "combo capture stream ACTIVE (producer survived; polling every ${STREAM_POLL_MS}ms)")
        } else {
            Log.w(TAG, "capture stream unavailable (start reply=$reply) — using windowed detection")
            runCommand(KILL_CMD) // best-effort cleanup of any half-started producer
        }
        return alive
    }

    /**
     * Poll the stream file. Held-state notes: edges are lossless while the producer lives, so the carried
     * held-set is the true controller state — deliberately NO idle expiry (a combo button legitimately held
     * for seconds, e.g. L3 sprint, must still complete the chord when its partner lands). The held-set resets
     * whenever the producer died (edges were lost while it was down). The read command's tr→truncate has a
     * sub-ms race that could in theory drop one edge; a dropped UP self-heals on that button's next cycle.
     */
    private suspend fun streamLoop(combo: Set<String>, onTrigger: () -> Unit) {
        var held = emptySet<String>()
        var consecutiveDead = 0
        while (currentCoroutineContext().isActive) {
            delay(if (fastPoll()) STREAM_POLL_MS else STREAM_POLL_IDLE_MS)
            val chunk = InputComboParser.parseStreamRead(runCommand(READ_CMD))
            if (!chunk.alive) {
                if (++consecutiveDead > STREAM_DEAD_FALLBACK) return // collapsed — caller falls back
                Log.w(TAG, "producer dead — respawning ($consecutiveDead/$STREAM_DEAD_FALLBACK)")
                runScript?.invoke(PRODUCER_SCRIPT)
                held = emptySet()
                continue
            }
            consecutiveDead = 0
            val (next, fired) = InputComboParser.advanceHeld(held, chunk.events, combo)
            held = next
            if (fired) onTrigger()
        }
    }

    private fun stopStream() {
        runCommand(KILL_CMD)
        Log.i(TAG, "combo capture stream stopped")
    }

    /**
     * The original windowed loop. Each window is judged on its own via [InputComboParser.chordPressedInWindow]
     * — NO held-set is carried across windows (lossy windows made a carried set go stale and wedge; see the
     * parser doc). IMPORTANT — lock contention: [readWindow] holds the process-wide `RootSupport` PServer lock
     * for the whole `timeout 1 getevent` window, and that same lock serializes the 120 ms Custom-fan duty
     * reconcile and AutoTDP's per-tick cap re-asserts. The lock is released only during [DETECT_GAP_MS], so
     * that gap is the fan/AutoTDP paths' ONLY turn — DON'T shrink it to chase combo latency, that starves
     * them (fan oscillation / cap drift). Stream mode exists precisely because this trade-off caps how good
     * windowed detection can get.
     */
    private suspend fun windowedLoop(combo: Set<String>, onTrigger: () -> Unit) {
        while (currentCoroutineContext().isActive) {
            if (!fastPoll()) {
                // Windowed capture has no backing file — reading while the action is gated off would burn a
                // full lock-held window for events that would be discarded anyway. Sleep instead.
                delay(STREAM_POLL_IDLE_MS)
                continue
            }
            if (InputComboParser.chordPressedInWindow(readWindow(), combo)) onTrigger()
            delay(DETECT_GAP_MS) // release the shared PServer lock so fan/AutoTDP re-asserts get a turn
        }
    }

    companion object {
        private const val TAG = "PulseCombo"

        // `timeout` / `getevent` / `grep` / `tr` / `setsid` / `pgrep` are all present on the AYN/Retroid
        // firmware (probed 2026-07-03 on the Odin: /system/bin/setsid exists, grep has --line-buffered).
        private const val WINDOW_CMD = "timeout 1 getevent -l 2>/dev/null | grep -a EV_KEY | tr '\\n' '~'"
        private const val CAPTURE_WINDOWS = 8
        private const val DETECT_GAP_MS = 600L

        private const val EV_FILE = "/data/local/tmp/.pulse_qa_ev"
        private const val PID_FILE = "/data/local/tmp/.pulse_qa_ev.pid"
        private const val STREAM_POLL_MS = 400L
        private const val STREAM_POLL_IDLE_MS = 2000L // action-gated (home/Settings): capture is lossless, read lazily
        private const val STREAM_VERIFY_MS = 1200L
        private const val STREAM_DEAD_FALLBACK = 3

        /**
         * The persistent producer. Critical shape constraints:
         *  - stdio fully detached at the `setsid` child (`</dev/null >/dev/null 2>&1`) — RootExec's binder
         *    transact is BLOCKING with no timeout, so nothing may keep PServer's reply pipe open;
         *  - `setsid` puts it in its own session so it survives the PServer shell exiting;
         *  - `grep --line-buffered -a EV_KEY` filters the analog-stick flood at the source (file stays tiny,
         *    ~zero flash writes) and keeps events flowing per-line;
         *  - `head -c 10MB` is the orphan safety valve: even if PULSE dies without cleanup, the pipeline
         *    self-terminates after 10 MB (millions of presses) instead of growing forever;
         *  - each start kills any stale instance via the pidfile (leader + its pipeline children);
         *  - `chmod 600` — a button-event log shouldn't be world-readable (paired keyboards emit KEY_* too).
         * Runs via runGeneratedScript: multi-line + ~600 chars would hit the PServer inline truncation.
         */
        private val PRODUCER_SCRIPT = """
            P=${'$'}(cat "$PID_FILE" 2>/dev/null)
            [ -n "${'$'}P" ] && kill ${'$'}P ${'$'}(pgrep -P ${'$'}P) 2>/dev/null
            : > "$EV_FILE"
            chmod 600 "$EV_FILE"
            setsid sh -c "getevent -l 2>/dev/null | grep --line-buffered -a EV_KEY | head -c 10000000 >> $EV_FILE" </dev/null >/dev/null 2>&1 &
            echo ${'$'}! > "$PID_FILE"
            chmod 600 "$PID_FILE"
            echo STARTED
        """.trimIndent()

        /**
         * One short poll: liveness marker, then the buffered events collapsed to the single line PServer
         * returns, then truncate. Producer appends (O_APPEND), so truncate-to-zero is safe.
         */
        private val READ_CMD =
            "kill -0 ${'$'}(cat $PID_FILE 2>/dev/null) 2>/dev/null && printf 'OK|' || printf 'DEAD|'; " +
                "tr '\\n' '~' < $EV_FILE 2>/dev/null; : > $EV_FILE 2>/dev/null"

        /** Kill the producer (leader + pipeline children) and remove its files. */
        private val KILL_CMD =
            "P=${'$'}(cat $PID_FILE 2>/dev/null); [ -n \"${'$'}P\" ] && kill ${'$'}P ${'$'}(pgrep -P ${'$'}P) 2>/dev/null; " +
                "rm -f $PID_FILE $EV_FILE"
    }
}
