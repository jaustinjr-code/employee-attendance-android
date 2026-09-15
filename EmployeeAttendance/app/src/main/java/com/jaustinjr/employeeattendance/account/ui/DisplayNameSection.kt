package com.jaustinjr.employeeattendance.account.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.R

/** Adds the display name section to a list. See [DisplayNameSection]. */
fun LazyListScope.displayNameSection(
    displayName: String,
    onDisplayNameChanged: (String) -> Unit,
) {
    item(key = "display_name_section") {
        DisplayNameSection(displayName = displayName, onDisplayNameChanged = onDisplayNameChanged)
    }
}

/**
 * The display name used in the attendance greeting. Writes straight through on every keystroke — the
 * store updates its StateFlow synchronously, so the field never lags behind what was typed, and there
 * is no draft state to lose when the screen leaves composition. The text is stored as typed; the
 * greeting trims it and falls back to a default when it is blank.
 */
@Composable
fun DisplayNameSection(
    displayName: String,
    onDisplayNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.account_name_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.account_name_desc,
                stringResource(R.string.greeting_default_name),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChanged,
            label = { Text(stringResource(R.string.account_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
