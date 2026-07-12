package com.kei.pulse.overlay

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.FanCurve
import com.kei.pulse.model.FanTempController
import com.kei.pulse.model.OverlayPreset
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.RgbMode
import com.kei.pulse.model.toSideControlState
import com.kei.pulse.model.withSideControls

/** Rail tabs for the Quick Access Bar. */
enum class QuickAccessTab { PERFORMANCE, FAN, RGB, OVERLAY, SYSTEM }

/** A control event from the Quick Access panel. */
sealed interface QuickAccessAction {
    object ToggleAutoTdp : QuickAccessAction
    /** Bind the foreground game to a power tier (AAA/Max · Balanced · Power-Saving · Custom) — a per-app mode
     *  switch that supersedes AutoTDP. Per-app like the AutoTDP controls; applied live via the service. */
    data class SetTier(val tier: PowerTier) : QuickAccessAction
    /** The "Stock — don't tune" mode: per-game it writes the explicit AUTO_OFF binding (PULSE hands-off even
     *  when the global default is on); in All-games scope it turns the AutoTDP global default off. */
    object SetStockMode : QuickAccessAction
    data class SetFpsTarget(val fps: Int) : QuickAccessAction
    data class SetBias(val bias: AutoTdpBias) : QuickAccessAction
    data class SetPowerTarget(val percent: Int) : QuickAccessAction
    /** Cap the Adreno at a supported frequency (kHz; the service snaps to the nearest level). Like the Power
     *  Target, this edits the GLOBAL Custom tuning — it defines the Custom tier itself, not a per-app value. */
    data class SetGpuCap(val freqKhz: Int) : QuickAccessAction
    data class SetFanMode(val mode: Int) : QuickAccessAction
    data class SetAggressivePark(val enabled: Boolean) : QuickAccessAction
    data class SetFanTargetTemp(val tempC: Int) : QuickAccessAction
    /** Cooler⟷Quieter curve offset (± [com.kei.pulse.model.FanCurve.MAX_BIAS]%; + = cooler/louder). Global,
     *  live within one poll tick — the fan loop re-reads the shifted curve every tick. */
    data class SetFanBias(val bias: Int) : QuickAccessAction
    data class SetRgbMode(val mode: RgbMode) : QuickAccessAction
    data class SetRgbColor(val color: Int) : QuickAccessAction
    data class SetOverlayEnabled(val enabled: Boolean) : QuickAccessAction
    data class SetOverlayPreset(val preset: OverlayPreset) : QuickAccessAction
    data class SetFanSmart(val enabled: Boolean) : QuickAccessAction
    /** Global system controls (set-and-leave, like the Deck) — applied directly to the device, not stored in
     *  [AppSettings]; the panel reflects the live value from telemetry. */
    data class SetBrightness(val percent: Int) : QuickAccessAction
    data class SetVolume(val percent: Int) : QuickAccessAction
    /** SteamOS-style scope: true = write the foreground game's per-app profile, false = write the global default. */
    data class SetScope(val perGame: Boolean) : QuickAccessAction
}

/** The panel dispatches control events here; the host (the watcher service) persists + applies them. */
fun interface QuickAccessActions {
    fun dispatch(action: QuickAccessAction)
}

/** Pure, device-free core for the Quick Access Bar. */
object QuickAccess {
    const val POWER_TARGET_MIN = 10
    const val POWER_TARGET_MAX = 100

    /** Maps a panel control event to the new settings. Pure — the service persists + applies the result. */
    fun reduce(settings: AppSettings, action: QuickAccessAction): AppSettings = when (action) {
        is QuickAccessAction.ToggleAutoTdp ->
            settings.copy(autoTdpDefaultEnabled = !settings.autoTdpDefaultEnabled)
        is QuickAccessAction.SetFpsTarget ->
            settings.copy(autoTdpFpsTarget = action.fps.coerceAtLeast(0))
        is QuickAccessAction.SetBias ->
            settings.copy(autoTdpBias = action.bias)
        is QuickAccessAction.SetTier, is QuickAccessAction.SetStockMode ->
            settings // per-app: applied to the foreground game's PerAppConfig via QuickAccessPerApp
        // (global-scope SetStockMode reduces via QuickAccessScope.globalReduce)
        is QuickAccessAction.SetPowerTarget -> {
            // Wired (2026-07-03): the service's applyQaPowerTarget computes the caps via the shared
            // PowerTargetMath and applies them with persistAsCustom — same path as the in-app slider.
            val pct = action.percent.coerceIn(POWER_TARGET_MIN, POWER_TARGET_MAX)
            settings.withSideControls(
                settings.toSideControlState().copy(
                    powerTargetPercent = pct,
                    powerTargetEnabled = pct < POWER_TARGET_MAX,
                ),
            )
        }
        is QuickAccessAction.SetGpuCap ->
            settings // device + Custom-tuning write (applyQaGpuCap); not an AppSettings field
        is QuickAccessAction.SetFanMode ->
            settings.copy(managedFanMode = action.mode)
        is QuickAccessAction.SetAggressivePark ->
            settings.copy(autoTdpAggressivePark = action.enabled)
        is QuickAccessAction.SetFanTargetTemp ->
            settings.copy(fanTargetTempC = action.tempC.coerceIn(FanTempController.TARGET_MIN_C, FanTempController.TARGET_MAX_C))
        is QuickAccessAction.SetFanBias ->
            settings.copy(fanBias = action.bias.coerceIn(-FanCurve.MAX_BIAS, FanCurve.MAX_BIAS))
        is QuickAccessAction.SetRgbMode ->
            settings.copy(rgbMode = action.mode)
        is QuickAccessAction.SetRgbColor ->
            settings.copy(rgbManualLeftColor = action.color, rgbManualRightColor = action.color)
        is QuickAccessAction.SetOverlayEnabled ->
            settings.copy(overlayEnabled = action.enabled)
        is QuickAccessAction.SetOverlayPreset ->
            settings.copy(overlayPreset = action.preset, overlayElements = action.preset.elements)
        is QuickAccessAction.SetFanSmart ->
            settings.copy(fanSmartEnabled = action.enabled)
        is QuickAccessAction.SetBrightness, is QuickAccessAction.SetVolume ->
            settings // global system controls — applied directly to the device by the service, not persisted here
        is QuickAccessAction.SetScope ->
            settings.copy(quickAccessPerGameScope = action.perGame)
    }

    /**
     * One-line audit form of an action for the `PulseQA` log ("SetBias SMOOTH", "SetTier Balanced"). Every
     * APPLIED action gets exactly one such line with its target appended by the service — the ten-second
     * answer to "why did X change?" (the bias=SMOOTH field incident took a DataStore autopsy because nothing
     * logged applied actions).
     */
    fun auditLabel(action: QuickAccessAction): String = when (action) {
        QuickAccessAction.ToggleAutoTdp -> "ToggleAutoTdp"
        is QuickAccessAction.SetTier -> "SetTier ${action.tier.label}"
        QuickAccessAction.SetStockMode -> "SetStockMode"
        is QuickAccessAction.SetFpsTarget -> "SetFpsTarget ${action.fps}"
        is QuickAccessAction.SetBias -> "SetBias ${action.bias}"
        is QuickAccessAction.SetPowerTarget -> "SetPowerTarget ${action.percent}%"
        is QuickAccessAction.SetGpuCap -> "SetGpuCap ${action.freqKhz / 1000}MHz"
        is QuickAccessAction.SetFanMode -> "SetFanMode ${action.mode}"
        is QuickAccessAction.SetAggressivePark -> "SetAggressivePark ${action.enabled}"
        is QuickAccessAction.SetFanTargetTemp -> "SetFanTargetTemp ${action.tempC}C"
        is QuickAccessAction.SetFanBias -> "SetFanBias ${action.bias}"
        is QuickAccessAction.SetRgbMode -> "SetRgbMode ${action.mode}"
        is QuickAccessAction.SetRgbColor -> "SetRgbColor #%08X".format(action.color)
        is QuickAccessAction.SetOverlayEnabled -> "SetOverlayEnabled ${action.enabled}"
        is QuickAccessAction.SetOverlayPreset -> "SetOverlayPreset ${action.preset}"
        is QuickAccessAction.SetFanSmart -> "SetFanSmart ${action.enabled}"
        is QuickAccessAction.SetBrightness -> "SetBrightness ${action.percent}"
        is QuickAccessAction.SetVolume -> "SetVolume ${action.percent}"
        is QuickAccessAction.SetScope -> "SetScope ${if (action.perGame) "PER-GAME" else "GLOBAL"}"
    }

    /** The floating handle shows only when the experiment is on, the overlay is permitted, and a real
     *  (non-neutral) app is foreground — same gating spirit as the OSD. */
    fun shouldShowHandle(enabled: Boolean, hasOverlayPermission: Boolean, foregroundNeutral: Boolean): Boolean =
        enabled && hasOverlayPermission && !foregroundNeutral

    /** Right-docked panel width: a [fraction] of the screen, clamped to [minPx]..[maxPx] and the screen. */
    fun widthPx(screenWidthPx: Int, fraction: Float, minPx: Int, maxPx: Int): Int =
        (screenWidthPx * fraction).toInt().coerceIn(minPx, maxPx).coerceAtMost(screenWidthPx)
}
