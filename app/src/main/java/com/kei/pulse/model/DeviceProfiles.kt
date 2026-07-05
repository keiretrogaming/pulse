package com.kei.pulse.model

import com.kei.pulse.data.FanController

/**
 * Per-device hardware invariants as DATA — the single source for facts that used to live as scattered
 * `ro.soc.model` string checks. Every row is pinned by `DeviceProfilesTest`, and the test also asserts the
 * consumers ([PerAppConfig.fpsTargetsFor], `AutoTuneController.appliesOdinPowerTuning`) agree with the table,
 * so a device fact can't silently drift between call sites. The recurring field-bug class this kills:
 * an assumption that's true on the Odin but false on the Thor/RP6 baked into imperative code.
 */
data class DeviceProfile(
    val name: String,
    /**
     * The `fan_mode` PULSE hands the fan to when RELEASING management (the managed→unmanaged edge).
     * Vendor Smart everywhere today — the adaptive, thermally safe mode the rest of PULSE also restores
     * (game-exit snapshot, Custom-stop, revert-to-stock). NOT a claim about the vendor's boot default
     * (the Thor reportedly boots Sport — unverified; PULSE never touches a fan it wasn't managing).
     */
    val fanReleaseMode: Int,
    /**
     * The prime cluster ignores a lower `scaling_max` mid-game (vendor floor): capping it sheds no heat or
     * power — only parking does, and thermal trims must skip it. True on the Odin's Q8; the SD 8 Gen 2's
     * prime genuinely scales (595→3187 MHz confirmed by log).
     */
    val primeIsVendorFloored: Boolean,
    /** Firmware honors the Game Mode fps cap (`cmd game mode --fps`); others enforce via the panel refresh. */
    val honorsGameModeFpsCap: Boolean,
    /** AutoTDP fps-target options this panel can actually pace (Odin has no 90/40 Hz mode; caps floor). */
    val fpsTargetOptions: List<Int>,
    /** Odin-tuned watt cap + prime-walled settle apply (tight ~13–14 W chassis, vendor-floored prime). */
    val appliesOdinPowerTuning: Boolean,
)

object DeviceProfiles {

    /** AYN Odin 3 — Dragonwing Q8 (`CQ8725S`), Adreno 830, 60/120 Hz panel. All rows hardware-verified. */
    val ODIN3 = DeviceProfile(
        name = "AYN Odin 3 (Dragonwing Q8)",
        fanReleaseMode = FanController.SMART,
        primeIsVendorFloored = true,
        honorsGameModeFpsCap = true,
        fpsTargetOptions = listOf(30, 60, 120),
        appliesOdinPowerTuning = true,
    )

    /** AYN Thor + Retroid Pocket 6 — SD 8 Gen 2 (`QCS8550`), Adreno 740, panels do 90 Hz via refresh. */
    val SD8GEN2 = DeviceProfile(
        name = "SD 8 Gen 2 (AYN Thor / Retroid Pocket 6)",
        fanReleaseMode = FanController.SMART,
        primeIsVendorFloored = false,
        honorsGameModeFpsCap = false,
        fpsTargetOptions = listOf(60, 90, 120),
        appliesOdinPowerTuning = false,
    )

    /** Unknown hardware: conservative — Smart release, no watt cap/settle, refresh-path fps targets. */
    val UNKNOWN = DeviceProfile(
        name = "Unknown device",
        fanReleaseMode = FanController.SMART,
        primeIsVendorFloored = false,
        honorsGameModeFpsCap = false,
        fpsTargetOptions = listOf(60, 90, 120),
        appliesOdinPowerTuning = false,
    )

    /** Resolve by `ro.soc.model` (the gating key everywhere in PULSE); null/unknown ⇒ [UNKNOWN]. */
    fun forSoc(socModel: String?): DeviceProfile = when (socModel?.trim()?.uppercase()) {
        "CQ8725S" -> ODIN3
        "QCS8550" -> SD8GEN2
        else -> UNKNOWN
    }
}
