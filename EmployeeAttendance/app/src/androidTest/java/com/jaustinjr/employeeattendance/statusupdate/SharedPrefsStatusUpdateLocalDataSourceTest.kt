package com.jaustinjr.employeeattendance.statusupdate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaustinjr.employeeattendance.storage.SecurePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real encrypted SharedPreferences round-trip; the JVM layer cannot exercise persistence. */
class SharedPrefsStatusUpdateLocalDataSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearPrefs() {
        SecurePreferences.create(context, "status_updates").edit().clear().commit()
        context.getSharedPreferences("status_updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun startsEmpty() {
        assertTrue(SharedPrefsStatusUpdateLocalDataSource(context).load().isEmpty())
    }

    @Test
    fun savedUpdatesSurviveANewInstance() {
        val update = StatusUpdate(
            clockOutId = "site-a@1000",
            didToday = "Wrote the report",
            plannedTomorrow = "",
            couldNotDo = "Deliveries",
            completedAtMillis = 2_000L,
            clockOutAtMillis = 1_000L,
            clockInAtMillis = 500L,
            worksiteName = "Main office",
            editedAtMillis = 3_000L,
        )
        SharedPrefsStatusUpdateLocalDataSource(context).save(listOf(update))

        // A fresh instance, as after process death, reads what was written.
        val reloaded = DefaultStatusUpdateRepository(SharedPrefsStatusUpdateLocalDataSource(context))

        assertEquals(listOf(update), reloaded.statusUpdates.value)
    }
}
