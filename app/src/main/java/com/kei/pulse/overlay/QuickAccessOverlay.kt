package com.kei.pulse.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.getSystemService
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.ui.theme.PulseTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/**
 * The Quick Access Bar overlay (EXPERIMENTAL). One overlay window with two states:
 *  - a small touchable HANDLE on the right edge (game keeps input), and
 *  - on tap, an EXPANDED right-docked panel that is FOCUSABLE so the controller's D-pad/A/B reach it
 *    (the game only loses input while it's open) with the game dimmed behind it.
 *
 * Reuses [OverlayViewHost]; all WindowManager ops are marshalled to the main thread (the watcher feeds it
 * from Dispatchers.IO). Width comes from the unit-tested [QuickAccess.widthPx].
 */
class QuickAccessOverlay(private val context: Context) {

    private val windowManager = context.getSystemService<WindowManager>()
    private val main = Handler(Looper.getMainLooper())

    private val statsFlow = MutableStateFlow(OverlayStats())
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val perAppFlow = MutableStateFlow<PerAppConfig?>(null)
    private val expandedFlow = MutableStateFlow(false)
    private val showHandleFlow = MutableStateFlow(true)
    // Controller-nav intents from the window key listener → the panel's explicit cursor. Buffered so a key press
    // emits without a suspending collector (the panel subscribes while expanded).
    private val navIntents = MutableSharedFlow<QuickAccessNavIntent>(extraBufferCapacity = 32)

    private var host: OverlayViewHost? = null
    private var params: WindowManager.LayoutParams? = null
    private var actions: QuickAccessActions = QuickAccessActions { }
    // HAT-axis D-pad edge detection: these handhelds report the D-pad as ABS_HAT0X/Y (a MotionEvent axis),
    // NOT KEYCODE_DPAD — confirmed by getevent on the Odin (M7). The generic-motion listener translates the hat
    // to nav intents; these track the last quantized direction so a held press fires once and the centering
    // (0) event is ignored. Reset on each expand.
    private var lastHatX = 0
    private var lastHatY = 0
    // An expand requested before the window exists (the combo can fire ahead of the poll tick that calls
    // show()). Timestamped so a stale request can't pop the panel much later; honored by show().
    private var pendingExpandAtMs: Long? = null

    @Volatile
    var isShowing = false
        private set

    fun update(stats: OverlayStats) { statsFlow.value = stats }
    fun updateSettings(settings: AppSettings) { settingsFlow.value = settings }
    /** The foreground game's per-app profile (null = none) — the Performance tab edits/reflects this. */
    fun updatePerApp(config: PerAppConfig?) { perAppFlow.value = config }
    /** Whether the floating handle is shown when collapsed (off = combo-only; window stays invisible). */
    fun updateShowHandle(show: Boolean) { showHandleFlow.value = show }

    /** Programmatic open/close/toggle — used by the controller-combo trigger. */
    fun open() = setExpanded(true)
    fun close() = setExpanded(false)
    fun toggle() = setExpanded(!expandedFlow.value)

    fun show(settings: AppSettings, actions: QuickAccessActions) {
        this.actions = actions
        settingsFlow.value = settings
        main.post {
            if (host != null) return@post
            if (!hasPermission(context)) return@post
            val wm = windowManager ?: return@post
            val newHost = OverlayViewHost(context)
            val lp = newParams(expanded = false)
            newHost.setContent {
                // Collect (don't capture) the settings for the theme so a theme change reaches a shown bar.
                val themeSettings by settingsFlow.collectAsState()
                PulseTheme(settings = themeSettings) {
                    QuickAccessContent(
                        statsFlow = statsFlow,
                        settingsFlow = settingsFlow,
                        perAppFlow = perAppFlow,
                        expandedFlow = expandedFlow,
                        showHandleFlow = showHandleFlow,
                        navIntents = navIntents,
                        onExpand = { setExpanded(true) },
                        onClose = { setExpanded(false) },
                        onAction = { this.actions.dispatch(it) },
                    )
                }
            }
            newHost.composeView.isFocusableInTouchMode = true
            // Controller nav: while expanded, translate D-pad / A / bumpers into nav intents at the VIEW level
            // (Compose's auto focus traversal is unreliable in a WindowManager overlay / touch mode) and consume
            // them so they don't leak to the game behind. B/back closes. While collapsed, pass everything through.
            newHost.composeView.setOnKeyListener { _, keyCode, event ->
                if (!expandedFlow.value) return@setOnKeyListener false
                val back = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B
                if (back) {
                    if (event.action == KeyEvent.ACTION_UP) setExpanded(false)
                    return@setOnKeyListener true
                }
                val intent = navKeyIntent(keyCode) ?: return@setOnKeyListener false
                // LEFT/RIGHT fire on key-repeat too, so holding the D-pad keeps adjusting a slider/selector
                // (hold-to-adjust); the rest fire once per press for precise nav.
                val holdable = intent == QuickAccessNavIntent.LEFT || intent == QuickAccessNavIntent.RIGHT
                if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || holdable)) navIntents.tryEmit(intent)
                true // consume DOWN and UP so a held button doesn't reach the game
            }
            // The D-pad arrives as a HAT-axis MotionEvent (ABS_HAT0X/Y) the key listener can't see (M7), so map it
            // here to the SAME nav intents. Edge-detected on the quantized hat value so each press fires once and
            // the centering event (back to 0) is ignored; only consume an event that actually moved the cursor.
            newHost.composeView.setOnGenericMotionListener listener@{ _, event ->
                if (!expandedFlow.value) return@listener false
                if (event.action != MotionEvent.ACTION_MOVE) return@listener false
                val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).roundToInt().coerceIn(-1, 1)
                val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).roundToInt().coerceIn(-1, 1)
                var consumed = false
                if (x != lastHatX) {
                    lastHatX = x
                    when (x) {
                        -1 -> { navIntents.tryEmit(QuickAccessNavIntent.LEFT); consumed = true }
                        1 -> { navIntents.tryEmit(QuickAccessNavIntent.RIGHT); consumed = true }
                    }
                }
                if (y != lastHatY) {
                    lastHatY = y
                    when (y) {
                        -1 -> { navIntents.tryEmit(QuickAccessNavIntent.UP); consumed = true }
                        1 -> { navIntents.tryEmit(QuickAccessNavIntent.DOWN); consumed = true }
                    }
                }
                consumed
            }
            val added = runCatching { wm.addView(newHost.composeView, lp) }.isSuccess
            if (!added) { newHost.onDestroyed(); return@post }
            newHost.onResumed()
            host = newHost
            params = lp
            isShowing = true
            // Honor a combo press that landed before the window existed (recent only — a stale request
            // must not pop the panel out of nowhere).
            val pending = pendingExpandAtMs
            pendingExpandAtMs = null
            if (pending != null && System.currentTimeMillis() - pending <= PENDING_EXPAND_MAX_AGE_MS) {
                setExpanded(true)
            }
        }
    }

    fun hide() {
        main.post {
            host?.let { h ->
                runCatching { windowManager?.removeView(h.composeView) }
                h.onDestroyed()
            }
            host = null
            params = null
            isShowing = false
            expandedFlow.value = false
            pendingExpandAtMs = null
        }
    }

    private fun setExpanded(expanded: Boolean) {
        main.post {
            val lp = params
            if (lp == null) {
                // Window not created yet — the old silent return here made a fast combo press do NOTHING.
                // Record the intent; show() applies it as soon as the window exists.
                pendingExpandAtMs = if (expanded) System.currentTimeMillis() else null
                return@post
            }
            expandedFlow.value = expanded
            applyGeometry(lp, expanded)
            runCatching { windowManager?.updateViewLayout(host?.composeView, lp) }
            // On expand the window becomes focusable; pull window focus to the view so the controller's D-pad
            // reaches the Compose focus system (the panel itself requests focus to its first control). On
            // collapse, hand focus back so the game gets its input.
            if (expanded) {
                lastHatX = 0; lastHatY = 0 // re-arm hat edge-detection so the first D-pad press registers
                runCatching { host?.composeView?.requestFocus() }
            } else {
                runCatching { host?.composeView?.clearFocus() }
            }
        }
    }

    /** Map a controller/D-pad keycode to a nav intent (bumpers switch tabs, D-pad moves, A activates). */
    private fun navKeyIntent(keyCode: Int): QuickAccessNavIntent? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> QuickAccessNavIntent.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> QuickAccessNavIntent.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> QuickAccessNavIntent.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> QuickAccessNavIntent.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> QuickAccessNavIntent.ACTIVATE
        KeyEvent.KEYCODE_BUTTON_L1 -> QuickAccessNavIntent.TAB_PREV
        KeyEvent.KEYCODE_BUTTON_R1 -> QuickAccessNavIntent.TAB_NEXT
        else -> null
    }

    private fun newParams(expanded: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        0,
        PixelFormat.TRANSLUCENT,
    ).also { applyGeometry(it, expanded) }

    private fun applyGeometry(lp: WindowManager.LayoutParams, expanded: Boolean) {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (expanded) {
            // Focusable (controller D-pad/A/B) + touchable; dim the game behind to focus the panel.
            lp.flags = base or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            lp.dimAmount = 0.5f
            lp.gravity = Gravity.TOP or Gravity.END
            val screenW = windowManager?.maximumWindowMetrics?.bounds?.width() ?: 0
            lp.width = if (screenW > 0) {
                QuickAccess.widthPx(screenW, PANEL_FRACTION, panelMinPx(), panelMaxPx())
            } else {
                WindowManager.LayoutParams.WRAP_CONTENT
            }
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
        } else {
            // Collapsed handle: only the handle is touchable; taps elsewhere pass to the game, which keeps focus.
            lp.flags = base or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            lp.dimAmount = 0f
            lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
    }

    private fun panelMinPx() = (PANEL_MIN_DP * context.resources.displayMetrics.density).toInt()
    private fun panelMaxPx() = (PANEL_MAX_DP * context.resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        const val PANEL_FRACTION = 0.37f
        const val PANEL_MIN_DP = 280
        const val PANEL_MAX_DP = 420
        private const val PENDING_EXPAND_MAX_AGE_MS = 3000L
        fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}
