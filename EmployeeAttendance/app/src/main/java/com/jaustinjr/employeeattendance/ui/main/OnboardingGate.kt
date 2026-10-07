package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows [onboarding] on first launch and [content] — the app itself — once it is finished.
 *
 * Like [StartupGate], the load-bearing property is that [content] is **not composed at all** while
 * [showOnboarding] is true. Composing it would construct the location ViewModels and the
 * attendance screen behind the carousel, which can raise the location permission prompt over the
 * onboarding the user is still reading.
 *
 * Finishing onboarding animates to the app with [AppNavTransitions] — the same cross-fade every
 * in-app navigation uses — so it reads as ordinary navigation to the home screen. [content] opens
 * the app's `NavHost` at [AppNavGraph.root], which it draws fully on its first frame, so the
 * cross-fade is between the carousel and the finished home screen. A returning user starts with
 * [showOnboarding] already false, and `AnimatedContent` does not animate its initial state.
 */
@Composable
fun OnboardingGate(
    showOnboarding: Boolean,
    onboarding: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = showOnboarding,
        // Mid-fade neither screen is opaque. Inside the NavHost the Scaffold's container colour sits
        // behind its cross-fade; out here nothing does, and the window's own background is the
        // platform default white even in dark theme. Painting the theme background here is what
        // keeps this cross-fade looking like the in-app ones instead of flashing white.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        transitionSpec = { AppNavTransitions.enter() togetherWith AppNavTransitions.exit() },
        label = "onboardingGate",
    ) { show ->
        if (show) onboarding() else content()
    }
}
