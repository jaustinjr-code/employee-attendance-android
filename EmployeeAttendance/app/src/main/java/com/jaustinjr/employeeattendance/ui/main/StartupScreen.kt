package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * Shown while app startup wiring is still in flight (issue #58).
 *
 * The ViewModel factories read `EncryptedSharedPreferences`-backed repositories out of the
 * container, and those are still being constructed on an IO worker for the first moments of a cold
 * start. Constructing a ViewModel then would block the main thread on the `by lazy` monitor, so the
 * UI renders this instead and the main thread stays free.
 *
 * Deliberately minimal: on a warm start the gate is already open and this never composes, and on a
 * cold start it should read as a continuation of the launch, not as a distinct screen.
 */
@Composable
fun StartupScreen(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.cd_startup_loading)
    Surface(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // mergeDescendants is what actually makes this one node: a plain semantics {}
                // would ADD a node beside the CircularProgressIndicator's ProgressBarRangeInfo
                // node, and TalkBack would encounter both. Merging absorbs it, so the screen
                // announces once as "loading".
                .semantics(mergeDescendants = true) { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Preview
@Composable
private fun StartupScreenPreview() {
    EmployeeAttendanceTheme {
        StartupScreen()
    }
}
