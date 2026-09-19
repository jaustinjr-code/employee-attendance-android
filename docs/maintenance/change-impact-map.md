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
| `di/AppContainer.kt` `DefaultAppContainer(context, appForegroundTracker)` | the constructor takes the tracker; every construction site passes one (`EmployeeAttendanceApplication.onCreate`, `StartupThreadPolicyTest`). Keep the tracker out of `by lazy` | `StartupThreadPolicyTest` (androidTest) |
| `di/AppContainer.kt` `clockOutListener` | the only `ClockOutSource` to `StatusUpdateTrigger` mapping; `AttendanceAutoClockController` and `ClockActionReceiver` must both use this instance | `ClockActionHandlerTest`, `AttendanceAutoClockControllerTest` |
| `EmployeeAttendanceApplication.kt` | tracker built before the container; `startupJob` order (auto-clock subscribed before the location coordinator); every store a factory can reach is forced; `startupComplete` flips on any terminal state; `biweeklyReportController.reconcile()` is the first WorkManager call and stays on IO | `StartupThreadPolicyTest`, `StartupGateTest` (androidTest) |
| `ui/main/StartupGate.kt` | content must not be composed at all while `started` is false, or factories run early | `StartupGateTest` (androidTest) |
| `MainActivity.kt` | up must stay routed through `ui/main/UpNavigation.kt`'s `performUp(upInterceptor, navController)`, not an unconditional direct pop, or up skips the status update editor's discard confirmation; app bar title `LaunchedEffect` per destination; ViewModel sharing (Activity-scoped on purpose); the bottom bar shows on every destination and its tab taps route through the editor's up interceptor (`selectTab` pops to the tab root when already inside that tab); the dev title-tap is gated on `AppNavGraph.root`, not on "no up button" (Reports has none either); the Reports deep link navigates from inside `composable<Attendance>` because the graph is not set before then; `StatusUpdateOverlayHost` mounted once as a sibling of the `Scaffold`; `consumeRequest` only when `savedInstanceState == null` and in `onNewIntent`; manifest `launchMode="singleTop"` | `LocationNavigationTest`, `AttendanceScreenTest`, `BottomBarNavigationTest`, `ReportsDeepLinkTest`, `StatusUpdateNotificationLaunchTest`, `StatusUpdateEditFlowTest` |
| `ui/main/UpNavigation.kt` | the single `performUp` rule shared by `MainActivity` and `StatusUpdateEditFlowTest` — a test that reimplements this instead of calling it will not catch a regression in the real wiring; the `interceptor.entryId == currentBackStackEntry?.id` check must stay — it is what makes it safe for a screen to register its interceptor as early as first composition (see the `StatusUpdateEditScreen.kt` row) instead of needing to gate registration on a lifecycle state; the write side (`updateInterceptor`) needs the same id discipline as the read side — a raw `upInterceptor = it` assignment lets a departing screen's delayed `onDispose(null)` (still in flight from its own exit transition) silently and permanently wipe a newer screen's registration if the two windows overlap (reopen while the previous instance is still exiting); only the current owner's id may clear the slot, and a fresh registration always wins regardless of whose id it carries | `UpNavigationTest` (JVM), `StatusUpdateEditFlowTest` (androidTest) |
| `startup/AppStartup.kt` | *when* every app-lifetime pipeline starts; the app-scoped `CoroutineExceptionHandler` | `AppStartupTest` |
| `startup/ForegroundGate.kt` | whether foreground-service starts are legal at all — see overview §8 | `ProcessLifecycleForegroundGateTest` (androidTest) |
| `attendance/AttendanceRepository.kt` `eventLog` | every mutation must publish a **new** list through `publish()` and never mutate an emitted one — `ReportGenerator`'s cache is keyed on list identity and would serve stale reports otherwise | `DefaultAttendanceRepositoryTest`, `ReportGeneratorTest` |
| `reporting/ReportPeriod.kt` | `BIWEEKLY_ANCHOR` must stay a Sunday and must never move: moving it re-pairs every fortnight and `lastNotifiedPeriodStart` stops matching, so a period can be notified twice or skipped | `ReportPeriodTest`, `BiweeklyReportTest` |
| `reporting/AttendanceReport.kt` `ReportCalculator` | the clipping and midnight-split rules; open shifts are never counted | `ReportCalculatorTest`, `SessionLogTest` |
| `reporting/ReportSharer.kt` | `PROVIDER_SUFFIX` must match `android:authorities` of `ReportFileProvider` in the main manifest, whose `FILE_PROVIDER_PATHS` meta-data must stay (the static `getUriForFile` reads it); `ClipData` carries the read grant | `FileReportSharerTest` |
| `reporting/BiweeklyReport*.kt` | the daily check with `KEEP`; WorkManager's startup initializer stays removed and its first call stays in the startup job, off the main thread | `BiweeklyReportTest`, `BiweeklyReportPlatformTest`, `StartupThreadPolicyTest` |
| `ui/reports/ReportsViewModel.kt` | per-section flows, `mapLatest` on `Dispatchers.Default`, no work before the first subscriber | `ReportsViewModelTest` |
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
| `location/ui/LocationViewModel.kt` | the `combine` feeding `LocationUiState`, derived flags, the degraded-only collector gate; `onClockOut` must use `recordIfStateChanges` and call `StatusUpdateCoordinator.onClockOut(..., MANUAL)` only for a recorded event | `LocationViewModelTest` |
| `location/ui/LocationPermissionViewModel.kt` | `computePrompt`, `SavedStateHandle` keys (changing them drops persisted dismissals) | `LocationPermissionViewModelTest` |
| `location/ui/LocationPermissionHost.kt` | `ON_RESUME` `DisposableEffect`, dismiss-before-launch ordering, both launchers | `LocationPermissionDialogTest` |
| `ui/attendance/AttendanceScreen.kt` | the single-control rule (pill XOR chip); `LocationPermissionHost` placement | `AttendanceScreenTest` |
| `location/ui/SettingsViewModel.kt` | no longer reads `UserProfileStore` (the name moved to Account); `onDeleteAllData` must keep calling `statusUpdateRepository.clearAll()`, which also empties history; the factory reads `statusUpdateSettingsStore` (forced in `startupJob`) | `SettingsDeleteAllDataTest`, `SettingsStatusUpdateToggleTest` (androidTest) |
| `attendance/AttendanceRepository.kt` `hasClockOutEvent` | abstract with no default body: every implementation overrides it, including `DefaultAttendanceRepository`, `RecordingAttendanceRepository`, `devtools` `FakeAttendanceRepository`, and the private fakes in `SettingsDeleteAllDataTest` and `SettingsStatusUpdateToggleTest`. It gates the notification launch path, so a fake returning `false` blocks the deck | `StatusUpdateCoordinatorTest`, `StatusUpdateNotificationLaunchTest` |
| `attendance/AttendanceRepository.kt` `clockInBefore` | default `null` (display-only); `DefaultAttendanceRepository` and `RecordingAttendanceRepository` answer it. A fake that leaves the default saves updates with no shift start ("Clocked out …") | `DefaultAttendanceRepositoryTest`, `StatusUpdateCoordinatorTest` |
| `attendance/ClockNotificationStrategy.kt` `ClockOutListener`, `ClockOutSource` | report only after an event is recorded, only for `CLOCK_OUT`; `attendance/` must not import `statusupdate`. A new `ClockOutSource` value needs a branch in `AppContainer.clockOutListener` | `ClockNotificationStrategyTest`, `AttendanceAutoClockControllerTest` |
| `attendance/ClockActionReceiver.kt` | `confirm` reports `NOTIFICATION_CONFIRMED` with the recorded event's time; `undo` calls `onClockOutUndone` only when `undoEvent` returned true for a clock-out | `ClockActionHandlerTest` |
| `settings/StatusUpdateSettingsStore.kt` | default `true`; `PREFS_NAME` `status_update_settings` needs backup exclusions; forced in `startupJob` | `StatusUpdateSettingsStoreTest` (androidTest), `BackupRulesTest` |
| `statusupdate/StatusUpdateCoordinator.kt` | the surface table in [status-updates.md](../features/status-updates.md#choosing-the-surface); `claimNotificationRequest` is the security check for exported-Activity extras; no notifier call inside `synchronized` | `StatusUpdateCoordinatorTest` |
| `statusupdate/AppForegroundTracker.kt` | must be registered before the first `onStart`; count clamped at 0 | `AppForegroundTrackerTest` (androidTest) |
| `statusupdate/StatusUpdateIntents.kt` | extra keys shared by `StatusUpdateNotifier` and `MainActivity`; strip after read; ignore `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` | `StatusUpdateIntentsTest` (androidTest) |
| `statusupdate/StatusUpdateNotifier.kt` | channel id `status_update`; notification id derived from `clockOutId` so `cancel` finds it; `FLAG_IMMUTABLE` | `StatusUpdateNotifierTest` (androidTest) |
| `statusupdate/StatusUpdateRepository.kt` | persisted through `StatusUpdateLocalDataSource`; `PREFS_NAME` `status_updates` needs backup exclusions and must stay forced in `startupJob`; `clearAll` backs "Delete all data"; `statusUpdates` is read by `claimNotificationRequest` and every history ViewModel | `DefaultStatusUpdateRepositoryTest`, `SharedPrefsStatusUpdateLocalDataSourceTest`, `SettingsDeleteAllDataTest`, `BackupRulesTest` |
| `statusupdate/StatusUpdateModels.kt` | `clockOutId` format `locationId@clockOutAtMillis` keys notifications, claims, undo, and the detail/edit route argument; `StatusUpdate` is persisted by name, so new fields need defaults and existing ones must not be renamed | `StatusUpdateCoordinatorTest`, `SharedPrefsStatusUpdateLocalDataSourceTest` |
| `statusupdate/history/StatusUpdateHistory.kt` | blank updates dropped; days by clock-out in the device time zone; answered questions only | `StatusUpdateHistoryTest` |
| `statusupdate/history/StatusUpdateHistoryViewModels.kt` | `CLOCK_OUT_ID_ARG` must equal the `clockOutId` property of `StatusUpdateDetail`/`StatusUpdateEdit`; edit drafts and dialog in `SavedStateHandle`; entering edit counts as changed; `onDiscardConfirmed()` must not navigate synchronously — it only closes the dialog and flips `exitConfirmed`, and the screen's `LaunchedEffect` performs the actual pop afterward, so the dialog is guaranteed gone first; `exitConfirmed` must be consumed via `onExitHandled()` once acted on, or a later Discard is silently dropped by `MutableStateFlow` conflating repeated `true` values | `StatusUpdateHistoryViewModelsTest` |
| `statusupdate/history/ui/StatusUpdateHistorySection.kt` | item keys prefixed so the section can share a list; all row gestures in one `detectTapGestures`; the peek popup is non-focusable while held | `AccountScreenTest` (androidTest) |
| `statusupdate/history/ui/StatusUpdateEditScreen.kt` | every exit except Save asks first; Save disabled while all answers are blank; the app bar up-button interceptor (`onInterceptUpChanged`) must register on a plain `DisposableEffect(state.found)` keyed to first composition, tagged with `upEntryId` — do not gate registration on a lifecycle state (an earlier version gated it on `RESUMED` via `LifecycleResumeEffect`, which only arrives once `NavHost`'s ~700ms enter transition finishes, leaving up silently skipping the discard confirmation for that whole window right after opening the editor); the id tag is what makes registering this early safe against the *other* direction of the same problem — a popped entry stays composed (and so keeps its stale registration) through its own ~700ms exit transition, and both `performUp` and `updateInterceptor` (`ui/main/UpNavigation.kt`) check the id before acting, so a stale registration or a stale clear is ignored once it no longer matches the current owner; this screen takes `upEntryId` as a plain `String`, not a `NavBackStackEntry` — layer rules (`docs/architecture/overview.md`) only allow `androidx.navigation` in app-shell files (`MainActivity`, `MainBottomBar.kt`, `UpNavigation.kt`), and this screen has no other reason to depend on it | `UpNavigationTest` (JVM), `StatusUpdateEditFlowTest` (androidTest) |
| `account/ui/AccountScreen.kt`, `account/ui/DisplayNameSection.kt` | sections are independent `LazyListScope` extensions; keep keys unique on the page | `AccountScreenTest` (androidTest) |
| `statusupdate/ui/StatusUpdateOverlayViewModel.kt` | Activity-scoped; `onBeginPrompt` is a no-op while a deck is open, and `openDeck` ignores a second request; undo closes a matching deck without saving | `StatusUpdateOverlayViewModelTest` |
| `statusupdate/ui/StatusUpdateOverlayHost.kt` | the deck stays inside a real `Dialog` window; consume the notification request once | `StatusUpdateOverlayContentTest` (androidTest) |
| `statusupdate/ui/StatusUpdateCardStack.kt` | stateless; swipe threshold; `StatusUpdateTestTags.CARD`; `CARD_COUNT` matches `StatusUpdateQuestion` | `StatusUpdateCardStackTest` (androidTest), `StatusUpdateCardStackUiStateTest` |
| `res/values/strings.xml` | referencing composables; keep `location_*` / `dev_*` naming. Compose UI tests match on exact text, so two strings sharing a value make `onNodeWithText` ambiguous | Compose UI tests match on text |
| `devtools/DeveloperToolsController.kt` | it mutates the *real* repositories on purpose — a simulated arrival records real attendance, and `clearProximity`/reset emit a real `Departed`. Do not reroute it through mock state. It reaches attendance, worksites and notifications **only** through `devtools/facade/`; do not give it those repositories back | `DeveloperToolsControllerTest`, `DevGeoTest` |
| `devtools/facade/DevAttendanceFacade.kt` | it must keep tagging writes `ClockSource.SIMULATED` (`clearSimulatedData` depends on it) and must keep exposing no undo — reversing an event is a user action | `DevAttendanceFacadeTest` |
| `devtools/facade/DevNotificationPreview.kt` | previews must keep posting under `DEV_PREVIEW_WORKSITE_ID`. Drop the id swap and a preview's **Confirm** writes a real attendance event for a real worksite. Do not 'fix' this with a `preview` flag on the production `ClockNotifications` | `DevNotificationPreviewTest` |
| `devtools/DebugLocationPermissionRepository.kt` | `permissionState` must stay `SharingStarted.Eagerly` (the coordinator reads `.value` at startup), and `refresh()` must still call the delegate | `DebugLocationPermissionRepositoryTest` |
| `devtools/DevUnlockTapCounter.kt` | the window is 10 s deliberately — at 3 s a deliberate tapper never completes a run. Test it with realistic timestamps, not a constant | `DevUnlockTapCounterTest` |
| `devtools/DeveloperSettingsStore.kt` | prefs file/key names — changing them silently drops a developer's overrides. A **new** store also needs backup-exclusion rules | `SharedPrefsDeveloperSettingsStoreTest`, `BackupRulesTest` |
| `devtools/DeveloperLogExporter.kt` | `PROVIDER_SUFFIX` must match `android:authorities` in `src/debug/AndroidManifest.xml`; the chooser needs the read grant repeated on it | `EmailDeveloperLogExporterTest` |
| `attendance/AttendanceEvent.kt` `ClockSource` | adding a value means auditing every branch on the enum. Only one exists in production — `lastClockOutManual` — so a new value silently joins the 'not manual' bucket with `AUTO`. Decide whether that is right, and assert it. Values persist by name: never rename or reorder | `DefaultAttendanceRepositoryTest`, `DevAttendanceFacadeTest` |
| `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | every `PREFS_NAME` needs both `<name>.xml` and `<name>_secure.xml` excluded, in **all** sections | `BackupRulesTest` |
| `ui/main/AppNavGraph.kt` | a destination missing here still renders, but falls back to the root's title and loses its up button. `destinationFor` strips route arguments, so a destination with arguments matches by type name. A parentless destination is a bottom-bar tab and must also be in `topLevel`. `DeveloperSettings` is `BuildConfig.DEBUG`-gated to match the `NavHost` | `AppBarTitleNavigationTest`, `AppBarUpButtonTest` |
| `AndroidManifest.xml` | matching runtime request in `LocationPermissions`; FGS type; `<service>`/`<receiver>` entries; `MainActivity` `launchMode="singleTop"` (without it a warm notification tap creates a second Activity) | instrumented suite |
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
6. **ViewModels never touch `Activity`/`Context`**. `LocationPermissionHost` and
   `StatusUpdateOverlayHost` are the two Activity-level composables that break the stateless rule.
7. **Location ViewModels are Activity-scoped, shared across destinations** — created in
   `MainActivity`, passed down.
8. **`register()` and `clear()` never interleave** — guaranteed by `collectLatest` in the
   coordinator.
9. **Domain models validate at construction** — some call sites project outside try/catch.
10. **Every `stateIn` uses `WhileSubscribed(5_000)`** — keeps upstreams warm across config changes
    without leaking.
11. **Attendance is the start destination and home** — Reports is a second tab, never the start
    destination; tab switches go through `navigateToTab` so tabs never stack on the back stack.
12. **Reports never count a shift in progress** — `SessionLog` keeps open shifts separate, and every
    surface that shows a report says when one is excluded.
13. **Every real clock-out reports to Status Update exactly once, and an undone one retracts it.**
    Producers guard with `recordIfStateChanges`/`undoEvent` before calling the listener.
14. **Notification extras are untrusted.** `MainActivity` is exported; only
    `claimNotificationRequest` may turn a launch intent into an open deck.
15. **Every store reachable from a ViewModel factory is forced in `startupJob`.**
16. **The app bar's up button falls through to an id-checked interceptor.** `MainActivity` holds an
    `upInterceptor` state that is `null` everywhere except the status update editor, which registers
    a handler tagged with its own back stack entry id as soon as it composes (`onInterceptUpChanged`)
    so up asks before leaving the same way its `BackHandler` does for system back; every other screen
    leaves the interceptor unset and up is a direct `popBackStack()`. Both the read side (`performUp`)
    and the write side (`updateInterceptor`) check that id before acting — a registration or a clear
    from an id that is no longer current is ignored rather than acted on or allowed to overwrite a
    newer registration. See the `ui/main/UpNavigation.kt` and `StatusUpdateEditScreen.kt` rows below.

## Reverse index — "who reads this state?"

| State | Producers | Consumers |
| --- | --- | --- |
| `LocationPermissionRepository.permissionState` | `refresh()` (3 call sites); in debug also `DeveloperSettingsStore.permissionOverride` via the decorator | coordinator, both ViewModels, service, `DeveloperSettingsViewModel` |
| `WorkLocationRepository.activeWorkLocation` | stub mutators (future registration flow) | coordinator ×2, `LocationViewModel` |
| `LocationStateRepository.latestLocation` | service, `LocationViewModel` collector | coordinator pipeline 2 |
| `LocationStateRepository.trackingStatus` | controller, service | `LocationViewModel.uiState` |
| `ProximityRepository.proximity` | geofence receiver, coordinator pipeline 2 | `LocationViewModel.uiState`, `LocationPowerPolicy` (via the VM) |
| `ProximityRepository.events` | `setState`, `reset` | **nobody yet** — the auto-clock-in seam |
| `AttendanceRepository.attendance` | `LocationViewModel.onClockIn`/`onClockOut`, auto-clock strategies, `ClockActionHandler`, dev facade | `LocationViewModel.uiState` |
| `AttendanceRepository.hasClockOutEvent` (query) | the event log | `StatusUpdateCoordinator.claimNotificationRequest` |
| `ClockOutListener.onClockOut` / `onClockOutUndone` | `GuardedClockStrategy.record` (`AUTO`), `ClockActionHandler.confirm` (`NOTIFICATION_CONFIRMED`), `ClockActionHandler.undo` | `AppContainer.clockOutListener` → `StatusUpdateCoordinator` |
| `StatusUpdateSettingsStore.enabled` | `SettingsViewModel.onStatusUpdateEnabledChanged` | `StatusUpdateCoordinator.onClockOut`, `SettingsViewModel.statusUpdateEnabled` |
| `AppForegroundTracker.isForeground` | `ActivityLifecycleCallbacks` | `StatusUpdateCoordinator.onClockOut` (`AUTO` only) |
| `StatusUpdateCoordinator.pendingPrompt` | `onClockOut` (set), `acceptPrompt`, `dismissPrompt`, `onClockOutUndone` (clear) | `StatusUpdateOverlayViewModel.uiState` |
| `StatusUpdateCoordinator.undoneClockOuts` | `onClockOutUndone` | `StatusUpdateOverlayViewModel` (closes a matching deck) |
| `StatusUpdateRepository.statusUpdates` | `StatusUpdateCoordinator.complete`, `StatusUpdateEditViewModel.save` (`updateAnswers`), `clearAll` | `StatusUpdateCoordinator.claimNotificationRequest`, `StatusUpdateHistoryViewModel`, `StatusUpdateDetailViewModel`, `StatusUpdateEditViewModel` (initial drafts) |
| `UserProfileStore.displayName` | `AccountViewModel.onDisplayNameChanged` | `AttendanceViewModel` greeting, `AccountViewModel` |
| `MainActivity.pendingStatusUpdateRequest` | `StatusUpdateIntents.consumeRequest` in `onCreate`/`onNewIntent` | `StatusUpdateOverlayHost` |
| `LocationClockInRepository.lastClockIns` | `LocationViewModel.onClockIn()` | `LocationViewModel.uiState` → detail screen |
| `AttendanceRepository.eventLog` | every attendance mutation (`publish()`) | `ReportsViewModel` (report + biweekly), `BiweeklyReportRunner` |
| `ReportSettings.biweeklyNotificationEnabled` | `BiweeklyReportController.setEnabled` (Settings switch) | `SettingsViewModel`, `BiweeklyReportController.reconcile` (startup), `BiweeklyReportRunner` |
