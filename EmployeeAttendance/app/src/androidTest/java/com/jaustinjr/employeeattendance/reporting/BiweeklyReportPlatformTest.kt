package com.jaustinjr.employeeattendance.reporting

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The Android edges of the biweekly report: WorkManager, the worker's wiring and the notification. */
class BiweeklyReportPlatformTest {

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context as EmployeeAttendanceApplication
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun awaitStartup(): Unit = runBlocking {
        // Startup reconciles the schedule from the persisted opt-in; let it finish so it cannot
        // race the scheduling assertions below.
        application.awaitStarted()
        WorkManager.getInstance(context).cancelUniqueWork(WorkManagerReportScheduler.WORK_NAME).result.get()
    }

    @After
    fun cleanUp() {
        WorkManager.getInstance(context).cancelUniqueWork(WorkManagerReportScheduler.WORK_NAME).result.get()
        manager.cancel(BiweeklyReportNotifier.NOTIFICATION_ID)
    }

    private fun uniqueWork(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerReportScheduler.WORK_NAME).get()

    @Test
    fun schedulingEnqueuesOneDailyPeriodicCheckAndCancellingRemovesIt() {
        val scheduler = WorkManagerReportScheduler(context)

        scheduler.setBiweeklyCheckScheduled(true)
        scheduler.setBiweeklyCheckScheduled(true)

        val scheduled = uniqueWork().filter { !it.state.isFinished }
        assertEquals(1, scheduled.size)
        assertNotNull(scheduled.single().periodicityInfo)
        assertEquals(24 * 60 * 60 * 1000L, scheduled.single().periodicityInfo!!.repeatIntervalMillis)

        scheduler.setBiweeklyCheckScheduled(false)
        assertTrue(uniqueWork().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun theWorkerRunsAgainstTheAppContainer() = runBlocking {
        val worker = TestListenableWorkerBuilder<BiweeklyReportWorker>(context).build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun theNotifierPostsATappableSummary() {
        val period = ReportPeriod.lastCompletedBiweekly(LocalDate.now())
        val hour = 3_600_000L
        val report = AttendanceReport(
            period = period,
            totalMillis = 70 * hour,
            days = period.days.map { DayTotal(it, 5 * hour) },
            worksites = listOf(WorksiteTotal("a", WorksiteLabel.Registered("Downtown Office"), 70 * hour)),
            shiftCount = 10,
            daysWorked = 10,
            averageMillisPerWorkedDay = 7 * hour,
            longestShiftMillis = 9 * hour,
            averageClockIn = LocalTime.of(8, 30),
        )

        BiweeklyReportNotifier(context).notifyBiweekly(BiweeklySummary(report, 60 * hour))

        val posted = awaitPosted()
        assertNotNull("biweekly report notification was not posted", posted)
        val text = posted!!.notification.extras.getCharSequence("android.bigText").toString()
        assertTrue(text, "70h 00m across 10 shifts" in text)
        assertTrue(text, "Up 10h 00m" in text)
        assertNotNull(posted.notification.contentIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(BiweeklyReportNotifier.CHANNEL_ID, posted.notification.channelId)
        }
    }

    private fun awaitPosted(): android.service.notification.StatusBarNotification? {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { it.id == BiweeklyReportNotifier.NOTIFICATION_ID }
                ?.let { return it }
            SystemClock.sleep(50)
        }
        return null
    }
}
