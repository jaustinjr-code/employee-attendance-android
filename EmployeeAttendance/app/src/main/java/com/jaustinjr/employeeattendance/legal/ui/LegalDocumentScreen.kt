package com.jaustinjr.employeeattendance.legal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.legal.LegalBlock
import com.jaustinjr.employeeattendance.legal.LegalDocument
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/** Test tags for the androidTest layer; not user-visible. */
object LegalDocumentTestTags {
    const val CONTENT = "legal_document_content"
}

/** One legal document, read-only. The document is named by the route's [LEGAL_DOCUMENT_ID_ARG]. */
@Composable
fun LegalDocumentScreen(
    modifier: Modifier = Modifier,
    viewModel: LegalDocumentViewModel = viewModel(factory = LegalDocumentViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LegalDocumentContent(uiState = uiState, modifier = modifier)
}

@Composable
fun LegalDocumentContent(
    uiState: LegalDocumentUiState,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        LegalDocumentUiState.Loading -> Box(modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        LegalDocumentUiState.Unavailable -> Box(modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
            Text(
                text = stringResource(R.string.legal_document_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        is LegalDocumentUiState.Loaded -> LazyColumn(
            modifier = modifier.fillMaxSize().navigationBarsPadding().testTag(LegalDocumentTestTags.CONTENT),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(uiState.blocks) { index, block ->
                LegalBlockText(
                    block = block,
                    // Extra air above each section heading so sections read as separate units.
                    modifier = if (block is LegalBlock.Heading && block.level > 1 && index > 0) {
                        Modifier.padding(top = 12.dp)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun LegalBlockText(block: LegalBlock, modifier: Modifier = Modifier) {
    when (block) {
        is LegalBlock.Heading -> Text(
            text = block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            modifier = modifier.semantics { heading() },
        )
        is LegalBlock.Paragraph -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        is LegalBlock.Bullet -> Row(
            modifier = modifier.fillMaxWidth().padding(start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "•", style = MaterialTheme.typography.bodyMedium)
            Text(text = block.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LegalDocumentPreview() {
    EmployeeAttendanceTheme {
        LegalDocumentContent(
            uiState = LegalDocumentUiState.Loaded(
                document = LegalDocument.PRIVACY_POLICY,
                blocks = listOf(
                    LegalBlock.Heading(1, "Privacy Policy"),
                    LegalBlock.Paragraph("Effective date: October 5, 2026"),
                    LegalBlock.Heading(2, "Information the app uses"),
                    LegalBlock.Paragraph("The app works on your device."),
                    LegalBlock.Bullet("Precise or approximate location while the app is open."),
                    LegalBlock.Bullet("Background location, only if you choose to grant it."),
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LegalDocumentUnavailablePreview() {
    EmployeeAttendanceTheme {
        LegalDocumentContent(uiState = LegalDocumentUiState.Unavailable)
    }
}
