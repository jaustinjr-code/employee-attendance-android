# Onboarding

Everything you need to go from a fresh clone to a running app and a green test suite.

## 1. Repository layout

The Gradle project is **not** at the repository root — it lives one level down in
`EmployeeAttendance/`.

```
employee-attendance-android/          <- git root
├── .github/workflows/                <- CI: code review + docs sync
├── docs/                             <- you are here
└── EmployeeAttendance/               <- Gradle root project ("Employee Attendance")
    ├── settings.gradle.kts           <- includes :app only
    ├── gradle/libs.versions.toml     <- version catalog; ALL dependency versions live here
    └── app/
        ├── build.gradle.kts
        └── src/
            ├── main/java/com/jaustinjr/employeeattendance/
            ├── test/       (JVM unit tests)
            └── androidTest/(instrumented + Compose UI tests)
```

**Open `EmployeeAttendance/` in Android Studio**, not the git root.

## 2. Toolchain

| Thing | Value | Where it's set |
| --- | --- | --- |
| Android Gradle Plugin | 9.2.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.2.10 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | 36 | `app/build.gradle.kts` |
| minSdk | 24 | `app/build.gradle.kts` |
| Java source/target | 11 | `app/build.gradle.kts` |
| Compose BOM | 2026.02.01 | `gradle/libs.versions.toml` |

Google Play Services (`play-services-location` 21.3.0) is a hard dependency — the Fused Location
Provider and geofencing both come from it. Use an emulator image **with** Google Play / Google APIs,
otherwise location and geofences silently do nothing.

`local.properties` (with `sdk.dir`) is machine-specific and not something you commit.

## 3. Build and run

```bash
cd EmployeeAttendance && ./gradlew assembleDebug
```

```bash
cd EmployeeAttendance && ./gradlew installDebug
```

## 4. Tests

JVM unit tests — fast, no device, this is the bulk of the suite:

```bash
cd EmployeeAttendance && ./gradlew testDebugUnitTest
```

Instrumented + Compose UI tests — needs a running emulator or device:

```bash
cd EmployeeAttendance && ./gradlew connectedDebugAndroidTest
```

Two testing details worth knowing up front:

- `android.testOptions.unitTests.isReturnDefaultValues = true` is enabled, so `android.util.Log`
  and similar framework stubs return defaults instead of throwing. That is why classes that log
  freely (nearly all of them) are still JVM-unit-testable.
- Anything that needs real `android.location.Location` math — notably `ProximityCalculator` — is
  covered by unit tests only because of that flag plus Robolectric-free arrangement; the truly
  platform-bound pieces (`SharedPrefsProximityStateStore`, `GeofenceBroadcastReceiver`,
  `LocationTrackingService`, `SystemLocationPermissionRepository`) live in `androidTest/`.

`app/src/test/java/.../testutil/MainDispatcherRule.kt` swaps `Dispatchers.Main` for a test
dispatcher. Every ViewModel test uses it — reuse it rather than writing your own.

## 5. Exercising the location feature by hand

The location feature has three permission states and behaves differently in each. To see all of
them:

1. **No permission** — fresh install. The attendance screen shows the "Set Up Location" chip and
   the rationale dialog appears.
2. **While using the app** — grant foreground only. The chip reads "Limited Location Access", the
   detail screen hides the map, and fixes come from a *foreground* collector inside
   `LocationViewModel`. No service, no geofences.
3. **Allow all the time** — grant background from system settings. The foreground service starts
   with its notification, OS geofences are registered, and the map card appears.

Emulator tips:

- Set a mock position with the emulator's Extended Controls → Location. The seeded work location is
  **37.7749, -122.4194** (150 m radius) — see `StubWorkLocationRepository.DEFAULT_OFFICE`.
- Geofence transitions are batched by the OS with a 2-minute notification responsiveness window
  (`GeofenceManager.NOTIFICATION_RESPONSIVENESS_MILLIS`). Expect a delay; it is not a bug.
- Revoking permission from system Settings while the app is backgrounded is a supported path and is
  re-read on every `ON_RESUME` by `LocationPermissionHost`.

## 6. Conventions this codebase follows

Match these when you add code; reviewers and the docs-sync workflow both assume them.

- **Package by feature, then by layer.** `location/<concern>/` where concern is
  `permission | tracking | proximity | geofence | registration | ui`.
- **Interface + default implementation in one file.** e.g. `LocationTracker` and
  `FusedLocationTracker` live together in `LocationTracker.kt`. The interface is the test seam; the
  class is the platform-backed production wiring.
- **Every platform dependency gets a seam.** `TrackingServiceLauncher`, `GeofenceRegistrar`,
  `ProximityUpdater`, and `ProximityStateStore` exist purely so policy classes are unit-testable
  without Play Services or a real Service.
- **Repositories expose `StateFlow`, never mutable state.** A private `MutableStateFlow` plus a
  public `asStateFlow()` is the pattern everywhere.
- **ViewModels are Android-UI-free.** They never hold an `Activity`, never launch permission
  requests. The composable layer (`LocationPermissionHost`) owns launchers and calls back into the
  ViewModel.
- **Composables are stateless and previewable.** Screens come in pairs: a stateful wrapper that
  collects the ViewModel, and a stateless content composable taking a UI-state data class. Every
  stateless one has an `@Preview`.
- **Domain models validate in `init { require(...) }`.** `WorkLocation` and `GeofenceTarget` both
  reject invalid coordinates/radii at construction so bad data can't reach the geofencing pipeline.
- **Logging uses a short per-class `TAG` in a `private companion object`.** `LocCoord`, `ProxRepo`,
  `TrackSvc`, `PermVM`, etc.
- **All user-facing strings live in `res/values/strings.xml`.** No hardcoded copy in composables.
  (Two known exceptions in `AttendanceScreen.kt` — see [features/attendance.md](features/attendance.md).)
- **Dependency versions go in `gradle/libs.versions.toml`,** referenced as `libs.*`. Do not inline
  versions in `build.gradle.kts`.

## 7. Where to look first for a given symptom

| Symptom | Start at |
| --- | --- |
| Wrong/absent permission dialog | `location/ui/LocationPermissionViewModel.kt` → `computePrompt` |
| Dialog shows but nothing happens on confirm | `location/ui/LocationPermissionHost.kt` |
| Background tracking never starts | `location/tracking/LocationTrackingController.kt`, then `LocationTrackingService.startTracking` |
| Notification missing / service crashes on Android 14+ | `LocationTrackingService.promoteToForeground` + manifest `foregroundServiceType` |
| Proximity stuck on UNKNOWN | `LocationFeatureCoordinator` pipeline 2, then `LocationStateRepository.latestLocation` |
| Arrived/Departed event missing | `ProximityRepository.setState`, and the persistence seeding in its constructor |
| Geofence never fires | `GeofenceManager.register`, `GeofenceBroadcastReceiver`, background permission |
| UI shows stale state | the `combine(...)` in `LocationViewModel.uiState` |
