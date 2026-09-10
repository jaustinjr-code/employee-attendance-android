package com.jaustinjr.employeeattendance.ui.main

import androidx.annotation.StringRes

/**
 * The app bar title for a rendered navigation destination, keyed by its route.
 *
 * This is deliberately a *pure function of the current destination* rather than state each screen
 * pushes into the app bar from a side effect. The app bar lives outside the `NavHost`, and a
 * predictive-back gesture keeps the outgoing and incoming destinations composed at the same time —
 * so with push-based state the displayed title depends on the relative ordering of two screens'
 * effects, and the screen the user is leaving can write last and win. Deriving the title from the
 * destination removes the ordering question entirely: there is one writer, and it is whatever the
 * back stack currently says is on screen.
 *
 * The titles themselves live on the destinations in [AppNavGraph], so a screen's title and its
 * place in the hierarchy are declared together and cannot be added one without the other. An
 * unrecognised or not-yet-resolved route (null on the very first frame) falls back to the root
 * destination's title — see [destinationOrRoot].
 */
@StringRes
fun appBarTitleResFor(route: String?): Int = destinationOrRoot(route).titleRes
