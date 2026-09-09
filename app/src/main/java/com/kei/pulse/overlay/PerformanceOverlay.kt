package com.kei.pulse.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.getSystemService
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.OverlayLocation
import com.kei.pulse.model.OverlayPreset
import com.kei.pulse.ui.theme.PulseTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns the floating OSD window. All WindowManager operations are marshalled to the main thread
 * (the watcher feeds it from Dispatchers.IO). Adding a TYPE_APPLICATION_OVERLAY window changes no
 * display config, so it's safe over translation-layer games (unlike the removed per-app render scale).
 */
class PerformanceOverlay(private val context: Context) {

    private val windowManager = context.getSystemService<WindowManager>()
    private val main = Handler(Looper.getMainLooper())

    private val statsFlow = MutableStateFlow(OverlayStats())
    private val configFlow = MutableStateFlow(OverlayConfig())

    private var host: OverlayViewHost? = null
    private var params: WindowManager.LayoutParams? = null

    @Volatile
    var isShowing = false
        private set

    private var themeSettings: AppSettings = AppSettings()
    private var onMoved: ((x: Int, y: Int) -> Unit)? = null
    private var onPresetCycled: ((OverlayPreset) -> Unit)? = null
    private var onLockToggled: ((locked: Boolean) -> Unit)? = null
    // Last floating-panel position (Detailed/Full). Held separately so it survives a trip through docked
    // Compact (which pins to 0,0) and is restored when the user cycles back to a floating layout.
    private var floatX = 0
    private var floatY = 0

    /** Push a fresh stats snapshot (thread-safe; collected by the overlay composition). */
    fun update(stats: OverlayStats) {
        statsFlow.value = stats
    }

    fun show(
        settings: AppSettings,
        config: OverlayConfig,
        onMoved: (x: Int, y: Int) -> Unit = { _, _ -> },
        onPresetCycled: (OverlayPreset) -> Unit = {},
        onLockToggled: (locked: Boolean) -> Unit = {},
    ) {
        themeSettings = settings
        configFlow.value = config
        floatX = settings.overlayPosX
        floatY = settings.overlayPosY
        this.onMoved = onMoved
        this.onPresetCycled = onPresetCycled
        this.onLockToggled = onLockToggled
        main.post {
            if (host != null) {
                reapply(config)
                return@post
            }
            if (!hasPermission(context)) return@post
            val wm = windowManager ?: return@post
            val newHost = OverlayViewHost(context)
            val lp = newParams(config)
            newHost.setContent {
                PulseTheme(settings = themeSettings) {
                    OverlayContent(
                        statsFlow = statsFlow,
                        configFlow = configFlow,
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onCyclePreset = { cyclePreset() },
                        onToggleLock = { toggleLock() },
                    )
                }
            }
            val added = runCatching { wm.addView(newHost.composeView, lp) }.isSuccess
            if (!added) {
                newHost.onDestroyed()
                return@post
            }
            newHost.onResumed()
            host = newHost
            params = lp
            isShowing = true
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
        }
    }

    fun setConfig(config: OverlayConfig) {
        configFlow.value = config
        main.post { reapply(config) }
    }

    fun toggleLock() {
        val locked = !configFlow.value.locked
        setConfig(configFlow.value.copy(locked = locked))
        // Tell the owner so its lock state (and notification text) tracks the in-overlay button —
        // otherwise the service's copy goes stale and the next config push reverts the user's tap.
        onLockToggled?.invoke(locked)
    }

    private fun cyclePreset() {
        val next = when (configFlow.value.preset) {
            OverlayPreset.COMPACT -> OverlayPreset.DETAILED
            OverlayPreset.DETAILED -> OverlayPreset.FULL
            OverlayPreset.FULL -> OverlayPreset.COMPACT
        }
        setConfig(configFlow.value.copy(preset = next))
        onPresetCycled?.invoke(next)
    }

    private fun moveBy(dx: Float, dy: Float) {
        main.post {
            // The docked Compact bar is pinned full-width to the top — not draggable.
            if (isDocked(configFlow.value)) return@post
            val lp = params ?: return@post
            // Clamp to the screen so the HUD can't be dragged fully off-edge (and that off-screen
            // X/Y then persisted, stranding it invisible with no reset). FLAG_LAYOUT_NO_LIMITS means
            // nothing else bounds it. View is measured by now (added + shown), so width/height are valid.
            val bounds = windowManager?.maximumWindowMetrics?.bounds
            val view = host?.composeView
            val maxX = bounds?.let { (it.width() - (view?.width ?: 0)).coerceAtLeast(0) }
            val maxY = bounds?.let { (it.height() - (view?.height ?: 0)).coerceAtLeast(0) }
            lp.x = (lp.x + dx.toInt()).coerceAtLeast(0).let { x -> maxX?.let { x.coerceAtMost(it) } ?: x }
            lp.y = (lp.y + dy.toInt()).coerceAtLeast(0).let { y -> maxY?.let { y.coerceAtMost(it) } ?: y }
            floatX = lp.x
            floatY = lp.y
            runCatching { windowManager?.updateViewLayout(host?.composeView, lp) }
            onMoved?.invoke(lp.x, lp.y)
        }
    }

    private fun isDocked(config: OverlayConfig) = config.preset == OverlayPreset.COMPACT

    /** Re-apply geometry + flags to the live window (preset change, lock toggle, or re-show). */
    private fun reapply(config: OverlayConfig) {
        val lp = params ?: return
        applyGeometry(lp, config)
        runCatching { windowManager?.updateViewLayout(host?.composeView, lp) }
    }

    private fun newParams(config: OverlayConfig) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        flagsFor(config.locked),
        PixelFormat.TRANSLUCENT,
    ).also { applyGeometry(it, config) }

    /**
     * Docked Compact = a strip anchored to one screen edge per [OverlayConfig.location] (full-width top/bottom,
     * or a wrap-content vertically-centred column on the left/right).
     * Detailed/Full = a wrap-content floating panel at the saved [floatX]/[floatY], clamped on-screen.
     */
    private fun applyGeometry(lp: WindowManager.LayoutParams, config: OverlayConfig) {
        lp.flags = flagsFor(config.locked)
        if (isDocked(config)) {
            applyDockedGeometry(lp, config.location)
        } else {
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.TOP or Gravity.START
            val bounds = windowManager?.maximumWindowMetrics?.bounds
            lp.x = if (bounds != null) floatX.coerceIn(0, (bounds.width() - 1).coerceAtLeast(0)) else floatX.coerceAtLeast(0)
            lp.y = if (bounds != null) floatY.coerceIn(0, (bounds.height() - 1).coerceAtLeast(0)) else floatY.coerceAtLeast(0)
        }
    }

    private fun applyDockedGeometry(lp: WindowManager.LayoutParams, location: OverlayLocation) {
        lp.x = 0
        lp.y = 0
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        when (location) {
            OverlayLocation.TOP_FULL_WIDTH -> {
                lp.width = WindowManager.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.TOP
            }
            OverlayLocation.BOTTOM_FULL_WIDTH -> {
                lp.width = WindowManager.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.BOTTOM
            }
            OverlayLocation.LEFT_CENTER_VERTICAL -> {
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            OverlayLocation.RIGHT_CENTER_VERTICAL -> {
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
        }
    }

    private fun flagsFor(locked: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // Locked = fully tap-through (never steals game input). Unlocked = only the overlay is
        // touchable for drag/controls; touches elsewhere still pass through to the game.
        return if (locked) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}
