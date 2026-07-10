package com.jaustinjr.employeeattendance.location.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers

/**
 * A started, foreground [Service] that keeps a background location stream alive while the user is
 * away from the app. Running as a foreground service with a persistent notification is mandatory for
 * background location on modern Android and keeps the user transparently informed that their
 * location is being used.
 *
 * The service is intentionally thin: it owns the notification and service lifecycle and forwards
 * fixes from the [LocationTracker] into the shared [LocationStateRepository]. Proximity decisions
 * live elsewhere so this class stays focused on the Android service contract.
 */
class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trackingJob: Job? = null

    private val tracker: LocationTracker
        get() = (application as EmployeeAttendanceApplication).container.locationTracker
    private val locationState: LocationStateRepository
        get() = (application as EmployeeAttendanceApplication).container.locationStateRepository
    private val permissionRepository: LocationPermissionRepository
        get() = (application as EmployeeAttendanceApplication).container.locationPermissionRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        // If the OS kills us under memory pressure, restart so background tracking resumes.
        return START_STICKY
    }

    private fun startTracking() {
        // Every startForegroundService() delivery must be matched by a prompt startForeground() call
        // or the OS crashes us with "did not start in time". startForeground is idempotent, so do it
        // before any early return — including when we're already tracking and just re-notified.
        promoteToForeground()

        // The service can be restarted by the OS (START_STICKY) after the user revoked location
        // access from settings. Requesting updates without permission throws SecurityException, so
        // bail out gracefully instead of crashing.
        if (!permissionRepository.refresh().supportsBackgroundTracking) {
            stopTracking()
            return
        }

        // Already collecting; the notification was refreshed above, so nothing more to do.
        if (trackingJob != null) return

        locationState.updateStatus(TrackingStatus.BACKGROUND_ACTIVE)

        trackingJob = tracker.locationUpdates(LocationRequestConfig.Background)
            .onEach(locationState::publishLocation)
            .catch { stopTracking() }
            .launchIn(serviceScope)
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        locationState.updateStatus(TrackingStatus.STOPPED)
        stopForegroundCompat()
        stopSelf()
    }

    private fun promoteToForeground() {
        ensureChannel(this)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.location_tracking_notification_title))
            .setContentText(getString(R.string.location_tracking_notification_body))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LocationTrackingSvc"
        private const val CHANNEL_ID = "location_tracking"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.jaustinjr.employeeattendance.action.STOP_TRACKING"

        /** Starts background tracking. Caller must hold background location permission. */
        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            context.startForegroundService(intent)
        }

        /** Stops background tracking and dismisses the notification. */
        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // startService is disallowed from the background on Android 8+. If we're
                // backgrounded the service isn't running anyway, so there is nothing to stop.
                Log.w(TAG, "Could not deliver stop command; service likely not running", e)
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService<NotificationManager>() ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.location_tracking_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.location_tracking_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
