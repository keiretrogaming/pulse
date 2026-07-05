package com.kei.pulse.data

import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.model.PerformanceProfile
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.ProfileStateResolver
import com.kei.pulse.model.ProfileSource
import com.kei.pulse.model.TunerState
import com.kei.pulse.root.PerformanceCommandBuilder
import com.kei.pulse.root.RootCommandRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import java.util.UUID

private data class StorageState(
    val storedProfiles: List<PerformanceProfile>,
    val deletedBundledProfileIds: Set<String>,
    val displayOrder: List<String>,
    val lastValues: Map<Int, Int>,
    val selectedProfileId: String?,
    val lastAppliedDisplayProfileId: String?,
)

private data class PartialStorageState(
    val storedProfiles: List<PerformanceProfile>,
    val deletedBundledProfileIds: Set<String>,
    val displayOrder: List<String>,
    val lastValues: Map<Int, Int>,
)

internal data class ImportedProfileMerge(
    val profiles: List<PerformanceProfile>,
    val restoredBundledProfileIds: Set<String>,
)

class PerformanceRepository(
    private val detector: CpuPolicyDetector,
    private val bundledProfileProvider: BundledProfileProvider,
    private val profileStorage: ProfileStorage,
    private val commandBuilder: PerformanceCommandBuilder,
    private val rootCommandRunner: RootCommandRunner,
    private val governorController: GovernorController,
) {
    companion object {
        @Volatile
        private var processCachedPolicies: List<CpuPolicyInfo> = emptyList()
    }

    data class ApplyOutcome(
        val actualValues: Map<Int, Int>,
        val verificationPassed: Boolean,
        val commandOutput: String?,
    )

    private val liveRefreshToken = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeState(): Flow<TunerState> {
        val storageState = combine(
            profileStorage.profiles,
            profileStorage.deletedBundledProfileIds,
            profileStorage.displayOrder,
            profileStorage.lastValues,
        ) { storedProfiles, deletedBundledProfileIds, displayOrder, lastValues ->
            PartialStorageState(
                storedProfiles = storedProfiles,
                deletedBundledProfileIds = deletedBundledProfileIds,
                displayOrder = displayOrder,
                lastValues = lastValues,
            )
        }
        val completeStorageState = combine(
            storageState,
            profileStorage.selectedProfileId,
            profileStorage.lastAppliedDisplayProfileId,
        ) { partial, selectedProfileId, lastAppliedDisplayProfileId ->
            StorageState(
                storedProfiles = partial.storedProfiles,
                deletedBundledProfileIds = partial.deletedBundledProfileIds,
                displayOrder = partial.displayOrder,
                lastValues = partial.lastValues,
                selectedProfileId = selectedProfileId,
                lastAppliedDisplayProfileId = lastAppliedDisplayProfileId,
            )
        }
        return combine(
            liveRefreshToken,
            completeStorageState,
        ) { _, storage -> storage }
            .transformLatest { storage ->
                val cachedPolicies = processCachedPolicies
                val policies = if (cachedPolicies.isEmpty()) {
                    detector.detectPolicies().also { detectedPolicies ->
                        processCachedPolicies = detectedPolicies
                    }
                } else {
                    val liveValues = detector.readCurrentMaxValues(cachedPolicies)
                    cachedPolicies.map { policy ->
                        policy.copy(currentMaxFreq = liveValues[policy.id] ?: policy.currentMaxFreq)
                    }
                }
                val actualValues = policies.associate { it.id to it.currentMaxFreq }
                val defaultBundledProfiles = bundledProfileProvider.createProfiles(policies)
                val storedById = storage.storedProfiles.associateBy { it.id }
                val knownBundledIds = defaultBundledProfiles.map { it.id }.toSet()
                val bundledProfiles = defaultBundledProfiles.mapIndexed { index, profile ->
                    if (profile.id in storage.deletedBundledProfileIds) {
                        null
                    } else {
                        val stored = storedById[profile.id]
                        if (stored != null) {
                            profile.copy(
                                name = stored.name,
                                maxFrequencies = stored.maxFrequencies,
                                order = stored.order,
                                isEditable = true,
                                isDeletable = true,
                            )
                        } else {
                            profile.copy(
                                order = index,
                                isEditable = true,
                                isDeletable = true,
                            )
                        }
                    }
                }.filterNotNull()
                val userProfiles = storage.storedProfiles
                    .filter { it.source == ProfileSource.USER && it.id !in knownBundledIds }
                val orderedRealProfiles = applyDisplayOrder(
                    profiles = bundledProfiles + userProfiles,
                    orderedIds = storage.displayOrder,
                )
                val defaultValues = policies.associate { it.id to it.currentMaxFreq }
                val stockProfile = ProfileStateResolver.buildStockProfile(policies)
                emit(
                    ProfileStateResolver.resolve(
                        TunerState(
                            isLoading = false,
                            isPServerAvailable = rootCommandRunner.isAvailable,
                            policies = policies,
                            actualValues = actualValues,
                            currentValues = mergeValues(policies, defaultValues, storage.lastValues),
                            bundledProfiles = orderedRealProfiles.filter { it.source == ProfileSource.BUNDLED },
                            userProfiles = orderedRealProfiles.filter { it.source == ProfileSource.USER },
                            selectedProfileId = storage.selectedProfileId?.takeIf { id ->
                                orderedRealProfiles.any { it.id == id }
                            },
                            lastAppliedDisplayProfileId = storage.lastAppliedDisplayProfileId,
                            displayProfiles = ProfileStateResolver.buildDisplayProfiles(
                                realProfiles = orderedRealProfiles,
                                stockProfile = stockProfile,
                                orderedIds = storage.displayOrder,
                            ),
                        ),
                    ),
                )
            }
            .flowOn(Dispatchers.IO)
    }

    suspend fun applyTier(tier: PowerTier): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val policies = resolvePolicies()
        if (policies.isEmpty()) {
            return Result.failure(IllegalStateException("No CPU clusters found"))
        }
        return applyValues(
            policies = policies,
            selectedValues = tierFrequencies(tier, policies),
            isReset = tier == PowerTier.MAX,
            appliedDisplayProfileId = ProfileStateResolver.MANUAL_PROFILE_ID,
        ).onSuccess {
            // Re-assert the preset's smart-default governor (the vendor daemon resets governors,
            // like the GPU min_pwrlevel stomp). Governors respect scaling_max_freq, so the caps
            // just applied still hold. CUSTOM has no default and is handled by its callers.
            tier.governorLabel?.let { label ->
                GovernorController.OPTIONS.firstOrNull { it.label == label }?.let { option ->
                    governorController.setGovernor(policies, option)
                }
            }
        }
    }

    /**
     * Re-apply the frequency map the user last configured in Custom mode. Used when the tile
     * (or the app) switches back to Custom, so it restores the user's own setup instead of
     * leaving whatever preset was last cycled through. Fails when no Custom setup is saved yet.
     */
    suspend fun restoreCustomValues(): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val policies = resolvePolicies()
        if (policies.isEmpty()) {
            return Result.failure(IllegalStateException("No CPU clusters found"))
        }
        val saved = profileStorage.customValues.first()
            .filterKeys { policyId -> policies.any { it.id == policyId } }
        if (saved.isEmpty()) {
            return Result.failure(IllegalStateException("No saved Custom configuration"))
        }
        return applyValues(
            policies = policies,
            selectedValues = saved,
            isReset = false,
            appliedDisplayProfileId = ProfileStateResolver.MANUAL_PROFILE_ID,
            persistAsCustom = true,
        )
    }

    /**
     * Live GPU scaling-max readback (kHz) for the Quick Access GPU-cap stepper — one node read, so the
     * stepper's shown value is what the device actually holds. Null when policies aren't detected yet.
     */
    fun readCurrentGpuCapKhz(): Int? {
        val gpu = processCachedPolicies.firstOrNull { it.isGpu } ?: return null
        return detector.readCurrentMaxValues(listOf(gpu))[gpu.id]
    }

    /**
     * Cap the GPU at [freqKhz] (snapped to a supported level) while PRESERVING the saved Custom CPU values —
     * the Quick Access bar's GPU-cap stepper. A partial persistAsCustom map would REPLACE the whole saved
     * Custom map (silent CPU-values loss), so this merges: the saved Custom values (falling back to the live
     * device values when nothing is saved yet) + the new GPU entry, applied and re-persisted as the full map.
     */
    suspend fun applyGpuCap(freqKhz: Int): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val policies = resolvePolicies()
        val gpu = policies.firstOrNull { it.isGpu }
            ?: return Result.failure(IllegalStateException("No GPU policy found"))
        val target = gpu.supportedFrequencies.minByOrNull { kotlin.math.abs(it - freqKhz) }
            ?: gpu.selectableMaxFreq
        val saved = profileStorage.customValues.first()
            .filterKeys { policyId -> policies.any { it.id == policyId } }
        val base = saved.ifEmpty { detector.readCurrentMaxValues(policies) }
        return applyValues(
            policies = policies,
            selectedValues = base + (gpu.id to target),
            isReset = false,
            appliedDisplayProfileId = ProfileStateResolver.MANUAL_PROFILE_ID,
            persistAsCustom = true,
        )
    }

    private fun resolvePolicies(): List<CpuPolicyInfo> {
        return if (processCachedPolicies.isNotEmpty()) {
            val liveValues = detector.readCurrentMaxValues(processCachedPolicies)
            processCachedPolicies.map { p -> p.copy(currentMaxFreq = liveValues[p.id] ?: p.currentMaxFreq) }
        } else {
            detector.detectPolicies().also { processCachedPolicies = it }
        }
    }

    /** Live per-policy max values (kHz), for snapshotting state before a per-app switch. */
    suspend fun readCurrentValues(): Map<Int, Int> {
        val policies = resolvePolicies()
        return policies.associate { it.id to it.currentMaxFreq }
    }

    /** Detected CPU+GPU policies (process-cached), e.g. for the overlay telemetry feed. */
    fun currentPolicies(): List<CpuPolicyInfo> = resolvePolicies()

    /** Friendly SoC model string for display (e.g. overlay header); null if undetected. */
    fun socModel(): String? = bundledProfileProvider.currentSocModel()

    /** Applies a display profile (saved profile or Stock) by id — the tile/per-app entry point. */
    suspend fun applyDisplayProfileById(profileId: String): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val state = observeState().first()
        val profile = state.displayProfiles.firstOrNull { it.id == profileId }
            ?: return Result.failure(IllegalStateException("Profile is unavailable"))
        return applyValues(
            policies = state.policies,
            selectedValues = profile.maxFrequencies,
            isReset = profileId == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = profileId,
        )
    }

    /** Re-applies a raw frequency snapshot, e.g. restoring pre-launch state after a per-app switch. */
    suspend fun applyRawValues(values: Map<Int, Int>, appliedDisplayProfileId: String?): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val policies = resolvePolicies()
        if (policies.isEmpty()) {
            return Result.failure(IllegalStateException("No CPU clusters found"))
        }
        val filtered = values.filterKeys { id -> policies.any { it.id == id } }
        if (filtered.isEmpty()) {
            return Result.failure(IllegalStateException("No stored values match detected policies"))
        }
        return applyValues(
            policies = policies,
            selectedValues = filtered,
            isReset = appliedDisplayProfileId == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = appliedDisplayProfileId,
        )
    }

    private fun tierFrequencies(tier: PowerTier, policies: List<CpuPolicyInfo>): Map<Int, Int> {
        val powerSaving = tier == PowerTier.POWER_SAVING
        val primePolicyId = policies.filterNot { it.isGpu }.sortedBy { it.id }.lastOrNull()?.id
        return policies.associate { policy ->
            val factor = when {
                policy.isGpu && !powerSaving -> 1.0
                policy.isGpu -> tier.gpuFactor
                else -> tier.cpuFactor
            }
            val target = (policy.selectableMaxFreq * factor).toInt()
            var snapped = policy.supportedFrequencies.minByOrNull { kotlin.math.abs(it - target) }
                ?: policy.selectableMaxFreq
            if (powerSaving && !policy.isGpu && policy.id == primePolicyId) {
                val freqs = policy.supportedFrequencies
                snapped = minOf(snapped, freqs.getOrNull(freqs.size - 2) ?: freqs.last())
            }
            policy.id to snapped
        }
    }

    suspend fun applyValues(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        persistAsCustom: Boolean = false,
    ): Result<ApplyOutcome> {
        return applyValuesInternal(
            policies = policies,
            selectedValues = selectedValues,
            isReset = isReset,
            appliedDisplayProfileId = appliedDisplayProfileId,
            persistNormalState = true,
            persistAsCustom = persistAsCustom,
        )
    }

    private suspend fun applyValuesInternal(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        persistNormalState: Boolean,
        persistAsCustom: Boolean = false,
    ): Result<ApplyOutcome> {
        val filtered = selectedValues.filterKeys { policyId -> policies.any { it.id == policyId } }
        // Hold a CPU cap against the vendor perflock: lower the PRIME cluster's scaling_min (and 444-lock it)
        // BEFORE writing its max, or the daemon floors the prime's min and the kernel clamps a lower max back
        // up — the prime cap silently won't bite (e.g. a 69% Power Target reads back at 4.2 GHz). PRIME ONLY:
        // touching the perf cluster's min wakes the HAL and stomps perf's max. On a reset (Max/Stock) hand
        // every CPU min back writable (644) to clear stale locks. Mirrors AutoTDP's apply/release path.
        val cpuPolicies = policies.filterNot { it.isGpu }
        val lowerMinPolicyIds = if (isReset) {
            cpuPolicies.map { it.id }.toSet()
        } else {
            setOfNotNull(cpuPolicies.maxByOrNull { it.selectableMaxFreq }?.id)
        }
        val script = commandBuilder.buildApplyScript(policies, filtered, isReset, lowerMinPolicyIds)
        return rootCommandRunner.executeScript(script).mapCatching { output ->
            if (persistNormalState) {
                profileStorage.persistLastValues(filtered)
                profileStorage.persistLastAppliedDisplayProfile(appliedDisplayProfileId)
            }
            // Snapshot the user's Custom setup so cycling away to a preset and back restores it.
            if (persistAsCustom) {
                profileStorage.persistCustomValues(filtered)
            }
            val actualValues = detector.readCurrentMaxValues(policies)
            refreshLiveValues()
            ApplyOutcome(
                actualValues = actualValues,
                verificationPassed = filtered.all { (policyId, requestedValue) ->
                    val policy = policies.firstOrNull { it.id == policyId } ?: return@all false
                    val actualValue = actualValues[policyId] ?: return@all false
                    ProfileStateResolver.isPolicyValueSatisfied(
                        policy = policy,
                        requestedValue = requestedValue,
                        actualValue = actualValue,
                    )
                },
                commandOutput = output,
            )
        }
    }

    suspend fun applySleepProfile(profileId: String): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val state = observeState().first()
        if (state.policies.isEmpty()) {
            return Result.failure(IllegalStateException("No CPU clusters found"))
        }
        val sleepProfile = state.displayProfiles.firstOrNull { profile -> profile.id == profileId }
            ?: return Result.failure(IllegalStateException("Sleep profile is unavailable"))
        val currentValues = detector.readCurrentMaxValues(state.policies)
        profileStorage.persistSleepRestoreState(
            values = currentValues,
            profileId = state.activeDisplayProfileId ?: state.lastAppliedDisplayProfileId,
        )
        return applyValuesInternal(
            policies = state.policies,
            selectedValues = sleepProfile.maxFrequencies,
            isReset = sleepProfile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = sleepProfile.id,
            persistNormalState = false,
        )
    }

    suspend fun restorePreSleepState(): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val policies = detector.detectPolicies()
        if (policies.isEmpty()) {
            return Result.failure(IllegalStateException("No CPU clusters found"))
        }
        val restoreValues = profileStorage.sleepRestoreValues.first()
        if (restoreValues.isEmpty()) {
            return Result.failure(IllegalStateException("No sleep restore state"))
        }
        val restoreProfileId = profileStorage.sleepRestoreDisplayProfileId.first()
        val filteredValues = restoreValues.filterKeys { policyId ->
            policies.any { it.id == policyId }
        }
        if (filteredValues.isEmpty()) {
            return Result.failure(IllegalStateException("No stored values match detected policies"))
        }
        return applyValuesInternal(
            policies = policies,
            selectedValues = filteredValues,
            isReset = restoreProfileId == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = restoreProfileId,
            persistNormalState = true,
        ).onSuccess {
            profileStorage.persistSelectedProfile(
                restoreProfileId?.takeUnless { id ->
                    id == ProfileStateResolver.STOCK_PROFILE_ID || id == ProfileStateResolver.MANUAL_PROFILE_ID
                },
            )
            profileStorage.clearSleepRestoreState()
        }
    }

    suspend fun applyPersistedLastValuesOnBoot(): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val policies = detector.detectPolicies()
        if (policies.isEmpty()) {
            return Result.failure(IllegalStateException("No CPU clusters found"))
        }
        val persistedValues = profileStorage.lastValues.first()
        if (persistedValues.isEmpty()) {
            return Result.failure(IllegalStateException("No stored values to apply"))
        }
        val lastAppliedDisplayProfileId = profileStorage.lastAppliedDisplayProfileId.first()
        if (lastAppliedDisplayProfileId == ProfileStateResolver.STOCK_PROFILE_ID) {
            return Result.failure(IllegalStateException("Boot apply skipped: stock is active"))
        }
        val filteredValues = persistedValues.filterKeys { policyId ->
            policies.any { it.id == policyId }
        }
        if (filteredValues.isEmpty()) {
            return Result.failure(IllegalStateException("No stored values match detected policies"))
        }
        return applyValues(
            policies = policies,
            selectedValues = filteredValues,
            isReset = false,
            appliedDisplayProfileId = lastAppliedDisplayProfileId,
        )
    }

    suspend fun cycleTileProfile(): Result<PerformanceProfile> {
        val state = observeState().first()
        if (!state.isPServerAvailable || state.policies.isEmpty()) {
            return Result.failure(IllegalStateException("Tile controls are unavailable"))
        }

        val cycleProfiles = state.displayProfiles.filter { profile ->
            profile.source != ProfileSource.VIRTUAL || profile.id == ProfileStateResolver.STOCK_PROFILE_ID
        }
        if (cycleProfiles.isEmpty()) {
            return Result.failure(IllegalStateException("No profiles available for tile cycling"))
        }

        val currentProfileId = state.lastAppliedDisplayProfileId
            ?.takeIf { id -> cycleProfiles.any { profile -> profile.id == id } }
            ?: state.activeDisplayProfileId
        val currentIndex = cycleProfiles.indexOfFirst { it.id == currentProfileId }
        val nextProfile = if (currentIndex == -1) {
            cycleProfiles.first()
        } else {
            cycleProfiles[(currentIndex + 1) % cycleProfiles.size]
        }

        return applyValues(
            policies = state.policies,
            selectedValues = nextProfile.maxFrequencies,
            isReset = nextProfile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = nextProfile.id,
        ).map {
            selectProfile(nextProfile.id.takeUnless { id -> id == ProfileStateResolver.STOCK_PROFILE_ID })
            nextProfile
        }
    }

    suspend fun createUserProfile(name: String, values: Map<Int, Int>) {
        val currentProfiles = realProfiles()
        profileStorage.saveProfile(
            PerformanceProfile(
                id = "user_${UUID.randomUUID()}",
                name = name,
                maxFrequencies = values,
                source = ProfileSource.USER,
                order = currentProfiles.size,
            ),
        )
    }

    suspend fun exportProfilesJson(): String {
        val profiles = realProfiles()
            .filter { profile -> profile.source != ProfileSource.VIRTUAL }
            .mapIndexed { index, profile ->
                profile.copy(
                    order = index,
                    isEditable = true,
                    isDeletable = true,
                )
            }
        return ProfileJsonCodec.encodeShareFile(
            profiles = profiles,
            socModel = bundledProfileProvider.currentSocModel(),
        )
    }

    suspend fun importProfilesJson(rawJson: String): Int {
        val state = observeState().first()
        val policyIds = state.policies.associateBy { it.id }
        val currentProfiles = state.displayProfiles.filter { it.source != ProfileSource.VIRTUAL }
        val defaultBundledProfiles = bundledProfileProvider.createProfiles(state.policies)
        val validProfiles = ProfileJsonCodec.parseShareProfiles(rawJson)
            .filter { profile ->
                profile.maxFrequencies.isNotEmpty() &&
                    profile.maxFrequencies.all { (policyId, frequency) ->
                        val policy = policyIds[policyId] ?: return@all false
                        frequency in policy.supportedFrequencies
                    }
            }

        val merge = mergeImportedProfiles(
            currentProfiles = currentProfiles,
            defaultBundledProfiles = defaultBundledProfiles,
            importedProfiles = validProfiles,
        )
        merge.restoredBundledProfileIds.forEach { bundledProfileId ->
            profileStorage.unmarkBundledProfileDeleted(bundledProfileId)
        }
        merge.profiles.forEach { profile ->
            profileStorage.saveProfile(profile)
        }
        return validProfiles.size
    }

    suspend fun updateProfile(profileId: String, name: String, values: Map<Int, Int>) {
        val existing = realProfiles().firstOrNull { it.id == profileId }
            ?: return
        if (existing.source == ProfileSource.BUNDLED) {
            profileStorage.unmarkBundledProfileDeleted(profileId)
        }
        profileStorage.saveProfile(
            existing.copy(
                name = name,
                maxFrequencies = values,
            ),
        )
    }

    suspend fun deleteProfile(profileId: String) {
        val existing = realProfiles().firstOrNull { it.id == profileId } ?: return
        if (existing.source == ProfileSource.BUNDLED) {
            profileStorage.markBundledProfileDeleted(profileId)
        } else {
            profileStorage.deleteProfile(profileId)
        }
        if (profileStorage.selectedProfileId.first() == profileId) {
            profileStorage.persistSelectedProfile(null)
        }
    }

    suspend fun moveProfile(profileId: String, offset: Int) {
        val state = observeState().first()
        val profiles = state.displayProfiles.toMutableList()
        val currentIndex = profiles.indexOfFirst { it.id == profileId }
        if (currentIndex == -1) return
        val targetIndex = (currentIndex + offset).coerceIn(0, profiles.lastIndex)
        if (currentIndex == targetIndex) return
        val profile = profiles.removeAt(currentIndex)
        profiles.add(targetIndex, profile)
        profileStorage.persistDisplayOrder(profiles.map { it.id })
        profileStorage.replaceProfiles(
            profiles
                .filter { it.source != ProfileSource.VIRTUAL }
                .mapIndexed { index, realProfile -> realProfile.copy(order = index) },
        )
    }

    suspend fun resetProfilesToDefault() {
        profileStorage.resetProfiles()
        profileStorage.persistSelectedProfile(null)
    }

    suspend fun selectProfile(profileId: String?) {
        profileStorage.persistSelectedProfile(profileId)
    }

    fun refreshLiveValues() {
        liveRefreshToken.update { it + 1 }
    }

    private fun mergeValues(
        policies: List<CpuPolicyInfo>,
        currentValues: Map<Int, Int>,
        persistedValues: Map<Int, Int>,
    ): Map<Int, Int> {
        return policies.associate { policy ->
            val supported = policy.supportedFrequencies.toSet()
            val persisted = persistedValues[policy.id]
            val safeValue = if (persisted != null && persisted in supported) {
                persisted
            } else {
                currentValues[policy.id] ?: policy.currentMaxFreq
            }
            policy.id to safeValue
        }
    }

    private suspend fun realProfiles(): List<PerformanceProfile> {
        val state = observeState().first()
        return state.displayProfiles.filter { it.source != ProfileSource.VIRTUAL }
    }

    private fun applyDisplayOrder(
        profiles: List<PerformanceProfile>,
        orderedIds: List<String>,
    ): List<PerformanceProfile> {
        if (orderedIds.isEmpty()) return profiles.sortedBy { it.order }
        val byId = profiles.associateBy { it.id }
        val ordered = orderedIds.mapNotNull(byId::get)
        val missing = profiles.filter { it.id !in orderedIds }.sortedBy { it.order }
        return ordered + missing
    }

}

internal fun mergeImportedProfiles(
    currentProfiles: List<PerformanceProfile>,
    defaultBundledProfiles: List<PerformanceProfile>,
    importedProfiles: List<PerformanceProfile>,
): ImportedProfileMerge {
    val currentById = currentProfiles.associateBy { it.id }
    val defaultBundledById = defaultBundledProfiles.associateBy { it.id }
    val restoredBundledProfileIds = mutableSetOf<String>()
    var nextNewProfileOrder = currentProfiles.size

    val profiles = importedProfiles.map { importedProfile ->
        val bundledProfile = defaultBundledById[importedProfile.id]
        if (bundledProfile != null) {
            restoredBundledProfileIds += bundledProfile.id
            val existing = currentById[importedProfile.id] ?: bundledProfile
            existing.copy(
                name = importedProfile.name,
                maxFrequencies = importedProfile.maxFrequencies,
                source = ProfileSource.BUNDLED,
                isEditable = true,
                isDeletable = true,
            )
        } else {
            val existing = currentById[importedProfile.id]
            existing?.copy(
                name = importedProfile.name,
                maxFrequencies = importedProfile.maxFrequencies,
                source = ProfileSource.USER,
                isEditable = true,
                isDeletable = true,
            )
                ?: importedProfile.copy(
                    source = ProfileSource.USER,
                    order = nextNewProfileOrder++,
                    isEditable = true,
                    isDeletable = true,
                )
        }
    }

    return ImportedProfileMerge(
        profiles = profiles,
        restoredBundledProfileIds = restoredBundledProfileIds,
    )
}
