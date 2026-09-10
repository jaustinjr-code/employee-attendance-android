# Feature: Developer Settings (debug builds only)

A hidden screen that drives the app into states a developer would otherwise have to travel, wait,
or re-grant system permissions to reach — plus an application-log export.

Reached by tapping the **Attendance** app bar title **five times** within three seconds. The
destination is not registered at all in a release build.

## Code map

| File | Role |
| --- | --- |
| `devtools/DevUnlockTapCounter.kt` | the five-tap unlock policy; pure and clock-injected |
| `devtools/PermissionOverride.kt` | the permission states a developer can pin, and their mapping to `LocationPermissionState` |
| `devtools/DeveloperSettingsStore.kt` | interface + `SharedPrefsDeveloperSettingsStore` for the sticky overrides |
| `devtools/DebugLocationPermissionRepository.kt` | decorator that applies the permission override app-wide |
| `devtools/DeveloperToolsController.kt` | every developer action; also `DevGeo`, the offset math for simulated fixes |
| `devtools/facade/DevAttendanceFacade.kt` | interface + `RepositoryDevAttendanceFacade`; the only attendance capability the dev tools get |
| `devtools/facade/DevWorksiteFacade.kt` | interface + `RepositoryDevWorksiteFacade`; owns the dev sample worksite's id and name |
| `devtools/facade/DevNotificationPreview.kt` | interface + `SandboxedDevNotificationPreview`; posts previews whose action buttons are defused |
| `devtools/ApplicationLogSource.kt` | interface + `LogcatApplicationLogSource` reading this app's own log back |
| `devtools/DeveloperLogExporter.kt` | interface + `EmailDeveloperLogExporter`; writes the attachment and opens a chooser |
| `devtools/ui/DeveloperSettingsViewModel.kt` | `DeveloperSettingsUiState`, `DevMessage`, the `Factory` |
| `devtools/ui/DeveloperSettingsScreen.kt` | stateful wrapper + stateless content + preview |
| `src/debug/AndroidManifest.xml` | the `devlogs` `FileProvider`, debug-only |
| `src/debug/res/xml/dev_log_paths.xml` | the one cache directory that provider exposes |

## Why it lives in the main source set

The feature code sits in `main` and is gated on `BuildConfig.DEBUG` rather than living in
`src/debug/java`. That is a deliberate trade-off, and the reason is the entry point:
`MainActivity` is in `main`, and `main` cannot reference a debug-only class. Splitting by source
set would therefore need a reflective or service-loader lookup for the screen and the container
bindings — machinery this repo uses nowhere else.

What the gate buys instead:

- the `DeveloperSettings` route is registered only `if (BuildConfig.DEBUG)`, so the destination does
  not exist in release;
- `MainAppBar.onTitleClick` is null in release, so the title is a plain, non-interactive label;
- `DefaultAppContainer` binds the *undecorated* `SystemLocationPermissionRepository` in release, so
  no override path exists;
- the `FileProvider` is declared only in `src/debug/AndroidManifest.xml`, so a release build ships
  no content provider for app logs.

The residue is unreachable code and a handful of `dev_*` strings in the release APK. `buildConfig =
true` in `app/build.gradle.kts` exists for this gate.

## Why the actions write through facades to the real repositories

Every simulation is applied through the app's own app-scoped sources of truth. Simulating an arrival
calls `ProximityRepository.onGeofenceTransition` — the *same* call `GeofenceBroadcastReceiver`
makes — so the auto-clock engine, the notification strategy, persistence and the UI all react
exactly as they would in the field:

```
DeveloperToolsController.simulateArrival()
└─ ProximityRepository.onGeofenceTransition(activeId, INSIDE)
   ├─ store.save(INSIDE, id)                        <- real persistence
   ├─ emit Arrived(id)
   │  └─ AttendanceAutoClockController.handle
   │     └─ ClockNotificationStrategy.forPreference(current preference)
   │        ├─ AttendanceRepository.recordClockIn   <- real attendance record
   │        └─ ClockNotifier.notifyRecorded         <- real notification
   └─ LocationViewModel.uiState -> attendance screen shows "Clocked in at …"
```

A simulation that took a shortcut around those would prove nothing. **The consequence to keep in
mind: these are real mutations.** The screen says so at the top.

Acting on real data is the point. Acting on it *indistinguishably from the user* is not — and it
used to be, at both ends: the controller took the real `AttendanceRepository`,
`WorkLocationRepository` and `ClockNotifications` and called `recordClockIn(id)` / `clearAll()` on
them, spelled exactly as a user action and persisted exactly like one. So wherever a developer
action can destroy or forge the user's data, it now goes through a narrow seam in
`devtools/facade/`. Each method there names the developer intent, so no call site reads like a user
action:

| Facade | Wraps | What it narrows |
| --- | --- | --- |
| `DevAttendanceFacade` | `AttendanceRepository` | tags every write `ClockSource.SIMULATED`; exposes record + clear only, and deliberately **no `undoLast`** — the one operation that silently deletes a genuine event. Reads narrow to a single `isClockedIn(id)` boolean |
| `DevWorksiteFacade` | `WorkLocationRepository` | owns the sample worksite's id and name, so the caller never names one: it can seed/remove **only** `dev-sample-worksite`. The wide `removeAllWorksites()` survives solely to back the explicitly destructive "Remove all worksites" button |
| `DevNotificationPreview` | `ClockNotifications` | posts previews against a sandbox worksite id, so their Undo/Confirm buttons cannot touch real history (below) |

`ProximityRepository` and `LocationStateRepository` are used **directly, on purpose**. Their
developer operations are transient state pokes — proximity back to `UNKNOWN`, a forced
`TrackingStatus`, a simulated fix the next real one overwrites — with nothing persisted that a user
would miss. A facade there would be ceremony with no risk to answer to.

### Provenance, and the limit of it

`ClockSource` gained a third value, `SIMULATED`, and `DevAttendanceFacade` tags its writes with it.
That is what makes **Clear simulated data** possible: it removes only `SIMULATED` events and the dev
sample worksite, leaving the user's `AUTO`/`MANUAL` history and their own worksites alone. Before
this, the only cleanup was "delete everything".

`SIMULATED` changes no behaviour. The only production branch on `ClockSource` is
`lastClockOutManual = lastOut?.source == ClockSource.MANUAL` in
`DefaultAttendanceRepository.attendanceByLocation`, and `SIMULATED` falls in the same "not manual"
bucket as `AUTO`, so a simulated clock-out is displayed exactly like an automatic one. There is a
test asserting precisely that.

**The limitation, which is deliberate:** provenance can only be tagged on the controller's *direct*
writes — `forceClockIn` / `forceClockOut`. `simulateArrival()` flows through the real
`ProximityRepository` -> `AttendanceAutoClockController` -> `ClockNotificationStrategy` pipeline,
which calls `recordClockIn` itself with the production source. Threading a "this is a developer
simulation" flag through that path would make production code aware of the developer tools and cost
exactly the realism the feature exists for. So **pipeline-driven events stay indistinguishable by
design**, and `Clear simulated data` will not remove them; `Clear attendance history` still will,
knowing it takes everything.

### The notification preview sandbox

Posting a card records nothing. Its *buttons* were the hazard. `ClockNotifier` wires them to
`ClockActionReceiver`, which resolves the worksite id carried in the notification against the real
attendance repository:

- **Undo** calls `attendanceRepository.undoLast(locationId)` — deleting the most recent **genuine**
  attendance event for that worksite. A developer checking how the card looks could silently destroy
  real data.
- **Confirm** calls `recordClockIn`/`recordClockOut` — creating a real event, the mirror hazard.

`SandboxedDevNotificationPreview` posts against `worksite.copy(id = DEV_PREVIEW_WORKSITE_ID)`. The
worksite **name** is what the notification text renders, so it is still the genuine card being
previewed; only the id travelling in the action `PendingIntent` changes. `undoLast` on that id finds
nothing to remove, and a `Confirm` writes into a bucket no screen reads. `ClockNotifier`'s
notification id derives from `locationId.hashCode()`, so a preview also gets its own notification id
and cannot replace (or be replaced by) a real card for the same worksite.

The fix lives entirely on the developer-tools side of the seam: no production type learns that
previews exist, and `ClockNotifications` gains no `preview` flag.

Two of the actions have effects worth spelling out:

- **Clear proximity** and the proximity half of **Reset developer configuration** call
  `ProximityRepository.reset()`, which by design emits a real `Departed` when it clears an `INSIDE`
  state — so an auto clock-out can follow a reset. That is the documented behaviour of `reset()`,
  not a bug here.
- **Fix at worksite / Fix far away** publish a `LocationSample` to `LocationStateRepository`, which
  goes through the *real* distance math and hysteresis rather than setting proximity directly. It is
  the way to exercise `ProximityCalculator` end to end — and the next genuine fix from the tracker
  overwrites it, so the simulated position only holds where the device is not producing fixes.

## The permission override

`PermissionOverride` is applied by decorating the repository, not the UI:

```
DefaultAppContainer.locationPermissionRepository
└─ if (BuildConfig.DEBUG) DebugLocationPermissionRepository(system, override, appScope)
   else                   SystemLocationPermissionRepository
```

The seam matters. Every consumer — `LocationFeatureCoordinator.reconcileTracking`,
`LocationTrackingController.sync`, `LocationTrackingService`'s pre-check, both ViewModels — reads
permission from that one binding, so an override exercises the app's real reactions rather than only
repainting the screen.

It follows that forcing `ALWAYS` without the underlying grant makes the app genuinely *attempt*
background tracking and be refused by the platform:

```
D/DebugPermRepo: permission overridden: real=…accessLevel=NONE… forced=…accessLevel=ALWAYS…
D/LocCoord: reconcile: access=ALWAYS location=dev-sample-worksite
W/LocCoord: Geofence reconciliation failed
W/LocCoord: java.lang.SecurityException: uid … does not have android.permission.ACCESS_FINE_LOCATION.
```

The coordinator's existing `try`/`catch` degrades to foreground proximity instead of crashing —
which is itself a path worth being able to trigger. The screen's hint text says as much.

`permissionState` is shared `Eagerly`, not `WhileSubscribed`: the coordinator reads `.value` during
startup, and a lazily-started `combine` would hand it the real grant on the first read.

## The application log export

The app logs freely through `android.util.Log` on every layer, so rather than retro-fitting a
logging facade over every call site, `LogcatApplicationLogSource` reads those same lines back out of
the platform ring buffer with `logcat -d -v time --pid=<mypid>`.

Since Android 4.1 an app reading `logcat` without `READ_LOGS` sees **only its own process's**
output, which is exactly the wanted scope — no other app's logs can reach an exported file. `--pid`
(API 24+, matching `minSdk`) makes that explicit; a device whose `logcat` rejects the flag falls
back to an unfiltered read rather than failing the export.

The export writes `cacheDir/devlogs/employee-attendance-log-<timestamp>.txt` — header first, then
the log — and hands it to `ACTION_SEND` through the debug-only `devlogs` `FileProvider`. Each export
deletes the previous file, so the cache does not grow for the life of the install.

**Nothing is sent automatically.** The chooser opens with the message pre-addressed and the log
attached, and the developer sends it from their own mail app. The file contains coordinates,
worksite ids and timestamps, so that stays under explicit human control — and the app needs no
network permission or mail credentials.

## What reset does and does not touch

`resetDeveloperConfiguration()` clears the persisted overrides *and* the simulated runtime state
they produced: permission override off, log recipient forgotten, proximity `UNKNOWN`, tracking
`STOPPED`, and the real grant re-read.

It deliberately leaves **worksites and attendance history** alone. Those are the user's data, not
developer configuration, and destroying them is a different decision — so they have their own
explicit actions: `Clear simulated data` (surgical: `SIMULATED` events and the dev sample worksite
only), and the destructive `Remove all worksites` / `Clear attendance history`.

## Tests

| Layer | Covers |
| --- | --- |
| `test/.../devtools/DevUnlockTapCounterTest` | the tap window, restart-on-gap, unlock-once semantics |
| `test/.../devtools/PermissionOverrideTest` | the state mapping, persisted-name round-trip, and that every `LocationAccessLevel` stays reachable |
| `test/.../devtools/DebugLocationPermissionRepositoryTest` | override precedence, pass-through, eager seeding, `refresh()` still re-reading the delegate |
| `test/.../devtools/DeveloperToolsControllerTest` | every action, including that a simulated arrival really emits `Arrived`, that forced clocks are tagged `SIMULATED`, that `clearSimulatedData` spares genuine history, and that reset spares user data |
| `test/.../devtools/facade/DevAttendanceFacadeTest` | the `SIMULATED` tag, selective clearing, that a simulated clock-out is not "manual", and that no undo is exposed |
| `test/.../devtools/facade/DevWorksiteFacadeTest` | that seeding/removal can reach only the dev sample, never a user's worksite |
| `test/.../devtools/facade/DevNotificationPreviewTest` | the sandbox: a preview's Undo/Confirm leaves a pre-existing real record untouched, and the card still renders the genuine name |
| `test/.../attendance/DefaultAttendanceRepositoryTest` | `clearBySource` deleting only that source, and `SIMULATED` not flagging as manual |
| `test/.../devtools/DevGeoTest` | the offset math, which `ProximityCalculator` cannot verify on the JVM |
| `test/.../devtools/ui/DeveloperSettingsViewModelTest` | ui-state composition, gating, and the message mapping |
| `androidTest/.../devtools/SharedPrefsDeveloperSettingsStoreTest` | persistence across instances (process death) |
| `androidTest/.../devtools/EmailDeveloperLogExporterTest` | the written attachment, cache supersession, empty/failing log handling |
| `androidTest/.../devtools/LogcatApplicationLogSourceTest` | that the app really reads its own log lines back |
| `androidTest/.../devtools/DeveloperSettingsUnlockTest` | the gesture through the real `MainAppBar`, and that a null handler is inert |
| `androidTest/.../devtools/ui/DeveloperSettingsScreenTest` | the rendered controls, gating, and reset confirmation |

`DevGeo` and `DevUnlockTapCounter` are pure for the reason given in
[../maintenance/testing.md](../maintenance/testing.md): the JVM framework stubs make
`ProximityCalculator.distanceMeters` return 0 and `SystemClock` return defaults, so any policy that
depends on distance or time has to be extractable to be testable at all.
