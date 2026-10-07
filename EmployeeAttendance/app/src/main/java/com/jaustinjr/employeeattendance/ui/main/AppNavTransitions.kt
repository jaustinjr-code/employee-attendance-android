package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/**
 * The app's one navigation transition: a cross-fade, the incoming screen fading in while the
 * outgoing one fades out over the same span.
 *
 * These are Navigation-Compose's own `NavHost` defaults (`fadeIn(tween(700))` /
 * `fadeOut(tween(700))`), spelled out so that `MainActivity`'s `NavHost` and [OnboardingGate] —
 * which sits outside the `NavHost` but should feel like the same navigation — share one definition
 * instead of each relying on the library's default staying what it is today.
 *
 * Both transitions need an opaque layer **behind** them: mid-fade, neither screen is fully opaque.
 * Inside the `NavHost` that is the `Scaffold`'s container colour; [OnboardingGate] paints
 * `colorScheme.background` itself. Without it the window background (white) shows through.
 */
object AppNavTransitions {

    /** Matches the `NavHost` default. */
    const val DURATION_MILLIS = 700

    fun enter(): EnterTransition = fadeIn(animationSpec = tween(DURATION_MILLIS))

    fun exit(): ExitTransition = fadeOut(animationSpec = tween(DURATION_MILLIS))
}
