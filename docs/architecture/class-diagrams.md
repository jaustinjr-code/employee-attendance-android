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
        -applicationScope: CoroutineScope
        +onCreate()
    }

    class AppContainer {
        <<interface>>
        +locationPermissionRepository: LocationPermissionRepository
        +locationTracker: LocationTracker
        +locationStateRepository: LocationStateRepository
        +locationTrackingController: LocationTrackingController
        +proximityRepository: ProximityRepository
        +geofenceManager: GeofenceManager
        +workLocationRepository: WorkLocationRepository
        +locationClockInRepository: LocationClockInRepository
        +locationFeatureCoordinator: LocationFeatureCoordinator
    }

    class DefaultAppContainer {
        -appContext: Context
    }
    note for DefaultAppContainer "every member is created with `by lazy`"


    class MainActivity {
        +onCreate(Bundle)
    }
    note for MainActivity "NavHost: Attendance to LocationDetail"


    class LocationFeatureCoordinator {
        +start(scope: CoroutineScope)
        -reconcileTracking(permission, activeLocation)
    }

    AppContainer <|.. DefaultAppContainer
    EmployeeAttendanceApplication *-- DefaultAppContainer
    EmployeeAttendanceApplication --> LocationFeatureCoordinator : start(applicationScope)
    DefaultAppContainer *-- LocationFeatureCoordinator
    MainActivity --> EmployeeAttendanceApplication : container via ViewModel factories
```

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
    LocationViewModel o-- LocationClockInRepository
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

## 7. Whole-feature dependency graph

The single most useful picture for impact analysis: who depends on whom across the location feature.

```mermaid
graph LR
    LPR[LocationPermissionRepository]
    WLR[WorkLocationRepository]
    LSR[LocationStateRepository]
    PR[ProximityRepository]
    LCIR[LocationClockInRepository]
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
    LVM --> LCIR
    LPVM --> LPR
    HOST --> LPVM
    AS --> LVM
    AS --> HOST
    LDS --> LVM

    classDef hub fill:#fde68a,stroke:#b45309,color:#111
    class LPR,PR,LSR,WLR hub
```

The four amber nodes are **hubs** — three or more dependents each. A behavior change in any of them
propagates widely; see [../maintenance/change-impact-map.md](../maintenance/change-impact-map.md).
