package com.kei.pulse.data

/**
 * Pure parsing for the Quick Access bar's controller-combo trigger. Reads LABELED `getevent -l` lines
 * (e.g. "<device>: EV_KEY   BTN_THUMBL   DOWN"), tracks which buttons are held by name, and decides when a
 * saved combo is pressed. Stateless — the watcher carries the held-set across reads. No device access here.
 *
 * Labeled + key-name based (not raw hex) on purpose: `getevent -l | grep EV_KEY` filters out the analog-stick
 * flood, keeps the output short enough for PServer's first-line read, and gives human-readable combos.
 */
object InputComboParser {

    data class KeyEvent(val name: String, val down: Boolean)

    /** Parse one labeled getevent line into a button event, or null if it isn't an EV_KEY button line. */
    fun parseEvent(line: String): KeyEvent? {
        val toks = line.trim().split(Regex("\\s+"))
        val i = toks.indexOf("EV_KEY")
        if (i < 0 || i + 2 >= toks.size) return null
        val name = toks[i + 1]
        if (!name.startsWith("BTN_") && !name.startsWith("KEY_")) return null
        val down = when (toks[i + 2].uppercase()) {
            "DOWN" -> true
            "UP" -> false
            else -> (toks[i + 2].toIntOrNull(16) ?: 0) != 0 // value 1/2 = down/repeat, 0 = up
        }
        return KeyEvent(name, down)
    }

    /** New held-button set after applying [event] to [held]. */
    fun applyEvent(held: Set<String>, event: KeyEvent): Set<String> =
        if (event.down) held + event.name else held - event.name

    /** True when every button in [combo] is held (extras allowed). An empty combo never matches. */
    fun matches(held: Set<String>, combo: Set<String>): Boolean =
        combo.isNotEmpty() && held.containsAll(combo)

    /** True only on the transition into the combo (fires once per press, not every event while held). */
    fun justTriggered(prevHeld: Set<String>, newHeld: Set<String>, combo: Set<String>): Boolean =
        !matches(prevHeld, combo) && matches(newHeld, combo)

    /**
     * True if [combo] becomes fully held at any point while replaying ONE capture [window]'s events from an
     * empty held-set. This is the QA-bar trigger's detection unit: each ~1 s `getevent` window is judged on its
     * OWN, carrying no held-set across windows. That's the reliability fix — controller buttons are edge-only
     * (no autorepeat), so when a release's UP landed in the lock-release gap between windows the old cross-window
     * held-set stayed STALE and `justTriggered` could never re-fire (the "had to press it a few times" bug). A
     * genuine chord emits all its DOWNs together inside one window, so it's caught here; a chord still held into
     * the next window emits nothing, so it fires exactly once. An empty [combo] never fires.
     */
    fun chordPressedInWindow(window: List<KeyEvent>, combo: Set<String>): Boolean {
        if (combo.isEmpty()) return false
        var held = emptySet<String>()
        for (e in window) {
            held = applyEvent(held, e)
            if (matches(held, combo)) return true
        }
        return false
    }

    /** One consumer poll of the lossless capture stream: producer liveness + the events since the last poll. */
    data class StreamChunk(val alive: Boolean, val events: List<KeyEvent>)

    /**
     * Parse one stream read — "OK|<line>~<line>~…" while the producer is alive, "DEAD|…" when its pid check
     * failed. A null/blank/markerless read means the read command itself failed (PServer hiccup), treated as
     * not-alive so the watcher re-verifies the producer. Buffered events in a DEAD read are still surfaced
     * (they were captured before the producer died), but the watcher resets its held-set on death anyway.
     */
    fun parseStreamRead(raw: String?): StreamChunk {
        if (raw.isNullOrBlank() || !raw.contains('|')) return StreamChunk(alive = false, events = emptyList())
        val alive = raw.startsWith("OK|")
        val events = raw.substringAfter('|').split('~').mapNotNull { parseEvent(it) }
        return StreamChunk(alive, events)
    }

    /**
     * Fold one stream chunk into the cross-poll held-set. Returns the new held-set and whether [combo]
     * TRANSITIONED into fully-held inside this chunk — once per press: a chord still held into later chunks
     * emits no events, so it cannot re-fire, and a release+re-press fires again. Carrying the held-set across
     * reads is CORRECT here only because the stream capture is lossless (every DOWN/UP edge reaches the file);
     * the windowed detector must keep using [chordPressedInWindow] — its lossy windows are exactly what made
     * a carried held-set wedge.
     */
    fun advanceHeld(held: Set<String>, events: List<KeyEvent>, combo: Set<String>): Pair<Set<String>, Boolean> {
        var current = held
        var fired = false
        for (e in events) {
            val next = applyEvent(current, e)
            if (!fired && justTriggered(current, next, combo)) fired = true
            current = next
        }
        return current to fired
    }

    /** Persisted form: the button names joined by '+'. */
    fun encode(combo: Set<String>): String = combo.sorted().joinToString("+")

    fun decode(s: String?): Set<String> =
        s?.split('+').orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Short, friendly label for a combo (e.g. "L3 + R3"), for the Settings UI. */
    fun displayName(combo: Set<String>): String =
        if (combo.isEmpty()) "None" else combo.sorted().joinToString(" + ") { friendly(it) }

    private fun friendly(name: String): String = FRIENDLY[name] ?: name.removePrefix("BTN_").removePrefix("KEY_")

    private val FRIENDLY = mapOf(
        "BTN_THUMBL" to "L3", "BTN_THUMBR" to "R3",
        "BTN_TL" to "L1", "BTN_TR" to "R1", "BTN_TL2" to "L2", "BTN_TR2" to "R2",
        "BTN_SELECT" to "Select", "BTN_START" to "Start", "BTN_MODE" to "Home",
        "BTN_NORTH" to "Y", "BTN_SOUTH" to "A", "BTN_EAST" to "B", "BTN_WEST" to "X",
    )
}
