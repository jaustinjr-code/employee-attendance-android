package com.jaustinjr.employeeattendance.legal.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.legal.AssetLegalDocumentSource
import com.jaustinjr.employeeattendance.legal.LegalDocument
import com.jaustinjr.employeeattendance.legal.parseLegalText
import com.jaustinjr.employeeattendance.location.ui.SettingsContent
import com.jaustinjr.employeeattendance.location.ui.SettingsTestTags
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Device-level tests for the legal screens: the shipped asset is actually readable from the APK
 * (the JVM layer only sees the source tree), it renders, and Settings' Legal section offers every
 * document and routes to the one tapped.
 */
class LegalScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun everyRegisteredDocumentIsPackagedInTheApk() {
        val source = AssetLegalDocumentSource(context)
        LegalDocument.entries.forEach { document ->
            // Throws if the asset was not packaged.
            check(parseLegalText(source.read(document)).isNotEmpty()) { "${document.id} is empty" }
        }
    }

    @Test
    fun privacyPolicyRendersFromTheShippedAsset() {
        val document = LegalDocument.PRIVACY_POLICY
        val blocks = parseLegalText(AssetLegalDocumentSource(context).read(document))

        composeRule.setContent {
            EmployeeAttendanceTheme {
                LegalDocumentContent(LegalDocumentUiState.Loaded(document, blocks))
            }
        }

        composeRule.onNodeWithText("Privacy Policy").assertIsDisplayed()
        composeRule.onNodeWithTag(LegalDocumentTestTags.CONTENT)
            .performScrollToNode(hasText("Contact"))
        composeRule.onNodeWithText("Contact").assertIsDisplayed()
    }

    @Test
    fun unavailableDocumentExplainsItself() {
        composeRule.setContent {
            EmployeeAttendanceTheme {
                LegalDocumentContent(LegalDocumentUiState.Unavailable)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.legal_document_unavailable))
            .assertIsDisplayed()
    }

    @Test
    fun settingsLegalSectionListsAndOpensEveryDocument() {
        val opened = mutableListOf<LegalDocument>()
        composeRule.setContent {
            EmployeeAttendanceTheme {
                SettingsContent(
                    selected = ClockNotificationPreference.NOTIFY_UNDO,
                    onSelect = {},
                    reverseGeocodeEnabled = true,
                    onReverseGeocodeChanged = {},
                    statusUpdateEnabled = true,
                    onStatusUpdateEnabledChanged = {},
                    onDeleteAllData = {},
                    onOpenLegalDocument = { opened += it },
                )
            }
        }

        LegalDocument.entries.forEach { document ->
            composeRule.onNodeWithTag(SettingsTestTags.legalRow(document))
                .performScrollTo()
                .assertTextContains(context.getString(document.titleRes))
                .performClick()
        }

        assertEquals(LegalDocument.entries, opened)
    }
}
