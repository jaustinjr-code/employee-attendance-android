# CLAUDE.md

Project instructions for Claude Code working in this repository.

## Orientation

Single-module Android app (`:app`) — Jetpack Compose, Material 3, Navigation-Compose, coroutines/Flow.
No DI framework: the graph is hand-wired in `di/AppContainer.kt` and hung off the `Application`.

**Gradle runs from `EmployeeAttendance/`, not the repo root.**

The `docs/` tree is accurate and maintained — read it rather than re-deriving from source:

| Question | Doc |
| --- | --- |
| How the layers fit together, threading, lifetimes | [docs/architecture/overview.md](docs/architecture/overview.md) |
| What breaks if I change file X | [docs/maintenance/change-impact-map.md](docs/maintenance/change-impact-map.md) |
| How this repo shapes a new feature | [docs/maintenance/adding-a-feature.md](docs/maintenance/adding-a-feature.md) |
| Which tests to write, and where | [docs/maintenance/testing.md](docs/maintenance/testing.md) |

The single most important thing to understand is `LocationFeatureCoordinator` and the repositories
it wires. Two invariants are load-bearing and documented in `docs/architecture/overview.md` §8 —
read that section before touching proximity or geofencing.

## Validation requirement

> [!IMPORTANT]
> **Do not propose opening a pull request until the automated suite has been run and is green.**
> Report the actual results. If a layer could not be run, say which and why — never describe a
> change as validated on the strength of the layers that did run.

Before proposing a PR, run from `EmployeeAttendance/`:

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:lintDebug
```

`lintDebug` currently fails on `main` with 6 pre-existing `NewApi` errors in
`location/registration/AddressGeocoder.kt` (the `Geocoder.GeocodeListener` overloads need API 33;
`minSdk` is 24). Treat lint as advisory until those are fixed, but **check that your change did not
add new findings** — compare the count rather than ignoring the task.

```bash
./gradlew :app:connectedDebugAndroidTest
```

The second needs a booted emulator or device. Check with `adb devices` first; if none is attached,
boot one:

```bash
$ANDROID_HOME/emulator/emulator -avd $(emulator -list-avds | head -1) -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
```

If no emulator can be started in the environment, state that the instrumentation and screenshot
layers were **not** run and that CI will be the first to exercise them. Do not treat a green JVM run
as sufficient — see the next section for why that gap is real here.

### Why the JVM layer alone is not enough

`isReturnDefaultValues = true` makes unmocked framework calls return defaults rather than throw.
That is what makes this codebase JVM-testable despite logging freely, and it also means a JVM test
touching real framework behaviour passes without asserting anything meaningful.
`ProximityCalculator.distanceMeters` returns 0 for every input on the JVM. Distance math,
`SharedPreferences` persistence, `SavedStateHandle`, and all rendering are only genuinely covered in
`androidTest`.

### When a screenshot test fails

A screenshot failure is either a real visual regression or an intended UI change. Decide which, and
say so — do not re-record goldens to make a failure disappear. For an intended change, re-record and
show what moved:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.screenshot_record=true
```

Actual and magenta-highlighted diff images are written to
`app/build/outputs/connected_android_test_additional_output/` on every failure. Look at the diff
before concluding anything.

Goldens are specific to the emulator config that recorded them (API level, target, ABI, density).
The pinned CI config lives in `.github/workflows/android-tests.yml`; details and the re-recording
workflow are in [docs/maintenance/testing.md](docs/maintenance/testing.md).

## Conventions

- **Interface + default impl in one file** (`LocationTracker.kt` holds both the interface and
  `FusedLocationTracker`). The interface is the test seam.
- **A seam for every platform dependency.** Anything needing `Context`, Play Services, a `Service`,
  or `SharedPreferences` gets a small interface so policy stays unit-testable — copy
  `TrackingServiceLauncher`, `GeofenceRegistrar`, `ProximityStateStore`, `ProximityUpdater`.
- **Composables stay stateless.** They take a UI-state data class and lambdas; they never reach a
  repository. `LocationPermissionHost` is the one deliberate exception (permission launchers can
  only be owned there).
- **Adding a dependency means editing three places**: the `AppContainer` interface, the
  `DefaultAppContainer` implementation, and the consuming ViewModel factory.
- **`@Synchronized` any read-modify-write** reachable from more than one thread; geofence callbacks
  land on main while the foreground pipeline runs on `Dispatchers.Default`.
- ViewModel tests use `testutil/MainDispatcherRule.kt` — reuse it rather than writing your own.

## Branch naming

```
feature/<author>/<slug>      feature/claude/add-location-tracking
fix/<author>/<issue>-<slug>  fix/claude/20-geocode-timeout
docs/<author>/<slug>         docs/jaustinjr/init-developer-maintenance-guides
```

## Docs are part of the change

This repo keeps its architecture docs in sync with the code deliberately. If a change alters a
relationship shown in a diagram, a layer rule, or one of the documented stubs, update the
corresponding doc in the same PR — see
[docs/maintenance/documentation-workflow.md](docs/maintenance/documentation-workflow.md).
