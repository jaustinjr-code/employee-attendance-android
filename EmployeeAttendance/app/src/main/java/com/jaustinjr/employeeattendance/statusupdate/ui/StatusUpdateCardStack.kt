package com.jaustinjr.employeeattendance.statusupdate.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * UI state for the Status Update card stack: the three drafts (indexed to [StatusUpdateQuestion])
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
        const val CARD_COUNT = 3
    }
}

/** The three fixed Status Update questions, in draft-index order, each with an example answer hint. */
enum class StatusUpdateQuestion(val questionRes: Int, val hintRes: Int) {
    DID_TODAY(
        R.string.status_update_question_did_today,
        R.string.status_update_hint_did_today,
    ),
    PLANNED_TOMORROW(
        R.string.status_update_question_planned_tomorrow,
        R.string.status_update_hint_planned_tomorrow,
    ),
    COULD_NOT_DO(
        R.string.status_update_question_could_not_do,
        R.string.status_update_hint_could_not_do,
    ),
}

/** Pixel distance a horizontal drag must cover before it counts as a swipe, not a text-field drag. */
private val SwipeThreshold = 96.dp

/** Card width / height when there is room for it; the card shrinks below this when there is not. */
private const val CardAspectRatio = 0.8f

/** Vertical room reserved above the front card so the peeking cards stay inside the deck bounds. */
private val PeekSpace = 24.dp

/**
 * Stateless three-card deck for the Status Update feature. Swipe left / "Next" advances (submitting
 * on the last card); swipe right / "Back" retreats, stopping at the first card. Knows nothing about
 * where it's mounted, permissions, or persistence — see [StatusUpdateOverlayHost].
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Size the card from the space actually available (which shrinks when the keyboard opens)
        // instead of from its width alone, so it can never overflow the deck.
        val cardHeight = minOf(maxHeight - PeekSpace, maxWidth / CardAspectRatio).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight + PeekSpace),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Peeking cards behind the front one, so the stack visibly has more than one card.
            val remaining = state.drafts.size - 1 - state.currentIndex
            repeat(minOf(remaining, 2)) { depthFromTop ->
                val depth = remaining - depthFromTop
                PeekingCard(depth = depth, height = cardHeight)
            }

            FlippingCard(
                targetIndex = state.currentIndex,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .pointerInput(state.currentIndex) {
                        var dragTotal = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragTotal += dragAmount
                            },
                            onDragEnd = {
                                when {
                                    dragTotal <= -swipeThresholdPx -> onForward()
                                    dragTotal >= swipeThresholdPx -> onBack()
                                }
                            },
                        )
                    },
            ) { displayedIndex ->
                QuestionCard(
                    question = StatusUpdateQuestion.entries[displayedIndex],
                    draft = state.drafts[displayedIndex],
                    isFirstCard = displayedIndex == 0,
                    isLastCard = displayedIndex == state.drafts.lastIndex,
                    onDraftChanged = { onDraftChanged(displayedIndex, it) },
                    onForward = onForward,
                    onBack = onBack,
                )
            }
        }
    }
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
    onDraftChanged: (String) -> Unit,
    onForward: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val questionText = stringResource(question.questionRes)
    ElevatedCard(modifier = modifier.testTag(StatusUpdateTestTags.CARD)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = questionText,
                style = MaterialTheme.typography.titleMedium,
                // The live region is scoped to this text, not the card, so a flip announces the new
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
                modifier = Modifier
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
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.status_update_back))
                    }
                }
                Button(onClick = onForward) {
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

/** A faded, inset card peeking out above the front one, purely decorative — it conveys "more cards follow". */
@Composable
private fun PeekingCard(depth: Int, height: Dp, modifier: Modifier = Modifier) {
    val insetFraction = 0.05f * depth
    val alpha = 1f - 0.3f * depth
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth(1f - insetFraction)
            .height(height)
            .offset(y = -(depth * 12).dp)
            .graphicsLayer { this.alpha = alpha },
        content = {},
    )
}

/**
 * Renders [content] for [targetIndex] with a card-flip transition: the visible card rotates to its
 * edge, swaps to the new content, then rotates back into view. `cameraDistance` keeps the
 * perspective from looking flattened at the 90° edge-on point.
 */
@Composable
private fun FlippingCard(
    targetIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int) -> Unit,
) {
    var displayedIndex by remember { mutableIntStateOf(targetIndex) }
    val rotationY = remember { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(targetIndex) {
        if (targetIndex != displayedIndex) {
            // Respect "remove animations" (developer option or the test harness's
            // animationsDisabled): a scale of 0 means every animateTo below would already resolve
            // instantly, but snapping directly avoids animating through the two-step
            // rotate-out/rotate-in sequence at all and keeps a11y announcements from lagging behind
            // the (invisible) transition.
            val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            if (durationScale == 0f) {
                displayedIndex = targetIndex
                rotationY.snapTo(0f)
            } else {
                rotationY.animateTo(90f, animationSpec = tween(150))
                displayedIndex = targetIndex
                rotationY.snapTo(-90f)
                rotationY.animateTo(0f, animationSpec = tween(150))
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.rotationY = rotationY.value
            cameraDistance = 12f * density.density
        },
    ) {
        content(displayedIndex)
    }
}

/** Test tags for the androidTest layer; not user-visible. */
object StatusUpdateTestTags {
    const val CARD = "status_update_card"
}

private fun previewState() = StatusUpdateCardStackUiState(
    drafts = listOf("Finished the quarterly report", "", ""),
    currentIndex = 0,
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
