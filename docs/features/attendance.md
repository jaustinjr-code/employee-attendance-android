# Feature: Attendance (home screen)

The app's start destination. Greets the user, shows a live clock, provides the clock in/out button,
and hosts the single location control.

## Code map

| File | Role |
| --- | --- |
| `ui/attendance/AttendanceScreen.kt` | stateful wrapper + stateless content + `Greeting`, `TimeCheck`, `LiveClock`, previews |
| `ui/attendance/AttendanceViewModel.kt` | one method: `getTodayDateName()` |
| `ui/main/MainAppBar.kt` | top app bar with profile/settings icon buttons, and the optional hidden title tap |
| `MainActivity.kt` | NavHost, shared ViewModel creation, the debug-only title-tap unlock |
| `ui/main/AppNavGraph.kt` | the destination hierarchy the app bar title and up button derive from |
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

## The hidden developer gesture

In a **debug** build only, `MainActivity` passes `MainAppBar` an `onTitleClick` that counts taps on
the Attendance title and opens developer settings on the fifth. It is offered when `!showUpButton` —
the root destination is Attendance by definition — so it reuses the same back-stack derivation as
the up button rather than testing the route separately. The handler is null on every other
destination and in every release build, so the title is otherwise an ordinary label: the click
carries no ripple and no semantics role by design.

A toast counts down the last few taps. That is load-bearing, not decoration — the gesture was
unusable in the field precisely because it was silent, so a deliberate tapper paused between taps
and silently restarted the run. See [developer-settings.md](developer-settings.md).

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

## What a clock-out also triggers

Every clock-out that records an event also offers a Status Update. This applies to the manual button,
automatic geofence clock-outs, and a clock-out confirmed from its notification.

- `LocationViewModel.onClockOut()` records through `recordIfStateChanges(id, CLOCK_OUT, source =
  MANUAL)`, so tapping clock-out while already clocked out records nothing and offers nothing. It
  then calls `StatusUpdateCoordinator.onClockOut(id, event.epochMillis, StatusUpdateTrigger.MANUAL)`.
- Automatic and confirmed clock-outs report through `ClockOutListener`
  (`AppContainer.clockOutListener`) from `GuardedClockStrategy.record` and
  `ClockActionHandler.confirm`.
- Undoing a clock-out from its notification calls `ClockOutListener.onClockOutUndone`, which pulls the
  prompt, the notification, or an open deck for it.

If you add a new way to clock out, report it after the event is recorded: through
`container.clockOutListener` from non-UI code, or by calling the coordinator with `MANUAL` from a
ViewModel. The policy, the overlay, and the tests are in [status-updates.md](status-updates.md).

## Known gaps

These are real, currently-shipping limitations. Fixing any of them is a well-scoped first task.

| Gap | Where | Notes |
| --- | --- | --- |
| Clock state is composable-local | `TimeCheck` uses `rememberSaveable` | survives config change, lost on process death; belongs in a ViewModel/repository |
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
| What clocking in records | `LocationViewModel.onClockIn()` → `AttendanceRepository.recordClockIn` |
| What happens after a clock-out | `StatusUpdateCoordinator.onClockOut`; see [status-updates.md](status-updates.md) |
| Which location control shows | `LocationUiState.isSetUp` in `LocationViewModel.kt` |
| App bar title for a new destination | the `LaunchedEffect` inside that destination's `composable<…>` block in `MainActivity` |
| Theme/colors/typography | `ui/theme/` |
