# Feature: Account and status update history

The account button at the left of the app bar on the home screen opens the Account screen. It has
two sections, separated by a divider:

1. **Name**: the display name used in the attendance greeting (moved here from Settings).
2. **Status updates**: every status update the user filled in, grouped by day, newest first.

From the history the user can press and hold a shift to peek at its full summary, or tap it to open a
read-only screen with an Edit button. Editing always asks before discarding.

How status updates are captured after a clock-out is in [status-updates.md](status-updates.md).

---

## Code map

All paths are under `EmployeeAttendance/app/src/main/java/com/jaustinjr/employeeattendance/`.

| File | Role |
| --- | --- |
| `ui/main/MainAppBar.kt` | `onOpenAccount`, called by the account button on the root destination |
| `ui/main/AppNavGraph.kt` | `Account` (parent `Attendance`), `StatusUpdateDetail` (parent `Account`), `StatusUpdateEdit` (parent `StatusUpdateDetail`); `destinationFor` ignores route arguments |
| `MainActivity.kt` | the `Account`, `StatusUpdateDetail(clockOutId)` and `StatusUpdateEdit(clockOutId)` destinations; the up button dispatches a back press |
| `account/ui/AccountScreen.kt` | `AccountViewModel`, `AccountScreen`, stateless `AccountContent`, `accountSectionDivider` |
| `account/ui/DisplayNameSection.kt` | `displayNameSection` and `DisplayNameSection` |
| `statusupdate/history/StatusUpdateHistory.kt` | `StatusUpdateShift`, `AnsweredQuestion`, `StatusUpdateDay`, `toShift()`, `groupStatusUpdatesByDay` |
| `statusupdate/history/StatusUpdateHistoryViewModels.kt` | `StatusUpdateHistoryViewModel`, `StatusUpdateDetailViewModel`, `StatusUpdateEditViewModel`, `CLOCK_OUT_ID_ARG` |
| `statusupdate/history/ui/StatusUpdateHistorySection.kt` | `statusUpdateHistorySection`, `StatusUpdateShiftRow` (gestures), the peek popup, `StatusUpdateAnswers`, `StatusUpdateHistoryTestTags` |
| `statusupdate/history/ui/StatusUpdateDetailScreen.kt` | read-only screen and stateless `StatusUpdateDetailContent` |
| `statusupdate/history/ui/StatusUpdateEditScreen.kt` | edit screen, stateless `StatusUpdateEditContent`, the discard dialog |
| `statusupdate/history/ui/StatusUpdateFormatting.kt` | day labels ("Today", "Yesterday"), shift time range, worksite fallback, "Edited" label |

---

## Sections are independent

`AccountContent` is one `LazyColumn` whose body is a list of section calls:

```kotlin
displayNameSection(displayName = displayName, onDisplayNameChanged = onDisplayNameChanged)
accountSectionDivider(key = "divider_name_history")
statusUpdateHistorySection(days = historyDays, onOpenShift = onOpenStatusUpdate)
```

Each section is a `LazyListScope` extension that takes only its own state and callbacks and prefixes
its own item keys. To reorder the page, move a call. To show a section on another screen, call it
from that screen's `LazyColumn` and give that screen the section's ViewModel
(`AccountViewModel` for the name, `StatusUpdateHistoryViewModel` for history). A divider key must be
unique on the page.

---

## History

`StatusUpdateHistoryViewModel.days` maps `StatusUpdateRepository.statusUpdates` through
`groupStatusUpdatesByDay`:

- Updates with every answer blank are dropped. The coordinator does not save them either.
- Days are the local calendar day of the shift's **clock-out**, in the device time zone.
- Days and the shifts within a day are newest first.
- A shift keeps only answered questions, trimmed, in question order.

Each row (`StatusUpdateShiftRow`) shows the time range ("9:02 AM – 5:31 PM", or "Clocked out 5:31 PM"
when no clock-in was on record), the worksite name (or "General timeclock"), the first answer as a
two-line preview, and "+N more answers".

The shift's clock-in time and worksite name are captured when the update is saved
(`StatusUpdateCoordinator.complete`, via `AttendanceRepository.clockInBefore` and the container's
worksite lookup), so history does not change if the worksite is later renamed or removed.

### Gestures

| Gesture | Result |
| --- | --- |
| Tap | opens `StatusUpdateDetail(clockOutId)` |
| Press and hold | haptic tick, then the peek shows until the finger lifts or the press turns into a scroll; the shift is not opened |
| TalkBack double-tap | the semantics `onClick` action opens the shift |
| TalkBack long-press action ("Peek at status update") | the peek stays open until back or a tap outside |

The peek is a `Popup` centred on the window with a scrim, showing the time, worksite and every
answer. While a finger holds it, the popup is not focusable, so the press stays with the row and
lifting the finger hides it. Opened from the accessibility action it is focusable and dismissable.
The gestures come from one `detectTapGestures` block (`onTap`, `onLongPress`, `onPress` for release
and the ripple). Add new gestures there rather than stacking another `pointerInput`.

---

## Detail and edit

`StatusUpdateDetailScreen` is read-only: the day, time range, worksite, an "Edited" line once edited,
then each answered question. The Edit button navigates to `StatusUpdateEdit(clockOutId)`.

`StatusUpdateEditScreen` shows all three questions, including unanswered ones, as text fields.

- **Entering edit mode counts as having changes.** Cancel, system back, and the app bar's up button
  all open "Discard changes?", even when no text changed. "Keep editing" (or tapping outside the
  dialog) stays; "Discard" pops back to the read-only screen, and the edits are lost.
- **Save** calls `StatusUpdateRepository.updateAnswers` (stamping `editedAtMillis`) and pops back.
  The read-only screen observes the repository, so it shows the new text immediately.
- Save is disabled while every answer is blank, with a message beside the buttons, because history
  keeps only filled-in updates.
- Drafts and the dialog's visibility live in `SavedStateHandle`, so rotation and process death keep
  unsaved edits.
- If the update no longer exists (for example after "Delete all data"), both screens say so and back
  leaves directly.

### Up goes through an id-checked interceptor

`MainActivity` passes `onNavigateUp = { performUp(upInterceptor, navController) }` to `MainAppBar`,
where `performUp` (`ui/main/UpNavigation.kt`) is a small shared function — the same one
`StatusUpdateEditFlowTest` calls, so a change to this rule can't drift between the app and its test.
`upInterceptor` is `null` on every destination except the status update editor, which registers a
`ui/main/UpInterceptor(entryId, onUp)` — its own discard-confirmation handler
(`viewModel::onExitRequested`), tagged with its own `NavBackStackEntry.id` — via
`StatusUpdateEditScreen`'s required `onInterceptUpChanged` parameter.

Registration happens as soon as the editor composes, via a plain `DisposableEffect`, not gated on any
lifecycle state: an earlier version gated it on the destination reaching `RESUMED` (mirroring how
`BackHandler` catches system back), but `RESUMED` only arrives once `NavHost`'s *enter* transition
finishes (~700ms by default), leaving a window right after opening the editor where up silently
skipped the confirmation while system back already worked correctly. What makes registering this
early *safe* — rather than reintroducing the opposite problem, a stale registration outliving a
screen that's mid-*exit* — is the id: `performUp` only invokes `interceptor.onUp()` when
`interceptor.entryId` still equals `navController.currentBackStackEntry?.id`. A popped entry stays
composed through its own exit transition and so can't clear its registration until that finishes, but
by then the current entry's id has already changed, so `performUp` falls through to a plain
`popBackStack()` instead of invoking a handler that belongs to a screen the user has already left.
With no registered interceptor at all, up is a direct `popBackStack()` everywhere else. Removing the
editor's registration, or the id check, would let up skip the discard confirmation.

---

## Storage and deletion

| Concern | Where | Detail |
| --- | --- | --- |
| Display name | `UserProfileStore` | unchanged; only the screen that edits it moved from Settings to Account |
| Status updates | `DefaultStatusUpdateRepository` over `SharedPrefsStatusUpdateLocalDataSource` | JSON in `SecurePreferences` file `status_updates`; excluded in `backup_rules.xml` and both sections of `data_extraction_rules.xml`; forced in `startupJob` |
| Old updates | `StatusUpdate` new fields have defaults | `clockOutAtMillis` defaults to `completedAtMillis`, the rest to null |
| "Delete all data" | `SettingsViewModel.onDeleteAllData` | `statusUpdateRepository.clearAll()` empties history |

---

## Tests

| Test | Layer | Covers |
| --- | --- | --- |
| `StatusUpdateHistoryTest` | JVM | day grouping, time-zone day boundary, blank updates dropped, answered-question filtering |
| `StatusUpdateHistoryViewModelsTest` | JVM | history following the repository; detail lookup and edits; edit drafts, save, blank-save blocked, discard dialog, `SavedStateHandle` restore |
| `DefaultStatusUpdateRepositoryTest` | JVM | persistence through the data source, `updateAnswers`, `clearAll` |
| `StatusUpdateCoordinatorTest` | JVM | blank updates not saved; clock-in and worksite captured |
| `DefaultAttendanceRepositoryTest` | JVM | `clockInBefore` |
| `AppNavGraphTest` | JVM | argument routes resolve; the status update screens nest under Account |
| `SharedPrefsStatusUpdateLocalDataSourceTest` | androidTest | encrypted round trip across instances |
| `AccountScreenTest` | androidTest | section order, name editing, empty state, row content, tap, hold-to-peek, accessibility peek, the app bar account button |
| `StatusUpdateEditFlowTest` | androidTest | real `NavHost` + app bar: read-only content, Cancel / system back / up all confirm, keep editing, discard, save returning with edits, blank-save disabled |

Two patterns specific to these tests are in
[testing.md §6](../maintenance/testing.md#6-patterns-for-overlay-and-launch-intent-tests): holding a
press on the test clock, and why the peek is asserted with `assertExists`.

---

## Known gaps

| Gap | Notes |
| --- | --- |
| History is on-device only | no backend sync; see [overview §7](../architecture/overview.md#7-known-stubs-and-follow-ups) |
| Day grouping uses the current time zone | a shift is regrouped if the device changes time zone |
| No paging | every kept update is loaded; fine at one per shift, revisit if history grows large |
| Status updates cannot be deleted individually | only "Delete all data" removes them |
