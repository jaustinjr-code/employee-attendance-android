package com.jaustinjr.employeeattendance.legal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.legal.LegalDocument
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * The legal screen, opened from Settings: one row per shipped [LegalDocument]. The list is the
 * registry itself, so a new document appears here as soon as it is added to the enum.
 */
@Composable
fun LegalScreen(
    onOpenDocument: (LegalDocument) -> Unit,
    modifier: Modifier = Modifier,
    documents: List<LegalDocument> = LegalDocument.entries,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(documents, key = { it.id }) { document ->
            ListItem(
                headlineContent = { Text(stringResource(document.titleRes)) },
                supportingContent = {
                    Text(
                        text = stringResource(document.summaryRes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                // Decorative: the row's own text and click role already say it opens the document.
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(role = Role.Button) { onOpenDocument(document) },
            )
            HorizontalDivider()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LegalScreenPreview() {
    EmployeeAttendanceTheme {
        LegalScreen(onOpenDocument = {})
    }
}
