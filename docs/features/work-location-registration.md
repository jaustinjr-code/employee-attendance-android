# Feature: Work Location Registration

The domain model for a registered work location, the repository that holds them, and the clock-in
record keyed to them. **This layer is deliberately stubbed** — it is the most likely place for the
next real feature.

## Code map

| File | Role |
| --- | --- |
| `location/registration/WorkLocation.kt` | domain model + validation + `toGeofenceTarget()` |
| `location/registration/WorkLocationRepository.kt` | interface + `StubWorkLocationRepository` |
| `location/registration/LocationClockInRepository.kt` | in-memory last-clock-in per location |
| `location/ui/LocationDetailScreen.kt` | displays name, address, proximity, map, last clock-in |
| `location/ui/WorkLocationMapCard.kt` | placeholder map card |
| `location/ui/LocationPill.kt` | the compact name badge on the attendance screen |

Tests: `WorkLocationTest`, `StubWorkLocationRepositoryTest`, `LocationClockInRepositoryTest` (JVM);
`LocationDetailScreenTest`, `LocationNavigationTest` (instrumented).

## `WorkLocation` vs `GeofenceTarget`

Two models on purpose:

| | `WorkLocation` | `GeofenceTarget` |
| --- | --- | --- |
| Package | `location.registration` | `location.proximity` |
| Carries | id, **name**, **address**, lat, lon, radius | id, lat, lon, radius |
| Audience | users / UI | the proximity + geofencing engine |
| Direction | `toGeofenceTarget()` projects down | never projects back up |

Keep display concerns out of `GeofenceTarget`. If the proximity engine starts needing a name, that
is a signal something is in the wrong layer.

Both validate in `init { require(...) }` — see
[proximity-and-geofencing.md § Validation](proximity-and-geofencing.md#validation). `WorkLocation`
additionally requires a non-blank `name`.

## `StubWorkLocationRepository`

In-memory, seeded with one location:

```kotlin
DEFAULT_OFFICE = WorkLocation(
    id = "downtown-office", name = "Downtown Office", address = "123 Market St",
    latitudeDegrees = 37.7749, longitudeDegrees = -122.4194, radiusMeters = 150f,
)
```

That is also the coordinate to mock on the emulator. State resets on process death.

The interface already exposes the full surface a real implementation needs —
`workLocations`, `activeWorkLocation`, `setActiveWorkLocation`, `registerWorkLocation`,
`removeWorkLocation` — so replacing the stub should require **no changes outside
`DefaultAppContainer`**.

Semantics the stub establishes, which a real implementation must preserve (they're covered by
`StubWorkLocationRepositoryTest`):

- `registerWorkLocation` replaces any location with the same id, and becomes active if none was.
- `setActiveWorkLocation` with an unknown id is a no-op, not a clear.
- `removeWorkLocation` on the active location falls back to another; removing the last one sets
  active to `null`.

All three mutators are `@Synchronized` — they're read-modify-write over two `StateFlow`s and are
callable from any thread.

## What consumes `activeWorkLocation`

This is a hub. Two of the three consumers cause real side effects:

| Consumer | Effect of a change |
| --- | --- |
| `LocationFeatureCoordinator` pipeline 1 | re-registers or clears OS geofences |
| `LocationFeatureCoordinator` pipeline 2 | re-targets proximity; `null` triggers `ProximityRepository.reset()` (which emits `Departed` if inside) |
| `LocationViewModel.uiState` | drives `isSetUp`, `canShowMap`, the pill, and the detail screen |

So setting `activeWorkLocation` to `null` is not a passive UI change — it deregisters geofences and
fires a departure event.

## `LocationClockInRepository`

A stand-in for the real attendance backend: `Map<locationId, epochMillis>` of the *last* clock-in.
Written by `LocationViewModel.onClockIn()`, read by `LocationUiState.lastClockInEpochMillis` and
rendered by `LastClockInRow` on the detail screen. In-memory only; `recordClockIn` is
`@Synchronized`.

There is no clock-*out* record and no history — see [attendance.md](attendance.md#known-gaps).

## Building the real registration flow

The stub's KDoc calls the registration UX explicitly out of scope. When you build it:

1. Add a new destination in `MainActivity`'s `NavHost` (`@Serializable object WorkLocationSetup`)
   and set the app bar title in its `LaunchedEffect`.
2. Implement a persisted `WorkLocationRepository` (DataStore or Room) behind the existing interface.
   Consider whether `activeWorkLocation` should also persist across process death.
3. Swap the binding in `DefaultAppContainer.workLocationRepository`. Nothing else should need to
   change — if it does, something has coupled past the interface.
4. Reuse `WorkLocation`'s `init` validation for user input; catch `IllegalArgumentException` at the
   form boundary and show a field error rather than pre-validating separately.
5. **If you allow more than one active location at a time, you must also fix `ProximityRepository`'s
   single-target assumption** — see
   [proximity-and-geofencing.md § single-active-target](proximity-and-geofencing.md#the-single-active-target-assumption-important).
6. Geocoding an address to coordinates is not implemented anywhere; `address` is display-only today.

## Map card

`WorkLocationMapCard` renders a gradient `Box` with a centered pin and a name chip — **not** a live
map. Embedding Google Maps needs a Maps SDK key and build wiring that was out of scope. The intended
follow-up is swapping that `Box` for a `GoogleMap` composable; the surrounding card and data flow
stay the same. It is gated on `LocationUiState.canShowMap` (full `ALWAYS` access + a registered
location); otherwise `MapLockedNotice` is shown instead. Accessibility: the `Box` carries the
`cd_map` content description and the pin icon is marked decorative — preserve that when you swap in
a real map.
