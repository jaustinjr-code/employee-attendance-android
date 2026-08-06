# Adding a Feature or Fixing a Bug

How work is structured in this repo, so your change looks like the code around it.

## Branch naming

The existing history uses:

```
feature/<author>/<slug>      feature/claude/add-location-tracking
fix/<author>/<issue>-<slug>  fix/claude/20-geocode-timeout
docs/<author>/<slug>         docs/jaustinjr/init-developer-maintenance-guides
```

`<author>` is your handle. For fixes, lead with the issue number.

## The standard shape of a new feature

Follow the location feature's structure — it is the reference implementation.

```
location/<yourfeature>/
├── <Model>.kt                 domain model, validated in init { require(...) }
├── <Thing>Repository.kt       interface + default impl in ONE file
├── <Thing>Seam.kt             interface over any platform dependency
└── ui/
    ├── <Thing>ViewModel.kt    UI-state data class + ViewModel + companion Factory
    ├── <Thing>Screen.kt       stateful wrapper + stateless content + @Preview
    └── <Thing>Components.kt   reusable stateless composables
```

### Checklist

1. **Model first.** A `data class` with `init { require(...) }` validation. If it needs to reach
   another layer, give it an explicit projection function (see `WorkLocation.toGeofenceTarget()`)
   rather than sharing one fat model.

2. **Repository with `StateFlow`.** Private `MutableStateFlow`, public `asStateFlow()`. Interface
   plus default implementation in the same file. `@Synchronized` any read-modify-write that more
   than one thread can reach.

3. **A seam for every platform dependency.** If it needs `Context`, Play Services, a `Service`, or
   `SharedPreferences`, define a small interface so policy stays unit-testable —
   `TrackingServiceLauncher`, `GeofenceRegistrar`, `ProximityStateStore`, `ProximityUpdater` are the
   models to copy.

4. **Wire it in `AppContainer`.** Add the property to the interface, add a `by lazy` binding in
   `DefaultAppContainer`.

5. **ViewModel with a `Factory`.** A `UiState` data class holding raw fields and *derived* boolean
   properties (`isSetUp`, `canShowMap`) so composables never re-derive policy. Combine repository
   flows with `combine(...).stateIn(viewModelScope, WhileSubscribed(5_000), initial)`. Add the
   companion `Factory` pulling from `APPLICATION_KEY`.

6. **Stateless composables + previews.** Stateful wrapper collects with
   `collectAsStateWithLifecycle()`; the stateless overload takes the UI-state object and lambdas.
   Every stateless composable gets an `@Preview`.

7. **Strings in `strings.xml`.** Prefix by feature (`location_*`), and give icons a
   `contentDescription` or explicitly mark them decorative (`contentDescription = null` with a
   comment saying why).

8. **New destination?** Add a `@Serializable object` route in `MainActivity`, a `composable<Route>`
   block, and a `LaunchedEffect` setting `appBarTitle`.

9. **New `Service` / `BroadcastReceiver`?** Declare it in `AndroidManifest.xml` with
   `android:exported="false"` unless it genuinely needs to be exported, and get its dependencies via
   `(application as EmployeeAttendanceApplication).container`.

10. **Tests.** JVM unit tests for models, policies, repositories, and ViewModels (use
    `MainDispatcherRule`); instrumented tests only for genuinely platform-bound code and Compose UI.

11. **Docs.** Update the relevant `docs/features/*.md`, any diagram whose relationships changed, and
    `docs/maintenance/change-impact-map.md` if you added a dependency edge. See
    [documentation-workflow.md](documentation-workflow.md).

## Fixing a bug

1. Find the owning file via [../README.md](../README.md) or the symptom table in
   [../onboarding.md](../onboarding.md#7-where-to-look-first-for-a-given-symptom).
2. Read the [change impact map](change-impact-map.md) entry for that file **before** editing —
   several classes carry non-obvious constraints (the service's permission pre-check, the
   repository's persistence seeding, the coordinator's `collectLatest`) that look removable and are
   not.
3. Write a failing test first where the layer allows it. Policy classes (`ProximityCalculator`,
   `LocationPowerPolicy`, `LocationTrackingController`, `computePrompt`) are pure and trivial to
   test — if your bug is in one, there is no excuse for not having a test.
4. If you're deleting a comment that explains *why* something is done a strange way, stop. Those
   comments encode Android-version bugs and race conditions that were hit for real.
5. Run `./gradlew testDebugUnitTest`; run the instrumented suite if you touched the service,
   receiver, prefs store, or any composable.

## Anti-patterns in this codebase

Reviewers will push back on these:

| Don't | Do |
| --- | --- |
| Collect `LocationTracker` from a new consumer | read `LocationStateRepository.latestLocation` |
| Inject a repository into a composable | add a field to the UI-state class |
| Put `Build.VERSION.SDK_INT` permission checks at a call site | add a property to `LocationPermissions` |
| Duplicate the permission→tracking-mode `when` | call `LocationTrackingController.sync` |
| Compare `accessLevel == ALWAYS` in UI | use `supportsBackgroundTracking` / `canShowMap` |
| Create the location ViewModels per-destination | share the `MainActivity`-scoped instances |
| Hardcode a version in `build.gradle.kts` | add it to `libs.versions.toml` |
| Hardcode user-facing text in a composable | `strings.xml` |
| Register a second concurrent geofence | fix `ProximityRepository`'s global state in the same PR |
| Make `ProximityRepository` start at `UNKNOWN` | keep seeding from `ProximityStateStore` |

## Pull requests

- The `.github/workflows/code-review.yml` workflow runs an automated Claude review on every opened
  PR, and again on any comment containing `@claude`.
- `.github/workflows/docs-sync.yml` opens a **separate** documentation PR when your merged change
  touches architecturally significant code. See [documentation-workflow.md](documentation-workflow.md).
- Keep documentation changes for a feature in the feature PR when you can — the docs-sync workflow
  is a safety net, not the primary path.
