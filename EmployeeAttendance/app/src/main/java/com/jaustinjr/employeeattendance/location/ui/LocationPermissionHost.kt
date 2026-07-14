package com.jaustinjr.employeeattendance.location.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.permission.LocationPermissions

/**
 * Hosts the location permission rationale flow: it observes [LocationPermissionViewModel], keeps
 * the permission state fresh across lifecycle resumes, owns the Android system permission launcher,
 * and renders the correct Material 3 rationale dialog for the current step.
 *
 * Drop this once high in the composition (e.g. from the screen that gates the location feature). It
 * renders nothing when no prompt is pending.
 */
@Composable
fun LocationPermissionHost(
    viewModel: LocationPermissionViewModel = viewModel(factory = LocationPermissionViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The user can grant or revoke permissions from system settings while we're backgrounded, and
    // the "Allow all the time" upgrade happens in Settings. Re-read on every resume so the UI and
    // downstream tracking react to those out-of-app changes.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onPermissionResult()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activity = context as? Activity
    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onPermissionResult()
        val granted = result[LocationPermissions.FINE] == true ||
            result[LocationPermissions.COARSE] == true
        if (!granted && activity != null) {
            // If the OS will no longer show the runtime dialog, the denial is permanent and the only
            // path forward is app settings.
            val canRetry = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                LocationPermissions.FINE,
            )
            viewModel.onForegroundDenied(canRetry)
        }
    }

    when (uiState.visiblePrompt) {
        LocationPermissionPrompt.EnableForeground -> {
            val blocked = uiState.requiresSettingsForForeground
            LocationPermissionRationaleDialog(
                title = stringResource(R.string.location_permission_enable_title),
                description = stringResource(
                    if (blocked) R.string.location_permission_blocked_body
                    else R.string.location_permission_enable_body,
                ),
                confirmLabel = stringResource(
                    if (blocked) R.string.location_permission_open_settings
                    else R.string.location_permission_enable_confirm,
                ),
                dismissLabel = stringResource(R.string.location_permission_dismiss),
                onConfirm = {
                    // Suppress the in-app rationale for the rest of the session before handing off
                    // to the system prompt, so a denial doesn't immediately re-nag.
                    viewModel.onPromptDismissed(LocationPermissionPrompt.EnableForeground)
                    if (blocked) {
                        // Runtime dialog would be silently auto-denied; send the user to settings.
                        context.startActivity(appSettingsIntent(context.packageName))
                    } else {
                        // Request foreground location plus (API 33+) notifications, so the tracking
                        // notification that discloses background location use can be shown.
                        foregroundLauncher.launch(LocationPermissions.initialRequest)
                    }
                },
                onDismiss = { viewModel.onPromptDismissed(LocationPermissionPrompt.EnableForeground) },
            )
        }

        LocationPermissionPrompt.UpgradeToAlways -> {
            LocationPermissionRationaleDialog(
                title = stringResource(R.string.location_permission_upgrade_title),
                description = stringResource(R.string.location_permission_upgrade_body),
                confirmLabel = stringResource(R.string.location_permission_upgrade_confirm),
                dismissLabel = stringResource(R.string.location_permission_dismiss),
                onConfirm = {
                    viewModel.onPromptDismissed(LocationPermissionPrompt.UpgradeToAlways)
                    // Background ("Allow all the time") cannot be requested via a runtime dialog
                    // once foreground is granted on modern Android; the user must choose it in the
                    // app's system settings. onPermissionResult() on resume will pick up the change.
                    context.startActivity(appSettingsIntent(context.packageName))
                },
                onDismiss = { viewModel.onPromptDismissed(LocationPermissionPrompt.UpgradeToAlways) },
            )
        }

        null -> Unit
    }
}

private fun appSettingsIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
