package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable

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
        transitionSpec = { onboardingExitTransition() },
        label = "onboardingGate",
    ) { show ->
        if (show) onboarding() else content()
    }
}

/**
 * The carousel recedes — fading while it grows slightly, as if the user is stepping through it —
 * and the home screen settles in from just below full size. The enter is delayed a beat so the two
 * never read as a muddy cross-fade.
 */
private fun AnimatedContentTransitionScope<Boolean>.onboardingExitTransition(): ContentTransform =
    (
        fadeIn(tween(ENTER_MILLIS, delayMillis = ENTER_DELAY_MILLIS, easing = LinearOutSlowInEasing)) +
            scaleIn(
                animationSpec = tween(ENTER_MILLIS, delayMillis = ENTER_DELAY_MILLIS, easing = FastOutSlowInEasing),
                initialScale = 0.92f,
            )
        ) togetherWith (
        fadeOut(tween(EXIT_MILLIS, easing = FastOutSlowInEasing)) +
            scaleOut(
                animationSpec = tween(EXIT_MILLIS, easing = FastOutSlowInEasing),
                targetScale = 1.08f,
            )
        )

private const val EXIT_MILLIS = 300
private const val ENTER_DELAY_MILLIS = 120
private const val ENTER_MILLIS = 450
