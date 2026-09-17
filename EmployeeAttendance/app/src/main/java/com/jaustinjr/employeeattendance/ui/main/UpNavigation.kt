package com.jaustinjr.employeeattendance.ui.main

import androidx.navigation.NavController

/**
 * An up-button interceptor, registered by whichever screen is current and must confirm before
 * leaving. [entryId] is the registering screen's own `NavBackStackEntry.id` — carried alongside
 * [onUp] so [performUp] can tell a still-current registration from a stale one, rather than trusting
 * that whoever cleared the last registration did so promptly. See [performUp]'s doc for why identity
 * matters on the read side, and [updateInterceptor]'s for why it matters on the write side too.
 */
data class UpInterceptor(val entryId: String, val onUp: () -> Unit)

/**
 * The app bar's up-button rule, in one place so `MainActivity`'s real wiring and any test hosting
 * the same app bar exercise the identical logic rather than two copies that can drift apart.
 *
 * Falls through to a direct [NavController.popBackStack] unless [interceptor] is both non-null and
 * still owned by the *current* back stack entry (`interceptor.entryId == currentEntryId`).
 *
 * The id check, not just non-nullness, is what makes this safe to register as early as first
 * composition rather than gating registration on the owning screen reaching RESUMED: a screen can
 * register the instant it composes (closing the ~700ms window during the *enter* transition where a
 * naively RESUMED-gated interceptor would not yet be registered, letting up silently skip the
 * screen's confirmation), while a stale registration left behind by a screen that is mid-*exit*
 * transition (and so still composed, but no longer the current entry) is harmless: its `entryId` no
 * longer matches `currentEntryId`, so `performUp` falls through to a plain pop instead of invoking a
 * handler that belongs to a screen the user has already left.
 */
fun performUp(interceptor: UpInterceptor?, navController: NavController) {
    val currentEntryId = navController.currentBackStackEntry?.id
    if (interceptor != null && interceptor.entryId == currentEntryId) {
        interceptor.onUp()
    } else {
        navController.popBackStack()
    }
}

/**
 * Computes the interceptor slot's next value from a registration event, so a screen's write is as
 * id-safe as [performUp]'s read.
 *
 * [performUp] alone is not enough: a screen that composes the same instant another one is mid-exit
 * (open the editor, discard it — its composition survives the ~700ms exit transition — then reopen
 * the editor before that transition finishes) races its own registration against the departing
 * screen's delayed `onDispose`. Both screens write to the same slot; without this reducer, "last
 * write wins" means the reopened screen's fresh registration can be overwritten by the *older*
 * screen's `onDispose(null)`, silently and permanently — its own `DisposableEffect` already ran and
 * will not fire again, so up stays unguarded for the rest of that screen's life, not just for a
 * window.
 *
 * [onUp] non-null always wins: a fresh registration is always newer information than a clear, so it
 * always replaces [current], whoever owns it. A clear ([onUp] null) is only honored when [entryId]
 * — the id of whoever is asking to clear — still matches [current]'s owner; a clear from an id that
 * no longer owns the slot (the exact scenario above) is a no-op, leaving whatever newer registration
 * is already there untouched.
 */
fun updateInterceptor(current: UpInterceptor?, entryId: String, onUp: (() -> Unit)?): UpInterceptor? =
    when {
        onUp != null -> UpInterceptor(entryId, onUp)
        current?.entryId == entryId -> null
        else -> current
    }
