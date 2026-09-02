# Change Impact Map

"I'm about to edit X — what else does that touch?" Look up the file, read the blast radius, check
the listed tests.

## Hub classes — highest blast radius

These four have three or more dependents each. Changing their *behavior* (not just internals)
ripples across the app.

### `location/proximity/ProximityRepository.kt`

**Dependents:** `GeofenceBroadcastReceiver` (writes), `LocationFeatureCoordinator` (writes via
`ProximityUpdater`), `LocationViewModel` (reads `proximity`), `SharedPrefsProximityStateStore`
(persistence), and any future `events` subscriber.

**Check before merging:**
- Constructor still seeds from `store` — never hardcode `UNKNOWN`, or geofence-driven cold starts
  swallow `Departed`.
- `setState`/`onLocation`/`reset` still `@Synchronized` — two threads write here.
- Dedupe still short-circuits identical states, or both producers double-emit.
- The single-active-target assumption still holds (see
  [../features/proximity-and-geofencing.md](../features/proximity-and-geofencing.md#the-single-active-target-assumption-important)).

**Tests:** `ProximityRepositoryTest` (incl. 2 concurrency tests), `ProximityPersistenceE2ETest`,
`GeofenceBroadcastReceiverTest`, `LocationFeatureCoordinatorTest`.

---

### `location/tracking/LocationStateRepository.kt`

**Dependents:** `LocationTrackingService` (writes fixes + status), `LocationTrackingController`
(writes status), `LocationViewModel` (writes fixes in degraded mode, reads `trackingStatus`),
`LocationFeatureCoordinator` (reads `latestLocation`).

**Check:** adding a `TrackingStatus` value means auditing every `when` over it and
`LocationUiState`. Confirm the controller/service split still holds — only the service sets
`BACKGROUND_ACTIVE`.

**Tests:** `LocationTrackingControllerTest`, `LocationViewModelTest`, `LocationFeatureCoordinatorTest`.

---

### `location/permission/LocationPermission.kt`

**Dependents:** `SystemLocationPermissionRepository`, `LocationTrackingController.sync`,
`LocationFeatureCoordinator.reconcileTracking`, `LocationTrackingService.startTracking`,
`LocationPermissionViewModel.computePrompt`, `LocationPermissionHost`, `LocationUiState`,
`LocationSetupChip`, `LocationViewModel`'s degraded gate.

**Check:** consumers must branch on `isGranted` / `isDegraded` / `supportsBackgroundTracking`, not
enum equality. Any new SDK gate belongs in `LocationPermissions`, nowhere else. Adding a permission
means editing `AndroidManifest.xml` too.

**Tests:** `LocationAccessLevelTest`, `LocationPermissionViewModelTest`,
`SystemLocationPermissionRepositoryTest`, `LocationTrackingControllerTest`.

---

### `location/registration/WorkLocationRepository.kt`

**Dependents:** `LocationFeatureCoordinator` (both pipelines), `LocationViewModel`,
`DefaultAppContainer`.

**Check:** `activeWorkLocation` changes re-register geofences and re-target proximity; setting it to
`null` fires `ProximityRepository.reset()` and thus a `Departed` if inside. Multiple simultaneous
active locations require a `ProximityRepository` change in the same PR.

**Tests:** `StubWorkLocationRepositoryTest`, `LocationFeatureCoordinatorTest`, `LocationViewModelTest`.

---

## Per-file quick reference

| If you change… | Also update / verify | Tests to run |
| --- | --- | --- |
| `di/AppContainer.kt` | `DefaultAppContainer`, every consuming ViewModel `Factory`, `LocationTrackingService`/`GeofenceBroadcastReceiver` container lookups | full JVM suite |
| `EmployeeAttendanceApplication.kt` | coordinator start; anything relying on app-scoped collection | `LocationFeatureCoordinatorTest`, `AppStartupTest` |
| `startup/AppStartup.kt` | *when* every app-lifetime pipeline starts; the app-scoped `CoroutineExceptionHandler` | `AppStartupTest` |
| `startup/ForegroundGate.kt` | whether foreground-service starts are legal at all — see overview §8 | `ProcessLifecycleForegroundGateTest` (androidTest) |
| `MainActivity.kt` | app bar title `LaunchedEffect` per destination; ViewModel sharing (Activity-scoped on purpose) | `LocationNavigationTest`, `AttendanceScreenTest` |
| `location/LocationFeatureCoordinator.kt` | both pipelines' invariants: `collectLatest`, `CancellationException` rethrow, geofence gating | `LocationFeatureCoordinatorTest` |
| `location/permission/LocationPermissionRepository.kt` | the three `refresh()` call sites (host `ON_RESUME`, launchers, service) | `SystemLocationPermissionRepositoryTest` |
| `location/tracking/LocationTracker.kt` | `.conflate()`, `awaitClose` removal, `toSample()` accuracy fallback | `LocationTrackingServiceTest` |
| `location/tracking/LocationTrackingService.kt` | permission pre-check, idempotent `startForeground`, the `startForegroundService()` obligation discharged on every stand-down path, `Dispatchers.Main.immediate` scope, manifest FGS type | `LocationTrackingServiceTest` |
| `location/tracking/TrackingServiceLauncher.kt` | `start()` returns whether the platform accepted the start; a refusal degrades to `FOREGROUND_ONLY` rather than throwing | `LocationTrackingControllerTest` |
| `location/tracking/LocationTrackingController.kt` | the ALWAYS/WHEN_IN_USE/NONE policy is duplicated nowhere else — keep it that way | `LocationTrackingControllerTest` |
| `location/tracking/LocationPowerPolicy.kt` | the proximity→cadence feedback loop; battery claims in docs | `LocationPowerPolicyTest` |
| `location/tracking/LocationRequestConfig.kt` | `init` invariants; both presets | `LocationRequestConfigTest`, `LocationPriorityTest` |
| `location/geofence/GeofenceManager.kt` | `FLAG_MUTABLE` on API 31+, `register` calling `clear()` first, responsiveness window | `LocationFeatureCoordinatorTest` (via seam) |
| `location/geofence/GeofenceBroadcastReceiver.kt` | keep work minimal; manifest `<receiver>`; `ACTION_GEOFENCE_EVENT` must match `GeofenceManager`'s intent | `GeofenceBroadcastReceiverTest` |
| `location/proximity/ProximityCalculator.kt` | hysteresis semantics; foreground path only | `ProximityCalculatorTest` |
| `location/proximity/ProximityStateStore.kt` | prefs file/key names — changing them silently resets users' state | `SharedPrefsProximityStateStoreTest`, `ProximityPersistenceE2ETest` |
| `location/registration/WorkLocation.kt` | `init` validation, `toGeofenceTarget()`; call sites project outside try/catch | `WorkLocationTest` |
| `location/ui/LocationViewModel.kt` | the 5-way `combine`, `LocationUiState` derived flags, the degraded-only collector gate | `LocationViewModelTest` |
| `location/ui/LocationPermissionViewModel.kt` | `computePrompt`, `SavedStateHandle` keys (changing them drops persisted dismissals) | `LocationPermissionViewModelTest` |
| `location/ui/LocationPermissionHost.kt` | `ON_RESUME` `DisposableEffect`, dismiss-before-launch ordering, both launchers | `LocationPermissionDialogTest` |
| `ui/attendance/AttendanceScreen.kt` | the single-control rule (pill XOR chip); `LocationPermissionHost` placement | `AttendanceScreenTest` |
| `res/values/strings.xml` | referencing composables; keep `location_*` naming | Compose UI tests match on text |
| `AndroidManifest.xml` | matching runtime request in `LocationPermissions`; FGS type; `<service>`/`<receiver>` entries | instrumented suite |
| `gradle/libs.versions.toml` | never inline versions in `build.gradle.kts` | full build |

## Cross-cutting invariants

Break one of these and something fails at runtime rather than at compile time. Each has a guard test
where one is possible.

1. **One active geofence target at a time** — `ProximityRepository`'s global state depends on it.
2. **Exactly one location producer at a time** — the service (`ALWAYS`) or the ViewModel collector
   (`WHEN_IN_USE`), never both. The gate is `permission.isDegraded` in
   `collectForegroundFixesWhenDegraded`.
3. **Proximity state must be persisted** — process death during geofencing is a normal path.
4. **Permission is re-read, never cached** — Android doesn't push changes.
5. **The service re-checks permission before `startForeground`** — Android 14+ FGS enforcement.
6. **ViewModels never touch `Activity`/`Context`** — `LocationPermissionHost` is the only place that
   may.
7. **Location ViewModels are Activity-scoped, shared across destinations** — created in
   `MainActivity`, passed down.
8. **`register()` and `clear()` never interleave** — guaranteed by `collectLatest` in the
   coordinator.
9. **Domain models validate at construction** — some call sites project outside try/catch.
10. **Every `stateIn` uses `WhileSubscribed(5_000)`** — keeps upstreams warm across config changes
    without leaking.

## Reverse index — "who reads this state?"

| State | Producers | Consumers |
| --- | --- | --- |
| `LocationPermissionRepository.permissionState` | `refresh()` (3 call sites) | coordinator, both ViewModels, service |
| `WorkLocationRepository.activeWorkLocation` | stub mutators (future registration flow) | coordinator ×2, `LocationViewModel` |
| `LocationStateRepository.latestLocation` | service, `LocationViewModel` collector | coordinator pipeline 2 |
| `LocationStateRepository.trackingStatus` | controller, service | `LocationViewModel.uiState` |
| `ProximityRepository.proximity` | geofence receiver, coordinator pipeline 2 | `LocationViewModel.uiState`, `LocationPowerPolicy` (via the VM) |
| `ProximityRepository.events` | `setState`, `reset` | **nobody yet** — the auto-clock-in seam |
| `LocationClockInRepository.lastClockIns` | `LocationViewModel.onClockIn()` | `LocationViewModel.uiState` → detail screen |
