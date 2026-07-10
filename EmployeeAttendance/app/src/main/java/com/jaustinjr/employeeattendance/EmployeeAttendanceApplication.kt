package com.jaustinjr.employeeattendance

import android.app.Application
import com.jaustinjr.employeeattendance.di.AppContainer
import com.jaustinjr.employeeattendance.di.DefaultAppContainer

/**
 * Application entry point that owns the app-scoped [AppContainer]. Screens and ViewModels reach
 * their dependencies through this container rather than constructing them ad hoc.
 */
class EmployeeAttendanceApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
