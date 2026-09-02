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
