package com.jaustinjr.employeeattendance.devtools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.jaustinjr.employeeattendance.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What happened when the developer asked for the application log to be emailed. */
sealed interface LogExportResult {

    /** The file was written and an email chooser was opened. The developer still has to hit send. */
    data class Launched(val fileName: String, val lineCount: Int) : LogExportResult

    /** The log came back empty, so nothing was worth attaching. */
    data object Empty : LogExportResult

    /** Reading, writing, or sharing failed. [reason] is developer-facing, not localised. */
    data class Failed(val reason: String) : LogExportResult
}

/**
 * Packages the application log as a file and hands it to an email app.
 *
 * Nothing is sent automatically: this opens a share chooser with the message pre-addressed and the
 * log attached, and the developer sends it from their own mail app. That keeps a potentially
 * sensitive artifact (it contains coordinates, worksite ids and timestamps) under explicit human
 * control, and means the app needs no network permission or mail credentials.
 */
interface DeveloperLogExporter {

    /**
     * Writes the current log to a shareable file and opens an email chooser.
     *
     * @param recipient address to pre-fill; blank leaves the "To" field for the mail app to ask.
     * @param header context lines prepended to the file and used as the message body — see
     *   [DeveloperToolsController.describeCurrentState].
     */
    suspend fun exportToEmail(recipient: String, header: String): LogExportResult
}

/**
 * [DeveloperLogExporter] over [ApplicationLogSource] and `ACTION_SEND`.
 *
 * The attachment is written under `cacheDir/devlogs`, exposed through the `devlogs` [FileProvider]
 * declared in the **debug** manifest (`src/debug/AndroidManifest.xml`) — so no content provider for
 * app logs exists in a release build at all. The developer-settings UI that reaches this class is
 * itself debug-gated, so the missing provider is unreachable rather than a latent crash.
 */
class EmailDeveloperLogExporter(
    context: Context,
    private val logSource: ApplicationLogSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DeveloperLogExporter {

    private val appContext = context.applicationContext

    override suspend fun exportToEmail(recipient: String, header: String): LogExportResult {
        val prepared = runCatching { prepare(header) }
            .getOrElse { error ->
                Log.w(TAG, "log export failed while preparing the file", error)
                return LogExportResult.Failed(error.message ?: error.javaClass.simpleName)
            }
        if (prepared == null) return LogExportResult.Empty

        return try {
            appContext.startActivity(chooserFor(prepared, recipient, header))
            Log.d(TAG, "log export chooser launched for ${prepared.file.name}")
            LogExportResult.Launched(prepared.file.name, prepared.lineCount)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app can share the log", e)
            LogExportResult.Failed("No app available to send the log")
        }
    }

    /** The written attachment plus what went into it. Null when the log had no content. */
    private data class PreparedLog(val file: File, val uri: Uri, val lineCount: Int)

    private suspend fun prepare(header: String): PreparedLog? = withContext(ioDispatcher) {
        val body = logSource.read()
        if (body.isBlank()) return@withContext null

        val directory = File(appContext.cacheDir, LOG_DIRECTORY).apply { mkdirs() }
        // Each export supersedes the last; without this the cache grows for the life of the install.
        directory.listFiles()?.forEach { it.delete() }

        val file = File(directory, fileName())
        file.writeText("$header\n\n$body\n")
        PreparedLog(
            file = file,
            uri = FileProvider.getUriForFile(appContext, authority(), file),
            lineCount = body.count { it == '\n' } + 1,
        )
    }

    private fun chooserFor(log: PreparedLog, recipient: String, header: String): Intent {
        val subject = appContext.getString(R.string.dev_log_export_subject, Build.MODEL.orEmpty())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            if (recipient.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, header)
            putExtra(Intent.EXTRA_STREAM, log.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, appContext.getString(R.string.dev_log_export_chooser))
            // Started from the application context, so the chooser needs its own task; the read
            // grant has to be repeated because the chooser, not `send`, is what we launch.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun authority(): String = "${appContext.packageName}.$PROVIDER_SUFFIX"

    private fun fileName(): String =
        "employee-attendance-log-${TIMESTAMP_FORMAT.format(Date())}.txt"

    private companion object {
        const val TAG = "DevLogExport"
        const val LOG_DIRECTORY = "devlogs"
        const val MIME_TYPE = "text/plain"

        /** Must match the `android:authorities` suffix in `src/debug/AndroidManifest.xml`. */
        const val PROVIDER_SUFFIX = "devlogs"

        val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
