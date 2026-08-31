# Screenshot goldens

Reference images for the tests in `app/src/androidTest/java/com/jaustinjr/employeeattendance/screenshot/`.

A golden is only meaningful against the emulator that produced it. API level, image target, ABI and
screen density all change the rendered pixels, so the config is part of the contract.

## Config these goldens were recorded on

| | |
| --- | --- |
| Recorded on | `Medium_Phone` AVD, API **37**, `google_apis_playstore`, **arm64-v8a**, 420 dpi |
| CI runs on | API **36**, `google_apis`, **x86_64**, `pixel_6` profile — see `.github/workflows/android-tests.yml` |

> [!IMPORTANT]
> These two configs do not match. The committed images were recorded on an Apple-silicon
> workstation, and GitHub's Linux runners cannot run that ABI. **They will fail the first CI run**,
> and that failure is not a regression in the app.
>
> To fix it once: run the **Android Tests** workflow via *Run workflow* with
> `record_screenshots = true`, download the `screenshot-output` artifact, replace every PNG in this
> directory with the recorded version, and commit. From then on CI is the source of truth and local
> runs on a non-matching emulator are the ones that will disagree.

## Re-recording

Whenever a deliberate UI change moves these pixels, re-record rather than widening the tolerance:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.screenshot_record=true
```

Recorded images land in `app/build/outputs/connected_android_test_additional_output/`. Review the
diff before copying them here — the point of the gate is that a human (or Claude) looks at what
changed and confirms it was intended.

Full workflow: [`docs/maintenance/testing.md`](../../../../../docs/maintenance/testing.md).
