# Testing and Automated Validation

The gate a change has to clear before it is proposed for merge, what each layer of the suite is
actually for, and how to work with screenshot goldens.

## 1. The three layers

| Layer | Location | Runs on | Command |
| --- | --- | --- | --- |
| JVM unit tests | `app/src/test/` | the JVM, no device | `./gradlew :app:testDebugUnitTest` |
| Instrumentation + integration | `app/src/androidTest/` | emulator/device | `./gradlew :app:connectedDebugAndroidTest` |
| Screenshot (golden image) | `app/src/androidTest/.../screenshot/` | emulator/device | same task as above |

Screenshot tests are not a separate Gradle task; they are instrumentation tests that happen to
assert on pixels, so `connectedDebugAndroidTest` runs everything on the device in one pass.

## 2. What belongs where

The dividing line is not "fast vs slow" — it is **what the Android framework actually does**.

`android.testOptions.unitTests.isReturnDefaultValues = true` makes unmocked framework calls return
defaults instead of throwing. That is what lets the bulk of this codebase be unit-tested on the JVM
despite logging freely. It also means **any test that depends on real framework behaviour is
silently meaningless on the JVM**.

The clearest example: `ProximityCalculator.distanceMeters` delegates to
`android.location.Location.distanceBetween`, which is native. On the JVM it returns 0, so every fix
would look like it is sitting exactly on the target. `ProximityCalculatorTest` therefore only covers
the pure `evaluate` hysteresis logic, and the distance math is covered on-device by
`LocationPipelineIntegrationTest`. Same reasoning applies to `SharedPreferences`
(`ProximityPersistenceE2ETest`) and to anything touching a real `Bundle` or `SavedStateHandle`.

> Rule of thumb: if the assertion would still pass when the framework call does nothing, it belongs
> on the JVM. If the framework call *is* the thing under test, it belongs in `androidTest`.

That rule is also *why* some policy is extracted rather than inlined. `DevGeo.offsetNorth` exists as
a pure function because the developer tools need to place a simulated fix a known distance from a
worksite, and the obvious implementation — ask `Location` — cannot be verified on the JVM at all.
`DevUnlockTapCounter` takes its timestamp as a parameter for the same reason, and that mattered: the
gesture's real defect was its inter-tap window, and the original test drove the counter with a
constant timestamp, so the window was never exercised and the bug shipped. If a new policy depends
on distance, time, or prefs, extract it or accept that it is untestable off-device.

## 3. Screenshot tests

### What they cover

The location widgets whose *appearance* carries meaning — the setup chip's icon and label change
with the granted access level, the proximity row's icon and tint change with proximity, and the
detail screen swaps the map for a locked notice under when-in-use access. A regression in any of
those would mislead the user without failing a single behavioural assertion.

### Determinism

An emulator render depends on more than your code, so three things are pinned:

1. **Emulator config** — API level, image target, ABI, and device profile (which fixes density, and
   therefore the pixel size of every capture). Pinned in `.github/workflows/android-tests.yml`.
2. **Theme** — `captureAndAssert` forces `dynamicColor = false`, because Material You would
   otherwise sample wallpaper colours, and pins the capture width so it does not inherit the
   emulator's screen width.
3. **Time and locale** — `LocationDetailScreenshotTest` renders a formatted clock-in timestamp, so it
   pins the default timezone to UTC and the locale to `Locale.US`.

Animations are disabled globally via `android.testOptions.animationsDisabled = true`; a half-finished
ripple would otherwise read as a regression.

**Never screenshot a composable whose content changes with the clock.** `AttendanceScreen` embeds
`LiveClock`, which re-renders every second — that screen is covered behaviourally instead.

Comparison allows a per-channel tolerance of 8 and up to 0.1% of pixels differing, which absorbs
text anti-aliasing without hiding real changes. A 6dp icon-size change registers as ~8% of pixels,
so the margin is wide.

### Re-recording goldens

When a UI change is deliberate, re-record — do not widen the tolerance:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.screenshot_record=true
```

Recorded PNGs land in `app/build/outputs/connected_android_test_additional_output/`. Review them,
then copy into `app/src/androidTest/assets/screenshots/` and commit.

On failure the same directory receives `<name>-actual.png` and `<name>-diff.png`, where differing
pixels are painted magenta over a faded backdrop. CI uploads both as the `screenshot-output`
artifact.

Because goldens are config-specific and GitHub's Linux runners cannot run an arm64 image, goldens
recorded on an Apple-silicon workstation will not match CI. Record them **on the CI config** by
running the *Android Tests* workflow with `record_screenshots = true` and committing the artifact.
See [`assets/screenshots/README.md`](../../EmployeeAttendance/app/src/androidTest/assets/screenshots/README.md).

## 4. CI

`.github/workflows/android-tests.yml` runs three jobs on every PR:

- **Unit tests (JVM)** — `testDebugUnitTest`
- **Lint** — `lintDebug`
- **Instrumentation + screenshot tests** — `connectedDebugAndroidTest` on an emulator provisioned by
  [`reactivecircus/android-emulator-runner`](https://github.com/ReactiveCircus/android-emulator-runner)

The emulator job enables KVM (without hardware acceleration the emulator is too slow to finish) and
caches an AVD snapshot, so subsequent runs boot from the snapshot rather than cold-booting.

> [!IMPORTANT]
> The emulator step's `script:` **must stay a single line.** `android-emulator-runner` splits that
> input on newlines and runs each line through its own `sh -c`, so multi-line shell control flow is
> torn apart — an `if`/`else` block there died with `Syntax error: end of file unexpected` before
> Gradle was ever invoked, and the job failed in seconds without running a test. Branch with a
> GitHub expression instead of shell syntax, or move the logic into a committed script file that
> `script:` invokes in one line.

Reports and screenshot diffs are uploaded as artifacts on every run, pass or fail.

## 5. Running the emulator locally

```bash
$ANDROID_HOME/emulator/emulator -avd <your-avd> -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
```

`swiftshader_indirect` matches what CI uses, which keeps local renders closer to the CI goldens.
Confirm the device is up with `adb devices` before running `connectedDebugAndroidTest`.

## 6. Patterns for overlay and launch-intent tests

The Status Update and account tests use the patterns below. Reuse them for any UI that swipes, opens from a launch
intent, or renders in its own window.

### Drive gestures with real swipes, then wait for idle

`StatusUpdateCardStackTest` advances and retreats with
`onNodeWithTag(StatusUpdateTestTags.CARD).performTouchInput { swipeLeft() }` (or `swipeRight()`)
instead of calling `onForward`/`onBack`. That exercises the `detectHorizontalDragGestures` handler
and its 96 dp threshold. The deck is hosted in a small stateful harness inside `setContent` that
holds drafts and the index in `remember`, so recomposition is real.

When a test swipes more than once, call `composeRule.waitForIdle()` after each swipe, as
`typedText_survivesSwipingAwayAndBack` does. The slide runs in a `LaunchedEffect` keyed on the
index, and the next gesture must land on the settled card.

Every card is composed at once; only the front card carries `StatusUpdateTestTags.CARD` and
semantics. The others are cleared and tagged `StatusUpdateTestTags.peek(index)`, so text and
`hasSetTextAction()` matchers only ever find the front card. Assert where peeking cards sit by
comparing their `getUnclippedBoundsInRoot()` with the front card and `onRoot()`, as
`afterNext_previousCardSlidesOutAndPeeksFromTheLeftEdge` does.

The answer field has no visible label, so `onAllNodesWithText(question)` matches the question
heading alone. The field carries the question as its `contentDescription`: find it with
`hasSetTextAction()` and check its name with `hasContentDescriptionExactly(question)`. The tests
still select the question with `onAllNodesWithText(text).onFirst()` so the helper keeps working if a
second text node appears.

To check that nothing in the card overflows when space is short (as with the keyboard open), pass a
fixed height through the composable's `modifier` and compare `getUnclippedBoundsInRoot()` of the
card, field and buttons, as `shortViewport_keepsFieldAndButtonsInsideTheCard` does.

### Launch a real Activity with an intent

`StatusUpdateNotificationLaunchTest` uses `createEmptyComposeRule()` with
`ActivityScenario.launch<MainActivity>(intent)`, because the notification extras must be on the
launch `Intent` and `createAndroidComposeRule<MainActivity>()` cannot supply one.

`MainActivity` shows `StartupScreen` until `EmployeeAttendanceApplication.startupComplete` is `true`,
so every test first calls `waitForStartupGateToOpen()`, which waits in two steps:

1. `composeRule.waitUntil(timeoutMillis = 10_000) { context.startupComplete.value }`
2. `waitUntil` again until a node that exists only inside the gated content (the "Attendance" app
   bar title) is present, because the `StateFlow` can flip before composition catches up.

Only then does the test wait for or assert on the deck. Skip the gate wait and a negative assertion
such as `forgedExtras_showNoDeck` passes because nothing has composed yet, not because
`claimNotificationRequest` rejected the extras.

The container is the real app-scoped one and outlives each scenario, so reset shared state in a
method annotated both `@Before` and `@After` (`attendanceRepository.clearAll()`,
`statusUpdateRepository.clearAll()`, re-enable the setting). Check configuration changes with
`scenario.recreate()`.

### Holding a press, and asserting a popup that is shown while held

`AccountScreenTest.holdingAShift_peeksUntilReleased_withoutOpeningIt` holds a press with
`performTouchInput { down(center) }`, then `composeRule.mainClock.advanceTimeBy(1_000)`.
`detectTapGestures`' long-press timeout runs on the test clock, and a held finger sends no events that
would advance it, so `advanceEventTime` inside `performTouchInput` is not enough. Release with
`performTouchInput { up() }`.

The peek is a non-focusable `Popup` window. While a finger is injected into the root below it, the
framework finds its nodes but does not report them as displayed, so the test uses `assertExists()` for
the peek and asserts it is gone after release. That the peek really draws, and that lifting the
finger hides it without opening the shift, was checked on an emulator with
`adb shell input swipe x y x y 4000` and `screencap` taken mid-press.

### Hosting a flow that needs the real up interceptor

`StatusUpdateEditFlowTest` uses `createAndroidComposeRule<ComponentActivity>()`, a real
`rememberNavController()` `NavHost`, and `MainAppBar` wired to `ui/main/UpNavigation.kt`'s
`performUp(interceptor, navController)` — the same function `MainActivity` calls, not a hand-copied
`onNavigateUp` lambda, so a change to the real up-button rule is caught here too — with an
`upInterceptor` state (typed `UpInterceptor?`) that `StatusUpdateEditScreen`'s `onInterceptUpChanged`
sets. ViewModels are built directly with a `SavedStateHandle` holding `CLOCK_OUT_ID_ARG` and an
in-memory repository. That exercises the discard confirmation from Cancel, `Espresso.pressBack()`,
and the up button against the same back stack the app uses.

`theAppBarUpButton_asksBeforeLeaving_evenImmediatelyAfterOpening` is the regression test for the gap
in an earlier version of the fix: gating the interceptor's registration on the destination reaching
`RESUMED` left a window — up to NavHost's ~700ms default enter-transition duration — right after
opening the editor where up silently popped back instead of asking, while system back (STARTED-gated,
via `BackHandler`) already worked. The test freezes the clock, taps Edit, advances by exactly one
frame (just enough for the editor to compose and register — deliberately far short of 700ms), taps
up, and asserts the discard dialog appears. Confirmed to fail against the RESUMED-gated version and
pass against the current one, which registers on first composition instead (see
[../features/account.md](../features/account.md#up-goes-through-an-id-checked-interceptor) for why
that's safe against the *other* direction of the same problem, a stale registration outliving a
screen mid-exit).

`theAppBarUpButton_discard_closesTheDialogOneFrameBeforeNavigatingAway` proves the *ordering* of the
fix for the dialog-stays-visible-behind-navigation bug, not just its settled end state. A plain
`assertDoesNotExist()` right after the click, with `autoAdvance` left on, passes whether or not the
fix is present: by the time the test framework returns from `performClick()`, both the dialog closing
and the navigation away have already settled, in either ordering. The test instead sets
`composeRule.mainClock.autoAdvance = false`, clicks Discard, steps the clock forward by exactly one
frame with `advanceTimeByFrame()`, and asserts from inside that single-frame window: the dialog must
already be gone, *and* the editor's app bar title ("Edit status update") must still be what's
displayed (navigation must not have happened yet). This was arrived at empirically — logging which
nodes existed frame by frame showed that with the fix, `onDiscardConfirmed()` flips
`showDiscardDialog` to `false` synchronously in the click handler, which `collectAsStateWithLifecycle`
re-emits and closes the dialog on the very next frame while the editor is still what's on screen; the
separate `LaunchedEffect(exitConfirmed)` that performs the actual `popBackStack()` only runs a frame
after that. Reverting `onDiscard` to the pre-fix handler (`onDiscardDialogDismissed()` then
`onExit()`, called synchronously in the same click handler, before either state change reaches
Compose) collapses both changes into that same first frame instead, landing directly on the read-only
screen with no such intermediate frame — confirmed by reverting the handler and re-running the test,
which failed as expected before the fix was restored. This ordering isn't only cosmetic: past the
point where the popped entry drops below STARTED, `collectAsStateWithLifecycle` stops collecting
altogether, so a `showDiscardDialog = false` emitted after `popBackStack()` (the pre-fix ordering)
would never be delivered at all, not merely delayed a frame.

### Cross-window touch blocking cannot be proven in compose-ui-test

`performClick()` and `performTouchInput` dispatch directly to the target node's own root, not
through the OS input pipeline. A click on content underneath a `Dialog` therefore succeeds in a test
whether or not the dialog covers it on a device. `StatusUpdateOverlayContentTest` asserts the
structural fact instead: the deck's text has an `isDialog()` ancestor and the underlying content does
not (`hasAnyAncestor(isDialog())`). Do not write a "click underneath is blocked" test, because it
passes without proving anything.

System back into a second window is also unreliable in this harness (`Espresso.pressBack()` and an
injected `KEYCODE_BACK` both fail to reach it). Back handling is tested on the composable directly in
`StatusUpdateCardStackTest.systemBack_dismissesTheDeck`.
