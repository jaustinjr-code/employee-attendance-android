package com.jaustinjr.employeeattendance.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReportSettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearPrefs() {
        for (name in listOf("report_settings", "report_settings_secure")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun defaultsToOptedOutWithNothingHandled() {
        val store = ReportSettingsStore(context)

        assertFalse(store.biweeklyNotificationEnabled.value)
        assertNull(store.lastNotifiedPeriodStart)
    }

    @Test
    fun theOptInAndLastHandledPeriodSurviveANewInstance() {
        ReportSettingsStore(context).apply {
            setBiweeklyNotificationEnabled(true)
            lastNotifiedPeriodStart = LocalDate.of(2026, 9, 6)
        }

        // The writes are apply()-async to disk but the in-memory prefs object is shared.
        val reread = ReportSettingsStore(context)
        assertTrue(reread.biweeklyNotificationEnabled.value)
        assertEquals(LocalDate.of(2026, 9, 6), reread.lastNotifiedPeriodStart)
    }
}
