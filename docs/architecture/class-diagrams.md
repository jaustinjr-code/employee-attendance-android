# Class Diagrams

UML class diagrams per package, plus one whole-feature dependency diagram. Members are trimmed to
what matters for understanding relationships — read the KDoc in the source for full signatures.

Notation: `<|--` implements/extends, `*--` composition (owner constructs it), `o--` aggregation
(injected, shared), `-->` uses/depends on.

---

## 1. App shell and dependency injection

```mermaid
classDiagram
    class EmployeeAttendanceApplication {
        +container: AppContainer
        +appForegroundTracker: AppForegroundTracker
        +applicationScope: CoroutineScope
        -startupJob: Job
        +startupComplete: StateFlow~Boolean~
        +onCreate()
        +awaitStarted()
    }

    class AppContainer {
        <<interface>>
        +attendanceRepository: AttendanceRepository
        +workLocationRepository: WorkLocationRepository
        +proximityRepository: ProximityRepository
        +locationFeatureCoordinator: LocationFeatureCoordinator
        +attendanceAutoClockController: AttendanceAutoClockController
        +statusUpdateSettingsStore: StatusUpdateSettingsStore
        +statusUpdateRepository: StatusUpdateRepository
        +statusUpdateCoordinator: StatusUpdateCoordinator
        +clockOutListener: ClockOutListener
    }
    note for AppContainer "members trimmed; see di/AppContainer.kt"

    class DefaultAppContainer {
        -appContext: Context
        -appForegroundTracker: AppForegroundTracker
        -statusUpdateNotifier: StatusUpdateNotifications
    }
    note for DefaultAppContainer "every member is created with by lazy; the tracker is a constructor parameter"

    class MainActivity {
        -pendingStatusUpdateRequest: StatusUpdateRequest?
        +onCreate(Bundle)
        +onNewIntent(Intent)
    }
    note for MainActivity "StartupGate wraps NavHost and StatusUpdateOverlayHost"

    class LocationFeatureCoordinator {
        +start(scope: CoroutineScope)
    }

    AppContainer <|.. DefaultAppContainer
    EmployeeAttendanceApplication *-- DefaultAppContainer
    EmployeeAttendanceApplication *-- AppForegroundTracker : built first
    DefaultAppContainer o-- AppForegroundTracker
    EmployeeAttendanceApplication --> LocationFeatureCoordinator : start in startupJob
    DefaultAppContainer *-- LocationFeatureCoordinator
    DefaultAppContainer *-- StatusUpdateCoordinator
    MainActivity --> EmployeeAttendanceApplication : startupComplete, container via factories
```

`DefaultAppContainer.clockOutListener` is an anonymous `ClockOutListener` that maps
`ClockOutSource` to `StatusUpdateTrigger` and forwards to `statusUpdateCoordinator`. See §7.

---

## 2. `location.permission`

```mermaid
classDiagram
    class LocationAccessLevel {
        <<enumeration>>
        NONE
        WHEN_IN_USE
        ALWAYS
        +isGranted: Boolean
        +supportsBackgroundTracking: Boolean
        +isDegraded: Boolean
    }

    class LocationPermissionState {
        +accessLevel: LocationAccessLevel
        +isPrecise: Boolean
        +isGranted: Boolean
        +supportsBackgroundTracking: Boolean
        +isDegraded: Boolean
        +Denied$
    }

    class LocationPermissions {
        <<object>>
        +FINE: String
        +COARSE: String
        +BACKGROUND: String
        +POST_NOTIFICATIONS: String
        +foreground: Array~String~
        +initialRequest: Array~String~
        +backgroundPermissionExists: Boolean
        +backgroundMustBeRequestedSeparately: Boolean
    }

    class LocationPermissionRepository {
        <<interface>>
        +permissionState: StateFlow~LocationPermissionState~
        +refresh() LocationPermissionState
    }

    class SystemLocationPermissionRepository {
        -appContext: Context
        -readCurrentState() LocationPermissionState
        -isGranted(permission) Boolean
    }

    LocationPermissionState *-- LocationAccessLevel
    LocationPermissionRepository <|.. SystemLocationPermissionRepository
    LocationPermissionRepository --> LocationPermissionState
    SystemLocationPermissionRepository --> LocationPermissions : reads grants
```

`LocationPermissions` is the **only** place Android version checks for the permission model live.
Any new SDK-gated permission rule belongs there, not scattered at call sites.

---

## 3. `location.tracking`

```mermaid
classDiagram
    class LocationSample {
        +latitudeDegrees: Double
        +longitudeDegrees: Double
        +accuracyMeters: Float
        +timestampEpochMillis: Long
    }

    class LocationPriority {
        <<enumeration>>
        HIGH_ACCURACY
        BALANCED
        LOW_POWER
        PASSIVE
        +toGmsPriority() Int
    }

    class LocationRequestConfig {
        +priority: LocationPriority
        +intervalMillis: Long
        +minUpdateIntervalMillis: Long
        +maxUpdateDelayMillis: Long
        +Foreground$
        +Background$
    }
    note for LocationRequestConfig "init block enforces interval invariants"


    class LocationPowerPolicy {
        <<object>>
        +foregroundConfig(proximity) LocationRequestConfig
    }

    class LocationTracker {
        <<interface>>
        +locationUpdates(config) Flow~LocationSample~
        +currentLocation(priority) LocationSample?
    }

    class FusedLocationTracker {
        -client: FusedLocationProviderClient
    }

    class TrackingStatus {
        <<enumeration>>
        STOPPED
        FOREGROUND_ONLY
        BACKGROUND_ACTIVE
    }

    class LocationStateRepository {
        +latestLocation: StateFlow~LocationSample?~
        +trackingStatus: StateFlow~TrackingStatus~
        +publishLocation(sample)
        +updateStatus(status)
    }

    class TrackingServiceLauncher {
        <<interface>>
        +start()
        +stop()
    }

    class DefaultTrackingServiceLauncher

    class LocationTrackingController {
        +sync(permission: LocationPermissionState)
        +stop()
    }

    class LocationTrackingService {
        -serviceScope: CoroutineScope
        -trackingJob: Job?
        +onStartCommand(...) Int
        -startTracking()
        -stopTracking()
        -promoteToForeground()
        +start(context)$
        +stop(context)$
    }

    LocationTracker <|.. FusedLocationTracker
    LocationTracker --> LocationSample
    LocationTracker --> LocationRequestConfig
    LocationRequestConfig *-- LocationPriority
    LocationPowerPolicy --> LocationRequestConfig
    LocationStateRepository *-- TrackingStatus
    LocationStateRepository --> LocationSample
    TrackingServiceLauncher <|.. DefaultTrackingServiceLauncher
    LocationTrackingController o-- TrackingServiceLauncher
    LocationTrackingController o-- LocationStateRepository
    DefaultTrackingServiceLauncher --> LocationTrackingService
    LocationTrackingService --> LocationTracker
    LocationTrackingService --> LocationStateRepository
    LocationTrackingService --> LocationPermissionRepository : re-checks before promoting
```

Two things to internalize here:

- `LocationStateRepository` is the **hand-off point**. Producers write; consumers read. It is why
  the rest of the app never needs to know whether the service or the ViewModel collector is running.
- `LocationTrackingController` holds the entire graceful-degradation policy. `ALWAYS` → start the
  service; `WHEN_IN_USE` → stop the service and report `FOREGROUND_ONLY`; `NONE` → stop and report
  `STOPPED`. It does **not** set `BACKGROUND_ACTIVE` — the service does that itself once it is
  actually collecting.

---

## 4. `location.proximity` and `location.geofence`

```mermaid
classDiagram
    class GeofenceTarget {
        +id: String
        +latitudeDegrees: Double
        +longitudeDegrees: Double
        +radiusMeters: Float
        +MAX_RADIUS_METERS$ Float
    }

    class ProximityState {
        <<enumeration>>
        UNKNOWN
        INSIDE
        OUTSIDE
    }

    class ProximityEvent {
        <<interface>>
        +targetId: String
    }
    class Arrived
    class Departed

    class ProximityCalculator {
        <<object>>
        +distanceMeters(sample, target) Float
        +evaluate(current, distance, radius, exitBuffer) ProximityState
    }

    class ProximityUpdater {
        <<interface>>
        +onLocation(sample, target)
        +reset()
    }

    class ProximityStateStore {
        <<interface>>
        +load() ProximityState
        +loadTargetId() String?
        +save(state, targetId)
    }

    class SharedPrefsProximityStateStore

    class ProximityRepository {
        +proximity: StateFlow~ProximityState~
        +events: SharedFlow~ProximityEvent~
        -lastTargetId: String?
        -exitBufferMeters: Float
        +onGeofenceTransition(targetId, state)
        +onLocation(sample, target)
        +reset()
        -setState(next, targetId)
    }

    class GeofenceRegistrar {
        <<interface>>
        +register(targets: List_GeofenceTarget)
        +clear()
    }

    class GeofenceManager {
        -client: GeofencingClient
        -pendingIntent: PendingIntent
        -NOTIFICATION_RESPONSIVENESS_MILLIS$ Int
    }

    class GeofenceBroadcastReceiver {
        +onReceive(context, intent)
        +ACTION_GEOFENCE_EVENT$
    }

    ProximityEvent <|.. Arrived
    ProximityEvent <|.. Departed
    ProximityUpdater <|.. ProximityRepository
    ProximityStateStore <|.. SharedPrefsProximityStateStore
    ProximityRepository o-- ProximityStateStore
    ProximityRepository --> ProximityCalculator
    ProximityRepository --> ProximityState
    ProximityRepository --> ProximityEvent : emits
    ProximityCalculator --> GeofenceTarget
    GeofenceRegistrar <|.. GeofenceManager
    GeofenceManager --> GeofenceTarget
    GeofenceManager ..> GeofenceBroadcastReceiver : PendingIntent
    GeofenceBroadcastReceiver --> ProximityRepository : onGeofenceTransition
```

`ProximityRepository` is the single most coupled class in the app: two producers, two consumers
(`LocationViewModel`, and the unconsumed `events` seam), plus persistence. Change it carefully and
read `docs/maintenance/change-impact-map.md` first.

---

## 5. `location.registration`

```mermaid
classDiagram
    class WorkLocation {
        +id: String
        +name: String
        +address: String?
        +latitudeDegrees: Double
        +longitudeDegrees: Double
        +radiusMeters: Float
        +toGeofenceTarget() GeofenceTarget
    }
    note for WorkLocation "init block validates id, name, coordinates and radius"


    class WorkLocationRepository {
        <<interface>>
        +workLocations: StateFlow~List_WorkLocation~
        +activeWorkLocation: StateFlow~WorkLocation?~
        +setActiveWorkLocation(id)
        +registerWorkLocation(location)
        +removeWorkLocation(id)
    }

    class StubWorkLocationRepository {
        +DEFAULT_OFFICE$ : WorkLocation
    }
    note for StubWorkLocationRepository "in-memory; mutators are @Synchronized"


    class LocationClockInRepository {
        +lastClockIns: StateFlow~Map_String_Long~
        +recordClockIn(locationId, epochMillis)
    }

    WorkLocationRepository <|.. StubWorkLocationRepository
    WorkLocationRepository --> WorkLocation
    WorkLocation ..> GeofenceTarget : projects to
    LocationClockInRepository ..> WorkLocation : keyed by id
```

`WorkLocation` → `GeofenceTarget` is the boundary between the **domain** model (has a name and
address, is shown to users) and the **geometric** model (what the proximity engine consumes). Keep
display concerns out of `GeofenceTarget`.

---

## 6. Presentation layer

```mermaid
classDiagram
    class LocationUiState {
        +activeWorkLocation: WorkLocation?
        +proximity: ProximityState
        +trackingStatus: TrackingStatus
        +accessLevel: LocationAccessLevel
        +lastClockInEpochMillis: Long?
        +isGranted: Boolean
        +isDegraded: Boolean
        +isSetUp: Boolean
        +canShowMap: Boolean
    }

    class LocationViewModel {
        +uiState: StateFlow~LocationUiState~
        +onClockIn()
        +onClockOut()
        -collectForegroundFixesWhenDegraded()
        +Factory$
    }

    class LocationPermissionPrompt {
        <<enumeration>>
        EnableForeground
        UpgradeToAlways
    }

    class LocationPermissionUiState {
        +permission: LocationPermissionState
        +visiblePrompt: LocationPermissionPrompt?
        +foregroundPermanentlyDenied: Boolean
        +showDegradedNotice: Boolean
        +requiresSettingsForForeground: Boolean
    }

    class LocationPermissionViewModel {
        +uiState: StateFlow~LocationPermissionUiState~
        +onPermissionResult()
        +onForegroundDenied(canRetry)
        +onSetupRequested()
        +onPromptDismissed(prompt)
        -computePrompt(permission, dismissed)
        +Factory$
    }

    class AttendanceViewModel {
        +getTodayDateName() String
    }

    class AttendanceScreen {
        <<composable>>
    }
    class LocationDetailScreen {
        <<composable>>
    }
    class LocationPermissionHost {
        <<composable>>
    }
    note for LocationPermissionHost "owns ActivityResult launchers and the ON_RESUME refresh"

    class LocationSetupChip {
        <<composable>>
    }
    class LocationPill {
        <<composable>>
    }
    class ProximityStatusRow {
        <<composable>>
    }
    class DegradedNotice {
        <<composable>>
    }
    class WorkLocationMapCard {
        <<composable>>
    }
    class LocationPermissionRationaleDialog {
        <<composable>>
    }

    LocationViewModel --> LocationUiState : produces
    LocationViewModel o-- WorkLocationRepository
    LocationViewModel o-- ProximityRepository
    LocationViewModel o-- LocationStateRepository
    LocationViewModel o-- LocationPermissionRepository
    LocationViewModel o-- LocationTracker
    LocationViewModel o-- AttendanceRepository
    LocationViewModel o-- StatusUpdateCoordinator : onClockOut MANUAL
    LocationViewModel --> LocationPowerPolicy

    LocationPermissionViewModel --> LocationPermissionUiState : produces
    LocationPermissionUiState *-- LocationPermissionPrompt
    LocationPermissionViewModel o-- LocationPermissionRepository
    LocationPermissionViewModel --> SavedStateHandle : persists dismissals

    AttendanceScreen --> LocationViewModel
    AttendanceScreen --> AttendanceViewModel
    AttendanceScreen --> LocationPermissionHost
    AttendanceScreen --> LocationSetupChip
    AttendanceScreen --> LocationPill
    LocationDetailScreen --> LocationViewModel
    LocationDetailScreen --> ProximityStatusRow
    LocationDetailScreen --> WorkLocationMapCard
    LocationDetailScreen --> DegradedNotice
    LocationPermissionHost --> LocationPermissionViewModel
    LocationPermissionHost --> LocationPermissionRationaleDialog
```

`LocationUiState`'s derived properties (`isSetUp`, `canShowMap`, `isDegraded`) are where UI policy
lives. Composables branch on those, never on raw `accessLevel` comparisons — keep it that way so a
policy change is a one-line edit.

---

## 7. `statusupdate` and `statusupdate.ui`

```mermaid
classDiagram
    class ClockOutListener {
        <<interface>>
        +onClockOut(locationId, clockOutAtMillis, source: ClockOutSource)
        +onClockOutUndone(locationId, clockOutAtMillis)
    }

    class ClockOutSource {
        <<enumeration>>
        AUTO
        NOTIFICATION_CONFIRMED
    }

    class StatusUpdateTrigger {
        <<enumeration>>
        MANUAL
        AUTO
        NOTIFICATION_CONFIRMED
    }

    class StatusUpdateCoordinator {
        +pendingPrompt: StateFlow~StatusUpdateRequest?~
        +undoneClockOuts: SharedFlow~String~
        +onClockOut(locationId, clockOutAtMillis, trigger)
        +acceptPrompt() StatusUpdateRequest?
        +dismissPrompt()
        +claimNotificationRequest(request) StatusUpdateRequest?
        +onClockOutUndone(locationId, clockOutAtMillis)
        +complete(request, didToday, plannedTomorrow, couldNotDo)
    }

    class AppForegroundTracker {
        <<interface>>
        +isForeground: StateFlow~Boolean~
    }
    class DefaultAppForegroundTracker

    class StatusUpdateNotifications {
        <<interface>>
        +notifyPending(request)
        +cancel(request)
    }
    class StatusUpdateNotifier

    class StatusUpdateRepository {
        <<interface>>
        +statusUpdates: StateFlow~List_StatusUpdate~
        +save(update)
        +updateAnswers(clockOutId, didToday, plannedTomorrow, couldNotDo, editedAtMillis) Boolean
        +clearAll()
    }
    class DefaultStatusUpdateRepository
    class StatusUpdateLocalDataSource {
        <<interface>>
        +load() List_StatusUpdate
        +save(updates)
    }
    class SharedPrefsStatusUpdateLocalDataSource
    note for SharedPrefsStatusUpdateLocalDataSource "SecurePreferences file status_updates"

    class StatusUpdateHistoryViewModel {
        +days: StateFlow~List_StatusUpdateDay~
    }
    class StatusUpdateDetailViewModel {
        +shift: StateFlow~StatusUpdateShift~
    }
    class StatusUpdateEditViewModel {
        +uiState: StateFlow~StatusUpdateEditUiState~
        +onDraftChanged(index, value)
        +onExitRequested()
        +onDiscardDialogDismissed()
        +save() Boolean
    }

    class StatusUpdateIntents {
        <<object>>
        +consumeRequest(intent) StatusUpdateRequest?
        +putExtras(intent, request) Intent
    }

    class StatusUpdateRequest {
        +locationId: String
        +clockOutAtMillis: Long
        +clockOutId: String
    }

    class StatusUpdateOverlayViewModel {
        +uiState: StateFlow~StatusUpdateOverlayUiState~
        +onBeginPrompt()
        +onNotificationRequest(request)
        +onForward()
        +onBack()
        +onDismissDeck()
        +Factory$
    }

    class StatusUpdateOverlayHost {
        <<composable>>
    }
    class StatusUpdateCardStack {
        <<composable>>
    }

    ClockOutListener --> ClockOutSource
    AppForegroundTracker <|.. DefaultAppForegroundTracker
    StatusUpdateNotifications <|.. StatusUpdateNotifier
    StatusUpdateRepository <|.. DefaultStatusUpdateRepository
    StatusUpdateLocalDataSource <|.. SharedPrefsStatusUpdateLocalDataSource
    DefaultStatusUpdateRepository o-- StatusUpdateLocalDataSource
    StatusUpdateHistoryViewModel o-- StatusUpdateRepository
    StatusUpdateDetailViewModel o-- StatusUpdateRepository
    StatusUpdateEditViewModel o-- StatusUpdateRepository
    StatusUpdateCoordinator --> StatusUpdateTrigger
    StatusUpdateCoordinator o-- AppForegroundTracker
    StatusUpdateCoordinator o-- StatusUpdateNotifications
    StatusUpdateCoordinator o-- StatusUpdateRepository
    StatusUpdateCoordinator o-- AttendanceRepository : hasClockOutEvent, clockInBefore
    StatusUpdateCoordinator --> StatusUpdateRequest
    StatusUpdateNotifier --> StatusUpdateIntents : putExtras
    StatusUpdateOverlayViewModel o-- StatusUpdateCoordinator
    StatusUpdateOverlayHost --> StatusUpdateOverlayViewModel
    StatusUpdateOverlayHost --> StatusUpdateCardStack : inside Dialog
```

`ClockOutListener` and `ClockOutSource` live in `attendance/ClockNotificationStrategy.kt`, not in
`statusupdate`, so `attendance/` never imports `statusupdate`. The coordinator also takes
`enabled: StateFlow<Boolean>` (from `StatusUpdateSettingsStore`), an injectable `clock`, and a
`worksiteName` lookup. The history classes are in `statusupdate.history`; the screens that use them
(`AccountScreen`, `StatusUpdateDetailScreen`, `StatusUpdateEditScreen`) are described in
[../features/account.md](../features/account.md).
`StatusUpdatePromptDialog` and `StatusUpdateOverlayContent` are omitted; both are stateless.

---

## 8. Whole-feature dependency graph

The single most useful picture for impact analysis: who depends on whom across the location and
Status Update features.

```mermaid
graph LR
    LPR[LocationPermissionRepository]
    WLR[WorkLocationRepository]
    LSR[LocationStateRepository]
    PR[ProximityRepository]
    AR[AttendanceRepository]
    SUC[StatusUpdateCoordinator]
    SUR[StatusUpdateRepository]
    AFT[AppForegroundTracker]
    COL[ClockOutListener]
    CAR[ClockActionReceiver]
    ACC[AttendanceAutoClockController]
    SUOVM[StatusUpdateOverlayViewModel]
    HISTVM[StatusUpdateHistory/Detail/EditViewModel]
    LT[LocationTracker]
    LTC[LocationTrackingController]
    GR[GeofenceRegistrar/GeofenceManager]
    LFC[LocationFeatureCoordinator]
    LTS[LocationTrackingService]
    GBR[GeofenceBroadcastReceiver]
    LVM[LocationViewModel]
    LPVM[LocationPermissionViewModel]
    HOST[LocationPermissionHost]
    AS[AttendanceScreen]
    LDS[LocationDetailScreen]

    LFC --> LPR
    LFC --> WLR
    LFC --> LTC
    LFC --> GR
    LFC --> LSR
    LFC --> PR
    LTC --> LSR
    LTS --> LT
    LTS --> LSR
    LTS --> LPR
    GBR --> PR
    GR -.->|PendingIntent| GBR
    LVM --> WLR
    LVM --> PR
    LVM --> LSR
    LVM --> LPR
    LVM --> LT
    LVM --> AR
    LVM --> SUC
    COL --> SUC
    HISTVM --> SUR
    CAR --> AR
    CAR --> COL
    ACC --> AR
    ACC --> COL
    SUC --> AR
    SUC --> SUR
    SUC --> AFT
    SUOVM --> SUC
    LPVM --> LPR
    HOST --> LPVM
    AS --> LVM
    AS --> HOST
    LDS --> LVM

    classDef hub fill:#fde68a,stroke:#b45309,color:#111
    class LPR,PR,LSR,WLR,AR,SUC hub
```

The amber nodes are **hubs** — three or more dependents each. A behavior change in any of them
propagates widely; see [../maintenance/change-impact-map.md](../maintenance/change-impact-map.md).
