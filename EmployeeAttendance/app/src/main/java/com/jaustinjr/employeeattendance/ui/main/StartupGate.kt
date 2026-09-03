package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.runtime.Composable

/**
 * Withholds [content] until app startup wiring has finished (issue #58).
 *
 * The ViewModel factories downstream of this read `EncryptedSharedPreferences`-backed repositories
 * out of the app container. On a cold start those are still being constructed on an IO worker, and
 * the container's fields are `SYNCHRONIZED` `by lazy` — so composing a factory before startup
 * settles would block the main thread on a monitor held by that worker. `ViewModelProvider.Factory`
 * cannot suspend, so the wait has to happen above it, here.
 *
 * The load-bearing property is that [content] is **not composed at all** while [started] is false —
 * not merely hidden. Anything that only made it invisible would still run the factories.
 *
 * @param started whether startup wiring has reached a terminal state. Terminal includes failure, so
 *   a broken startup shows the app rather than an endless loading screen.
 */
@Composable
fun StartupGate(started: Boolean, content: @Composable () -> Unit) {
    if (started) content() else StartupScreen()
}
