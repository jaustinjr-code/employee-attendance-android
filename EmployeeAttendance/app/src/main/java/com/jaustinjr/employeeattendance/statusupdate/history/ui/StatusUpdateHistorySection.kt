package com.jaustinjr.employeeattendance.statusupdate.history.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateDay
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateShift

/** Test tags for the androidTest layer; not user-visible. */
object StatusUpdateHistoryTestTags {
    const val PEEK = "status_update_history_peek"
    fun shift(clockOutId: String) = "status_update_history_shift_$clockOutId"
}

/**
 * Adds the status update history section to a list: a header, then each day's work shifts, newest
 * first. Each shift is a compact row showing its time, worksite and a short look at what was
 * written. Tap a shift to open it ([onOpenShift]); press and hold to peek at its full summary
 * without leaving the list.
 *
 * Every item key is prefixed so the section can share a list with other sections.
 */
fun LazyListScope.statusUpdateHistorySection(
    days: List<StatusUpdateDay>,
    onOpenShift: (clockOutId: String) -> Unit,
) {
    item(key = "status_update_history_header") {
        StatusUpdateHistoryHeader()
    }
    if (days.isEmpty()) {
        item(key = "status_update_history_empty") {
            Text(
                text = stringResource(R.string.status_update_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
    days.forEach { day ->
        item(key = "status_update_history_day_${day.dayStartMillis}") {
            Text(
                text = dayLabel(day.dayStartMillis),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 8.dp)
                    .semantics { heading() },
            )
        }
        items(day.shifts, key = { "status_update_history_shift_${it.clockOutId}" }) { shift ->
            StatusUpdateShiftRow(
                shift = shift,
                onOpen = { onOpenShift(shift.clockOutId) },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusUpdateHistoryHeader(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.status_update_history_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.status_update_history_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class PeekMode { HIDDEN, WHILE_HELD, UNTIL_DISMISSED }

/**
 * One work shift in the history. Tapping opens it. Pressing and holding shows [StatusUpdatePeek]
 * until the finger lifts (or the press turns into a scroll). Screen readers get the same two actions:
 * the long-press action keeps the peek open until it is dismissed, since there is no finger to lift.
 */
@Composable
fun StatusUpdateShiftRow(
    shift: StatusUpdateShift,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var peek by remember { mutableStateOf(PeekMode.HIDDEN) }
    val currentOnOpen by rememberUpdatedState(onOpen)
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val openLabel = stringResource(R.string.status_update_history_action_open)
    val peekLabel = stringResource(R.string.status_update_history_action_peek)

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(StatusUpdateHistoryTestTags.shift(shift.clockOutId))
            .clip(CardDefaults.outlinedShape)
            .indication(interactionSource, ripple())
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactionSource.emit(press)
                        val released = tryAwaitRelease()
                        interactionSource.emit(
                            if (released) PressInteraction.Release(press)
                            else PressInteraction.Cancel(press),
                        )
                        if (peek == PeekMode.WHILE_HELD) peek = PeekMode.HIDDEN
                    },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        peek = PeekMode.WHILE_HELD
                    },
                    onTap = { currentOnOpen() },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = openLabel) {
                    currentOnOpen()
                    true
                }
                onLongClick(label = peekLabel) {
                    peek = PeekMode.UNTIL_DISMISSED
                    true
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = shiftTimeRange(shift), style = MaterialTheme.typography.titleSmall)
            Text(
                text = shiftWorksite(shift),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = shift.previewText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val more = shift.answers.size - 1
            if (more > 0) {
                Text(
                    text = pluralStringResource(R.plurals.status_update_history_more_answers, more, more),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (peek != PeekMode.HIDDEN) {
        StatusUpdatePeek(
            shift = shift,
            onDismiss = if (peek == PeekMode.UNTIL_DISMISSED) {
                { peek = PeekMode.HIDDEN }
            } else {
                null
            },
        )
    }
}

/**
 * A summary of [shift] floating over the screen: its time, worksite and every answer in full.
 *
 * While shown for a held press ([onDismiss] null) the popup is not focusable, so the press stays with
 * the row underneath and lifting the finger hides it. With [onDismiss] it takes focus and closes on
 * back or a tap outside the card.
 */
@Composable
private fun StatusUpdatePeek(shift: StatusUpdateShift, onDismiss: (() -> Unit)?) {
    Popup(
        popupPositionProvider = WindowCenterPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = onDismiss != null,
            dismissOnBackPress = onDismiss != null,
            dismissOnClickOutside = onDismiss != null,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .testTag(StatusUpdateHistoryTestTags.PEEK),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = shiftTimeRange(shift), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = shiftWorksite(shift),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusUpdateAnswers(shift)
                }
            }
        }
    }
}

/** Every answered question of [shift] with its answer in full. Shared by the peek and the detail screen. */
@Composable
fun StatusUpdateAnswers(shift: StatusUpdateShift, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shift.answers.forEach { answered ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(answered.question.questionRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(text = answered.answer, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Places a popup over the whole window, independent of the row that opened it. */
private object WindowCenterPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        (windowSize.width - popupContentSize.width) / 2,
        (windowSize.height - popupContentSize.height) / 2,
    )
}
