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
import androidx.core.content.ContextCompat
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

    // Confine the service's coroutine work to the main thread. onStartCommand already runs there, so
    // this makes trackingJob (and the .catch { stopTracking() } continuation) single-threaded,
    // removing the read/write race on trackingJob between a start and a stream-failure stop. The work
    // itself is trivial (forwarding fixes to a StateFlow), so the main thread is fine.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackingJob: Job? = null

    private val tracker: LocationTracker
        get() = (application as EmployeeAttendanceApplication).container.locationTracker
    private val locationState: LocationStateRepository
        get() = (application as EmployeeAttendanceApplication).container.locationStateRepository
    private val permissionRepository: LocationPermissionRepository
        get() = (application as EmployeeAttendanceApplication).container.locationPermissionRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action} startId=$startId")
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
        // Check permission BEFORE promoting to a location foreground service. START_STICKY can
        // redeliver an intent after the user revoked location access from Settings; calling
        // startForeground() with FOREGROUND_SERVICE_TYPE_LOCATION while holding no location
        // permission can throw SecurityException under Android 14+ FGS-type enforcement. In the
        // redelivery case there is no startForegroundService() obligation to satisfy, so stopping
        // without ever promoting is safe.
        //
        // TODO(#50): when the start *did* come from startForegroundService() and the permission has
        // since been revoked, standing down here leaves that obligation undischarged and the system
        // kills the process with ForegroundServiceDidNotStartInTimeException. Discharging it needs a
        // permission-free FGS type: FOREGROUND_SERVICE_TYPE_NONE is rejected outright at targetSdk
        // 36 ("Starting FGS with type none ... has been prohibited"), so the fix is a manifest
        // change adding shortService.
        if (!permissionRepository.refresh().supportsBackgroundTracking) {
            Log.d(TAG, "startTracking: no background permission; stopping")
            stopTracking()
            return
        }

        // startForeground is idempotent; call it on every delivery (including when already tracking)
        // to satisfy the startForegroundService() obligation. Guard against a permission revocation
        // that races between the check above and this call.
        try {
            promoteToForeground()
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing while promoting foreground service; stopping", e)
            stopTracking()
            return
        }

        // Already collecting; the notification was refreshed above, so nothing more to do.
        if (trackingJob != null) {
            Log.v(TAG, "startTracking: already active; notification refreshed")
            return
        }

        Log.d(TAG, "startTracking: promoting to foreground and starting background updates")
        locationState.updateStatus(TrackingStatus.BACKGROUND_ACTIVE)

        trackingJob = tracker.locationUpdates(LocationRequestConfig.Background)
            .onEach(locationState::publishLocation)
            .catch { e -> Log.w(TAG, "update stream failed; stopping", e); stopTracking() }
            .launchIn(serviceScope)
    }

    private fun stopTracking() {
        Log.d(TAG, "stopTracking")
        trackingJob?.cancel()
        trackingJob = null
        locationState.updateStatus(TrackingStatus.STOPPED)
        stopForegroundCompat()
        stopSelf()
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.location_tracking_notification_title))
        .setContentText(getString(R.string.location_tracking_notification_body))
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun promoteToForeground() {
        ensureChannel(this)
        val notification: Notification = buildNotification()

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

    private fun stopForegroundCompat() {
        // STOP_FOREGROUND_REMOVE exists since API 24 (== minSdk), so no version branch is needed.
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrackSvc"
        private const val CHANNEL_ID = "location_tracking"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.jaustinjr.employeeattendance.action.STOP_TRACKING"

        /**
         * Starts background tracking. Caller must hold background location permission.
         *
         * Never throws: since Android 12 the system refuses a foreground-service start from a
         * background process, and that refusal must degrade tracking rather than kill the app.
         *
         * @return `true` if the platform accepted the start request.
         */
        fun start(context: Context): Boolean {
            Log.d(TAG, "start() requested")
            val intent = Intent(context, LocationTrackingService::class.java)
            return try {
                // ContextCompat routes to startForegroundService on API 26+ and startService below
                // it, where startForegroundService does not exist (minSdk is 24).
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException;
                // catching the supertype keeps this compiling and correct at minSdk 24. Reached
                // whenever the process is not in a foreground state at the instant of the call.
                Log.w(TAG, "Foreground service start refused; tracking stays degraded", e)
                false
            } catch (e: SecurityException) {
                // Location permission revoked between the permission check and this call, or an
                // FGS-type enforcement failure on Android 14+.
                Log.e(TAG, "Foreground service start denied; check permissions", e)
                false
            }
        }

        /** Stops background tracking and dismisses the notification. */
        fun stop(context: Context) {
            Log.d(TAG, "stop() requested")
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
