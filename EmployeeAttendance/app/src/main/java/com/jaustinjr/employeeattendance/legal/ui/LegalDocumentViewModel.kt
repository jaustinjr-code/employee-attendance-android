package com.jaustinjr.employeeattendance.legal.ui

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.legal.LegalBlock
import com.jaustinjr.employeeattendance.legal.LegalDocument
import com.jaustinjr.employeeattendance.legal.LegalDocumentSource
import com.jaustinjr.employeeattendance.legal.parseLegalText
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Navigation argument naming the [LegalDocument.id] a document screen shows. */
const val LEGAL_DOCUMENT_ID_ARG = "documentId"

/** What the legal document screen renders. */
sealed interface LegalDocumentUiState {
    data object Loading : LegalDocumentUiState

    data class Loaded(val document: LegalDocument, val blocks: List<LegalBlock>) : LegalDocumentUiState

    /** The id names no shipped document, or its text could not be read. */
    data object Unavailable : LegalDocumentUiState
}

/** Loads and parses the document named by [LEGAL_DOCUMENT_ID_ARG], off the main thread. */
class LegalDocumentViewModel(
    savedStateHandle: SavedStateHandle,
    private val source: LegalDocumentSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LegalDocumentUiState>(LegalDocumentUiState.Loading)
    val uiState: StateFlow<LegalDocumentUiState> = _uiState.asStateFlow()

    init {
        val documentId: String? = savedStateHandle[LEGAL_DOCUMENT_ID_ARG]
        val document = LegalDocument.fromId(documentId)
        if (document == null) {
            Log.w(TAG, "no legal document with id $documentId")
            _uiState.value = LegalDocumentUiState.Unavailable
        } else {
            viewModelScope.launch { _uiState.value = load(document) }
        }
    }

    private suspend fun load(document: LegalDocument): LegalDocumentUiState = try {
        val blocks = withContext(ioDispatcher) { parseLegalText(source.read(document)) }
        LegalDocumentUiState.Loaded(document, blocks)
    } catch (e: IOException) {
        Log.w(TAG, "failed to read ${document.assetPath}", e)
        LegalDocumentUiState.Unavailable
    }

    companion object {
        private const val TAG = "LegalDocument"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                LegalDocumentViewModel(createSavedStateHandle(), container.legalDocumentSource)
            }
        }
    }
}
