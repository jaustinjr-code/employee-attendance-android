# Feature: Location Permissions

The runtime permission ladder — no access → "While using the app" → "Allow all the time" — plus the
rationale dialogs, permanent-denial handling, and the Settings round-trip.

This is the most Android-version-sensitive part of the codebase. Read
[the sequence diagrams](../architecture/sequence-diagrams.md#2-permission-request--first-grant)
alongside it.

## Code map

| File | Role |
| --- | --- |
| `location/permission/LocationPermission.kt` | `LocationAccessLevel`, `LocationPermissionState`, `LocationPermissions` (all version rules) |
| `location/permission/LocationPermissionRepository.kt` | interface + `SystemLocationPermissionRepository` |
| `location/ui/LocationPermissionViewModel.kt` | `LocationPermissionPrompt`, `LocationPermissionUiState`, prompt policy |
| `location/ui/LocationPermissionHost.kt` | launchers, `ON_RESUME` refresh, Settings intents, dialog selection |
| `location/ui/LocationPermissionDialog.kt` | the stateless Material 3 rationale dialog |
| `location/ui/LocationComponents.kt` | `LocationSetupChip`, `DegradedNotice` |
| `AndroidManifest.xml` | the five `uses-permission` declarations |
| `res/values/strings.xml` | all rationale copy (`location_permission_*`) |

Tests: `test/.../permission/LocationAccessLevelTest.kt`,
`test/.../ui/LocationPermissionViewModelTest.kt`,
`androidTest/.../permission/SystemLocationPermissionRepositoryTest.kt`,
`androidTest/.../ui/LocationPermissionDialogTest.kt`.

## The model

```
LocationAccessLevel:  NONE ──→ WHEN_IN_USE ──→ ALWAYS
                       │            │             │
                  isGranted=false   isDegraded   supportsBackgroundTracking
```

`LocationPermissionState` adds `isPrecise` (fine vs. approximate, relevant on Android 12+) and
delegates the three booleans to the access level. Consumers branch on the **derived booleans**, not
on enum equality — that keeps a future access level from requiring edits everywhere.

## Version rules — all in one object

`LocationPermissions` centralizes every SDK check. Do not add `Build.VERSION.SDK_INT` comparisons
about permissions anywhere else.

| Property | Rule |
| --- | --- |
| `initialRequest` | FINE + COARSE, plus `POST_NOTIFICATIONS` on API 33+ |
| `backgroundPermissionExists` | API 29+ (below that, a foreground grant already covers background) |
| `backgroundMustBeRequestedSeparately` | API 30+ (background can only be granted from Settings) |

Consequences you will trip over:

- On API < 29, `SystemLocationPermissionRepository` reports `ALWAYS` for any foreground grant. The
  `UpgradeToAlways` prompt is therefore suppressed by `backgroundPermissionExists`.
- On API 29 exactly, background can still be granted by a runtime dialog — that is the only reason
  `backgroundLauncher` exists in `LocationPermissionHost`.
- `POST_NOTIFICATIONS` is bundled into the *first* request because the foreground-service
  notification is the transparency signal for background location. Drop it and Android 13+ users get
  silent background tracking.

## No push notifications from the platform

Android never tells the app that a permission changed. The state is **pull-based** via
`LocationPermissionRepository.refresh()`, called from exactly three places:

1. `LocationPermissionHost`'s `DisposableEffect` on every `Lifecycle.Event.ON_RESUME`.
2. Each permission launcher callback.
3. `LocationTrackingService.startTracking()` before promoting to a foreground service.

If you find permission state going stale, one of those three is the culprit.

## Prompt policy

`LocationPermissionViewModel.computePrompt` is the whole policy:

```
!isGranted && EnableForeground not dismissed          → EnableForeground
isDegraded && backgroundPermissionExists
             && UpgradeToAlways not dismissed          → UpgradeToAlways
otherwise                                              → null
```

Two pieces of state modify it, both persisted in `SavedStateHandle` so they survive process death:

- **`dismissedPrompts`** — "Maybe Later" suppresses that prompt. `onSetupRequested()` (the chip tap)
  clears all dismissals, which is the user's way of asking to see it again.
- **`foregroundPermanentlyDenied`** — set when `shouldShowRequestPermissionRationale` returns false
  after a denial. It makes `requiresSettingsForForeground` true, which swaps the dialog's body and
  confirm label to the Settings path. It is cleared automatically once access is granted.

`LocationPermissionHost` calls `onPromptDismissed(...)` **before** launching the system prompt. That
is deliberate: without it, a denial would leave the rationale visible and immediately re-nag.

## Degraded mode

`WHEN_IN_USE` is a supported, functional state, not an error:

- `LocationSetupChip` reads "Limited Location Access".
- `LocationDetailScreen` hides the map (`canShowMap` requires `supportsBackgroundTracking`) and
  shows `MapLockedNotice` plus `DegradedNotice`.
- No foreground service, no geofences; `LocationViewModel` runs its own foreground fix collector.

## Impact of changes here

Editing `LocationAccessLevel` or `LocationPermissionState` reaches:
`LocationTrackingController.sync`, `LocationFeatureCoordinator.reconcileTracking`,
`LocationViewModel` (both `uiState` and the degraded-gated collector),
`LocationPermissionViewModel.computePrompt`, `LocationUiState`'s derived flags, `LocationSetupChip`,
and `LocationTrackingService.startTracking`. See
[../maintenance/change-impact-map.md](../maintenance/change-impact-map.md).

## Testing notes

- `LocationPermissionViewModelTest` uses a fake `LocationPermissionRepository` and a real
  `SavedStateHandle`, including two tests that recreate the ViewModel to prove persistence.
- Version-gated behavior (`backgroundMustBeRequestedSeparately`) is not unit-testable —
  `Build.VERSION.SDK_INT` is read directly. Verify those branches on emulators at API 29, 30, and
  33+.
- `SystemLocationPermissionRepositoryTest` is instrumented because it reads real grants.
