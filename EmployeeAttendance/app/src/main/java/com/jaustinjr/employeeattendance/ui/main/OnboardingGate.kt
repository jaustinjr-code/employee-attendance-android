package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.runtime.Composable

/**
 * Shows [onboarding] on first launch and [content] — the app itself — once it is finished.
 *
 * Like [StartupGate], the load-bearing property is that [content] is **not composed at all** while
 * [showOnboarding] is true. Composing it would construct the location ViewModels and the
 * attendance screen behind the carousel, which can raise the location permission prompt over the
 * onboarding the user is still reading.
 *
 * The switch is deliberately not animated. [content] opens the app's `NavHost` at its start
 * destination — [AppNavGraph.root], the designated home — exactly as a normal launch does, and that
 * screen is fully drawn on its first frame. An earlier animated hand-off (a cross-fade, then a
 * fade-in over the carousel) let the window background show through as a white flash.
 */
@Composable
fun OnboardingGate(
    showOnboarding: Boolean,
    onboarding: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (showOnboarding) onboarding() else content()
}
