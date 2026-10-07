package com.jaustinjr.employeeattendance.onboarding.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.onboarding.OnboardingPage
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/** The illustration's main glyph for [OnboardingPage]. Kept here so the model has no Compose types. */
private val OnboardingPage.icon: ImageVector
    get() = when (this) {
        OnboardingPage.ATTENDANCE -> Icons.Outlined.Schedule
        OnboardingPage.WORKSITE -> Icons.Filled.LocationOn
        OnboardingPage.REPORTING -> Icons.Outlined.AssignmentTurnedIn
    }

/** The smaller badge glyph overlapping the main one, as in the design drafts' clock + briefcase. */
private val OnboardingPage.badgeIcon: ImageVector
    get() = when (this) {
        OnboardingPage.ATTENDANCE -> Icons.Filled.Work
        OnboardingPage.WORKSITE -> Icons.Filled.MyLocation
        OnboardingPage.REPORTING -> Icons.Outlined.Insights
    }

/**
 * One onboarding page: an illustration over a feature label, headline, and two lines of copy.
 * Centred and scrollable by the caller, so large font scales never clip the text.
 */
@Composable
fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OnboardingIllustration(icon = page.icon, badgeIcon = page.badgeIcon)
        Spacer(Modifier.height(40.dp))
        Text(
            text = stringResource(page.featureRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(page.supportingRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A large glyph on a tonal disc with a smaller glyph badged on its lower edge. Purely decorative —
 * the page's headline and copy carry the meaning — so it is hidden from accessibility services.
 */
@Composable
fun OnboardingIllustration(
    icon: ImageVector,
    badgeIcon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics {},
    ) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null, // decorative; see KDoc
                    modifier = Modifier.size(size * 0.48f),
                )
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(size * 0.36f),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            // Separates the badge from the disc behind it, the way the drafts' cut-out outline does.
            border = BorderStroke(
                width = 4.dp,
                color = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null, // decorative; see KDoc
                    modifier = Modifier.size(size * 0.18f),
                )
            }
        }
    }
}

/**
 * Page position dots. The current page's dot stretches into a pill and takes the primary colour,
 * animated so a swipe and a button press read the same. Announced once as "Page x of y" rather
 * than as a row of unlabeled shapes.
 */
@Composable
fun OnboardingPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.onboarding_page_indicator, currentPage + 1, pageCount)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            val width by animateDpAsState(
                targetValue = if (selected) 24.dp else 8.dp,
                label = "indicatorWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                label = "indicatorColor",
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Preview(showBackground = true, name = "Onboarding pages")
@Composable
private fun OnboardingPageContentPreview() {
    EmployeeAttendanceTheme {
        Column(verticalArrangement = Arrangement.spacedBy(48.dp)) {
            OnboardingPage.entries.forEach { OnboardingPageContent(page = it) }
        }
    }
}

@Preview(showBackground = true, name = "Page indicator")
@Composable
private fun OnboardingPageIndicatorPreview() {
    EmployeeAttendanceTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(3) { OnboardingPageIndicator(pageCount = 3, currentPage = it) }
        }
    }
}
