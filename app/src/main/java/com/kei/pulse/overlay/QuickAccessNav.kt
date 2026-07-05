package com.kei.pulse.overlay

/**
 * Pure, device-free cursor math for the Quick Access bar's CONTROLLER navigation.
 *
 * The bar is a WindowManager overlay, where Compose's automatic D-pad focus traversal is unreliable (handle-tap
 * leaves the system in touch mode, which suppresses focus nav). So instead the overlay's key listener translates
 * D-pad / A / B / bumper presses into [QuickAccessNavIntent]s, and the panel drives an EXPLICIT cursor with these
 * helpers — no dependency on Android's focus system at all. The cursor math is the testable core; the panel owns
 * the item→action map (it knows the live control list, which varies with mode/scope).
 *
 * Convention: bumpers (L1/R1) switch the rail TAB (wrap-around, console-style); the D-pad moves the cursor
 * WITHIN the active tab's content (clamped — a vertical list shouldn't wrap past its ends); A/Left/Right are
 * applied by the panel to the focused item.
 */
enum class QuickAccessNavIntent { TAB_PREV, TAB_NEXT, UP, DOWN, LEFT, RIGHT, ACTIVATE }

object QuickAccessNav {

    /** Move the item cursor by [delta], clamped to `[0, itemCount)`. An empty tab pins the cursor at 0. */
    fun moveItem(item: Int, delta: Int, itemCount: Int): Int =
        if (itemCount <= 0) 0 else (item + delta).coerceIn(0, itemCount - 1)

    /** Move the tab index by [delta] with wrap-around (so R1 past the last tab lands on the first). */
    fun moveTab(tabIndex: Int, delta: Int, tabCount: Int): Int =
        if (tabCount <= 0) 0 else ((tabIndex + delta) % tabCount + tabCount) % tabCount

    /** Re-clamp a cursor after the item list shrank (e.g. AutoTDP sub-controls hidden on a mode switch). */
    fun clampItem(item: Int, itemCount: Int): Int =
        if (itemCount <= 0) 0 else item.coerceIn(0, itemCount - 1)
}
