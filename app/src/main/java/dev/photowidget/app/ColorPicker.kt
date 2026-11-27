package dev.photowidget.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Simple colour chooser: a neutral palette plus exact ARGB entry. */
object ColorPicker {

    private val presets = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFF4F1E8.toInt(), 0xFFCFC9BA.toInt(),
        0xFF8B8D84.toInt(), 0xFF3A3D38.toInt(), 0xFF000000.toInt(),
        0xFFF3D9A4.toInt(), 0xFFD9A05B.toInt(), 0xFFA15C3E.toInt(),
        0xFFB9D8C6.toInt(), 0xFF5E8C79.toInt(), 0xFF2E5E50.toInt(),
        0xFFBFD3EA.toInt(), 0xFF5D7FA6.toInt(), 0xFF2F4858.toInt(),
        0xFFE6BFD3.toInt(), 0xFF9E6B8A.toInt(), 0xFF5C3A4E.toInt(),
    )

    fun show(context: Context, initial: Long, onPicked: (Long) -> Unit) {
        var dialogRef: AlertDialog? = null
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
        }

        val grid = GridLayout(context).apply {
            columnCount = 6
            rowCount = ceil(presets.size / 6.0).toInt()
        }
        val size = dp(context, 40)
        presets.forEach { color ->
            val swatch = View(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 10).toFloat()
                    setColor(color)
                    setStroke(dp(context, 1), 0x22000000)
                }
                isClickable = true
                isFocusable = true
                contentDescription = String.format(Locale.US, "#%08X", color)
                setOnClickListener {
                    onPicked(color.toLong() and 0xFFFFFFFFL)
                    dialogRef?.dismiss()
                }
            }
            grid.addView(swatch)
        }
        root.addView(grid)

        val hexLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.colour_hex)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 12) }
        }
        val edit = TextInputEditText(hexLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(String.format(Locale.US, "#%08X", initial and 0xFFFFFFFFL))
            setSelection(text?.length ?: 0)
            isSingleLine = true
        }
        hexLayout.addView(edit)
        root.addView(hexLayout)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.colour_picker_title)
            .setView(root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.colour_apply, null)
            .create()
        dialogRef = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = parseColor(edit.text?.toString())
                if (parsed == null) {
                    hexLayout.error = context.getString(R.string.colour_invalid)
                    return@setOnClickListener
                }
                onPicked(parsed)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    fun parseColor(input: String?): Long? {
        val cleaned = input?.trim()?.removePrefix("#") ?: return null
        if (cleaned.isEmpty() || cleaned.length !in setOf(6, 8)) return null
        val value = cleaned.toLongOrNull(16) ?: return null
        return if (cleaned.length == 6) 0xFF000000L or value else value
    }

    /** Tints a MaterialButton with the selected colour and readable text. */
    fun styleButton(button: MaterialButton, color: Long) {
        val argb = color.toInt()
        button.backgroundTintList = ColorStateList.valueOf(argb)
        val light = ColorUtils.calculateLuminance(argb) > 0.45
        button.setTextColor(if (light) Color.BLACK else Color.WHITE)
        button.strokeWidth = 0
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
