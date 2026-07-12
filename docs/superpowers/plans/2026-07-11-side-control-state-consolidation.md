# Side-Control State Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace repeated seven-field side-control plumbing with the canonical `SideControlState` value object without changing persistence keys, controller behavior, or user-visible behavior.

**Architecture:** `AppSettings` remains flattened and gains two tested boundary adapters. `CustomTuning`, `SettingsStorage.persistTuningState()`, the ViewModel, and Quick Access pass one `SideControlState`; only `SettingsStorage` expands it into the existing Preferences keys and only the ViewModel expands it into existing UI flows.

**Tech Stack:** Kotlin, Android Preferences DataStore, Kotlin coroutines/Flow, JUnit 4, Gradle Android unit tests.

## Global Constraints

- Existing Preferences DataStore keys and stored values remain unchanged; no data migration.
- Governor selection, persistence, controller calls, and restoration behavior remain unchanged.
- Device writes and their ordering remain unchanged.
- Power-tier transition semantics remain unchanged.
- Quick Access AutoTDP ownership guards and Custom-tier persistence behavior remain unchanged.
- `AppSettings` keeps its seven flattened fields; broader nesting is a separate PR.
- Work only in `/Users/joakimb/Projects/vibe/pulse/.worktrees/side-control-state-consolidation` on `codex/side-control-state-consolidation`.

## File Map

- Modify `app/src/main/java/com/kei/pulse/model/TierTransition.kt`: give `SideControlState` neutral defaults and return the embedded Custom snapshot directly.
- Modify `app/src/main/java/com/kei/pulse/model/AppSettings.kt`: own the flattened-to-canonical boundary adapters.
- Modify `app/src/main/java/com/kei/pulse/model/CustomTuning.kt`: compose `SideControlState` with the independently handled governor label.
- Modify `app/src/main/java/com/kei/pulse/data/SettingsStorage.kt`: serialize the canonical object into the unchanged live and Custom Preferences keys.
- Modify `app/src/main/java/com/kei/pulse/ui/TunerViewModel.kt`: snapshot once, persist once, and read Custom side controls through the canonical object.
- Modify `app/src/main/java/com/kei/pulse/overlay/QuickAccessState.kt`: route Power Target reduction through the AppSettings boundary adapters.
- Modify `app/src/main/java/com/kei/pulse/appwatch/ForegroundAppMonitorService.kt`: persist and update complete canonical snapshots from Quick Access.
- Create `app/src/test/java/com/kei/pulse/model/SideControlStateMappingTest.kt`: verify both AppSettings boundary directions and unrelated-field preservation.
- Modify `app/src/test/java/com/kei/pulse/model/TierTransitionTest.kt`: verify composed Custom snapshots and unchanged transition behavior.
- Create `app/src/test/java/com/kei/pulse/data/SettingsStorageApiTest.kt`: compile-check the canonical persistence signature.
- Modify `app/src/test/java/com/kei/pulse/overlay/QuickAccessStateTest.kt`: compare the complete side-control snapshot after a Power Target reduction.

---

### Task 1: Add and adopt the AppSettings boundary adapters

**Files:**
- Modify: `app/src/main/java/com/kei/pulse/model/TierTransition.kt`
- Modify: `app/src/main/java/com/kei/pulse/model/AppSettings.kt`
- Modify: `app/src/main/java/com/kei/pulse/overlay/QuickAccessState.kt`
- Create: `app/src/test/java/com/kei/pulse/model/SideControlStateMappingTest.kt`
- Modify: `app/src/test/java/com/kei/pulse/overlay/QuickAccessStateTest.kt`

**Interfaces:**
- Produces: `AppSettings.toSideControlState(): SideControlState`
- Produces: `AppSettings.withSideControls(sideControls: SideControlState): AppSettings`
- Produces: `SideControlState()` as the neutral snapshot while retaining `SideControlState.CLEARED`

- [ ] **Step 1: Write the failing AppSettings mapping tests**

Create `SideControlStateMappingTest.kt`:

```kotlin
package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SideControlStateMappingTest {
    private val engaged = SideControlState(
        powerTargetEnabled = true,
        powerTargetPercent = 73,
        powerTargetCpuOnly = true,
        gpuLocked = true,
        gpuFloorPercent = 41,
        cpuFloorPercent = 29,
        primeCoreBoostLimited = true,
    )

    @Test
    fun `AppSettings exports every side-control field`() {
        val settings = AppSettings(
            powerTargetEnabled = true,
            powerTargetPercent = 73,
            powerTargetCpuOnly = true,
            gpuLocked = true,
            gpuFloorPercent = 41,
            cpuFloorPercent = 29,
            primeCoreBoostLimited = true,
        )

        assertEquals(engaged, settings.toSideControlState())
    }

    @Test
    fun `AppSettings replaces every side-control field and preserves unrelated settings`() {
        val before = AppSettings(accentColor = 0x12345678, activeTierLabel = "Balanced")
        val after = before.withSideControls(engaged)

        assertEquals(engaged, after.toSideControlState())
        assertEquals(before.accentColor, after.accentColor)
        assertEquals(before.activeTierLabel, after.activeTierLabel)
    }

    @Test
    fun `neutral SideControlState matches the cleared alias`() {
        assertEquals(SideControlState.CLEARED, SideControlState())
    }
}
```

- [ ] **Step 2: Run the mapping test and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests com.kei.pulse.model.SideControlStateMappingTest
```

Expected: compilation fails because `toSideControlState()`, `withSideControls()`, and the zero-argument `SideControlState()` do not exist.

- [ ] **Step 3: Implement the canonical defaults and AppSettings adapters**

Change `SideControlState` in `TierTransition.kt` to:

```kotlin
data class SideControlState(
    val powerTargetEnabled: Boolean = false,
    val powerTargetPercent: Int = 100,
    val powerTargetCpuOnly: Boolean = false,
    val gpuLocked: Boolean = false,
    val gpuFloorPercent: Int = 0,
    val cpuFloorPercent: Int = 0,
    val primeCoreBoostLimited: Boolean = false,
) {
    companion object {
        /** All side-controls at their neutral defaults (nothing governing). */
        val CLEARED = SideControlState()
    }
}
```

Append these model-boundary functions after `AppSettings` in `AppSettings.kt`:

```kotlin
fun AppSettings.toSideControlState(): SideControlState = SideControlState(
    powerTargetEnabled = powerTargetEnabled,
    powerTargetPercent = powerTargetPercent,
    powerTargetCpuOnly = powerTargetCpuOnly,
    gpuLocked = gpuLocked,
    gpuFloorPercent = gpuFloorPercent,
    cpuFloorPercent = cpuFloorPercent,
    primeCoreBoostLimited = primeCoreBoostLimited,
)

fun AppSettings.withSideControls(sideControls: SideControlState): AppSettings = copy(
    powerTargetEnabled = sideControls.powerTargetEnabled,
    powerTargetPercent = sideControls.powerTargetPercent,
    powerTargetCpuOnly = sideControls.powerTargetCpuOnly,
    gpuLocked = sideControls.gpuLocked,
    gpuFloorPercent = sideControls.gpuFloorPercent,
    cpuFloorPercent = sideControls.cpuFloorPercent,
    primeCoreBoostLimited = sideControls.primeCoreBoostLimited,
)
```

- [ ] **Step 4: Run the mapping test and verify GREEN**

Run the command from Step 2.

Expected: `SideControlStateMappingTest` passes.

- [ ] **Step 5: Strengthen the Quick Access characterization test**

Replace the Power Target test body in `QuickAccessStateTest.kt` with a whole-snapshot assertion and add the import:

```kotlin
import com.kei.pulse.model.toSideControlState
```

```kotlin
@Test
fun `set power target changes only cap enablement and percent`() {
    val before = base.copy(
        powerTargetCpuOnly = true,
        gpuLocked = true,
        gpuFloorPercent = 41,
        cpuFloorPercent = 29,
        primeCoreBoostLimited = true,
    )

    val result = QuickAccess.reduce(before, QuickAccessAction.SetPowerTarget(85))

    assertEquals(
        before.toSideControlState().copy(powerTargetEnabled = true, powerTargetPercent = 85),
        result.toSideControlState(),
    )
}
```

- [ ] **Step 6: Run the Quick Access characterization test**

Run:

```bash
./gradlew testDebugUnitTest --tests com.kei.pulse.overlay.QuickAccessStateTest
```

Expected: PASS before the internal refactor, proving current behavior is pinned.

- [ ] **Step 7: Refactor Quick Access to use the boundary adapters**

Add imports in `QuickAccessState.kt`:

```kotlin
import com.kei.pulse.model.toSideControlState
import com.kei.pulse.model.withSideControls
```

Replace the `SetPowerTarget` branch with:

```kotlin
is QuickAccessAction.SetPowerTarget -> {
    val pct = action.percent.coerceIn(POWER_TARGET_MIN, POWER_TARGET_MAX)
    settings.withSideControls(
        settings.toSideControlState().copy(
            powerTargetPercent = pct,
            powerTargetEnabled = pct < POWER_TARGET_MAX,
        ),
    )
}
```

- [ ] **Step 8: Run focused tests and commit**

Run:

```bash
./gradlew testDebugUnitTest --tests com.kei.pulse.model.SideControlStateMappingTest --tests com.kei.pulse.overlay.QuickAccessStateTest
```

Expected: both test classes pass.

Commit:

```bash
git add app/src/main/java/com/kei/pulse/model/TierTransition.kt app/src/main/java/com/kei/pulse/model/AppSettings.kt app/src/main/java/com/kei/pulse/overlay/QuickAccessState.kt app/src/test/java/com/kei/pulse/model/SideControlStateMappingTest.kt app/src/test/java/com/kei/pulse/overlay/QuickAccessStateTest.kt
git commit -m "refactor: add canonical side-control adapters"
```

---

### Task 2: Compose the Custom tuning snapshot

**Files:**
- Modify: `app/src/main/java/com/kei/pulse/model/CustomTuning.kt`
- Modify: `app/src/main/java/com/kei/pulse/model/TierTransition.kt`
- Modify: `app/src/main/java/com/kei/pulse/data/SettingsStorage.kt`
- Modify: `app/src/main/java/com/kei/pulse/ui/TunerViewModel.kt`
- Modify: `app/src/main/java/com/kei/pulse/appwatch/ForegroundAppMonitorService.kt`
- Modify: `app/src/test/java/com/kei/pulse/model/TierTransitionTest.kt`

**Interfaces:**
- Consumes: `SideControlState` and `AppSettings.toSideControlState()` from Task 1
- Produces: `CustomTuning(sideControls: SideControlState = SideControlState(), governorLabel: String? = null)`
- Produces: `TierTransition.afterCustomRestore(saved)` as a direct return of `saved.sideControls`

- [ ] **Step 1: Rewrite the Custom restoration test for composition**

Replace `custom restore copies every saved knob` in `TierTransitionTest.kt` with:

```kotlin
@Test
fun `custom restore returns the complete saved side-control snapshot`() {
    val saved = CustomTuning(sideControls = engaged, governorLabel = "Performance")

    assertEquals(engaged, TierTransition.afterCustomRestore(saved))
    assertEquals("Performance", saved.governorLabel)
}
```

Keep `custom restore of default tuning yields the cleared state`; it will verify the composed default.

- [ ] **Step 2: Run the transition test and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests com.kei.pulse.model.TierTransitionTest
```

Expected: compilation fails because `CustomTuning` has no `sideControls` parameter.

- [ ] **Step 3: Replace duplicated CustomTuning fields with composition**

Replace the data class in `CustomTuning.kt` with:

```kotlin
data class CustomTuning(
    val sideControls: SideControlState = SideControlState(),
    /** GovernorController.OPTIONS label the user last chose while in Custom; null = leave alone. */
    val governorLabel: String? = null,
)
```

Replace `TierTransition.afterCustomRestore()` with:

```kotlin
fun afterCustomRestore(saved: CustomTuning): SideControlState = saved.sideControls
```

- [ ] **Step 4: Update Custom Preferences serialization without changing keys**

In `SettingsStorage.kt`, add the `SideControlState` import. Construct `CustomTuning` as:

```kotlin
CustomTuning(
    sideControls = SideControlState(
        powerTargetEnabled = preferences[customPtEnabledKey] ?: false,
        powerTargetPercent = preferences[customPtPercentKey] ?: 100,
        powerTargetCpuOnly = preferences[customPtCpuOnlyKey] ?: false,
        gpuLocked = preferences[customGpuLockedKey] ?: false,
        gpuFloorPercent = preferences[customGpuFloorKey] ?: 0,
        cpuFloorPercent = preferences[customCpuFloorKey] ?: 0,
        primeCoreBoostLimited = preferences[customPrimeBoostKey] ?: false,
    ),
    governorLabel = preferences[customGovernorLabelKey],
)
```

Start `persistCustomTuning()` with `val sideControls = tuning.sideControls`, then keep the same seven assignments using `sideControls.<field>`. Leave the governor key write/remove block unchanged.

- [ ] **Step 5: Update ViewModel Custom snapshot consumers**

In `persistTuning()`, replace the scalar `CustomTuning` construction with:

```kotlin
CustomTuning(
    sideControls = currentSideControls(),
    governorLabel = GovernorController.optionForGovernor(_governor.value)?.label,
)
```

Change `reapplyCustomSideControls` to accept `sideControls: SideControlState` and replace each `tuning.<field>` access with `sideControls.<field>`. At restoration, use:

```kotlin
val sideControls = tuning.sideControls
applySideControls(TierTransition.afterCustomRestore(tuning))
if (sideControls.powerTargetEnabled) applyPowerTargetValues(sideControls.powerTargetPercent)
reapplyCustomSideControls(sideControls)
```

Do not change the following governor block or any device-controller call ordering.

- [ ] **Step 6: Update the Quick Access Custom snapshot copy**

In `ForegroundAppMonitorService.applyQaPowerTarget()`, replace the scalar `CustomTuning.copy()` call with:

```kotlin
val customTuning = store.customTuning.first()
store.persistCustomTuning(
    customTuning.copy(
        sideControls = customTuning.sideControls.copy(
            powerTargetEnabled = enabled,
            powerTargetPercent = percent,
        ),
    ),
)
```

- [ ] **Step 7: Run focused and full unit tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.kei.pulse.model.TierTransitionTest --tests com.kei.pulse.model.SideControlStateMappingTest
./gradlew testDebugUnitTest
```

Expected: focused tests pass, followed by a successful full unit-test build.

- [ ] **Step 8: Commit the composed Custom snapshot**

```bash
git add app/src/main/java/com/kei/pulse/model/CustomTuning.kt app/src/main/java/com/kei/pulse/model/TierTransition.kt app/src/main/java/com/kei/pulse/data/SettingsStorage.kt app/src/main/java/com/kei/pulse/ui/TunerViewModel.kt app/src/main/java/com/kei/pulse/appwatch/ForegroundAppMonitorService.kt app/src/test/java/com/kei/pulse/model/TierTransitionTest.kt
git commit -m "refactor: compose custom side-control state"
```

---

### Task 3: Consolidate live tuning persistence

**Files:**
- Modify: `app/src/main/java/com/kei/pulse/data/SettingsStorage.kt`
- Modify: `app/src/main/java/com/kei/pulse/ui/TunerViewModel.kt`
- Modify: `app/src/main/java/com/kei/pulse/appwatch/ForegroundAppMonitorService.kt`
- Create: `app/src/test/java/com/kei/pulse/data/SettingsStorageApiTest.kt`

**Interfaces:**
- Consumes: `SideControlState` and `AppSettings.toSideControlState()` from Task 1
- Produces: `suspend fun SettingsStorage.persistTuningState(sideControls: SideControlState, activeTierLabel: String)`

- [ ] **Step 1: Add a compile-time API test for the canonical signature**

Create `SettingsStorageApiTest.kt`:

```kotlin
package com.kei.pulse.data

import com.kei.pulse.model.SideControlState
import org.junit.Assert.assertNotNull
import org.junit.Test

class SettingsStorageApiTest {
    @Test
    fun `tuning persistence accepts one canonical snapshot plus tier`() {
        val persist: suspend SettingsStorage.(SideControlState, String) -> Unit =
            SettingsStorage::persistTuningState

        assertNotNull(persist)
    }
}
```

- [ ] **Step 2: Run the API test and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests com.kei.pulse.data.SettingsStorageApiTest
```

Expected: test compilation fails because the current eight-scalar method does not match the declared function type.

- [ ] **Step 3: Replace the scalar persistence API**

Replace `persistTuningState()` in `SettingsStorage.kt` with:

```kotlin
suspend fun persistTuningState(
    sideControls: SideControlState,
    activeTierLabel: String,
) {
    withContext(NonCancellable) { context.settingsDataStore.edit { preferences ->
        preferences[powerTargetEnabledKey] = sideControls.powerTargetEnabled
        preferences[powerTargetPercentKey] = sideControls.powerTargetPercent
        preferences[powerTargetCpuOnlyKey] = sideControls.powerTargetCpuOnly
        preferences[gpuLockedKey] = sideControls.gpuLocked
        preferences[gpuFloorPercentKey] = sideControls.gpuFloorPercent
        preferences[cpuFloorPercentKey] = sideControls.cpuFloorPercent
        preferences[activeTierKey] = activeTierLabel
        preferences[primeCoreBoostKey] = sideControls.primeCoreBoostLimited
    } }
}
```

- [ ] **Step 4: Update the ViewModel call site**

At the start of the `persistTuning()` coroutine, take one snapshot and reuse it for both writes:

```kotlin
val sideControls = currentSideControls()
settingsStorage.persistTuningState(
    sideControls = sideControls,
    activeTierLabel = _activeTier.value.label,
)
```

Use that same `sideControls` in `CustomTuning(sideControls = sideControls, ...)`. This ensures the live and Custom writes cannot observe seven flows at different moments.

- [ ] **Step 5: Update the Quick Access call site**

Add the `toSideControlState` import in `ForegroundAppMonitorService.kt`. Replace the scalar call with:

```kotlin
val sideControls = s.toSideControlState().copy(
    powerTargetEnabled = enabled,
    powerTargetPercent = percent,
)
store.persistTuningState(
    sideControls = sideControls,
    activeTierLabel = PowerTier.CUSTOM.label,
)
```

Update only the edited fields on the independently saved Custom snapshot:

```kotlin
val customTuning = store.customTuning.first()
store.persistCustomTuning(
    customTuning.copy(
        sideControls = customTuning.sideControls.copy(
            powerTargetEnabled = enabled,
            powerTargetPercent = percent,
        ),
    ),
)
```

This intentionally matches the existing behavior: the live snapshot starts from current `AppSettings`, while the saved Custom snapshot starts from `customTuning.sideControls`. A preset may have cleared live GPU/floor/boost controls, so replacing the saved Custom snapshot with the live one would silently erase values that must return when Custom is restored. The governor label is retained by `copy()`.

- [ ] **Step 6: Run the API test and full unit tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.kei.pulse.data.SettingsStorageApiTest
./gradlew testDebugUnitTest
```

Expected: the API test passes and the full unit-test build succeeds.

- [ ] **Step 7: Confirm duplicated scalar calls are gone**

Run:

```bash
rg -n "persistTuningState\(" app/src/main/java app/src/test
```

Expected: one declaration and two call sites, each passing `sideControls` and `activeTierLabel`; no seven-field scalar call remains.

- [ ] **Step 8: Commit live persistence consolidation**

```bash
git add app/src/main/java/com/kei/pulse/data/SettingsStorage.kt app/src/main/java/com/kei/pulse/ui/TunerViewModel.kt app/src/main/java/com/kei/pulse/appwatch/ForegroundAppMonitorService.kt app/src/test/java/com/kei/pulse/data/SettingsStorageApiTest.kt
git commit -m "refactor: persist canonical side-control state"
```

---

### Task 4: Verify scope and behavior end to end

**Files:**
- Verify only; no source changes expected.

**Interfaces:**
- Consumes: all interfaces produced by Tasks 1–3
- Produces: evidence that unit tests and the debug build pass and that broader follow-up work stayed out of scope

- [ ] **Step 1: Run the complete verification command**

```bash
./gradlew testDebugUnitTest assembleDebug
```

Expected: `BUILD SUCCESSFUL` with no failed tests or compilation errors. Existing Gradle/Android deprecation warnings are baseline warnings and do not fail this task.

- [ ] **Step 2: Audit the seven-field duplication boundary**

```bash
rg -n "powerTargetEnabled|powerTargetPercent|powerTargetCpuOnly|gpuLocked|gpuFloorPercent|cpuFloorPercent|primeCoreBoostLimited" app/src/main/java/com/kei/pulse/model/CustomTuning.kt app/src/main/java/com/kei/pulse/data/SettingsStorage.kt app/src/main/java/com/kei/pulse/ui/TunerViewModel.kt app/src/main/java/com/kei/pulse/appwatch/ForegroundAppMonitorService.kt
```

Expected:

- `CustomTuning.kt` contains only `sideControls`, not seven properties.
- `SettingsStorage.kt` expands fields only at the two intentional Preferences serialization boundaries.
- `TunerViewModel.kt` expands fields only in `currentSideControls()`, `applySideControls()`, and device-control reads.
- `ForegroundAppMonitorService.kt` updates one copied `SideControlState`; it has no scalar `persistTuningState()` call.

- [ ] **Step 3: Audit compatibility constraints**

```bash
git diff 3971244 -- app/src/main/java/com/kei/pulse/data/SettingsStorage.kt app/src/main/java/com/kei/pulse/ui/TunerViewModel.kt app/src/main/java/com/kei/pulse/appwatch/ForegroundAppMonitorService.kt
```

Expected: no Preferences key names change; governor blocks and device-controller call ordering are unchanged; changes are limited to snapshot construction, access, and persistence parameters.

- [ ] **Step 4: Confirm a clean worktree and review the commit series**

```bash
git status --short
git log --oneline 3971244..HEAD
```

Expected: clean status and three focused refactor commits corresponding to Tasks 1–3.
