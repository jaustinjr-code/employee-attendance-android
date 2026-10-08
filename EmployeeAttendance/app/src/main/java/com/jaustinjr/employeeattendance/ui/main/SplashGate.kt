package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jaustinjr.employeeattendance.ui.splash.SplashTiming

/**
 * Shows [splash] — the in-app half of the launch sequence — and then [content], the app.
 *
 * Like [StartupGate] and [OnboardingGate], [content] is **not composed at all** while [showSplash]
 * is true, so nothing behind the splash (the location permission prompt in particular) starts
 * before the user can see it.
 *
 * The hand-off is step 6 of the storyboard: the splash eases inward and fades out while the app
 * fades in, over [SplashTiming.HAND_OFF_MILLIS]. As in [OnboardingGate], the theme background is
 * painted behind the cross-fade so the white window background does not show through mid-fade.
 * When [showSplash] starts false (a configuration change, or a recreated activity), nothing
 * animates.
 */
@Composable
fun SplashGate(
    showSplash: Boolean,
    splash: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = showSplash,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        transitionSpec = {
            val spec = tween<Float>(SplashTiming.HAND_OFF_MILLIS)
            fadeIn(spec) togetherWith
                (fadeOut(spec) + scaleOut(spec, targetScale = SplashTiming.HAND_OFF_TARGET_SCALE))
        },
        label = "splashGate",
    ) { show ->
        if (show) splash() else content()
    }
}
