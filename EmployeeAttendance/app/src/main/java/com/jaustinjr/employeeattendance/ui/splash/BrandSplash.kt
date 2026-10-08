package com.jaustinjr.employeeattendance.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.ui.theme.BrandDeepBlue
import com.jaustinjr.employeeattendance.ui.theme.BrandSkyBlue
import kotlinx.coroutines.flow.first

/** The system splash's icon canvas when the icon has no background (`Theme.SplashScreen`). */
private val SplashIconSize = 288.dp

/**
 * Where the icon artwork ends inside [SplashIconSize]: `drawable/splash_icon.xml` draws down to
 * y = 742 of its 1024 viewport. The wordmark hangs below this rather than below the canvas, which
 * is mostly empty margin.
 */
private val IconArtworkBottom = SplashIconSize * (742f / 1024f)

/**
 * Steps 5-6 of the launch sequence (`SplashTiming`), drawn by the activity after the system splash
 * has played the animated icon.
 *
 * Its first frame is the system splash's last: the same sky-blue background and
 * `drawable/splash_icon.xml` at the same size, centred. That is what lets `MainActivity` remove
 * the system splash with no exit animation of its own.
 *
 * Nothing moves until [begin] is true — the system splash has been removed and this is what is on
 * screen. The icon then lifts while the wordmark rises and fades in beneath it, and everything
 * holds.
 * [onFinished] is called once that has played **and** [ready] is true, so a slow cold start waits
 * here, on the brand screen, rather than cutting to a loading spinner. The hand-off itself (easing
 * inward and cross-fading) is [com.jaustinjr.employeeattendance.ui.main.SplashGate]'s transition.
 *
 * Accessibility services see one node announced as the app name, not the two halves of the
 * wordmark — which also keeps its "Attendance" from matching tests that look for the app bar
 * title.
 */
@Composable
fun BrandSplash(
    begin: Boolean,
    ready: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val currentBegin by rememberUpdatedState(begin)
    val currentReady by rememberUpdatedState(ready)
    val currentOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        snapshotFlow { currentBegin }.first { it }
        // The hold is the tail of the same animation, not a delay(): it runs on the frame clock,
        // like the rest, so tests that drive the clock see one continuous animation.
        progress.animateTo(
            targetValue = 1f,
            animationSpec = keyframes {
                durationMillis = SplashTiming.WORDMARK_MILLIS + SplashTiming.HOLD_MILLIS
                1f at SplashTiming.WORDMARK_MILLIS using LinearOutSlowInEasing
            },
        )
        snapshotFlow { currentReady }.first { it }
        currentOnFinished()
    }

    val appName = stringResource(R.string.app_name)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(BrandSkyBlue)
            .clearAndSetSemantics { contentDescription = appName },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val liftPx = with(density) { maxHeight.toPx() } * SplashTiming.ICON_LIFT_FRACTION
        val risePx = with(density) { SplashTiming.WORDMARK_RISE_DP.dp.toPx() }
        Box(
            modifier = Modifier
                .size(SplashIconSize)
                .graphicsLayer { translationY = -liftPx * progress.value },
        ) {
            Image(
                painter = painterResource(R.drawable.splash_icon),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Wordmark(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = IconArtworkBottom + 16.dp)
                    // Wider than the icon canvas; let it overhang rather than wrap.
                    .wrapContentWidth(unbounded = true)
                    .graphicsLayer {
                        val p = progress.value
                        translationY = risePx * (1f - p)
                        alpha = p
                    },
            )
        }
    }
}

@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.splash_wordmark_eyebrow).uppercase(),
            color = BrandDeepBlue,
            fontSize = 14.sp,
            letterSpacing = 4.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.splash_wordmark_title),
            color = BrandDeepBlue,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun BrandSplashPreview() {
    BrandSplash(begin = false, ready = false, onFinished = {})
}
