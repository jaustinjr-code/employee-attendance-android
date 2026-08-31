package com.jaustinjr.employeeattendance.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.abs

/**
 * Golden-image screenshot testing for Compose, running on a device/emulator.
 *
 * Goldens live in `app/src/androidTest/assets/screenshots/<name>.png` and are therefore packaged
 * into the *test* APK, which is why they are read from the instrumentation context rather than the
 * app under test.
 *
 * Recording: run with `-Pandroid.testInstrumentationRunnerArguments.screenshot_record=true`. In that
 * mode nothing is compared; every capture is written out and the run passes. Copy the emitted files
 * into the assets directory and commit them. See `docs/maintenance/testing.md`.
 *
 * On mismatch the actual and a diff image (differing pixels painted magenta) are written next to the
 * test output so CI can upload them as artifacts.
 *
 * Determinism matters more here than in a JVM renderer, because the image depends on the device.
 * Callers get help with two of the three usual culprits — [captureAndAssert] pins a fixed width and
 * forces `dynamicColor = false`, since Material You would otherwise sample wallpaper colors. The
 * third is yours to manage: never screenshot a composable whose content changes over time (a live
 * clock) or with the machine's locale/timezone, unless the test pins those itself.
 */
object Screenshots {

    private const val RECORD_ARG = "screenshot_record"
    private const val GOLDEN_ASSET_DIR = "screenshots"

    /**
     * Per-channel difference below which two pixels count as equal. Small non-zero value absorbs the
     * last-bit variation that text anti-aliasing produces between otherwise identical renders.
     */
    private const val CHANNEL_TOLERANCE = 8

    /** Fraction of pixels allowed to differ before the comparison fails. */
    private const val MAX_DIFFERING_FRACTION = 0.001

    private val isRecording: Boolean
        get() = InstrumentationRegistry.getArguments().getString(RECORD_ARG) == "true"

    /**
     * Asserts [actual] matches the committed golden for [name], or records it when in record mode.
     *
     * @throws AssertionError if the golden is missing, differently sized, or differs beyond the
     *   tolerance. The message names the artifact paths written for debugging.
     */
    fun assertMatchesGolden(name: String, actual: Bitmap) {
        if (isRecording) {
            val recorded = write(actual, "$name.png")
            println("Recorded golden for '$name' at $recorded")
            return
        }

        val golden = readGolden(name)
            ?: throw AssertionError(
                "No golden image for '$name'. Recorded the current rendering at " +
                    "${write(actual, "$name-actual.png")}. Re-run with " +
                    "-Pandroid.testInstrumentationRunnerArguments.screenshot_record=true and copy " +
                    "the output into app/src/androidTest/assets/$GOLDEN_ASSET_DIR/.",
            )

        if (golden.width != actual.width || golden.height != actual.height) {
            val actualPath = write(actual, "$name-actual.png")
            throw AssertionError(
                "Screenshot '$name' changed size: golden is ${golden.width}x${golden.height}, " +
                    "actual is ${actual.width}x${actual.height}. Actual written to $actualPath.",
            )
        }

        val diff = compare(golden, actual)
        val differingFraction = diff.differingPixels.toDouble() / (golden.width * golden.height)
        if (differingFraction > MAX_DIFFERING_FRACTION) {
            val actualPath = write(actual, "$name-actual.png")
            val diffPath = write(diff.image, "$name-diff.png")
            throw AssertionError(
                "Screenshot '$name' differs from its golden: ${diff.differingPixels} pixels " +
                    "(${"%.3f".format(differingFraction * 100)}%) exceed the allowed " +
                    "${"%.3f".format(MAX_DIFFERING_FRACTION * 100)}%. " +
                    "Actual: $actualPath, diff: $diffPath.",
            )
        }
    }

    private class Diff(val image: Bitmap, val differingPixels: Int)

    private fun compare(golden: Bitmap, actual: Bitmap): Diff {
        val width = golden.width
        val height = golden.height
        val goldenPixels = IntArray(width * height).also {
            golden.getPixels(it, 0, width, 0, 0, width, height)
        }
        val actualPixels = IntArray(width * height).also {
            actual.getPixels(it, 0, width, 0, 0, width, height)
        }

        val diffPixels = IntArray(width * height)
        var differing = 0
        for (i in goldenPixels.indices) {
            val g = goldenPixels[i]
            val a = actualPixels[i]
            if (pixelsDiffer(g, a)) {
                differing++
                diffPixels[i] = Color.MAGENTA
            } else {
                // Keep the unchanged parts as a faded greyscale backdrop so the magenta stands out.
                val grey = (Color.red(g) * 30 + Color.green(g) * 59 + Color.blue(g) * 11) / 100
                val faded = 255 - (255 - grey) / 4
                diffPixels[i] = Color.rgb(faded, faded, faded)
            }
        }

        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        image.setPixels(diffPixels, 0, width, 0, 0, width, height)
        return Diff(image, differing)
    }

    private fun pixelsDiffer(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) > CHANNEL_TOLERANCE ||
            abs(Color.green(a) - Color.green(b)) > CHANNEL_TOLERANCE ||
            abs(Color.blue(a) - Color.blue(b)) > CHANNEL_TOLERANCE ||
            abs(Color.alpha(a) - Color.alpha(b)) > CHANNEL_TOLERANCE

    private fun readGolden(name: String): Bitmap? {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return try {
            assets.open("$GOLDEN_ASSET_DIR/$name.png").use {
                android.graphics.BitmapFactory.decodeStream(it)
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /**
     * Writes [bitmap] where the build can retrieve it. AGP pulls `additionalTestOutputDir` off the
     * device into `app/build/outputs/connected_android_test_additional_output/` automatically; the
     * external files dir is the fallback for runs that don't set it (e.g. straight from the IDE).
     */
    private fun write(bitmap: Bitmap, fileName: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = configured?.let(::File)
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        dir.mkdirs()
        val file = File(dir, fileName)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }
}

private const val CAPTURE_TAG = "screenshot-capture-root"

/**
 * Renders [content] inside the app theme at a fixed width and compares it to the golden for [name].
 *
 * The width is pinned so the capture does not inherit the emulator's screen width, and dynamic color
 * is disabled so the palette does not vary by OS version or wallpaper. Only the wrapped content is
 * captured, not the whole window.
 *
 * @param widthDp fixed width for the captured surface; raise it for wide content.
 * @param darkTheme which palette to render, so a screen can be covered in both.
 */
fun ComposeContentTestRule.captureAndAssert(
    name: String,
    darkTheme: Boolean = false,
    widthDp: Int = 360,
    content: @Composable () -> Unit,
) {
    setContent {
        EmployeeAttendanceTheme(darkTheme = darkTheme, dynamicColor = false) {
            Surface(modifier = Modifier.width(widthDp.dp).testTag(CAPTURE_TAG)) {
                content()
            }
        }
    }
    waitForIdle()
    val bitmap = onNodeWithTag(CAPTURE_TAG).captureToImage().asAndroidBitmap()
    Screenshots.assertMatchesGolden(name, bitmap)
}
