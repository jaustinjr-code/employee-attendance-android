package com.jaustinjr.employeeattendance.reporting

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Instrumented because the share writes a real file and resolves it through the release
 * [ReportFileProvider], both of which a JVM test would stub away. As with the developer log
 * exporter, `share()` does open a chooser on the device; what is asserted is everything up to it.
 */
class FileReportSharerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "reports")
    private val period = ReportPeriod.containing(ReportPeriodType.WEEK, LocalDate.of(2026, 9, 10))
    private val hour = 3_600_000L

    private val report = AttendanceReport(
        period = period,
        totalMillis = 8 * hour,
        days = period.days.map { DayTotal(it, if (it == period.start.plusDays(2)) 8 * hour else 0) },
        worksites = listOf(WorksiteTotal("a", WorksiteLabel.Registered("Downtown Office"), 8 * hour)),
        shiftCount = 1,
        daysWorked = 1,
        averageMillisPerWorkedDay = 8 * hour,
        longestShiftMillis = 8 * hour,
        averageClockIn = LocalTime.of(9, 0),
    )

    @Before
    @After
    fun clearReports() {
        directory.deleteRecursively()
    }

    @Test
    fun writesTheReportAsTextReadableThroughTheProvider() = runTest {
        val notice = ActiveShiftNotice(WorksiteLabel.Registered("Downtown Office"), Instant.now())

        val result = FileReportSharer(context).share(report, notice, ShareTarget.ANY_APP)

        assertTrue("unexpected $result", result == ReportShareResult.Launched || result == ReportShareResult.NoApp)
        val file = directory.listFiles()!!.single()
        assertEquals("attendance-report-week-2026-09-06.txt", file.name)
        val uri = FileProvider.getUriForFile(context, FileReportSharer.authority(context), file)
        val viaProvider = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(file.readText(), viaProvider)
        assertTrue(viaProvider, "Total worked: 8h 00m" in viaProvider)
        assertTrue(viaProvider, "You're clocked in at Downtown Office" in viaProvider)
    }

    @Test
    fun keepsOnlyTheLatestReport() = runTest {
        val sharer = FileReportSharer(context)
        sharer.share(report, null, ShareTarget.ANY_APP)
        sharer.share(report.copy(period = period.previous()), null, ShareTarget.ANY_APP)

        assertEquals(listOf("attendance-report-week-2026-08-30.txt"), directory.listFiles()!!.map { it.name })
    }

    @Test
    fun theProviderRefusesFilesOutsideTheReportsDirectory() {
        val outside = File(context.cacheDir, "devlogs/secret.txt")

        val refused = runCatching {
            FileProvider.getUriForFile(context, FileReportSharer.authority(context), outside)
        }

        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun theShareIntentCarriesTheFileWithAReadGrant() {
        val uri = Uri.parse("content://${FileReportSharer.authority(context)}/reports/r.txt")

        val intent = FileReportSharer.sendIntent(uri, "subject", "body", ShareTarget.ANY_APP)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        @Suppress("DEPRECATION")
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals("subject", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("body", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull(intent.selector)
    }

    @Test
    fun theEmailIntentIsLimitedToMailAppsAndKeepsTheAttachment() {
        val uri = Uri.parse("content://${FileReportSharer.authority(context)}/reports/r.txt")

        val intent = FileReportSharer.sendIntent(uri, "subject", "body", ShareTarget.EMAIL)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(Intent.ACTION_SENDTO, intent.selector!!.action)
        assertEquals("mailto", intent.selector!!.data!!.scheme)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
    }
}
