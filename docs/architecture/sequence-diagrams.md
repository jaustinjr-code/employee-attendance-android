# Sequence Diagrams

The runtime flows that are hard to reconstruct by reading files in isolation. Each one names the
files involved so you can jump straight to the code.

---

## 1. App startup and coordinator wiring

**Files:** `EmployeeAttendanceApplication.kt`, `di/AppContainer.kt`,
`location/LocationFeatureCoordinator.kt`, `MainActivity.kt`

```mermaid
sequenceDiagram
    autonumber
    participant OS as Android
    participant App as EmployeeAttendanceApplication
    participant C as DefaultAppContainer
    participant LFC as LocationFeatureCoordinator
    participant LPR as LocationPermissionRepository
    participant WLR as WorkLocationRepository
    participant MA as MainActivity

    OS->>App: onCreate()
    App->>C: DefaultAppContainer(this)
    Note over C: every member is `by lazy` —<br/>nothing constructed yet
    App->>LFC: start(applicationScope)
    activate LFC
    Note over C: touching locationFeatureCoordinator<br/>forces creation of LPR, WLR, LTC,<br/>GeofenceManager, LSR, ProximityRepository
    LFC->>LPR: collect permissionState
    LFC->>WLR: collect activeWorkLocation
    Note over LFC: pipeline 1 = combine(permission, activeLocation)<br/>pipeline 2 = combine(latestFix, activeLocation)
    deactivate LFC

    OS->>MA: onCreate()
    MA->>MA: setContent { NavHost(startDestination = Attendance) }
    MA->>C: LocationViewModel.Factory / LocationPermissionViewModel.Factory
    Note over MA: both ViewModels are Activity-scoped and<br/>shared by the Attendance + LocationDetail destinations
```

The coordinator starts **before** any UI exists and keeps running when the UI is gone. That is why
permission changes made in system Settings still reconcile tracking.

---

## 2. Permission request — first grant

**Files:** `location/ui/LocationPermissionHost.kt`, `location/ui/LocationPermissionViewModel.kt`,
`location/permission/LocationPermissionRepository.kt`, `location/LocationFeatureCoordinator.kt`

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant Chip as LocationSetupChip
    participant VM as LocationPermissionViewModel
    participant Host as LocationPermissionHost
    participant Sys as Android permission UI
    participant Repo as SystemLocationPermissionRepository
    participant LFC as LocationFeatureCoordinator
    participant LTC as LocationTrackingController

    U->>Chip: tap "Set Up Location"
    Chip->>VM: onSetupRequested()
    Note over VM: clears per-session dismissals<br/>(also cleared in SavedStateHandle)
    VM-->>Host: uiState.visiblePrompt = EnableForeground
    Host->>U: rationale dialog ("Enable Auto Clock-In?")

    alt not permanently denied
        U->>Host: confirm
        Host->>VM: onPromptDismissed(EnableForeground)
        Note right of Host: suppress rationale BEFORE handing off,<br/>so a denial doesn't immediately re-nag
        Host->>Sys: launch(LocationPermissions.initialRequest)
        Note over Sys: FINE + COARSE (+ POST_NOTIFICATIONS on API 33+)
        Sys-->>Host: result map
        Host->>VM: onPermissionResult()
        VM->>Repo: refresh()
        Repo-->>VM: LocationPermissionState(WHEN_IN_USE, isPrecise)
        opt denied
            Host->>Host: shouldShowRequestPermissionRationale(FINE)
            Host->>VM: onForegroundDenied(canRetry)
            Note over VM: canRetry == false ⇒ permanent denial,<br/>persisted to SavedStateHandle
        end
    else requiresSettingsForForeground
        U->>Host: confirm ("Open Settings")
        Host->>Sys: ACTION_APPLICATION_DETAILS_SETTINGS
    end

    Repo-->>LFC: permissionState emits
    LFC->>LTC: sync(permission)
    Note over LTC: WHEN_IN_USE ⇒ stop service,<br/>status = FOREGROUND_ONLY
    LFC->>LFC: geofenceRegistrar.clear()
```

---

## 3. Upgrading to "Allow all the time"

**Files:** same as above, plus `location/geofence/GeofenceManager.kt`,
`location/tracking/LocationTrackingService.kt`

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant Host as LocationPermissionHost
    participant VM as LocationPermissionViewModel
    participant Sys as System Settings
    participant LC as Lifecycle
    participant Repo as SystemLocationPermissionRepository
    participant LFC as LocationFeatureCoordinator
    participant LTC as LocationTrackingController
    participant Svc as LocationTrackingService
    participant GM as GeofenceManager

    Note over VM: permission.isDegraded && backgroundPermissionExists<br/>⇒ visiblePrompt = UpgradeToAlways
    Host->>U: "Allow Location All the Time?"
    U->>Host: confirm

    alt API 30+ (backgroundMustBeRequestedSeparately)
        Host->>Sys: open app settings
        U->>Sys: choose "Allow all the time"
        Sys-->>LC: app resumes
        LC->>VM: ON_RESUME ⇒ onPermissionResult()
    else API 29
        Host->>Sys: backgroundLauncher.launch(BACKGROUND)
        Sys-->>Host: result
        Host->>VM: onPermissionResult()
    end

    VM->>Repo: refresh()
    Repo-->>LFC: LocationPermissionState(ALWAYS)
    LFC->>LTC: sync(ALWAYS)
    LTC->>Svc: startForegroundService()
    activate Svc
    Svc->>Repo: refresh().supportsBackgroundTracking?
    Svc->>Svc: promoteToForeground() — notification + FGS_TYPE_LOCATION
    Svc->>Svc: status = BACKGROUND_ACTIVE, collect Background config
    deactivate Svc
    LFC->>GM: register([activeLocation.toGeofenceTarget()])
    GM->>GM: clear() then addGeofences(INITIAL_TRIGGER_ENTER)
```

The `ON_RESUME` re-read in `LocationPermissionHost` is what makes the Settings round-trip work. If
you remove that `DisposableEffect`, the app will never notice a background grant.

---

## 4. Background proximity via OS geofences (the `ALWAYS` path)

**Files:** `location/geofence/GeofenceManager.kt`, `location/geofence/GeofenceBroadcastReceiver.kt`,
`location/proximity/ProximityRepository.kt`, `location/proximity/ProximityStateStore.kt`

```mermaid
sequenceDiagram
    autonumber
    participant OS as Play Services geofencing
    participant BR as GeofenceBroadcastReceiver
    participant App as EmployeeAttendanceApplication
    participant PR as ProximityRepository
    participant Store as SharedPrefsProximityStateStore
    participant VM as LocationViewModel
    participant UI as LocationDetailScreen

    Note over OS: user crosses the 150 m radius —<br/>delivery batched within the 120 s responsiveness window
    OS->>BR: broadcast ACTION_GEOFENCE_EVENT
    activate BR
    BR->>BR: GeofencingEvent.fromIntent then guard null / hasError
    BR->>BR: map ENTER/DWELL to INSIDE and EXIT to OUTSIDE
    BR->>App: read container.proximityRepository
    Note right of App: if the process was cold-started for this<br/>broadcast, ProximityRepository seeds from Store
    App->>Store: load and loadTargetId
    BR->>PR: onGeofenceTransition requestId + state
    deactivate BR

    activate PR
    Note over PR: @Synchronized setState — main thread here<br/>and a background thread on the foreground path
    alt state unchanged
        PR-->>PR: return (dedupe)
    else changed
        PR->>Store: save next state + targetId
        PR->>PR: emit Arrived / Departed on `events`
    end
    deactivate PR

    PR-->>VM: proximity StateFlow emits
    VM-->>UI: LocationUiState.proximity drives ProximityStatusRow
```

`ProximityEvent`s currently have **no subscriber**. They are the deliberate integration seam for
auto clock-in — see [../features/proximity-and-geofencing.md](../features/proximity-and-geofencing.md).

---

## 5. Foreground proximity (the `WHEN_IN_USE` degraded path)

**Files:** `location/ui/LocationViewModel.kt`, `location/tracking/LocationPowerPolicy.kt`,
`location/tracking/LocationTracker.kt`, `location/LocationFeatureCoordinator.kt`,
`location/proximity/ProximityCalculator.kt`

```mermaid
sequenceDiagram
    autonumber
    participant VM as LocationViewModel
    participant Policy as LocationPowerPolicy
    participant Tracker as FusedLocationTracker
    participant LSR as LocationStateRepository
    participant LFC as LocationFeatureCoordinator
    participant PR as ProximityRepository
    participant Calc as ProximityCalculator

    Note over VM: init { collectForegroundFixesWhenDegraded() }
    VM->>VM: combine isDegraded + proximity then distinctUntilChanged
    alt not degraded (ALWAYS or NONE)
        VM-->>VM: idle — service and geofences already cover it
    else degraded (WHEN_IN_USE)
        VM->>Policy: foregroundConfig for current proximity
        Note right of Policy: UNKNOWN → HIGH_ACCURACY 5 s<br/>INSIDE → BALANCED 30 s<br/>OUTSIDE → LOW_POWER 60 s / 120 s batch
        Policy-->>VM: LocationRequestConfig
        VM->>Tracker: collect locationUpdates for that config
        loop each fix
            Tracker-->>VM: LocationSample — conflated
            VM->>LSR: publishLocation sample
            LSR-->>LFC: latestLocation emits
            LFC->>PR: onLocation with fix + activeLocation target
            PR->>Calc: distanceMeters then evaluate with a 50 m exit buffer
            Calc-->>PR: next ProximityState
            PR->>PR: setState — dedupe then persist then emit
        end
        Note over VM: a proximity change re-emits upstream so<br/>collectLatest cancels and restarts the stream<br/>with the new cadence
    end
```

This is the feedback loop that makes power usage adaptive: proximity picks the cadence, the cadence
produces fixes, the fixes update proximity. `collectLatest` is what makes the restart safe.

---

## 6. Reconciliation when the active work location changes

**Files:** `location/LocationFeatureCoordinator.kt`, `location/registration/WorkLocationRepository.kt`

```mermaid
sequenceDiagram
    autonumber
    participant WLR as WorkLocationRepository
    participant LFC as LocationFeatureCoordinator
    participant LTC as LocationTrackingController
    participant GR as GeofenceRegistrar
    participant PR as ProximityRepository

    WLR-->>LFC: activeWorkLocation emits a new value or null
    Note over LFC: collectLatest cancels any in-flight reconcile<br/>so clear and register cannot interleave

    LFC->>LTC: sync with current permission

    alt background allowed and a location is active
        LFC->>GR: register the single target
        GR->>GR: clear then addGeofences
    else
        LFC->>GR: clear all geofences
    end
    Note over LFC: register and clear are wrapped in try/catch —<br/>CancellationException is rethrown and others are logged.<br/>Play Services can be unavailable so we degrade instead of crashing.

    par pipeline 2
        alt activeLocation == null
            LFC->>PR: reset
            Note over PR: emits Departed if it was INSIDE
        else a fix is available
            LFC->>PR: onLocation with fix + target
        end
    end
```

---

## 7. Clock in from the attendance screen

**Files:** `ui/attendance/AttendanceScreen.kt`, `location/ui/LocationViewModel.kt`,
`location/registration/LocationClockInRepository.kt`, `location/ui/LocationDetailScreen.kt`

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant TC as TimeCheck composable
    participant VM as LocationViewModel
    participant WLR as WorkLocationRepository
    participant CIR as LocationClockInRepository
    participant LDS as LocationDetailScreen

    U->>TC: tap "Clock in"
    TC->>TC: set isClockedIn true and record clockInTime in rememberSaveable
    TC->>VM: onClockIn
    VM->>WLR: read activeWorkLocation
    alt an active location exists
        VM->>CIR: recordClockIn for location.id at now
        CIR-->>VM: lastClockIns StateFlow emits
        VM-->>LDS: LocationUiState.lastClockInEpochMillis
        LDS->>U: "Last clocked in: Sun, May 24 at 9:05 AM"
    else no active location
        VM-->>VM: no-op
    end

    U->>TC: tap "Clock out"
    TC->>TC: set isClockedIn false and record clockOutTime
    Note right of TC: clock-out is NOT recorded anywhere —<br/>it stays in local composable state
```

Note the asymmetry: clock-**in** reaches a repository, clock-**out** does not. Clock state also
lives in `rememberSaveable` inside the composable rather than in a ViewModel, so it is lost on
process death. Both are known gaps listed in
[../features/attendance.md](../features/attendance.md).

---

## 8. Navigation between destinations

**Files:** `MainActivity.kt`, `ui/attendance/AttendanceScreen.kt`, `ui/main/MainAppBar.kt`

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant AS as AttendanceScreen
    participant Nav as NavHostController
    participant MA as MainActivity
    participant Bar as MainAppBar
    participant LDS as LocationDetailScreen

    Note over AS: LocationUiState.isSetUp ⇒ LocationPill,<br/>otherwise LocationSetupChip
    U->>AS: tap LocationPill
    AS->>Nav: navigate(LocationDetail)
    Nav->>MA: composable<LocationDetail>
    MA->>MA: LaunchedEffect(title) { appBarTitle = R.string.location_detail_title }
    MA->>Bar: MainAppBar(appBarTitle)
    MA->>LDS: LocationDetailScreen(viewModel = shared LocationViewModel)

    U->>MA: system back
    Nav->>MA: composable<Attendance>
    MA->>MA: LaunchedEffect(Unit) { appBarTitle = "Attendance" }
```

Destinations are type-safe `@Serializable` objects (`Attendance`, `LocationDetail`) declared at the
top of `MainActivity.kt`, using Navigation-Compose's Kotlin-serialization routes. The app bar title
is Activity-level state driven by `LaunchedEffect` in each destination — add a destination and you
must set the title there too. Note the Attendance title is a hardcoded string while the detail
title comes from `strings.xml`.
