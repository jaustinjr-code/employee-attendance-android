package com.jaustinjr.employeeattendance.reporting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.MainActivity
import com.jaustinjr.employeeattendance.R
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Runs [BiweeklyReportRunner] from WorkManager. */
class BiweeklyReportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as EmployeeAttendanceApplication
        // WorkManager can cold-start the process for this; the container's stores are built by the
        // startup job, so wait for it rather than racing it for the same lazy monitors.
        application.awaitStarted()
        val decision = application.container.biweeklyReportRunner.run()
        Log.d(TAG, "biweekly check: $decision")
        return Result.success()
    }

    private companion object {
        const val TAG = "BiweeklyWorker"
    }
}

/** [ReportScheduler] backed by a unique daily periodic job. */
class WorkManagerReportScheduler(context: Context) : ReportScheduler {

    private val appContext = context.applicationContext

    override fun setBiweeklyCheckScheduled(scheduled: Boolean) {
        val workManager = WorkManager.getInstance(appContext)
        if (scheduled) {
            // KEEP: reconciling on every launch must not reset the job's next run time.
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BiweeklyReportWorker>(1, TimeUnit.DAYS).build(),
            )
        } else {
            workManager.cancelUniqueWork(WORK_NAME)
        }
    }

    companion object {
        const val WORK_NAME = "biweekly_report_check"
    }
}

/** Posts the biweekly summary; tapping it opens the Reports tab. */
class BiweeklyReportNotifier(context: Context) : ReportNotifications {

    private val appContext = context.applicationContext

    override fun notifyBiweekly(summary: BiweeklySummary) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) {
            Log.d(TAG, "notifications disabled; skipping biweekly report")
            return
        }
        ensureChannel()
        val formatter = ReportTextFormatter(
            ReportStrings.fromResources(appContext.resources),
            Locale.getDefault(),
            ZoneId.systemDefault(),
        )
        val body = buildString {
            append(formatter.biweeklyHeadline(summary))
            append(". ")
            append(formatter.change(summary))
            append('.')
            summary.topWorksite?.let {
                append(' ')
                append(appContext.getString(R.string.reports_biweekly_top_worksite, formatter.worksite(it.label)))
                append('.')
            }
        }
        val openReports = PendingIntent.getActivity(
            appContext,
            0,
            MainActivity.openReportsIntent(appContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(
                appContext.getString(
                    R.string.report_notification_title,
                    formatter.dateRange(summary.report.period),
                ),
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openReports)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify blocked (missing POST_NOTIFICATIONS)", e)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.report_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.report_notification_channel_description)
            },
        )
    }

    companion object {
        private const val TAG = "ReportNotifier"
        const val CHANNEL_ID = "biweekly_report"
        const val NOTIFICATION_ID = 0x5E90
    }
}
