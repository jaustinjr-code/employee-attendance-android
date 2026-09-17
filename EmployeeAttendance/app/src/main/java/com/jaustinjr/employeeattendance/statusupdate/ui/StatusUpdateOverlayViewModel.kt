package com.jaustinjr.employeeattendance.statusupdate.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateCoordinator
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * UI state for [StatusUpdateOverlayHost]: the "Start status update?" prompt (non-null => show it)
 * and, once accepted, the card deck.
 */
data class StatusUpdateOverlayUiState(
    val prompt: StatusUpdateRequest? = null,
    val deck: StatusUpdateCardStackUiState? = null,
)

/**
 * Drives the Status Update overlay: the in-app "Start status update?" confirmation and the
 * three-card deck it opens into. Activity-scoped (see [Factory] and how [StatusUpdateOverlayHost]
 * is mounted in MainActivity) so a notification tap (`onNewIntent`, cold or warm) and an in-app
 * "Begin" both feed this one instance and can never open two decks at once.
 *
 * Card drafts and the current card index live here, not in any composable's `remember`, so typed
 * text survives swiping away and back, and the deck reopens correctly across a configuration
 * change.
 */
class StatusUpdateOverlayViewModel(
    private val coordinator: StatusUpdateCoordinator,
) : ViewModel() {

    private val _deckRequest = MutableStateFlow<StatusUpdateRequest?>(null)
    private val _drafts = MutableStateFlow(emptyDrafts())
    private val _cardIndex = MutableStateFlow(0)

    init {
        // A clock-out this deck was open for can be undone (see ClockActionHandler.undo) while
        // the deck is still showing; close it without saving so the answers don't get attached to
        // an event that no longer exists.
        coordinator.undoneClockOuts
            .onEach { undoneClockOutId ->
                if (_deckRequest.value?.clockOutId == undoneClockOutId) {
                    closeDeck()
                }
            }
            .launchIn(viewModelScope)
    }

    val uiState: StateFlow<StatusUpdateOverlayUiState> = combine(
        coordinator.pendingPrompt,
        _deckRequest,
        _drafts,
        _cardIndex,
    ) { prompt, deckRequest, drafts, cardIndex ->
        StatusUpdateOverlayUiState(
            prompt = prompt,
            deck = deckRequest?.let {
                StatusUpdateCardStackUiState(drafts = drafts, currentIndex = cardIndex)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUpdateOverlayUiState())

    /**
     * "Begin" on the in-app prompt: accept it from the coordinator and open the deck.
     *
     * A no-op while a deck is already open (e.g. a second clock-out's prompt arrived and was
     * tapped before the first deck closed) — leaves the prompt in place rather than accepting and
     * discarding it, since [openDeck] would ignore the now-accepted request anyway. The user can
     * tap Begin again once the current deck closes.
     */
    fun onBeginPrompt() {
        if (_deckRequest.value != null) return
        coordinator.acceptPrompt()?.let(::openDeck)
    }

    /** "Not now", or dismissing the in-app prompt any other way. No resurfacing. */
    fun onDismissPrompt() {
        coordinator.dismissPrompt()
    }

    /**
     * A request delivered by a notification tap. The Activity calls this once per fresh intent,
     * after `StartupGate` opens; see MainActivity's `onNewIntent` handling and
     * [com.jaustinjr.employeeattendance.statusupdate.StatusUpdateIntents].
     *
     * `MainActivity` is exported, so the request is untrusted: it is routed through
     * [StatusUpdateCoordinator.claimNotificationRequest] first, which returns null (and the deck
     * does not open) for forged or stale extras — no such clock-out, or already completed.
     */
    fun onNotificationRequest(request: StatusUpdateRequest) {
        coordinator.claimNotificationRequest(request)?.let(::openDeck)
    }

    fun onDraftChanged(cardIndex: Int, value: String) {
        _drafts.update { drafts ->
            drafts.toMutableList().also { it[cardIndex] = value }
        }
    }

    /** Next on any card before the last; submits and closes the deck on the last card. */
    fun onForward() {
        val request = _deckRequest.value ?: return
        val isLastCard = _cardIndex.value == StatusUpdateCardStackUiState.CARD_COUNT - 1
        if (isLastCard) {
            val drafts = _drafts.value
            coordinator.complete(
                request = request,
                didToday = drafts[0],
                plannedTomorrow = drafts[1],
                couldNotDo = drafts[2],
            )
            closeDeck()
        } else {
            _cardIndex.update { (it + 1).coerceAtMost(StatusUpdateCardStackUiState.CARD_COUNT - 1) }
        }
    }

    /** Back a card; a no-op on the first card. */
    fun onBack() {
        _cardIndex.update { (it - 1).coerceAtLeast(0) }
    }

    /** System back, or backing all the way out of the deck: skip this status update, no resurfacing. */
    fun onDismissDeck() {
        closeDeck()
    }

    /** Ignores a second request while a deck is already open, so its drafts survive untouched. */
    private fun openDeck(request: StatusUpdateRequest) {
        if (_deckRequest.value != null) return
        _deckRequest.value = request
        _drafts.value = emptyDrafts()
        _cardIndex.value = 0
    }

    private fun closeDeck() {
        _deckRequest.value = null
        _drafts.value = emptyDrafts()
        _cardIndex.value = 0
    }

    companion object {
        private fun emptyDrafts() = List(StatusUpdateCardStackUiState.CARD_COUNT) { "" }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                StatusUpdateOverlayViewModel(container.statusUpdateCoordinator)
            }
        }
    }
}
