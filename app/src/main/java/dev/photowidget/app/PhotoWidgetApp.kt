package dev.photowidget.app

import android.app.Application
import dev.photowidget.widget.WidgetRuntime

/**
 * Nothing is initialised that talks to the network or an account: the app is
 * fully offline. The widget graph only opens the local database on demand.
 */
class PhotoWidgetApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WidgetRuntime.get(this)
    }
}
