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
        if (trackingJob != null) return

        // Once started via startForegroundService(), we must call startForeground() promptly or the
        // OS kills us with a "did not start in time" crash. Promote first, then guard.
        promoteToForeground()

        // The service can be restarted by the OS (START_STICKY) after the user revoked location
        // access from settings. Requesting updates without permission throws SecurityException, so
        // bail out gracefully instead of crashing.
        if (!permissionRepository.refresh().supportsBackgroundTracking) {
            stopTracking()
            return
        }

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
            context.startService(intent)
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
