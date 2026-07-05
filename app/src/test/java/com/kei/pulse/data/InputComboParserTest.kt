package com.kei.pulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the controller-combo parsing that drives the Quick Access bar's getevent trigger. The
 * getevent subprocess + capture/detect loop are hardware-verified; the labeled-line parsing, held-button
 * tracking, combo matching, and the persisted encoding are pure and tested here.
 */
class InputComboParserTest {

    @Test
    fun `parses a labeled key-down event`() {
        val e = InputComboParser.parseEvent("/dev/input/event3: EV_KEY       BTN_THUMBL           DOWN")
        assertEquals(InputComboParser.KeyEvent("BTN_THUMBL", down = true), e)
    }

    @Test
    fun `parses a labeled key-up event`() {
        val e = InputComboParser.parseEvent("/dev/input/event3: EV_KEY       BTN_THUMBR           UP")
        assertEquals(InputComboParser.KeyEvent("BTN_THUMBR", down = false), e)
    }

    @Test
    fun `treats a nonzero hex value as held down`() {
        val e = InputComboParser.parseEvent("/dev/input/event3: EV_KEY       BTN_SOUTH            00000001")
        assertEquals(InputComboParser.KeyEvent("BTN_SOUTH", down = true), e)
    }

    @Test
    fun `ignores non-key events and junk lines`() {
        assertNull(InputComboParser.parseEvent("/dev/input/event3: EV_ABS       ABS_X                00000123"))
        assertNull(InputComboParser.parseEvent("add device 1: /dev/input/event3"))
        assertNull(InputComboParser.parseEvent(""))
    }

    @Test
    fun `applyEvent adds on down and removes on up`() {
        var held = emptySet<String>()
        held = InputComboParser.applyEvent(held, InputComboParser.KeyEvent("BTN_THUMBL", true))
        held = InputComboParser.applyEvent(held, InputComboParser.KeyEvent("BTN_THUMBR", true))
        assertEquals(setOf("BTN_THUMBL", "BTN_THUMBR"), held)
        held = InputComboParser.applyEvent(held, InputComboParser.KeyEvent("BTN_THUMBL", false))
        assertEquals(setOf("BTN_THUMBR"), held)
    }

    @Test
    fun `matches when every combo button is held`() {
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        assertTrue(InputComboParser.matches(held = setOf("BTN_THUMBL", "BTN_THUMBR"), combo = combo))
        assertTrue(InputComboParser.matches(held = setOf("BTN_THUMBL", "BTN_THUMBR", "BTN_SOUTH"), combo = combo))
        assertFalse(InputComboParser.matches(held = setOf("BTN_THUMBL"), combo = combo))
    }

    @Test
    fun `an empty combo never matches`() {
        assertFalse(InputComboParser.matches(held = setOf("BTN_THUMBL"), combo = emptySet()))
    }

    @Test
    fun `justTriggered fires only on the press transition`() {
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        assertTrue(InputComboParser.justTriggered(setOf("BTN_THUMBL"), setOf("BTN_THUMBL", "BTN_THUMBR"), combo))
        assertFalse(InputComboParser.justTriggered(setOf("BTN_THUMBL", "BTN_THUMBR"), setOf("BTN_THUMBL", "BTN_THUMBR"), combo))
        assertFalse(InputComboParser.justTriggered(emptySet(), setOf("BTN_THUMBL"), combo))
    }

    private fun down(name: String) = InputComboParser.KeyEvent(name, down = true)
    private fun up(name: String) = InputComboParser.KeyEvent(name, down = false)

    @Test
    fun `chordPressedInWindow fires when the whole combo lands in one window`() {
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        assertTrue(InputComboParser.chordPressedInWindow(listOf(down("BTN_THUMBL"), down("BTN_THUMBR")), combo))
    }

    @Test
    fun `chordPressedInWindow re-arms every window so a missed UP cannot wedge it`() {
        // The old cross-window held-set bug: a release whose UP fell in the lock-release gap stayed "held", so
        // the next press never re-triggered. Per-window detection carries no state, so an identical press in a
        // later window fires again — even though no UP was ever observed between them.
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        val press = listOf(down("BTN_THUMBL"), down("BTN_THUMBR"))
        assertTrue(InputComboParser.chordPressedInWindow(press, combo))
        assertTrue("re-press fires again (no stale-held wedge)", InputComboParser.chordPressedInWindow(press, combo))
    }

    @Test
    fun `chordPressedInWindow allows extras and fires mid-window even before a release`() {
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        // An unrelated button held, the chord completes, then one is released — all in one window.
        val window = listOf(down("BTN_SOUTH"), down("BTN_THUMBL"), down("BTN_THUMBR"), up("BTN_THUMBL"))
        assertTrue(InputComboParser.chordPressedInWindow(window, combo))
    }

    @Test
    fun `chordPressedInWindow does not fire on a partial press, a split chord, or an empty combo`() {
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        assertFalse("only one button this window", InputComboParser.chordPressedInWindow(listOf(down("BTN_THUMBL")), combo))
        // A chord whose second button lands in a LATER window isn't seen together here (press-together is the gesture).
        assertFalse("split across windows", InputComboParser.chordPressedInWindow(listOf(down("BTN_THUMBR")), combo))
        assertFalse("empty combo never fires", InputComboParser.chordPressedInWindow(listOf(down("BTN_THUMBL"), down("BTN_THUMBR")), emptySet()))
        assertFalse("empty window", InputComboParser.chordPressedInWindow(emptyList(), combo))
    }

    // ---- Lossless capture-stream mode (parseStreamRead + advanceHeld) ----
    // The stream consumer polls a file the persistent root getevent writes, so NO edge is ever lost. That's
    // what makes a cross-chunk held-set correct here (the windowed detector must NOT do this — its lossy
    // windows are exactly why chordPressedInWindow re-arms from empty).

    @Test
    fun `parseStreamRead reads an alive chunk with events`() {
        val raw = "OK|/dev/input/event3: EV_KEY       BTN_THUMBL           DOWN~" +
            "/dev/input/event3: EV_KEY       BTN_THUMBR           DOWN~"
        val chunk = InputComboParser.parseStreamRead(raw)
        assertTrue(chunk.alive)
        assertEquals(listOf(down("BTN_THUMBL"), down("BTN_THUMBR")), chunk.events)
    }

    @Test
    fun `parseStreamRead reads an alive empty chunk`() {
        val chunk = InputComboParser.parseStreamRead("OK|")
        assertTrue(chunk.alive)
        assertTrue(chunk.events.isEmpty())
    }

    @Test
    fun `parseStreamRead flags a dead producer but still surfaces buffered events`() {
        val raw = "DEAD|/dev/input/event3: EV_KEY       BTN_THUMBL           DOWN~"
        val chunk = InputComboParser.parseStreamRead(raw)
        assertFalse(chunk.alive)
        assertEquals(listOf(down("BTN_THUMBL")), chunk.events)
    }

    @Test
    fun `parseStreamRead treats null, blank, or junk as dead and empty`() {
        assertFalse(InputComboParser.parseStreamRead(null).alive)
        assertTrue(InputComboParser.parseStreamRead(null).events.isEmpty())
        assertFalse(InputComboParser.parseStreamRead("").alive)
        assertFalse(InputComboParser.parseStreamRead("garbage with no marker").alive)
        assertTrue(InputComboParser.parseStreamRead("garbage with no marker").events.isEmpty())
    }

    @Test
    fun `advanceHeld fires once when the chord completes inside one chunk`() {
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        val (held, fired) = InputComboParser.advanceHeld(emptySet(), listOf(down("BTN_THUMBL"), down("BTN_THUMBR")), combo)
        assertTrue(fired)
        assertEquals(combo, held)
    }

    @Test
    fun `advanceHeld fires when the chord is split across chunks`() {
        // THE case the windowed detector structurally misses: first DOWN in one read, second in the next.
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        val (held1, fired1) = InputComboParser.advanceHeld(emptySet(), listOf(down("BTN_THUMBL")), combo)
        assertFalse(fired1)
        val (held2, fired2) = InputComboParser.advanceHeld(held1, listOf(down("BTN_THUMBR")), combo)
        assertTrue(fired2)
        assertEquals(combo, held2)
    }

    @Test
    fun `advanceHeld does not re-fire while held and fires again after release and re-press`() {
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        var held = InputComboParser.advanceHeld(emptySet(), listOf(down("BTN_THUMBL"), down("BTN_THUMBR")), combo).first
        // Still held, no new events: no fire.
        val quiet = InputComboParser.advanceHeld(held, emptyList(), combo)
        assertFalse(quiet.second)
        // Release one, then re-press it: fires again exactly once.
        held = InputComboParser.advanceHeld(held, listOf(up("BTN_THUMBL")), combo).first
        val (_, refired) = InputComboParser.advanceHeld(held, listOf(down("BTN_THUMBL")), combo)
        assertTrue(refired)
    }

    @Test
    fun `advanceHeld tolerates extra buttons and an empty combo never fires`() {
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        val withExtra = InputComboParser.advanceHeld(setOf("BTN_SOUTH"), listOf(down("BTN_THUMBL"), down("BTN_THUMBR")), combo)
        assertTrue(withExtra.second)
        val emptyCombo = InputComboParser.advanceHeld(emptySet(), listOf(down("BTN_THUMBL"), down("BTN_THUMBR")), emptySet())
        assertFalse(emptyCombo.second)
    }

    @Test
    fun `encode and decode round-trip`() {
        val combo = setOf("BTN_THUMBL", "BTN_THUMBR")
        assertEquals(combo, InputComboParser.decode(InputComboParser.encode(combo)))
    }

    @Test
    fun `decode of null or blank is empty`() {
        assertTrue(InputComboParser.decode(null).isEmpty())
        assertTrue(InputComboParser.decode("").isEmpty())
        assertTrue(InputComboParser.decode("  ").isEmpty())
    }

    @Test
    fun `displayName maps known buttons to friendly labels`() {
        assertEquals("L3 + R3", InputComboParser.displayName(setOf("BTN_THUMBL", "BTN_THUMBR")))
        assertEquals("None", InputComboParser.displayName(emptySet()))
    }
}
