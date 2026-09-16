package com.jaustinjr.employeeattendance.statusupdate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateRequest

/**
 * Mounted exactly **once**, as a sibling of the Scaffold/NavHost in `MainActivity` (wrapped in a
 * `Box`), so the Status Update prompt and card deck render over whatever destination is currently
 * on screen without navigating or disturbing it. Moving the overlay later should mean remounting
 * this composable only — it does not know about the NavHost or any destination.
 *
 * @param pendingNotificationRequest a request delivered by a notification tap, captured by the
 *   Activity in `onCreate`/`onNewIntent` and handed down once `StartupGate` opens. `null` when
 *   there is nothing new to consume.
 * @param onNotificationRequestConsumed called once [pendingNotificationRequest] has been forwarded
 *   to the ViewModel, so the Activity can clear it and a configuration change does not reopen it.
 */
@Composable
fun StatusUpdateOverlayHost(
    pendingNotificationRequest: StatusUpdateRequest?,
    onNotificationRequestConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusUpdateOverlayViewModel = viewModel(factory = StatusUpdateOverlayViewModel.Factory),
) {
    LaunchedEffect(pendingNotificationRequest) {
        pendingNotificationRequest?.let {
            viewModel.onNotificationRequest(it)
            onNotificationRequestConsumed()
        }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    StatusUpdateOverlayContent(
        state = state,
        onBeginPrompt = viewModel::onBeginPrompt,
        onDismissPrompt = viewModel::onDismissPrompt,
        onDraftChanged = viewModel::onDraftChanged,
        onForward = viewModel::onForward,
        onBack = viewModel::onBack,
        onDismissDeck = viewModel::onDismissDeck,
        modifier = modifier,
    )
}

/** Stateless content, split out so it can be exercised directly in tests and previews. */
@Composable
fun StatusUpdateOverlayContent(
    state: StatusUpdateOverlayUiState,
    onBeginPrompt: () -> Unit,
    onDismissPrompt: () -> Unit,
    onDraftChanged: (index: Int, value: String) -> Unit,
    onForward: () -> Unit,
    onBack: () -> Unit,
    onDismissDeck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.prompt?.let {
        StatusUpdatePromptDialog(
            onBegin = onBeginPrompt,
            onNotNow = onDismissPrompt,
        )
    }

    // A real Dialog (its own Window), not just a Box drawn on top: it is what actually blocks
    // touches from reaching whatever is underneath, contains focus and TalkBack navigation to the
    // deck, and gets its own back handling — a Box overlay would let a touch or a screen-reader
    // swipe fall straight through to the destination behind it. decorFitsSystemWindows = false and
    // the insets below let the deck draw edge-to-edge and still clear the keyboard/status/nav bars.
    state.deck?.let { deck ->
        Dialog(
            onDismissRequest = onDismissDeck,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                // The deck fills the entire window, so there is no "outside" area a click could
                // land on other than the deck itself — explicit so this doesn't silently start
                // dismissing the deck if a future change shrinks it below full-screen.
                dismissOnClickOutside = false,
            ),
        ) {
            StatusUpdateCardStack(
                state = deck,
                onDraftChanged = onDraftChanged,
                onForward = onForward,
                onBack = onBack,
                onDismiss = onDismissDeck,
                modifier = modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .safeDrawingPadding()
                    .imePadding(),
            )
        }
    }
}
