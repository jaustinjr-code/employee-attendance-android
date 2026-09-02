package com.jaustinjr.employeeattendance.location.tracking

import android.content.Context

/**
 * Seam over starting/stopping the background tracking service, so the tracking policy in
 * [LocationTrackingController] can be unit-tested without launching a real Android service.
 */
interface TrackingServiceLauncher {

    /**
     * Requests that background tracking start.
     *
     * @return `true` if the platform accepted the start request, `false` if it refused it (the
     * foreground-service background-start restriction, a revoked permission, an OEM policy). A
     * refusal is reported rather than thrown: it is an expected outcome, not a programming error.
     */
    fun start(): Boolean

    fun stop()
}

/** Default [TrackingServiceLauncher] that drives the real [LocationTrackingService]. */
class DefaultTrackingServiceLauncher(
    context: Context,
) : TrackingServiceLauncher {

    private val appContext = context.applicationContext

    override fun start(): Boolean = LocationTrackingService.start(appContext)
    override fun stop() = LocationTrackingService.stop(appContext)
}
