package com.jaustinjr.employeeattendance.devtools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Instrumented because the export writes a real file and resolves it through the debug-only
 * `FileProvider` — both of which a JVM test would stub away.
 *
 * The launch itself is not exercised here (that would open a chooser on the device); what is
 * verified is everything up to it: the attachment's content, the state header, and the fact that
 * an empty log is reported rather than mailed.
 */
class EmailDeveloperLogExporterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logDirectory = File(context.cacheDir, "devlogs")

    private class FixedLogSource(private val content: String) : ApplicationLogSource {
        override suspend fun read(maxLines: Int): String = content
    }

    @Before
    @After
    fun clearExportedLogs() {
        logDirectory.deleteRecursively()
    }

    @Test
    fun writesTheLogAndItsHeaderToACacheFile() = runTest {
        val exporter = EmailDeveloperLogExporter(context, FixedLogSource("line one\nline two"))

        val result = exporter.exportToEmail(recipient = "dev@example.com", header = "build: debug")

        // A device with no email app resolves no chooser; either way the file must have been written.
        assertTrue("unexpected result: $result", result is LogExportResult.Launched || result is LogExportResult.Failed)
        val written = logDirectory.listFiles()!!.single()
        val content = written.readText()
        assertTrue(content, content.startsWith("build: debug"))
        assertTrue(content, content.contains("line one"))
        assertTrue(content, content.contains("line two"))
    }

    @Test
    fun namesTheAttachmentIdentifiablyAndAsPlainText() = runTest {
        val exporter = EmailDeveloperLogExporter(context, FixedLogSource("something"))

        exporter.exportToEmail(recipient = "", header = "header")

        val name = logDirectory.listFiles()!!.single().name
        assertTrue(name, name.startsWith("employee-attendance-log-"))
        assertTrue(name, name.endsWith(".txt"))
    }

    @Test
    fun supersedesThePreviousExportRatherThanFillingTheCache() = runTest {
        val exporter = EmailDeveloperLogExporter(context, FixedLogSource("something"))

        repeat(3) { exporter.exportToEmail(recipient = "", header = "header $it") }

        assertEquals(1, logDirectory.listFiles()!!.size)
    }

    @Test
    fun anEmptyLogIsReportedAndWritesNothing() = runTest {
        val exporter = EmailDeveloperLogExporter(context, FixedLogSource("   \n  "))

        val result = exporter.exportToEmail(recipient = "dev@example.com", header = "header")

        assertEquals(LogExportResult.Empty, result)
        assertTrue(logDirectory.listFiles().isNullOrEmpty())
    }

    @Test
    fun aFailingLogSourceIsReportedRatherThanThrown() = runTest {
        val failing = object : ApplicationLogSource {
            override suspend fun read(maxLines: Int): String = throw java.io.IOException("nope")
        }
        val exporter = EmailDeveloperLogExporter(context, failing)

        val result = exporter.exportToEmail(recipient = "", header = "header")

        assertTrue("unexpected result: $result", result is LogExportResult.Failed)
        assertEquals("nope", (result as LogExportResult.Failed).reason)
    }
}
