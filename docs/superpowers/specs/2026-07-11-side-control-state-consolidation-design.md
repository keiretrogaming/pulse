# Side-Control State Consolidation

## Summary

Consolidate the seven simple side-control fields into the existing `SideControlState` value object wherever they currently travel together. This is a representation and plumbing refactor only. It prevents persistence and restoration omissions by replacing repeated scalar argument lists and hand-written field copies with one typed value.

The seven fields are:

- `powerTargetEnabled`
- `powerTargetPercent`
- `powerTargetCpuOnly`
- `gpuLocked`
- `gpuFloorPercent`
- `cpuFloorPercent`
- `primeCoreBoostLimited`

## Goals

- Make `SideControlState` the canonical transfer type for these seven values.
- Remove duplicated seven-field construction and parameter lists from `CustomTuning`, `persistTuningState()`, the ViewModel, and Quick Access.
- Reduce future side-control additions to a few explicit, round-trip-tested storage/model boundaries instead of repeated hand-written plumbing across consumers.
- Preserve all existing user-visible and device-control behavior.

## Compatibility Constraints

This refactor must leave the following unchanged:

- Existing Preferences DataStore keys and stored values. No data migration is required.
- Governor selection, persistence, controller calls, and restoration behavior.
- Device writes and their ordering.
- Power-tier transition semantics.
- Quick Access behavior, including its AutoTDP ownership guard and Custom-tier persistence behavior.
- The flattened public shape of `AppSettings` outside the new boundary adapters.

## Design

### Canonical value object

`SideControlState` remains the canonical seven-field value object. Its fields receive the current neutral defaults so `SideControlState()` represents the cleared state; `CLEARED` may remain as a readable alias if useful at call sites.

`TierTransition` continues to accept and return `SideControlState`. Preset clearing and individual interlink rules remain unchanged.

### Custom tuning snapshot

Replace the seven duplicated properties in `CustomTuning` with:

```kotlin
data class CustomTuning(
    val sideControls: SideControlState = SideControlState(),
    val governorLabel: String? = null,
)
```

The governor stays separate because it is restored through `GovernorController` rather than applied as a simple side-control flag.

### AppSettings boundary

Keep the seven persisted properties flattened in `AppSettings`. Add two explicit model-boundary helpers:

- `AppSettings.toSideControlState()` returns the canonical snapshot.
- `AppSettings.withSideControls(state)` returns a copy with all seven flattened properties replaced from the snapshot.

This is the one intentional mapping boundary between the broad settings model and the focused value object. Both directions receive round-trip tests with distinct values.

### Persistence boundary

Change live tuning persistence from eight scalar arguments to:

```kotlin
suspend fun persistTuningState(
    sideControls: SideControlState,
    activeTierLabel: String,
)
```

`SettingsStorage` continues to write the same seven Preferences keys. Custom tuning reads and writes `CustomTuning.sideControls` against the existing `custom_*` keys. Nullable governor-key handling remains unchanged.

The explicit key assignments are retained inside `SettingsStorage`; they are the necessary serialization boundary, not duplicated business-state plumbing.

### ViewModel

The ViewModel keeps its existing individual `StateFlow` properties so the UI API and recomposition behavior remain stable.

- `currentSideControls()` remains the single flow-to-value-object adapter.
- `applySideControls()` remains the single value-object-to-flow adapter.
- `persistTuning()` passes `currentSideControls()` directly to both live tuning persistence and the Custom snapshot.
- Custom restoration passes `CustomTuning.sideControls` directly through `TierTransition` and `applySideControls()`.

No device-controller calls or coroutine ordering change.

### Quick Access

Quick Access continues to receive and reduce `AppSettings`. Any side-control edit uses `toSideControlState()`, copies the changed field on that value, and uses `withSideControls()` when an updated `AppSettings` is needed.

The foreground service passes the complete canonical value to `persistTuningState()` and updates the Custom snapshot with the same value. Its existing clock-write, AutoTDP guard, bound-cap recapture, and logging behavior remain unchanged.

## Testing

Implementation follows test-driven development. Tests should first fail against the duplicated API, then pass after consolidation.

Required coverage:

- `AppSettings.toSideControlState()` copies all seven distinct non-default values.
- `AppSettings.withSideControls()` replaces all seven values while preserving unrelated settings.
- Converting `AppSettings` to side controls and back round-trips all seven values.
- `CustomTuning` defaults to cleared side controls and retains its governor label independently.
- Preset clearing and Custom restoration retain the existing `TierTransition` behavior.
- Quick Access Power Target reduction changes only the intended side-control values.
- Existing model and Quick Access tests remain green.

Where practical, assertions compare the whole `SideControlState` rather than seven independent assertions. This makes an added field participate in equality and exposes incomplete mappings.

## Follow-up PR Candidates

The following are worthwhile improvements but are intentionally outside this PR:

1. **Compose side controls into `AppSettings`.** Replace its seven flattened fields with a nested `SideControlState` throughout the application. This would remove the final model adapter but has broad call-site, `copy()`, UI, and service impact and should be reviewed independently.
2. **Review governor state as part of a broader tuning snapshot.** Explore whether governor persistence and restoration should share a higher-level Custom tuning transaction with the simple side controls. This touches controller-specific behavior and ordering, so it should not be bundled with a structural omission-prevention refactor.
3. **Review runtime tuning transactions.** Consider whether device writes, live state persistence, and Custom snapshot persistence should be represented as one explicit operation. That could improve atomicity but changes failure and concurrency semantics and requires separate behavioral design and testing.

## Non-goals

- Changing settings defaults or validation.
- Renaming Preferences keys or migrating stored data.
- Changing UI controls or their public ViewModel flows.
- Changing governor or device-controller behavior.
- Changing Quick Access scope, AutoTDP, or per-app routing.
- Addressing unrelated settings duplication.
