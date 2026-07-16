package com.jaustinjr.employeeattendance.location.tracking

import android.content.Context

/**
 * Seam over starting/stopping the background tracking service, so the tracking policy in
 * [LocationTrackingController] can be unit-tested without launching a real Android service.
 */
interface TrackingServiceLauncher {
    fun start()
    fun stop()
}

/** Default [TrackingServiceLauncher] that drives the real [LocationTrackingService]. */
class DefaultTrackingServiceLauncher(
    context: Context,
) : TrackingServiceLauncher {

    private val appContext = context.applicationContext

    override fun start() = LocationTrackingService.start(appContext)
    override fun stop() = LocationTrackingService.stop(appContext)
}
