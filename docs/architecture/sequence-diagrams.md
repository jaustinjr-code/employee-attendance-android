# Sequence Diagrams

The runtime flows that are hard to reconstruct by reading files in isolation. Each one names the
files involved so you can jump straight to the code.

---

## 1. App startup and coordinator wiring

**Files:** `EmployeeAttendanceApplication.kt`, `di/AppContainer.kt`, `statusupdate/AppForegroundTracker.kt`,
`ui/main/StartupGate.kt`, `MainActivity.kt`

```mermaid
sequenceDiagram
    autonumber
    participant OS as Android
    participant App as EmployeeAttendanceApplication
    participant AFT as DefaultAppForegroundTracker
    participant C as DefaultAppContainer
    participant IO as startupJob on Dispatchers.IO
    participant MA as MainActivity

    OS->>App: onCreate() on main
    App->>AFT: DefaultAppForegroundTracker(this)
    Note over AFT: registers ActivityLifecycleCallbacks<br/>before any onStart can fire
    App->>C: DefaultAppContainer(this, appForegroundTracker)
    Note over C: every member is by lazy, nothing constructed yet
    App->>IO: applicationScope.launch(Dispatchers.IO)
    OS->>MA: onCreate()
    MA->>MA: consumeRequest(intent) if savedInstanceState == null
    MA->>MA: StartupGate(started = false) shows StartupScreen
    IO->>C: attendanceAutoClockController.start, then awaitSubscribed()
    IO->>C: locationFeatureCoordinator.start(applicationScope)
    IO->>C: force privacySettingsStore, userProfileStore, statusUpdateSettingsStore
    IO-->>App: job completes (success or failure)
    App-->>MA: startupComplete = true
    MA->>C: ViewModel factories run inside StartupGate
```

The coordinators start **before** any UI exists and keep running when the UI is gone. The UI waits on
`startupComplete` so no factory constructs an encrypted store on the main thread (issue #58).

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

## 7. Manual clock-out from the attendance screen

**Files:** `location/ui/LocationViewModel.kt`, `attendance/AttendanceRepository.kt`,
`statusupdate/StatusUpdateCoordinator.kt`

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant VM as LocationViewModel
    participant AR as AttendanceRepository
    participant SUC as StatusUpdateCoordinator

    U->>VM: onClockOut()
    VM->>AR: recordIfStateChanges(id, CLOCK_OUT, source = MANUAL)
    alt already clocked out
        AR-->>VM: null
        Note over VM: return, no Status Update
    else recorded
        AR-->>VM: AttendanceEvent
        VM->>SUC: onClockOut(id, event.epochMillis, MANUAL)
    end
```

`id` is the active worksite's id, or `AttendanceRepository.GENERAL_TIMECLOCK_ID` when none is active.
What the coordinator does next is §10.

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

---

## 9. Switching to the Reports tab

**Files:** `MainActivity.kt`, `ui/main/MainBottomBar.kt`, `ui/reports/ReportsViewModel.kt`
    participant Bar as MainBottomBar
    participant Nav as NavHostController
    participant RS as ReportsScreen
    participant RVM as ReportsViewModel
    participant RG as ReportGenerator

    U->>Bar: tap Reports
    Bar->>Nav: navigateToTab(Reports)<br/>popUpTo(start){saveState}, restoreState
    Nav->>RS: composable<Reports>
    RS->>RVM: viewModel() — created on first visit, restored after
    RS->>RVM: collectAsStateWithLifecycle(selection, report, biweekly)
    RVM->>RG: report(period, eventLog, workLocations) on Dispatchers.Default
    RG-->>RVM: cached if the same period and list instances
    RVM-->>RS: ReportSection.Ready
    U->>Bar: tap Attendance
    Bar->>Nav: navigateToTab(Attendance) — Reports entry saved, not destroyed
```

The biweekly notification flow is drawn in
[../features/reporting.md](../features/reporting.md#biweekly-notification).

---

## 10. Status Update after a clock-out

**Files:** `attendance/ClockNotificationStrategy.kt`, `attendance/ClockActionReceiver.kt`,
`di/AppContainer.kt`, `statusupdate/StatusUpdateCoordinator.kt`, `statusupdate/StatusUpdateNotifier.kt`,
`statusupdate/StatusUpdateIntents.kt`, `MainActivity.kt`, `statusupdate/ui/StatusUpdateOverlayViewModel.kt`

### 10a. Choosing prompt or notification

```mermaid
sequenceDiagram
    autonumber
    participant P as Clock-out producer
    participant COL as AppContainer.clockOutListener
    participant SUC as StatusUpdateCoordinator
    participant AFT as AppForegroundTracker
    participant N as StatusUpdateNotifier
    participant VM as StatusUpdateOverlayViewModel

    alt manual (LocationViewModel)
        P->>SUC: onClockOut(id, at, MANUAL)
    else auto (GuardedClockStrategy.record) or Confirm (ClockActionHandler)
        P->>COL: onClockOut(id, at, AUTO or NOTIFICATION_CONFIRMED)
        COL->>SUC: onClockOut(id, at, mapped StatusUpdateTrigger)
    end
    alt enabled is false
        SUC-->>SUC: return
    else MANUAL, or AUTO while isForeground
        SUC->>AFT: isForeground.value
        SUC-->>VM: pendingPrompt = request
        VM-->>VM: StatusUpdatePromptDialog shows
    else AUTO in background, or NOTIFICATION_CONFIRMED
        SUC->>N: notifyPending(request)
    end
```

### 10b. Opening the deck from the notification

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant N as StatusUpdateNotifier
    participant MA as MainActivity
    participant SI as StatusUpdateIntents
    participant VM as StatusUpdateOverlayViewModel
    participant SUC as StatusUpdateCoordinator
    participant AR as AttendanceRepository
    participant SUR as StatusUpdateRepository

    U->>N: tap "Status update waiting"
    N->>MA: PendingIntent with request extras
    alt cold start
        MA->>SI: consumeRequest(intent) in onCreate
    else already running (singleTop)
        MA->>SI: consumeRequest(intent) in onNewIntent
    end
    SI-->>MA: request, extras removed (null if launched from history)
    Note over MA: held in pendingStatusUpdateRequest<br/>until StartupGate opens
    MA->>VM: onNotificationRequest(request) via StatusUpdateOverlayHost
    VM->>SUC: claimNotificationRequest(request)
    SUC->>AR: hasClockOutEvent(locationId, clockOutAtMillis)
    SUC->>SUR: statusUpdates.value has clockOutId?
    alt real clock-out, not yet answered
        SUC-->>VM: request
        VM-->>MA: deck opens in a full-screen Dialog
    else forged or already answered
        SUC-->>VM: null, no deck
    end
    U->>VM: Next, Next, Done
    VM->>SUC: complete(request, three answers)
    SUC->>SUR: save(StatusUpdate)
```

### 10c. Undo

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant H as ClockActionHandler
    participant AR as AttendanceRepository
    participant COL as AppContainer.clockOutListener
    participant SUC as StatusUpdateCoordinator
    participant N as StatusUpdateNotifier
    participant VM as StatusUpdateOverlayViewModel

    U->>H: Undo on a clock-out notification
    H->>AR: undoEvent(locationId, CLOCK_OUT, epochMillis)
    alt already gone or superseded
        AR-->>H: false
    else undone
        AR-->>H: true
        H->>COL: onClockOutUndone(locationId, epochMillis)
        COL->>SUC: onClockOutUndone(locationId, epochMillis)
        SUC->>SUC: clear pendingPrompt if it matches
        SUC->>N: cancel(request)
        SUC-->>VM: undoneClockOuts emits clockOutId
        VM->>VM: close the deck if open for that clock-out, without saving
    end
```

Dismissing the prompt, the notification, or the deck drops the request. Nothing resurfaces it.
See [../features/status-updates.md](../features/status-updates.md).
