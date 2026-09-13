package com.jaustinjr.employeeattendance.reporting

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import com.jaustinjr.employeeattendance.R
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a report is sent. */
enum class ShareTarget {
    /** The system share sheet: Drive, Files, messaging, email, and anything else that takes text. */
    ANY_APP,

    /** Email apps only. */
    EMAIL,
}

sealed interface ReportShareResult {
    data object Launched : ReportShareResult
    data object WriteFailed : ReportShareResult
    data object NoApp : ReportShareResult
}

/** Hands a report to another app. Seam over files, FileProvider and activity launches. */
interface ReportSharer {
    suspend fun share(
        report: AttendanceReport,
        activeShift: ActiveShiftNotice?,
        target: ShareTarget,
    ): ReportShareResult
}

/**
 * Writes the report as a `.txt` under `cacheDir/reports` and shares it with `ACTION_SEND`.
 *
 * Nothing leaves the device unless the user picks a destination and sends it. The file is readable
 * by the receiving app only through the one-shot URI grant, and each share replaces the last file,
 * so the cache holds at most one report. The cache directory is not backed up.
 */
class FileReportSharer(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReportSharer {

    private val appContext = context.applicationContext

    override suspend fun share(
        report: AttendanceReport,
        activeShift: ActiveShiftNotice?,
        target: ShareTarget,
    ): ReportShareResult {
        val formatter = ReportTextFormatter(
            ReportStrings.fromResources(appContext.resources),
            Locale.getDefault(),
            ZoneId.systemDefault(),
        )
        val text = formatter.fullReport(report, activeShift)
        val uri = runCatching { withContext(ioDispatcher) { writeFile(report.period, text) } }
            .getOrElse {
                Log.w(TAG, "failed to write report file", it)
                return ReportShareResult.WriteFailed
            }
        val send = sendIntent(uri, formatter.subject(report.period), text, target)
        val chooser = Intent.createChooser(send, appContext.getString(R.string.report_share_chooser))
            // Launched from the application context, so the chooser needs its own task; the grant
            // is repeated because the chooser, not `send`, is what is started.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            appContext.startActivity(chooser)
            ReportShareResult.Launched
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app can receive the report", e)
            ReportShareResult.NoApp
        }
    }

    private fun writeFile(period: ReportPeriod, text: String): Uri {
        val directory = File(appContext.cacheDir, DIRECTORY).apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val type = period.type.name.lowercase(Locale.US)
        val file = File(directory, "attendance-report-$type-${period.start}.txt")
        file.writeText(text)
        return FileProvider.getUriForFile(appContext, authority(appContext), file)
    }

    companion object {
        private const val TAG = "ReportShare"
        private const val DIRECTORY = "reports"
        private const val MIME_TYPE = "text/plain"

        /** Must match the `android:authorities` suffix in the main `AndroidManifest.xml`. */
        private const val PROVIDER_SUFFIX = "reports"

        fun authority(context: Context): String = "${context.packageName}.$PROVIDER_SUFFIX"

        @VisibleForTesting
        fun sendIntent(uri: Uri, subject: String, text: String, target: ShareTarget): Intent =
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TITLE, subject)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_STREAM, uri)
                // ClipData is what actually carries the grant to the target on API 29+.
                clipData = ClipData.newUri(null, subject, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (target == ShareTarget.EMAIL) {
                    // ACTION_SEND keeps the attachment; the mailto: selector limits the
                    // resolvers to email apps.
                    selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                }
            }
    }
}
