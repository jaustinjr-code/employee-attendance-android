package com.jaustinjr.employeeattendance.legal.ui

import androidx.lifecycle.SavedStateHandle
import com.jaustinjr.employeeattendance.legal.LegalBlock
import com.jaustinjr.employeeattendance.legal.LegalDocument
import com.jaustinjr.employeeattendance.legal.LegalDocumentSource
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LegalDocumentViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(documentId: String?, source: LegalDocumentSource) = LegalDocumentViewModel(
        savedStateHandle = SavedStateHandle(mapOf(LEGAL_DOCUMENT_ID_ARG to documentId)),
        source = source,
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    @Test
    fun `loads and parses the document named by the route`() {
        val requested = mutableListOf<LegalDocument>()
        val vm = viewModel(LegalDocument.PRIVACY_POLICY.id) { document ->
            requested += document
            "# Privacy Policy\n\nBody."
        }

        assertEquals(listOf(LegalDocument.PRIVACY_POLICY), requested)
        assertEquals(
            LegalDocumentUiState.Loaded(
                LegalDocument.PRIVACY_POLICY,
                listOf(LegalBlock.Heading(1, "Privacy Policy"), LegalBlock.Paragraph("Body.")),
            ),
            vm.uiState.value,
        )
    }

    @Test
    fun `an unknown id is unavailable without touching the source`() {
        var reads = 0
        val vm = viewModel("not-a-document") { reads++; "" }

        assertEquals(LegalDocumentUiState.Unavailable, vm.uiState.value)
        assertEquals(0, reads)
    }

    @Test
    fun `a missing id is unavailable`() {
        val vm = viewModel(null) { "" }

        assertEquals(LegalDocumentUiState.Unavailable, vm.uiState.value)
    }

    @Test
    fun `a read failure is unavailable rather than a crash`() {
        val vm = viewModel(LegalDocument.PRIVACY_POLICY.id) { throw IOException("asset missing") }

        assertTrue(vm.uiState.value is LegalDocumentUiState.Unavailable)
    }
}
