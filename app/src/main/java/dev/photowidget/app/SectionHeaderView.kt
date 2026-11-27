package dev.photowidget.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tappable title row of a collapsible settings card.
 *
 * The whole configuration screen is a stack of these, so only the section the user
 * cares about is expanded. Expanding and collapsing animate the panel's height, and
 * the chevron rotates instead of swapping drawables, so the screen never jumps.
 */
class SectionHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val titleView: TextView
    private val captionView: TextView
    private val chevron: ImageView
    private var body: View? = null
    private var bodyAnimator: ValueAnimator? = null

    var expanded: Boolean = true
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        LayoutInflater.from(context).inflate(R.layout.view_section_header, this, true)
        titleView = findViewById(R.id.section_title)
        captionView = findViewById(R.id.section_caption)
        chevron = findViewById(R.id.section_chevron)
        setOnClickListener { setExpanded(!expanded, animate = true) }
    }

    /** Connects the row to the panel it shows and hides. */
    fun attach(panel: View, initiallyExpanded: Boolean = true) {
        bodyAnimator?.cancel()
        body = panel
        setExpanded(initiallyExpanded, animate = false)
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setCaption(text: CharSequence?) {
        captionView.text = text ?: ""
        captionView.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun setExpanded(value: Boolean, animate: Boolean) {
        val panel = body
        expanded = value
        isSelected = value
        chevron.animate().cancel()
        if (animate) {
            chevron.animate()
                .rotation(if (value) 90f else 0f)
                .setDuration(CHEVRON_MS)
                .start()
        } else {
            chevron.rotation = if (value) 90f else 0f
        }
        if (panel == null) return
        if (!animate) {
            panel.visibility = if (value) View.VISIBLE else View.GONE
            panel.layoutParams = panel.layoutParams?.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            return
        }
        animateBody(panel, value)
    }

    private fun animateBody(panel: View, expand: Boolean) {
        bodyAnimator?.cancel()
        val params = panel.layoutParams ?: return
        val fullHeight = measureFullHeight(panel)
        val start = if (expand) 0 else panel.height.takeIf { it > 0 } ?: fullHeight
        if (expand) {
            panel.visibility = View.VISIBLE
            params.height = 0
            panel.layoutParams = params
        }
        val animator = ValueAnimator.ofInt(start, if (expand) fullHeight else 0).apply {
            duration = BODY_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                params.height = animation.animatedValue as Int
                panel.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    panel.layoutParams = params
                    if (!expand) panel.visibility = View.GONE
                }
            })
            start()
        }
        bodyAnimator = animator
    }

    /** Natural height of the panel at its current width, used as the animation target. */
    private fun measureFullHeight(panel: View): Int {
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val widthSpec = MeasureSpec.makeMeasureSpec(
            panel.width.takeIf { it > 0 } ?: available,
            MeasureSpec.EXACTLY,
        )
        panel.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return panel.measuredHeight
    }

    private companion object {
        const val ANIMATION_MS = 160L
        const val CHEVRON_MS = 160L
        const val BODY_MS = 190L
    }
}
