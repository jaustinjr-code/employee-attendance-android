# Feature: Status Update

After every real clock-out, the app offers a short Status Update: three free-text answers ("What did
you do today?", "What's planned for tomorrow?", "What couldn't be done?") entered on a three-card
deck. It is on by default and switched off from Settings. A clock-out that is not surfaced, or a
prompt the user dismisses, is skipped for good: nothing re-prompts, badges, or re-notifies.

An update with every answer blank is not saved. Saved updates are kept on-device in encrypted
storage and are shown, and can be edited, from the Account screen; see [account.md](account.md).

---

## Code map

All paths are under `EmployeeAttendance/app/src/main/java/com/jaustinjr/employeeattendance/`.

| File | Role |
| --- | --- |
| `attendance/ClockNotificationStrategy.kt` | declares `ClockOutListener` and `ClockOutSource { AUTO, NOTIFICATION_CONFIRMED }`; `GuardedClockStrategy.record` reports auto clock-outs |
| `attendance/ClockActionReceiver.kt` | `ClockActionHandler.confirm` reports confirmed clock-outs; `ClockActionHandler.undo` reports undone ones |
| `attendance/AttendanceRepository.kt` | `hasClockOutEvent(locationId, epochMillis)`, the read-only check used to validate notification extras; `clockInBefore` for the shift start saved with an update |
| `location/ui/LocationViewModel.kt` | `onClockOut()` records a manual clock-out and calls the coordinator with `StatusUpdateTrigger.MANUAL` |
| `di/AppContainer.kt` | `clockOutListener` maps `ClockOutSource` to `StatusUpdateTrigger`; binds the store, repository, notifier and coordinator |
| `EmployeeAttendanceApplication.kt` | builds `DefaultAppForegroundTracker` before the container; forces `statusUpdateSettingsStore` and `statusUpdateRepository` in `startupJob` |
| `settings/StatusUpdateSettingsStore.kt` | persisted `enabled: StateFlow<Boolean>`, default `true`, prefs file `status_update_settings` |
| `statusupdate/StatusUpdateModels.kt` | `StatusUpdateTrigger`, `StatusUpdateRequest` (with derived `clockOutId`), `@Serializable StatusUpdate` (answers plus the shift's clock-in, clock-out, worksite name and edit time) |
| `statusupdate/StatusUpdateCoordinator.kt` | the policy: which surface, prompt state, notification claim, undo, completion |
| `statusupdate/AppForegroundTracker.kt` | interface + `DefaultAppForegroundTracker`, an `ActivityLifecycleCallbacks` started-activity counter |
| `statusupdate/StatusUpdateNotifier.kt` | interface `StatusUpdateNotifications` + `StatusUpdateNotifier`, channel `status_update` |
| `statusupdate/StatusUpdateIntents.kt` | the launch-intent extras contract and `consumeRequest` |
| `statusupdate/StatusUpdateRepository.kt` | interface, `StatusUpdateLocalDataSource` + `SharedPrefsStatusUpdateLocalDataSource` (file `status_updates`), and `DefaultStatusUpdateRepository` |
| `statusupdate/ui/StatusUpdateOverlayHost.kt` | `StatusUpdateOverlayHost` (Activity-level) and stateless `StatusUpdateOverlayContent` |
| `statusupdate/ui/StatusUpdateOverlayViewModel.kt` | `StatusUpdateOverlayUiState`, drafts, card index, the `Factory` |
| `statusupdate/ui/StatusUpdatePromptDialog.kt` | the "Start status update?" `AlertDialog` |
| `statusupdate/ui/StatusUpdateCardStack.kt` | stateless deck, `StatusUpdateCardStackUiState`, `StatusUpdateQuestion`, `StatusUpdateTestTags` |
| `location/ui/SettingsScreen.kt`, `SettingsViewModel.kt` | the "Ask after clock-out" switch; "Delete all data" calls `statusUpdateRepository.clearAll()` |
| `MainActivity.kt` | `launchMode="singleTop"` in the manifest, `onNewIntent`, mounts `StatusUpdateOverlayHost` |

---

## How a clock-out reaches the coordinator

Every clock-out that actually writes an event reaches `StatusUpdateCoordinator.onClockOut` exactly
once. Each producer is guarded, so a redundant clock-out (already clocked out) records nothing and
reports nothing.

| Producer | Guard | Reported as |
| --- | --- | --- |
| Clock-out button, `LocationViewModel.onClockOut()` | `recordIfStateChanges(..., CLOCK_OUT, source = MANUAL)` returns null | called directly with `StatusUpdateTrigger.MANUAL` |
| Geofence departure under `SILENT` or `NOTIFY_UNDO`, `GuardedClockStrategy.record` | `recordIfStateChanges` returns null | `ClockOutSource.AUTO` through `AppContainer.clockOutListener` |
| "Confirm" on a `CONFIRM` clock-out notification, `ClockActionHandler.confirm` | `recordIfStateChanges` returns null | `ClockOutSource.NOTIFICATION_CONFIRMED` through `AppContainer.clockOutListener` |
| "Undo" on a clock-out notification, `ClockActionHandler.undo` | `undoEvent` returns false | `onClockOutUndone` through `AppContainer.clockOutListener` |

`attendance/` does not import `statusupdate/`. That is why `ClockOutSource` exists as a separate,
narrower enum and why the mapping to `StatusUpdateTrigger` lives in `AppContainer.clockOutListener`.
Keep producers on `container.clockOutListener` rather than constructing their own listener, so
there is one mapping.

---

## Choosing the surface

`StatusUpdateCoordinator.onClockOut` decides in this order.

| Setting | Trigger | App in foreground | Result |
| --- | --- | --- | --- |
| off | any | any | nothing |
| on | `MANUAL` | any | `pendingPrompt` set, dialog shows |
| on | `AUTO` | yes | `pendingPrompt` set, dialog shows |
| on | `AUTO` | no | `StatusUpdateNotifier.notifyPending` |
| on | `NOTIFICATION_CONFIRMED` | any | `StatusUpdateNotifier.notifyPending` |

`NOTIFICATION_CONFIRMED` always notifies because the user was in the notification shade, not the
app. A second clock-out while a prompt is pending replaces that prompt.

"Foreground" is `AppForegroundTracker.isForeground`. `EmployeeAttendanceApplication.onCreate` builds
`DefaultAppForegroundTracker` **before** `DefaultAppContainer` and passes it in. Do not move it into
a `by lazy` binding: if it registers after `MainActivity`'s first `onStart`, the counter only sees
the matching `onStop` and `isForeground` stays `false` for the life of the process, so every
foreground auto clock-out would post a notification instead of the dialog.

The coordinator is an app-scoped singleton because a geofence departure can cold-start the process
and call `onClockOut` with no Activity. It holds its monitor only around state changes and calls
the notifier outside it, since posting is IPC into `system_server`.

---

## The notification launch path

Tapping the "Status update waiting" notification opens the deck, provided the clock-out is real and
not already answered.

1. `StatusUpdateNotifier` builds a `PendingIntent` for `MainActivity` with
   `StatusUpdateIntents.putExtras` (location id and clock-out time). The notification id is derived
   from `clockOutId`, so a repeat post for the same clock-out replaces the card.
2. `MainActivity` reads the extras with `StatusUpdateIntents.consumeRequest` in `onCreate` (only when
   `savedInstanceState == null`) or in `onNewIntent`. `launchMode="singleTop"` is what routes a warm
   tap to `onNewIntent` instead of a second Activity.
3. `consumeRequest` returns null for a launch with `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` (a relaunch
   from recents), and removes both extras after reading them, so a replay of the same `Intent` does
   not reopen the deck.
4. The request waits in `MainActivity.pendingStatusUpdateRequest` until `StartupGate` opens, because
   `StatusUpdateOverlayViewModel.Factory` must not run before `startupComplete`.
5. `StatusUpdateOverlayHost` forwards it to `StatusUpdateOverlayViewModel.onNotificationRequest`,
   then clears it.
6. The ViewModel calls `StatusUpdateCoordinator.claimNotificationRequest`, which returns null unless
   `AttendanceRepository.hasClockOutEvent` finds that exact clock-out **and** no saved
   `StatusUpdate` has the same `clockOutId`.

`MainActivity` is exported, so any app can launch it with these extras. Treat step 6 as a security
check. Do not open the deck from `consumeRequest`'s result directly.

The flow is drawn in [sequence-diagrams.md §9](../architecture/sequence-diagrams.md#9-status-update-after-a-clock-out).

---

## Undo

An undone clock-out retracts everything Status Update did for it. `ClockActionHandler.undo` calls
`onClockOutUndone` only after `undoEvent` succeeds and only for `ClockType.CLOCK_OUT`. The
coordinator then:

- clears `pendingPrompt` if it names that clock-out;
- cancels the notification for it;
- emits its `clockOutId` on `undoneClockOuts`.

`StatusUpdateOverlayViewModel` collects `undoneClockOuts` and closes an open deck for that
clock-out without saving, so answers are never attached to an event that no longer exists.

---

## The overlay

The prompt and deck render over whichever destination is current, without navigating.

| Piece | Scope | Notes |
| --- | --- | --- |
| `StatusUpdateOverlayHost` | mounted once in `MainActivity`, in a `Box` as a sibling after the `Scaffold`/`NavHost` | obtains its own ViewModel; one of two deliberate exceptions to stateless composables (see [overview §2](../architecture/overview.md#layer-rules)) |
| `StatusUpdateOverlayViewModel` | Activity-scoped | one instance receives both the in-app "Begin" and notification taps; while a deck is open, `onBeginPrompt` returns without accepting (the prompt stays pending) and `openDeck` ignores a notification request |
| `StatusUpdateOverlayContent` | stateless | renders the prompt when `state.prompt != null` and the deck when `state.deck != null` |
| `StatusUpdatePromptDialog` | stateless | "Begin" calls `acceptPrompt`; "Not now" and dismissal call `dismissPrompt` |
| `StatusUpdateCardStack` | stateless | takes `StatusUpdateCardStackUiState` and lambdas |

The deck is inside a full-screen `Dialog` (`usePlatformDefaultWidth = false`,
`decorFitsSystemWindows = false`), not a `Box` drawn on top. The separate window is what blocks
touches and TalkBack focus from reaching the destination underneath and gives the deck its own back
handling. Replacing it with an in-window overlay reintroduces those leaks.

Deck behavior:

- Each card holds the question as a heading, an unlabeled answer field showing an example answer as
  its placeholder hint, and the Back and Next/Done buttons. The buttons are inside the card and move
  with it. The field's accessible name is the question (`contentDescription`).
- Cards have a 2 dp border in the theme's `outline` colour on `surfaceContainerHigh`, so their edges
  stay visible in light and dark themes.
- The cards sit side by side. Moving to another card slides the front card off the screen edge and
  the next one in (300 ms); nothing rotates. The previous and next cards peek 28 dp (`PeekWidth`)
  from the left and right screen edges, and a card two positions away shows as a shorter edge
  tucked behind its neighbour, so the remaining cards read as a stack.
- While dragging, the cards follow the finger. Releasing short of the threshold slides back.
- Peeking cards are hidden from accessibility, can't take focus, and ignore taps; only the front
  card is interactive. Changing cards clears focus so typing never lands in a card that is leaving.
- The card is 0.8 width-to-height when there is room, and shrinks to the height available otherwise
  (for example with the keyboard open), so the field and buttons never overflow it.
- Swipe left or "Next" advances. On the last card the button reads "Done", and advancing calls
  `StatusUpdateCoordinator.complete`, which saves a `StatusUpdate` with `completedAtMillis` from the
  coordinator's injected clock, then closes the deck.
- Swipe right or "Back" retreats and stops at the first card, where "Back" is hidden.
- A drag counts as a swipe past 96 dp (`SwipeThreshold`).
- System back calls `onDismissDeck` and discards the drafts.
- Drafts and the card index live in the ViewModel, so typed text survives swiping away and back and
  a configuration change.
- The slide finishes immediately when the system animation scale is 0.

---

## Setting, storage, and deletion

| Concern | Where | Detail |
| --- | --- | --- |
| On/off switch | Settings, "Status updates" section, "Ask after clock-out" | `SettingsViewModel.onStatusUpdateEnabledChanged` writes `StatusUpdateSettingsStore.setEnabled` |
| Persistence of the switch | `StatusUpdateSettingsStore` | `SecurePreferences` file `status_update_settings`; excluded in `backup_rules.xml` and both sections of `data_extraction_rules.xml` |
| Startup | `EmployeeAttendanceApplication.startupJob` | forces `container.statusUpdateSettingsStore` so `LocationViewModel.Factory` and `SettingsViewModel.Factory` never construct it on main after `StartupGate` opens (#58) |
| Completed updates | `DefaultStatusUpdateRepository` | persisted as JSON in `SecurePreferences` file `status_updates` (backup-excluded, forced in `startupJob`); all-blank updates are not saved; displayed and edited from [Account](account.md) |
| "Delete all data" | `SettingsViewModel.onDeleteAllData` | calls `statusUpdateRepository.clearAll()` |

A new store-backed dependency of the coordinator must also be forced in `startupJob`. See
[overview §8](../architecture/overview.md#8-constraints-the-architecture-depends-on).

---

## Tests

| Test | Layer | Covers |
| --- | --- | --- |
| `StatusUpdateCoordinatorTest` | JVM | the surface table, prompt accept and dismiss, claim validation, undo, completion |
| `StatusUpdateOverlayViewModelTest` | JVM | deck open and close, drafts, submit on last card, undo closing the deck |
| `StatusUpdateCardStackUiStateTest` | JVM | `StatusUpdateCardStackUiState` invariants |
| `ClockActionHandlerTest`, `ClockNotificationStrategyTest`, `AttendanceAutoClockControllerTest`, `LocationViewModelTest` | JVM | each producer reports once, and not for a redundant clock-out |
| `StatusUpdateCardStackTest` | androidTest | Next, Back, real swipes, draft retention, system back |
| `StatusUpdateOverlayContentTest` | androidTest | overlay over a `NavHost` destination; deck inside an `isDialog()` node |
| `StatusUpdatePromptDialogTest` | androidTest | prompt buttons |
| `StatusUpdateNotificationLaunchTest` | androidTest | cold start from notification extras through to a saved update; forged extras show no deck |
| `StatusUpdateIntentsTest` | androidTest | `consumeRequest` stripping, history launches, round trip |
| `StatusUpdateNotifierTest` | androidTest | posting and cancelling |
| `AppForegroundTrackerTest` | androidTest | the started-activity counter |
| `StatusUpdateSettingsStoreTest`, `SettingsStatusUpdateToggleTest` | androidTest | default on, persistence, the Settings switch |
| `SettingsDeleteAllDataTest` | androidTest | "Delete all data" clears saved updates |
| `DefaultStatusUpdateRepositoryTest`, `SharedPrefsStatusUpdateLocalDataSourceTest` | JVM, androidTest | persistence; history and editing tests are listed in [account.md](account.md#tests) |

The test patterns specific to this feature are in [testing.md §6](../maintenance/testing.md#6-patterns-for-overlay-and-launch-intent-tests).

---

## Known gaps

| Gap | Where | Notes |
| --- | --- | --- |
| Completed updates stay on the device | `DefaultStatusUpdateRepository` | no backend sync; listed in [overview §7](../architecture/overview.md#7-known-stubs-and-follow-ups) |
| A notification that cannot be posted is lost | `StatusUpdateNotifier.post` | with notifications disabled or `POST_NOTIFICATIONS` denied the post is skipped, and no prompt is created in its place, so that clock-out's update is skipped |
| System back on the deck is not covered end to end | `StatusUpdateNotificationLaunchTest` KDoc | covered at composable level by `StatusUpdateCardStackTest.systemBack_dismissesTheDeck` |
