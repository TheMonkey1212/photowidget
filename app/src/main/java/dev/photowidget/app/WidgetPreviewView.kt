package dev.photowidget.app

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.WidgetConfig
import dev.photowidget.widget.WidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live widget preview that renders with the exact same renderer as the launcher
 * widget, so appearance settings are shown truthfully.
 */
class WidgetPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val image = ImageView(context).apply {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        scaleType = ImageView.ScaleType.FIT_XY
        contentDescription = context.getString(R.string.cd_widget_preview)
    }

    private var renderer: WidgetRenderer? = null
    private var config: WidgetConfig? = null
    private var photos: List<PhotoRef> = emptyList()
    private var emptyTitle: String? = null
    private var emptySubtitle: String? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    init {
        addView(image)
        setBackgroundResource(R.drawable.bg_preview)
    }

    fun bind(renderer: WidgetRenderer) {
        this.renderer = renderer
        requestRender()
    }

    /**
     * @param emptyTitle shown instead of the default placeholder title when there is
     *   nothing to draw, e.g. when the only sources are currently unreadable.
     * @param emptySubtitle shown under [emptyTitle]; pass the reason when known.
     */
    fun render(
        config: WidgetConfig,
        photos: List<PhotoRef>,
        emptyTitle: String? = null,
        emptySubtitle: String? = null,
    ) {
        this.config = config
        this.photos = photos
        this.emptyTitle = emptyTitle
        this.emptySubtitle = emptySubtitle
        requestRender()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        requestRender()
    }

    override fun onDetachedFromWindow() {
        job?.cancel()
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestRender()
    }

    private fun requestRender() {
        val renderer = renderer ?: return
        val config = config ?: return
        val scope = scope ?: return
        val width = width
        val height = height
        if (width <= 0 || height <= 0) return
        val currentPhotos = photos
        val title = emptyTitle ?: context.getString(R.string.preview_title)
        val subtitle = emptySubtitle ?: context.getString(R.string.preview_empty_subtitle)
        job?.cancel()
        job = scope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.Default) {
                try {
                    if (currentPhotos.isEmpty() && config.mode != dev.photowidget.model.WidgetMode.SINGLE) {
                        renderer.renderPlaceholder(
                            appearance = config.appearance,
                            widthPx = width,
                            heightPx = height,
                            title = title,
                            subtitle = subtitle,
                        )
                    } else {
                        renderer.render(config, currentPhotos, width, height)
                    }
                } catch (e: Throwable) {
                    null
                }
            }
            if (bitmap != null && scope === this@WidgetPreviewView.scope) {
                image.setImageBitmap(bitmap)
                // A short fade hides the re-render behind each slider change instead
                // of the preview blinking between frames.
                image.alpha = 0f
                image.animate().alpha(1f).setDuration(140L).start()
            }
        }
    }
}
