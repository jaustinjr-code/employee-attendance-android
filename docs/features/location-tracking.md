# Feature: Location Tracking

Producing location fixes: who requests them, at what cadence, from which process, and where they
land.

## Code map

| File | Role |
| --- | --- |
| `location/tracking/LocationSample.kt` | framework-free fix model |
| `location/tracking/LocationRequestConfig.kt` | `LocationPriority` (+ GMS mapping), config with invariants, `Foreground`/`Background` presets |
| `location/tracking/LocationPowerPolicy.kt` | proximity → config, the adaptive power lever |
| `location/tracking/LocationTracker.kt` | interface + `FusedLocationTracker` (callbackFlow over FLP) |
| `location/tracking/LocationStateRepository.kt` | `TrackingStatus` + the shared latest-fix hand-off |
| `location/tracking/LocationTrackingController.kt` | permission → tracking-mode policy |
| `location/tracking/TrackingServiceLauncher.kt` | test seam over the service |
| `location/tracking/LocationTrackingService.kt` | the foreground service |
| `location/ui/LocationViewModel.kt` | the *foreground-only* collector |

Tests: `LocationRequestConfigTest`, `LocationPriorityTest`, `LocationPowerPolicyTest`,
`LocationTrackingControllerTest` (JVM); `LocationTrackingServiceTest` (instrumented).

## Two producers, one repository

```
ALWAYS       → LocationTrackingService ──┐
                                          ├──→ LocationStateRepository.latestLocation
WHEN_IN_USE  → LocationViewModel        ──┘
```

`LocationStateRepository` is the seam that makes this invisible to consumers. Never bypass it by
collecting `LocationTracker` directly from a consumer — you would get a second FLP request and
double the battery cost.

The two producers are **mutually exclusive** by construction:

- `LocationTrackingController.sync(ALWAYS)` starts the service.
- `LocationViewModel.collectForegroundFixesWhenDegraded()` only collects when
  `permission.isDegraded` — i.e. exactly `WHEN_IN_USE`. Under `ALWAYS` it stays idle on purpose,
  because the service and geofences already cover it.

If you ever see two streams running, that gate is what broke.

## Power policy

Two independent mechanisms, don't confuse them:

**`LocationRequestConfig.Background`** — the fixed config the *service* uses. Low power, 120 s
interval, 300 s batching window. Precise arrival/departure is geofences' job, so this stream only
keeps a roughly-current position for display.

**`LocationPowerPolicy.foregroundConfig(proximity)`** — the *adaptive* config for the foreground-only
path, which has no geofences to lean on:

| Proximity | Priority | Interval | Min | Max batch delay |
| --- | --- | --- | --- | --- |
| `UNKNOWN` | HIGH_ACCURACY | 5 s | 2 s | 0 |
| `INSIDE` | BALANCED | 30 s | 15 s | 30 s |
| `OUTSIDE` | LOW_POWER | 60 s | 30 s | 120 s |

Because proximity feeds the policy and the policy's fixes update proximity, this is a closed loop.
`collectLatest` in `LocationViewModel` restarts the stream when the cadence should change — that
restart is what applies the new config.

`LocationRequestConfig`'s `init` block enforces `intervalMillis > 0`,
`minUpdateIntervalMillis in 0..intervalMillis`, and `maxUpdateDelayMillis >= 0`. New presets that
violate these throw at construction, which is how `LocationRequestConfigTest` catches mistakes.

## The foreground service

`LocationTrackingService` is deliberately thin: notification + lifecycle + forwarding. No proximity
logic lives here.

Details that exist for specific, hard-won reasons — preserve them:

- **Permission re-check before `startForeground`.** `START_STICKY` can redeliver an intent after the
  user revoked location in Settings. Calling `startForeground` with
  `FOREGROUND_SERVICE_TYPE_LOCATION` without the permission throws `SecurityException` under
  Android 14+ FGS-type enforcement. The service refreshes permission and bails first, then also
  catches `SecurityException` around the promotion to cover the race.
- **`startForeground` is called on every delivery,** even when already tracking, to satisfy the
  `startForegroundService()` obligation (which must be honored within a few seconds or the app ANRs).
- **`serviceScope` uses `Dispatchers.Main.immediate`,** making `trackingJob` single-threaded and
  removing the read/write race between a start and a stream-failure stop. The work is just
  forwarding to a `StateFlow`, so the main thread is fine. Don't "optimize" this to `Default`.
- **`stop()` catches `IllegalStateException`** — `startService` is disallowed from the background on
  Android 8+, and if we're backgrounded the service isn't running anyway.
- **`stopForeground(STOP_FOREGROUND_REMOVE)`** needs no version branch: it exists since API 24 == minSdk.

Manifest requirements (all present, all necessary): `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, and
`android:foregroundServiceType="location"` on the `<service>`.

Notification channel `location_tracking` is created lazily in `ensureChannel` at `IMPORTANCE_LOW`;
copy lives in `strings.xml` under `location_tracking_*`.

## `TrackingStatus` — who sets what

| Value | Set by |
| --- | --- |
| `STOPPED` | `LocationTrackingController` (NONE / `stop()`), `LocationTrackingService.stopTracking` |
| `FOREGROUND_ONLY` | `LocationTrackingController` (WHEN_IN_USE) |
| `BACKGROUND_ACTIVE` | `LocationTrackingService.startTracking`, once collection actually begins |

The controller never claims `BACKGROUND_ACTIVE` — it only requests a start; the service confirms it.
Keep that distinction if you extend the status enum.

## `FusedLocationTracker` notes

- `locationUpdates` is a **cold** `callbackFlow`: the FLP request is created on collection and
  removed in `awaitClose`. The flow's lifecycle *is* the battery lifecycle.
- `.conflate()` means a slow consumer always sees the freshest fix instead of a backlog of stale
  positions — important for low-latency proximity decisions.
- Callbacks are delivered on the main looper and only forward to a channel.
- Permission is the **caller's** responsibility; the class is annotated
  `@SuppressLint("MissingPermission")`.
- `Location.toSample()` maps a missing accuracy to `Float.MAX_VALUE` rather than 0 — so any future
  accuracy gate treats "unknown accuracy" as worst-case, not best-case.

## Common changes

| Change | Edit | Also check |
| --- | --- | --- |
| Background cadence | `LocationRequestConfig.Background` | battery expectations, `LocationRequestConfigTest` |
| Foreground adaptive cadence | `LocationPowerPolicy` | `LocationPowerPolicyTest` |
| Notification copy/icon | `promoteToForeground` + `strings.xml` | channel importance |
| Add a tracking mode | `TrackingStatus` + `LocationTrackingController.sync` | `LocationUiState`, any `when` over the enum |
| Swap the location source | implement `LocationTracker`, wire in `DefaultAppContainer` | nothing else — that's the point of the seam |
| Add an accuracy gate | `ProximityRepository.onLocation` (has `sample.accuracyMeters`) | `LocationSample` already carries it |
