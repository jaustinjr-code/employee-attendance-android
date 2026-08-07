# Feature: Proximity & Geofencing

Deciding whether the user is at their work location, and emitting the arrival/departure events that
auto clock-in will eventually consume.

## Code map

| File | Role |
| --- | --- |
| `location/proximity/GeofenceTarget.kt` | geometric target with validation, `MAX_RADIUS_METERS` |
| `location/proximity/ProximityState.kt` | `UNKNOWN/INSIDE/OUTSIDE` + `ProximityEvent.Arrived/Departed` |
| `location/proximity/ProximityCalculator.kt` | distance + hysteresis, pure and unit-testable |
| `location/proximity/ProximityRepository.kt` | the single source of truth; both producers converge here |
| `location/proximity/ProximityStateStore.kt` | interface + `SharedPrefsProximityStateStore` |
| `location/proximity/ProximityUpdater.kt` | seam so the coordinator is testable |
| `location/geofence/GeofenceRegistrar.kt` | seam over OS geofence registration |
| `location/geofence/GeofenceManager.kt` | Play Services registration |
| `location/geofence/GeofenceBroadcastReceiver.kt` | OS transition delivery |
| `location/LocationFeatureCoordinator.kt` | decides when geofences exist and feeds foreground fixes |

Tests: `GeofenceTargetTest`, `ProximityCalculatorTest`, `ProximityRepositoryTest` (incl. two
concurrency tests) in `test/`; `SharedPrefsProximityStateStoreTest`, `GeofenceBroadcastReceiverTest`,
`ProximityPersistenceE2ETest` in `androidTest/`.

## Two producers, one state

```
ALWAYS      → OS geofence → GeofenceBroadcastReceiver → onGeofenceTransition(id, state) ──┐
                                                                                            ├→ ProximityRepository
WHEN_IN_USE → fix → LocationFeatureCoordinator → onLocation(sample, target) ──────────────┘
```

Both funnel through the private `@Synchronized setState`, which dedupes (no-ops when the state is
unchanged), persists, and emits. That is why running both paths simultaneously is harmless —
relevant because pipeline 2 of the coordinator feeds `onLocation` whenever a fix and an active
location exist, regardless of permission level.

## Hysteresis

`ProximityCalculator.evaluate` prevents flapping at the boundary:

- `distance <= radius` → `INSIDE`
- `distance > radius + exitBuffer` → `OUTSIDE`
- in between → hold the current state (`INSIDE` stays inside; `UNKNOWN` and `OUTSIDE` resolve to
  `OUTSIDE`)

`exitBufferMeters` defaults to **50 m** (`ProximityRepository.DEFAULT_EXIT_BUFFER_METERS`) and is a
constructor parameter, so tests can vary it. Note this applies only to the **foreground** path — OS
geofences do their own hysteresis internally, which is one reason the two paths can disagree
slightly at the edge.

`ProximityCalculator` uses `android.location.Location.distanceBetween` (WGS84). That is an Android
framework call, which is only unit-testable here because of
`testOptions.unitTests.isReturnDefaultValues` — check `ProximityCalculatorTest` before assuming a
new test will "just work".

## Persistence and process death

This is the subtlest thing in the codebase. Android kills the app process and cold-starts it *just*
to deliver a geofence transition. Without persistence:

1. User is `INSIDE`. Process dies.
2. User leaves; OS delivers EXIT; the process cold-starts.
3. In-memory state would be `UNKNOWN`, so `OUTSIDE` after `UNKNOWN` is not a departure → the
   `Departed` event is swallowed.

So `ProximityRepository` seeds `_proximity` and `lastTargetId` from `ProximityStateStore` in its
constructor, and `setState` writes back on every commit.
`ProximityRepositoryTest."exit after process death seeded INSIDE still emits Departed"` and the
instrumented `ProximityPersistenceE2ETest` guard this. **Do not make the repository's initial state
a hardcoded `UNKNOWN`.**

## Event semantics

| Transition | Event |
| --- | --- |
| any → `INSIDE` | `Arrived(targetId)` |
| `INSIDE` → `OUTSIDE` | `Departed(targetId)` |
| `UNKNOWN` → `OUTSIDE` | *nothing* — never being inside is not a departure |
| → `UNKNOWN` | nothing from `setState` |
| `reset()` from `INSIDE` | `Departed(lastTargetId)` — de-registering a location you're at *is* a departure |

`events` is a `MutableSharedFlow(replay = 0, extraBufferCapacity = 8)` emitted with `tryEmit`, so a
slow/absent subscriber never blocks a producer — but also means events can be dropped if more than
8 pile up. Zero replay means **a subscriber that attaches late misses everything**. Both matter if
you build auto clock-in on top: use `proximity` (a `StateFlow`) for current truth and `events` for
transitions, and subscribe from an app-scoped collector, not a ViewModel.

**Nothing currently subscribes to `events`.** It is the intentional integration seam for auto
clock-in — the natural place to start is an app-scoped collector launched next to the coordinator in
`EmployeeAttendanceApplication`, calling into `LocationClockInRepository`.

## Geofence registration

`LocationFeatureCoordinator.reconcileTracking` owns the decision:

```kotlin
val useGeofences = permission.supportsBackgroundTracking && activeLocation != null
if (useGeofences) geofenceRegistrar.register(listOf(activeLocation.toGeofenceTarget()))
else              geofenceRegistrar.clear()
```

Wrapped in `try/catch` that rethrows `CancellationException` and logs everything else — Play
Services or device location can be unavailable, and the app degrades to foreground proximity rather
than crashing. The whole reconcile runs under `collectLatest`, so a rapid input change cancels the
in-flight reconcile and `clear()`/`register()` cannot interleave across reconciliations.

`GeofenceManager` details:

- `register` calls `clear()` first, making it idempotent.
- `INITIAL_TRIGGER_ENTER` fires ENTER immediately if the user is already inside at registration
  time.
- `NEVER_EXPIRE`, ENTER|EXIT transitions.
- `setNotificationResponsiveness(120_000)` lets the OS batch transitions and use lower-power
  location sources. **This is why clock-ins can be up to ~2 minutes late.** Lower it for snappier
  clock-ins, raise it to favor battery.
- The `PendingIntent` must be `FLAG_MUTABLE` on Android 12+ so the system can populate the event.

`GeofenceBroadcastReceiver` keeps its work minimal (an in-memory state update) to stay within the
broadcast time budget. It maps `ENTER`/`DWELL` → `INSIDE`, `EXIT` → `OUTSIDE`, ignores everything
else, and guards `null`/`hasError()` events.

## The single-active-target assumption (important)

`ProximityRepository` holds **one global** `ProximityState`, not per-target state, even though every
event carries a `targetId`. This is only safe because `LocationFeatureCoordinator` registers exactly
one active work location at a time.

If multi-location support is added, an EXIT for target B while still inside target A will flip the
global state to `OUTSIDE` and fire a spurious `Departed(B)`. The documented fix (see the
`ProximityRepository` KDoc `TODO`) is to replace the single state with the **set of target ids
currently inside** and derive aggregate proximity and events from that set.

Any PR that registers more than one geofence must change `ProximityRepository` in the same PR.

## Validation

Both `GeofenceTarget` and `WorkLocation` validate in `init`: non-blank id, latitude in −90..90,
longitude in −180..180, and `radiusMeters` in `(0, 100_000]`. The upper bound and the explicit
`> 0f` check exist to reject `NaN`/`Infinity`, which pass a naive positivity check and would
otherwise blow up inside `Geofence.Builder().setCircularRegion(...)`. Validating at construction
matters because some call sites project `WorkLocation → GeofenceTarget` *outside* a try/catch.

## Concurrency

`onLocation`, `reset`, and `setState` are all `@Synchronized` because geofence callbacks arrive on
the main thread while the foreground pipeline runs on `Dispatchers.Default`. `onLocation` computes
*and* commits under the monitor so a concurrent geofence commit can't slip between the read and the
write. Two tests (`concurrent identical transitions settle on that state`, `concurrent mixed
transitions never corrupt state`) cover this — keep them green.
