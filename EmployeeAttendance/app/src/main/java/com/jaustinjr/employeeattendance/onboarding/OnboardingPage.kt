package com.jaustinjr.employeeattendance.onboarding

import androidx.annotation.StringRes
import com.jaustinjr.employeeattendance.R

/**
 * One page of the first-launch onboarding carousel, in display order. Each highlights one of the
 * app's three feature areas. Icons are chosen in the UI layer (see `OnboardingComponents`), so this
 * stays a plain model with no Compose dependency.
 */
enum class OnboardingPage(
    /** The feature area, shown as a small label above the headline. */
    @StringRes val featureRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @StringRes val supportingRes: Int,
) {
    ATTENDANCE(
        featureRes = R.string.onboarding_attendance_feature,
        titleRes = R.string.onboarding_attendance_title,
        bodyRes = R.string.onboarding_attendance_body,
        supportingRes = R.string.onboarding_attendance_supporting,
    ),
    WORKSITE(
        featureRes = R.string.onboarding_worksite_feature,
        titleRes = R.string.onboarding_worksite_title,
        bodyRes = R.string.onboarding_worksite_body,
        supportingRes = R.string.onboarding_worksite_supporting,
    ),
    REPORTING(
        featureRes = R.string.onboarding_reporting_feature,
        titleRes = R.string.onboarding_reporting_title,
        bodyRes = R.string.onboarding_reporting_body,
        supportingRes = R.string.onboarding_reporting_supporting,
    ),
}

/** What the carousel's primary button does on a given page. */
enum class OnboardingAction { NEXT, FINISH }

/**
 * The primary button's action on [currentPage] of [pageCount]: advance until the last page, then
 * finish. A pure function so the policy is JVM-testable rather than buried in a click lambda.
 */
fun onboardingActionFor(currentPage: Int, pageCount: Int): OnboardingAction {
    require(pageCount > 0) { "pageCount must be positive, was $pageCount" }
    require(currentPage in 0 until pageCount) {
        "currentPage $currentPage out of range 0 until $pageCount"
    }
    return if (currentPage == pageCount - 1) OnboardingAction.FINISH else OnboardingAction.NEXT
}
