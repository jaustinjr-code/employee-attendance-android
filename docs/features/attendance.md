# Feature: Attendance (home screen)

The app's start destination. Greets the user, shows a live clock, provides the clock in/out button,
and hosts the single location control.

## Code map

| File | Role |
| --- | --- |
| `ui/attendance/AttendanceScreen.kt` | stateful wrapper + stateless content + `Greeting`, `TimeCheck`, `LiveClock`, previews |
| `ui/attendance/AttendanceViewModel.kt` | one method: `getTodayDateName()` |
| `ui/main/MainAppBar.kt` | top app bar with profile/settings icon buttons |
| `MainActivity.kt` | NavHost, app bar title state, shared ViewModel creation |
| `ui/theme/` | `EmployeeAttendanceTheme`, `Color.kt`, `Type.kt` |
| `androidTest/.../ui/attendance/AttendanceScreenTest.kt` | Compose UI tests |

## Composable structure

```
AttendanceScreen(onOpenLocationDetail, attendanceViewModel, locationViewModel, locationPermissionViewModel)
├─ collects locationViewModel.uiState
├─ AttendanceScreen(todayDate, locationState, …)          <- stateless, previewable
│   ├─ Greeting(todayDate)
│   ├─ TimeCheck(onClockIn)
│   │   ├─ LiveClock()                                    <- 1 Hz LaunchedEffect loop
│   │   └─ Button "Clock in" / "Clock out"
│   └─ if (isSetUp) LocationPill else LocationSetupChip
└─ LocationPermissionHost(locationPermissionViewModel)    <- renders rationale dialogs
```

The stateful/stateless pair is the convention — keep the stateless overload free of ViewModels so
`@Preview` keeps working.

## The single-control rule

The attendance screen shows **exactly one** location affordance, chosen by
`LocationUiState.isSetUp` (`isGranted && activeWorkLocation != null`):

- **not set up** → `LocationSetupChip`, whose label and icon vary by `accessLevel`
  (`Set Up Location` / `Limited Location Access` / `Location On`). Tapping calls
  `LocationPermissionViewModel.onSetupRequested()`.
- **set up** → `LocationPill` with the location name. Tapping navigates to the detail screen.

Everything else about location — the map, proximity text, degraded notice — lives on the detail
screen by design. Resist adding location detail back onto this screen; the split is intentional and
`AttendanceScreenTest` asserts it.

## Coupling into the location feature

`AttendanceScreen` imports five location types: `LocationAccessLevel`, `ProximityState`,
`WorkLocation`, `LocationUiState`, and the two location ViewModels. This is the app's only
attendance↔location seam, and it flows through `LocationUiState` — so if you need new location data
on this screen, add a field or derived property to `LocationUiState` rather than injecting another
repository here.

`LocationPermissionHost` is placed here (not in `MainActivity`) so it shares the same
`LocationPermissionViewModel` instance the setup chip drives. Move one without the other and tapping
the chip stops opening the dialog.

## Known gaps

These are real, currently-shipping limitations. Fixing any of them is a well-scoped first task.

| Gap | Where | Notes |
| --- | --- | --- |
| Clock state is composable-local | `TimeCheck` uses `rememberSaveable` | survives config change, lost on process death; belongs in a ViewModel/repository |
| Clock-out isn't recorded | `TimeCheck.onClick` else-branch | only `onClockIn()` reaches `LocationClockInRepository` |
| Clock-in isn't gated on proximity | `TimeCheck` | you can clock in while `ProximityState.OUTSIDE` |
| Hardcoded strings | `"Good morning, Superstar"`, `"Current Time"`, `"Clock in"`, `"Clock out"`, `"Attendance"` title in `MainActivity` | should move to `strings.xml` |
| Greeting is time-of-day-agnostic | `Greeting` | always "Good morning" |
| App bar buttons are inert | `MainAppBar` | both `onClick = {}`; no profile or settings destination |
| `LiveClock` recreates `SimpleDateFormat` per composition | `LiveClock` | minor; wrap in `remember` |

## Where to make common changes

| Change | Edit |
| --- | --- |
| Greeting text/format | `Greeting` + `AttendanceViewModel.getTodayDateName()` |
| Clock format | `SimpleDateFormat("HH:mm:ss")` in `TimeCheck` and `LiveClock` |
| What clocking in records | `LocationViewModel.onClockIn()` → `LocationClockInRepository` |
| Which location control shows | `LocationUiState.isSetUp` in `LocationViewModel.kt` |
| App bar title for a new destination | the `LaunchedEffect` inside that destination's `composable<…>` block in `MainActivity` |
| Theme/colors/typography | `ui/theme/` |
