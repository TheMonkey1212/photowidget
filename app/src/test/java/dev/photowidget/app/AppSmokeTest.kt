package dev.photowidget.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.photowidget.widget.WidgetIntents
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSmokeTest {

    @Test
    fun mainScreenInflatesAndSwitchesTabs() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val nav = activity.findViewById<BottomNavigationView>(R.id.bottom_nav)
        assertNotNull(nav)

        nav.selectedItemId = R.id.nav_library
        shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        assertTrue(activity.supportFragmentManager.fragments.any { it is LibraryFragment })

        nav.selectedItemId = R.id.nav_settings
        shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        assertTrue(activity.supportFragmentManager.fragments.any { it is SettingsFragment })

        controller.pause().stop().destroy()
    }

    @Test
    fun configureScreenInflatesWithEverySection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, WidgetConfigureActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 77)
        val controller = Robolectric.buildActivity(WidgetConfigureActivity::class.java, intent)
            .setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        listOf(
            R.id.preview,
            R.id.name_input,
            R.id.sources_container,
            R.id.mode_input,
            R.id.interval_slider,
            R.id.interval_value,
            R.id.corner_slider,
            R.id.fit_fill,
            R.id.overlay_date_switch,
            R.id.tap_input,
            R.id.advanced_group,
            R.id.save_button,
        ).forEach { id ->
            assertNotNull(
                "missing view $id",
                activity.findViewById<View>(id),
            )
        }

        controller.pause().stop().destroy()
    }

    /**
     * In-app "edit" and the widget's "open settings" tap pass WidgetIntents.EXTRA_WIDGET_ID.
     * The screen used to read only EXTRA_APPWIDGET_ID and closed itself straight away.
     */
    @Test
    fun configureScreenAcceptsTheWidgetIntentExtra() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, WidgetConfigureActivity::class.java)
            .putExtra(WidgetIntents.EXTRA_WIDGET_ID, 88)
        val controller = Robolectric.buildActivity(WidgetConfigureActivity::class.java, intent)
            .setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "config screen finished although it was given a widget id",
            activity.isFinishing,
        )
        assertNotNull(activity.findViewById<View>(R.id.save_button))

        controller.pause().stop().destroy()
    }
}
