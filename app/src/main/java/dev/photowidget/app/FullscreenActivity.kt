package dev.photowidget.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.photowidget.app.databinding.ActivityFullscreenBinding
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.RenderQuality
import dev.photowidget.model.WidgetMode
import dev.photowidget.widget.WidgetIntents
import dev.photowidget.widget.WidgetRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen view of the current widget photo, with previous/next. Tap the
 * photo to hide the bars. Photos are decoded at screen size only while visible.
 */
class FullscreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFullscreenBinding
    private val graph by lazy { WidgetRuntime.get(this) }
    private var photos: List<PhotoRef> = emptyList()
    private var index = 0
    private var loadJob: Job? = null
    private var controlsVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val widgetId = intent.getIntExtra(WidgetIntents.EXTRA_WIDGET_ID, -1)
        if (widgetId <= 0) {
            finish()
            return
        }

        binding.fullscreenClose.setOnClickListener { finish() }
        binding.fullscreenPrevious.setOnClickListener { move(-1) }
        binding.fullscreenNext.setOnClickListener { move(1) }
        binding.fullscreenImage.setOnClickListener { toggleControls() }

        load(widgetId)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    private fun load(widgetId: Int) {
        lifecycleScope.launch {
            val config = graph.repository.getWidget(widgetId)
            val loaded = when {
                config == null -> emptyList()
                config.mode == WidgetMode.SINGLE -> listOfNotNull(
                    config.singlePhotoUri?.let { PhotoRef(uri = it, key = it) },
                )

                else -> withContext(Dispatchers.IO) {
                    runCatching { graph.resolver.resolve(config.sources).photos }
                        .getOrDefault(emptyList())
                }
            }
            photos = loaded
            if (photos.isEmpty()) {
                binding.fullscreenImage.isVisible = false
                binding.fullscreenEmpty.isVisible = true
                binding.fullscreenInfo.text = ""
                binding.fullscreenPrevious.isEnabled = false
                binding.fullscreenNext.isEnabled = false
                return@launch
            }
            binding.fullscreenEmpty.isVisible = false
            binding.fullscreenImage.isVisible = true
            val latest = runCatching { graph.repository.latestSelection(widgetId) }
                .getOrDefault(emptyList())
                .firstOrNull()
                ?.key
            val found = photos.indexOfFirst { it.key == latest }
            index = if (found >= 0) found else 0
            showPhoto()
        }
    }

    private fun move(delta: Int) {
        if (photos.size <= 1) return
        index = (index + delta + photos.size) % photos.size
        showPhoto()
    }

    private fun showPhoto() {
        val photo = photos.getOrNull(index) ?: return
        binding.fullscreenInfo.text = photo.displayName.ifBlank { getString(R.string.unknown) }
        binding.fullscreenPrevious.isEnabled = photos.size > 1
        binding.fullscreenNext.isEnabled = photos.size > 1

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val metrics = resources.displayMetrics
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    graph.imageLoader.load(
                        photo.uri,
                        metrics.widthPixels,
                        metrics.heightPixels,
                        RenderQuality.HIGH,
                    )
                }.getOrNull()
            }
            if (bitmap == null) {
                binding.fullscreenImage.setImageDrawable(null)
                binding.fullscreenEmpty.isVisible = true
                binding.fullscreenEmpty.setText(R.string.updated_no_photo)
            } else {
                binding.fullscreenEmpty.isVisible = false
                binding.fullscreenImage.setImageBitmap(bitmap)
            }
        }
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        binding.fullscreenBar.isVisible = controlsVisible
        binding.fullscreenControls.isVisible = controlsVisible
    }
}
