package com.jaustinjr.employeeattendance.onboarding.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.onboarding.OnboardingAction
import com.jaustinjr.employeeattendance.onboarding.OnboardingPage
import com.jaustinjr.employeeattendance.onboarding.onboardingActionFor
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

/** Test tag on the carousel, so tests can swipe it without depending on page copy. */
const val ONBOARDING_PAGER_TAG = "onboarding_pager"

/**
 * First-launch onboarding: a swipeable carousel of [OnboardingPage]s with a primary button that
 * advances one page at a time and, on the last page, calls [onFinished].
 *
 * Owns only the pager state (saveable, so a rotation keeps the page). Whether onboarding is shown
 * at all, and recording that it finished, belong to [OnboardingViewModel] via the caller.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    pages: List<OnboardingPage> = OnboardingPage.entries,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    // System back steps to the previous page rather than leaving the app mid-carousel. On the first
    // page it is disabled and back behaves as it does anywhere else.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    OnboardingContent(
        pages = pages,
        pagerState = pagerState,
        onNext = {
            // From targetPage, not currentPage: a second tap while the first is still animating
            // must move on from where the pager is headed, not repeat the same step.
            scope.launch {
                pagerState.animateScrollToPage((pagerState.targetPage + 1).coerceAtMost(pages.lastIndex))
            }
        },
        onFinish = onFinished,
        modifier = modifier,
    )
}

/**
 * Stateless onboarding layout: the carousel, the page indicator, and the primary button. The button
 * reads `targetPage` rather than `currentPage` so its label switches as soon as a swipe or tap
 * commits to the last page, not once the animation has settled.
 */
@Composable
fun OnboardingContent(
    pages: List<OnboardingPage>,
    pagerState: PagerState,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = onboardingActionFor(pagerState.targetPage, pages.size)
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(ONBOARDING_PAGER_TAG),
            ) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Pages ease out as they slide away, so a swipe reads as moving between
                            // cards rather than a hard-edged strip. Read here, in the layer block,
                            // so scrolling redraws the page instead of recomposing it every frame.
                            val offset = pagerState.getOffsetDistanceInPages(index)
                                .absoluteValue
                                .coerceIn(0f, 1f)
                            alpha = 1f - offset * 0.6f
                            val scale = 1f - offset * 0.08f
                            scaleX = scale
                            scaleY = scale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Scrollable so large font scales or short screens never clip the copy.
                    OnboardingPageContent(
                        page = pages[index],
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 24.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OnboardingPageIndicator(
                    pageCount = pages.size,
                    currentPage = pagerState.targetPage,
                )
                Spacer(Modifier.height(32.dp))
                OnboardingPrimaryButton(
                    action = action,
                    onClick = {
                        when (action) {
                            OnboardingAction.NEXT -> onNext()
                            OnboardingAction.FINISH -> onFinish()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** "Next" with a forward arrow, or "Get started" on the last page; the label cross-fades. */
@Composable
fun OnboardingPrimaryButton(
    action: OnboardingAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
    ) {
        AnimatedContent(
            targetState = action,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboardingPrimaryLabel",
        ) { target ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
            ) {
                when (target) {
                    OnboardingAction.NEXT -> {
                        Text(stringResource(R.string.onboarding_next))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null, // the label already says "Next"
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    }
                    OnboardingAction.FINISH -> Text(stringResource(R.string.onboarding_get_started))
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Onboarding – first page")
@Composable
private fun OnboardingContentFirstPagePreview() {
    EmployeeAttendanceTheme {
        OnboardingContent(
            pages = OnboardingPage.entries,
            pagerState = rememberPagerState(pageCount = { OnboardingPage.entries.size }),
            onNext = {},
            onFinish = {},
        )
    }
}

@Preview(showBackground = true, name = "Onboarding – last page")
@Composable
private fun OnboardingContentLastPagePreview() {
    EmployeeAttendanceTheme {
        OnboardingContent(
            pages = OnboardingPage.entries,
            pagerState = rememberPagerState(
                initialPage = OnboardingPage.entries.lastIndex,
                pageCount = { OnboardingPage.entries.size },
            ),
            onNext = {},
            onFinish = {},
        )
    }
}
