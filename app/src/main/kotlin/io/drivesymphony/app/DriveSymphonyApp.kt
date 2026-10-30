package io.drivesymphony.app

import android.app.Application
import android.util.Log

class DriveSymphonyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Audio thread safety net: never let an engine exception kill the process silently.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("DriveSymphony", "uncaught on ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }
}
