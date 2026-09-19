package com.jaustinjr.employeeattendance.statusupdate.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateQuestion
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch

/**
 * UI state for the Status Update card stack: the drafts (indexed to [StatusUpdateQuestion])
 * and which card is on top. Held by [StatusUpdateOverlayViewModel] so typed text survives swiping
 * away and back.
 */
data class StatusUpdateCardStackUiState(
    val drafts: List<String>,
    val currentIndex: Int,
) {
    init {
        require(drafts.size == CARD_COUNT) {
            "expected $CARD_COUNT drafts, got ${drafts.size}"
        }
        require(currentIndex in drafts.indices) {
            "currentIndex $currentIndex out of range ${drafts.indices}"
        }
    }

    val isFirstCard: Boolean get() = currentIndex == 0
    val isLastCard: Boolean get() = currentIndex == drafts.lastIndex

    companion object {
        /** One card per [StatusUpdateQuestion]. */
        val CARD_COUNT: Int get() = StatusUpdateQuestion.COUNT
    }
}

/** Pixel distance a horizontal drag must cover before it counts as a swipe, not a text-field drag. */
private val SwipeThreshold = 96.dp

/** Card width / height when there is room for it; the card shrinks below this when there is not. */
private const val CardAspectRatio = 0.8f

/** How much of the previous and next cards shows at the screen edges. */
private val PeekWidth = 28.dp

/** Space between the front card and a neighbour's peeking edge. */
private val CardGap = 16.dp

/**
 * How far a card two positions away shows from behind its neighbour, inside [CardGap], so a deck
 * of more than one remaining card reads as a stack.
 */
private val StackInset = 10.dp

/** Height fraction a card two positions away loses, so its edge sits visibly behind the neighbour. */
private const val StackScaleStep = 0.12f

private const val SlideDurationMillis = 300

/**
 * Stateless three-card deck for the Status Update feature. Swipe left / "Next" slides the card off
 * to the left and the next one in (submitting on the last card); swipe right / "Back" slides back,
 * stopping at the first card. The previous and next cards peek from the screen edges. Knows nothing
 * about where it's mounted, permissions, or persistence — see [StatusUpdateOverlayHost].
 *
 * [onBack] on the first card is expected to leave the index unchanged.
 */
@Composable
fun StatusUpdateCardStack(
    state: StatusUpdateCardStackUiState,
    onDraftChanged: (index: Int, value: String) -> Unit,
    onForward: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // System back (or backing out of the deck) skips this status update entirely; no resurfacing.
    BackHandler(onBack = onDismiss)

    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { SwipeThreshold.toPx() }
    val stackInsetPx = with(density) { StackInset.toPx() }
    val lastIndex = state.drafts.lastIndex

    // Continuous deck position: equal to currentIndex at rest, fractional while sliding or dragging.
    val position = remember { Animatable(state.currentIndex.toFloat()) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val slideSpec = tween<Float>(SlideDurationMillis, easing = FastOutSlowInEasing)

    LaunchedEffect(state.currentIndex) {
        // Typing must not keep going into the card that is sliding away.
        focusManager.clearFocus()
        position.animateTo(state.currentIndex.toFloat(), slideSpec)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Cards stop short of the screen edges by the peek width plus the gap, so the neighbours'
        // edges show there. Height follows the space actually available (it shrinks when the
        // keyboard opens), so the card can never overflow the deck.
        val cardWidth = (maxWidth - (PeekWidth + CardGap) * 2).coerceAtLeast(0.dp)
        val cardHeight = minOf(maxHeight, cardWidth / CardAspectRatio).coerceAtLeast(0.dp)
        val slidePx = with(density) { (cardWidth + CardGap).toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.currentIndex, slidePx) {
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragTotal += dragAmount
                            val dragged = (state.currentIndex - dragTotal / slidePx)
                                .coerceIn(0f, lastIndex.toFloat())
                            scope.launch { position.snapTo(dragged) }
                        },
                        onDragEnd = {
                            val forward = dragTotal <= -swipeThresholdPx
                            val back = dragTotal >= swipeThresholdPx
                            when {
                                forward -> onForward()
                                back -> onBack()
                            }
                            val indexChanges = (forward && state.currentIndex < lastIndex) ||
                                (back && state.currentIndex > 0)
                            // When the index changes, the LaunchedEffect above slides to the new card.
                            if (!indexChanges) {
                                scope.launch {
                                    position.animateTo(state.currentIndex.toFloat(), slideSpec)
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                position.animateTo(state.currentIndex.toFloat(), slideSpec)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            state.drafts.indices.forEach { index ->
                key(index) {
                    val isCurrent = index == state.currentIndex
                    Box(
                        modifier = Modifier
                            .zIndex(-abs(index - state.currentIndex).toFloat())
                            .size(cardWidth, cardHeight)
                            .offset {
                                val x = cardOffsetX(index - position.value, slidePx, stackInsetPx)
                                IntOffset(x.roundToInt(), 0)
                            }
                            .graphicsLayer {
                                val distance = abs(index - position.value)
                                scaleY = 1f - (distance - 1f).coerceIn(0f, 1f) * StackScaleStep
                                alpha = (3f - distance).coerceIn(0f, 1f)
                            }
                            .then(
                                if (isCurrent) {
                                    Modifier
                                } else {
                                    // A peeking card is a visual cue only: hidden from
                                    // accessibility and not a place to type or tap.
                                    Modifier.clearAndSetSemantics {
                                        testTag = StatusUpdateTestTags.peek(index)
                                    }
                                }
                            ),
                    ) {
                        QuestionCard(
                            question = StatusUpdateQuestion.entries[index],
                            draft = state.drafts[index],
                            isFirstCard = index == 0,
                            isLastCard = index == lastIndex,
                            interactive = isCurrent,
                            onDraftChanged = { onDraftChanged(index, it) },
                            onForward = onForward,
                            onBack = onBack,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (!isCurrent) {
                            // Being hit-tested on top of the card keeps taps from reaching its
                            // field and buttons; drags still reach the deck's gesture handler.
                            Box(Modifier.matchParentSize().pointerInput(Unit) {})
                        }
                    }
                }
            }
        }
    }
}

/**
 * Horizontal offset, from the deck centre, of a card [distance] positions from the deck position.
 * Up to one position away the card slides a full card width plus gap, which leaves its edge peeking
 * from the screen edge. Between one and two positions it tucks back by [stackInsetPx], so it shows
 * as a thinner edge behind its neighbour.
 */
private fun cardOffsetX(distance: Float, slidePx: Float, stackInsetPx: Float): Float {
    val magnitude = abs(distance)
    val x = if (magnitude <= 1f) {
        magnitude * slidePx
    } else {
        slidePx - (minOf(magnitude, 2f) - 1f) * stackInsetPx
    }
    return x * sign(distance)
}

/**
 * A single question card: a one-line prompt, a text box for the draft answer with an example
 * answer as its hint, and the Back / Next-or-Done controls, all inside the card.
 */
@Composable
private fun QuestionCard(
    question: StatusUpdateQuestion,
    draft: String,
    isFirstCard: Boolean,
    isLastCard: Boolean,
    interactive: Boolean,
    onDraftChanged: (String) -> Unit,
    onForward: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val questionText = stringResource(question.questionRes)
    val focusable = Modifier.focusProperties { canFocus = interactive }
    Card(
        modifier = modifier.testTag(StatusUpdateTestTags.CARD),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        // A bold outline in the theme's outline colour keeps every card's edge visible in both
        // light and dark themes, including the slivers peeking from the screen edges.
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = questionText,
                style = MaterialTheme.typography.titleMedium,
                // The live region is scoped to this text, not the card, so a new card announces its
                // question once without re-announcing on every keystroke in the field below.
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                placeholder = { Text(stringResource(question.hintRes)) },
                // No visible label (the heading above says the same thing), so the question is
                // given to accessibility services as the field's name instead.
                modifier = focusable
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics { contentDescription = questionText },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isFirstCard) {
                    Spacer(Modifier)
                } else {
                    TextButton(onClick = onBack, modifier = focusable) {
                        Text(stringResource(R.string.status_update_back))
                    }
                }
                Button(onClick = onForward, modifier = focusable) {
                    Text(
                        stringResource(
                            if (isLastCard) R.string.status_update_done
                            else R.string.status_update_next
                        ),
                    )
                }
            }
        }
    }
}

/** Test tags for the androidTest layer; not user-visible. */
object StatusUpdateTestTags {
    const val CARD = "status_update_card"

    /** The card at [index] while it is not the front card, peeking from a screen edge. */
    fun peek(index: Int) = "status_update_peek_$index"
}

private fun previewState() = StatusUpdateCardStackUiState(
    drafts = listOf("Finished the quarterly report", "", ""),
    currentIndex = 1,
)

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun StatusUpdateCardStackPreview() {
    EmployeeAttendanceTheme {
        StatusUpdateCardStack(
            state = previewState(),
            onDraftChanged = { _, _ -> },
            onForward = {},
            onBack = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusUpdateCardStackPreviewDark() {
    EmployeeAttendanceTheme {
        StatusUpdateCardStack(
            state = previewState(),
            onDraftChanged = { _, _ -> },
            onForward = {},
            onBack = {},
            onDismiss = {},
        )
    }
}
