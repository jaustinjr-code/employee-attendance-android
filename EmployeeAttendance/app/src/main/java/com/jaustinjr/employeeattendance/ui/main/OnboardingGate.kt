package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows [onboarding] on first launch and [content] — the app itself — once it is finished, with a
 * transition from one to the other.
 *
 * Like [StartupGate], the load-bearing property is that [content] is **not composed at all** while
 * [showOnboarding] is true. Composing it would construct the location ViewModels and the
 * attendance screen behind the carousel, which can raise the location permission prompt over the
 * onboarding the user is still reading.
 *
 * The transition only plays when [showOnboarding] changes while composed: a returning user starts
 * with it already false and lands on [content] with no animation.
 */
@Composable
fun OnboardingGate(
    showOnboarding: Boolean,
    onboarding: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = showOnboarding,
        // Painted behind both screens: the window's own background is the platform default (white,
        // even in dark theme), and any frame where neither screen fully covers the window would
        // otherwise flash it.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        transitionSpec = { onboardingExitTransition() },
        label = "onboardingGate",
    ) { show ->
        if (show) onboarding() else content()
    }
}

/**
 * The home screen fades in **on top of** the carousel while settling from just below full size;
 * the carousel stays fully opaque underneath, growing slightly as if the user is stepping through
 * it, and is removed once the home screen has covered it.
 *
 * The outgoing screen deliberately does not fade. An earlier version cross-faded the two with a
 * delayed enter, and in the gap — carousel mostly gone, home screen not yet visible — the window
 * background showed through as a white flash. With one screen always opaque there is no such frame.
 */
private fun AnimatedContentTransitionScope<Boolean>.onboardingExitTransition(): ContentTransform =
    (
        fadeIn(tween(ENTER_MILLIS, easing = LinearOutSlowInEasing)) +
            scaleIn(
                animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
                initialScale = ENTER_INITIAL_SCALE,
            )
        ).togetherWith(
        scaleOut(
            animationSpec = tween(ENTER_MILLIS, easing = FastOutSlowInEasing),
            targetScale = EXIT_TARGET_SCALE,
        ),
    ).apply {
        // Explicit rather than relying on composition order: the incoming screen must draw above
        // the opaque outgoing one, or it would fade in behind it and never be seen until the swap.
        targetContentZIndex = 1f
    }

private const val ENTER_MILLIS = 450
private const val ENTER_INITIAL_SCALE = 0.94f
private const val EXIT_TARGET_SCALE = 1.04f
