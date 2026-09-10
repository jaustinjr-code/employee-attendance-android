package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.jaustinjr.employeeattendance.R

/**
 * The app bar shared by every destination.
 *
 * The navigation slot carries one of two affordances, never both: on a child destination it is an
 * up button that pops a single entry off the back stack — landing on the destination the user
 * came from, not on home — and on the root destination it reverts to the account affordance.
 * [showUpButton] is derived from the current back stack entry by the caller (see
 * `isChildDestination`), so it can never disagree with what the NavHost is rendering.
 *
 * @param onTitleClick optional hidden gesture on the title. Debug builds pass a handler that counts
 *   taps to reveal developer settings (see
 *   [com.jaustinjr.employeeattendance.devtools.DevUnlockTapCounter]); release builds pass null and
 *   the title is not clickable at all. The click carries no ripple or semantics role on purpose —
 *   it is meant to be invisible to anyone not looking for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppBar(
    title: String,
    showUpButton: Boolean = false,
    onNavigateUp: () -> Unit = {},
    onOpenWorksites: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
) {
    TopAppBar(title = {
        val interactionSource = remember { MutableInteractionSource() }
        val titleModifier = if (onTitleClick != null) {
            Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTitleClick,
            )
        } else {
            Modifier
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = titleModifier)
    },
        navigationIcon = {
            if (showUpButton) {
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.cd_navigate_back),
                    )
                }
            } else {
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(R.drawable.account_circle_24px),
                        contentDescription = stringResource(R.string.cd_account),
                    )
                }
            }
        },
        actions = {
            var menuExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.settings_24px),
                    contentDescription = stringResource(R.string.cd_more_options),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_worksites)) },
                    onClick = {
                        menuExpanded = false
                        onOpenWorksites()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_settings)) },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    },
                )
            }
        })
}
