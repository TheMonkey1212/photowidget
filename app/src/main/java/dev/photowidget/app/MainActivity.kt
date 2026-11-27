package dev.photowidget.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import dev.photowidget.app.databinding.ActivityMainBinding
import dev.photowidget.widget.WidgetRuntime

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_widgets -> switchTo(
                    WidgetsFragment(),
                    TAG_WIDGETS,
                    getString(R.string.widgets_title),
                )

                R.id.nav_library -> switchTo(
                    LibraryFragment(),
                    TAG_LIBRARY,
                    getString(R.string.library_title),
                )

                R.id.nav_settings -> switchTo(
                    SettingsFragment(),
                    TAG_SETTINGS,
                    getString(R.string.settings_title),
                )

                else -> return@setOnItemSelectedListener false
            }
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_widgets
        } else {
            currentTag = savedInstanceState.getString(STATE_TAG)
            binding.toolbar.title = savedInstanceState.getString(STATE_TITLE)
        }

        // Repair alarms and catch up anything that was missed while the app was closed.
        WidgetRuntime.launch(applicationContext) { graph -> graph.coordinator.catchUp() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAG, currentTag)
        outState.putString(STATE_TITLE, binding.toolbar.title?.toString())
    }

    private fun switchTo(fragment: Fragment, tag: String, title: String) {
        if (currentTag == tag && supportFragmentManager.findFragmentByTag(tag) != null) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commit()
        currentTag = tag
        binding.toolbar.title = title
    }

    private companion object {
        const val TAG_WIDGETS = "widgets"
        const val TAG_LIBRARY = "library"
        const val TAG_SETTINGS = "settings"
        const val STATE_TAG = "current_tag"
        const val STATE_TITLE = "current_title"
    }
}
