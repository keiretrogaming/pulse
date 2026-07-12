package com.kei.pulse.model

enum class AppColorSource {
    SYSTEM,
    CUSTOM_ACCENT,
}

/** Selectable full-palette themes. SIGNAL is the original/default and the safe baseline. */
enum class PulseThemeId(val label: String, val tagline: String) {
    SIGNAL("Signal", "Default — listening to the void"),
    CRIMSON("Crimson", "Embers of a dying star"),
    CYBERPUNK("Cyberpunk", "Neon grid"),
    RONIN("Ronin", "Ink, blade, and autumn"),
    ADASTRA("Ad Astra", "To the stars"),
}

enum class TileInteractionBehavior {
    SHOW_DIALOG,
    CYCLE_PROFILES,
    OPEN_APP,
}

/**
 * One toggleable item on the in-game overlay. The user picks which of these show via per-element switches;
 * a [OverlayPreset] is just a one-tap bundle of them. Some items only render in the denser layouts
 * (e.g. [CORE_BARS]/[SOC_NAME] are Full-only, [FPS_TREND]'s sparkline is Full) — the layout decides how an
 * enabled item is drawn, the set decides whether it's drawn at all.
 */
enum class OverlayElement(val label: String) {
    FPS("FPS"),
    FPS_TREND("FPS avg · low · trend"),
    GPU_LOAD("GPU load"),
    GPU_CLOCK("GPU clock"),
    GPU_TEMP("GPU temp"),
    CPU_LOAD("CPU load"),
    CPU_CLOCK("CPU clock"),
    CPU_TEMP("CPU temp"),
    CORE_BARS("CPU core bars"),
    RAM("RAM"),
    POWER("Power draw"),
    BATTERY_LEFT("Battery time left"),
    AUTOTDP("AutoTDP / profile"),
    SESSION_TIMER("Session timer"),
    SOC_NAME("Chip name"),
}

/**
 * Density presets for the in-game overlay (OSD): the LAYOUT (cycled in-overlay or chosen in Settings) plus a
 * default [elements] bundle that the Settings chip uses as a one-tap quick-fill. The in-overlay LAYOUT cycle
 * changes only the layout and leaves the user's chosen element set intact.
 */
enum class OverlayPreset(val label: String, val elements: Set<OverlayElement>) {
    COMPACT(
        "Compact",
        setOf(
            OverlayElement.FPS, OverlayElement.GPU_LOAD, OverlayElement.GPU_TEMP,
            OverlayElement.CPU_LOAD, OverlayElement.CPU_TEMP, OverlayElement.POWER,
            OverlayElement.BATTERY_LEFT,
        ),
    ),
    DETAILED(
        "Detailed",
        setOf(
            OverlayElement.FPS, OverlayElement.FPS_TREND, OverlayElement.GPU_LOAD,
            OverlayElement.GPU_CLOCK, OverlayElement.GPU_TEMP, OverlayElement.POWER,
            OverlayElement.CPU_LOAD, OverlayElement.CPU_CLOCK, OverlayElement.CPU_TEMP,
            OverlayElement.RAM, OverlayElement.BATTERY_LEFT, OverlayElement.AUTOTDP,
            OverlayElement.SESSION_TIMER,
        ),
    ),
    FULL("Full", OverlayElement.entries.toSet()),
}

/**
 * RGB joystick-LED mode (AYN / Retroid handhelds that expose the vendor joystick-LED keys). OFF leaves the LED
 * to the system; BATTERY/HEAT are automatic "info LED" mappings (green→red as battery drains; blue→red as it
 * heats); MANUAL is full per-stick control — color, saturation and brightness, per [RgbStick].
 */
enum class RgbMode(val label: String) {
    OFF("Off"),
    BATTERY("Battery"),
    HEAT("Heat"),
    MANUAL("Manual"),
}

/** Which joystick LED(s) the Manual RGB controls edit. */
enum class RgbStick(val label: String) {
    LEFT("Left"),
    RIGHT("Right"),
    BOTH("Both"),
}

data class AppSettings(
    val themeId: PulseThemeId = PulseThemeId.SIGNAL,
    val colorSource: AppColorSource = AppColorSource.SYSTEM,
    val accentColor: Int = 0xFF3F51B5.toInt(),
    val tileTapBehavior: TileInteractionBehavior = TileInteractionBehavior.SHOW_DIALOG,
    /**
     * Master switch. When false, PULSE hands every control back to manufacturer stock (uncapped clocks,
     * Smart fan, restored governor/refresh) and its background service fully stops — the clean "system is in
     * control" state to leave the device in before uninstalling. Default on.
     */
    val pulseEnabled: Boolean = true,
    val applyLastProfileOnBoot: Boolean = false,
    val sleepProfileEnabled: Boolean = false,
    val sleepProfileId: String? = null,
    val hasPromptedQuickSettingsTile: Boolean = false,
    val isQuickSettingsTileAdded: Boolean = false,
    /** True once we've shown the "ignore battery optimization so PULSE keeps running" system prompt (ask once). */
    val batteryOptPromptShown: Boolean = false,
    val powerTargetEnabled: Boolean = false,
    val powerTargetPercent: Int = 100,
    val powerTargetCpuOnly: Boolean = false,
    val gpuLocked: Boolean = false,
    val gpuFloorPercent: Int = 0,
    val cpuFloorPercent: Int = 0,
    /** Fan mode PULSE is holding globally (from the Fan card). Re-asserted vs. the system Fan tile; null = none. */
    val managedFanMode: Int? = null,
    val activeTierLabel: String = "Custom",
    val primeCoreBoostLimited: Boolean = false,
    val learnedPeakW: Float = 0f,
    /** AutoTDP as the global default: dynamically tune any foreground game without its own binding. */
    val autoTdpDefaultEnabled: Boolean = false,
    /** Global default AutoTDP frame-rate target (fps); `0` = Max. Per-app bindings can override it. */
    val autoTdpFpsTarget: Int = 60,
    /**
     * AutoTDP aggressive park: offline the prime cores when they aren't the limiter. Defaults ON — the
     * prime can't be frequency-scaled below the vendor floor mid-game, so parking is its only power lever,
     * and it auto-unparks the instant fps dips. Per-app profiles can override.
     */
    val autoTdpAggressivePark: Boolean = true,
    /** AutoTDP efficiency↔smoothness lean (global default; per-app can override). Default EFFICIENT. */
    val autoTdpBias: AutoTdpBias = AutoTdpBias.EFFICIENT,
    // In-game overlay (OSD). Position is a TOP|START pixel offset; opacity is a 40–100 percent.
    val overlayEnabled: Boolean = false,
    /**
     * Quick Access Bar (EXPERIMENTAL, default OFF). A right-docked in-game panel to swap PULSE settings
     * live, à la the Steam Deck Quick Access menu. Opt-in only — nothing shows unless this is on.
     */
    val quickAccessEnabled: Boolean = false,
    /** Show the floating handle for the Quick Access bar. Off = combo-only (no on-screen arrow). */
    val quickAccessShowHandle: Boolean = true,
    /** Controller combo that toggles the Quick Access bar, as `+`-joined button names; null = none set. */
    val quickAccessCombo: String? = null,
    /**
     * Quick Access scope (SteamOS-style "This game" vs "All games"): when true, the bar's performance edits
     * (AutoTDP/tier/fps/bias/park) write the FOREGROUND game's per-app profile; when false they write the
     * GLOBAL default. Sticky. Default true = the historical per-game behavior. Fan/RGB/overlay/system controls
     * are always global regardless.
     */
    val quickAccessPerGameScope: Boolean = true,
    val overlayPreset: OverlayPreset = OverlayPreset.COMPACT,
    val overlayOpacity: Int = 90,
    val overlayPosX: Int = 24,
    val overlayPosY: Int = 48,
    /** Which items the overlay shows (independent of the layout preset). Defaults to the Compact bundle. */
    val overlayElements: Set<OverlayElement> = OverlayPreset.COMPACT.elements,
    // Custom fan curve (Odin 3 only). Active when the fan mode is FanController.CUSTOM; PULSE drives the
    // gpio5_pwm2 PWM from this temp→% curve, slewing toward the target at [fanResponseStep] %/second. The
    // Cooler/Quieter [fanBias] is a live % offset applied on top of the curve. Inert elsewhere.
    val fanCurve: FanCurve = FanCurve.DEFAULT,
    val fanResponseStep: Int = FanCurve.DEFAULT_SLEW,
    val fanBias: Int = 0,
    // Smart (closed-loop) Custom fan: a PI controller holds the SoC at [fanTargetTempC] with minimum noise,
    // instead of the static curve. On by default — it's the self-adapting "scientific" mode; toggle off to
    // hand-edit the curve. Inert unless the fan mode is FanController.CUSTOM (Odin 3 / RP6 / Thor).
    val fanSmartEnabled: Boolean = true,
    val fanTargetTempC: Int = FanTempController.DEFAULT_TARGET_C,
    // RGB joystick LED. Inert where the vendor joystick-LED key isn't present. BATTERY/HEAT are automatic;
    // MANUAL is per-stick: each stick's color (ARGB, stored at full value) + brightness (0..1, baked in at apply).
    val rgbMode: RgbMode = RgbMode.OFF,
    val rgbManualTarget: RgbStick = RgbStick.BOTH,
    val rgbManualLeftColor: Int = 0xFF3F6BFF.toInt(),
    val rgbManualLeftBrightness: Float = 1f,
    val rgbManualRightColor: Int = 0xFF3F6BFF.toInt(),
    val rgbManualRightBrightness: Float = 1f,
)

/** Flattened settings boundary for the seven side-controls that always travel together. */
fun AppSettings.toSideControlState(): SideControlState = SideControlState(
    powerTargetEnabled = powerTargetEnabled,
    powerTargetPercent = powerTargetPercent,
    powerTargetCpuOnly = powerTargetCpuOnly,
    gpuLocked = gpuLocked,
    gpuFloorPercent = gpuFloorPercent,
    cpuFloorPercent = cpuFloorPercent,
    primeCoreBoostLimited = primeCoreBoostLimited,
)

/** Replace all seven flattened side-controls while preserving every unrelated setting. */
fun AppSettings.withSideControls(sideControls: SideControlState): AppSettings = copy(
    powerTargetEnabled = sideControls.powerTargetEnabled,
    powerTargetPercent = sideControls.powerTargetPercent,
    powerTargetCpuOnly = sideControls.powerTargetCpuOnly,
    gpuLocked = sideControls.gpuLocked,
    gpuFloorPercent = sideControls.gpuFloorPercent,
    cpuFloorPercent = sideControls.cpuFloorPercent,
    primeCoreBoostLimited = sideControls.primeCoreBoostLimited,
)
