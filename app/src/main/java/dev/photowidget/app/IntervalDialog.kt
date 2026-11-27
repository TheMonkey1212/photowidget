package dev.photowidget.app

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.photowidget.app.databinding.DialogIntervalBinding
import dev.photowidget.model.WidgetLimits
import kotlin.math.roundToInt

/**
 * Exact interval picker: a slider for coarse changes plus a numeric field and a
 * unit, so intervals like 93 seconds are possible (not only round presets).
 */
object IntervalDialog {

    fun show(context: Context, initialSeconds: Long, onPicked: (Long) -> Unit) {
        val binding = DialogIntervalBinding.inflate(LayoutInflater.from(context))
        val units = listOf(
            context.getString(R.string.interval_unit_seconds),
            context.getString(R.string.interval_unit_minutes),
            context.getString(R.string.interval_unit_hours),
        )
        binding.dialogIntervalUnit.setSimpleItems(units.toTypedArray())

        var seconds = WidgetLimits.clampInterval(initialSeconds)
        var unitIndex = IntervalMath.bestUnit(seconds)
        var updating = false

        fun refreshFields() {
            updating = true
            binding.dialogIntervalSummary.text = context.getString(
                R.string.interval_summary,
                Format.duration(seconds),
            )
            binding.dialogIntervalSlider.value = IntervalMath.progressOf(seconds).toFloat()
            binding.dialogIntervalValue.setText(
                IntervalMath.displayValue(seconds, unitIndex).toString(),
            )
            binding.dialogIntervalUnit.setText(units[unitIndex], false)
            binding.dialogIntervalValueLayout.error = null
            updating = false
        }
        refreshFields()

        binding.dialogIntervalSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updating) return@addOnChangeListener
            seconds = IntervalMath.secondsOf(value.roundToInt())
            unitIndex = IntervalMath.bestUnit(seconds)
            refreshFields()
        }

        binding.dialogIntervalValue.doAfterTextChanged { editable ->
            if (updating) return@doAfterTextChanged
            val typed = editable?.toString()?.toLongOrNull() ?: return@doAfterTextChanged
            val total = IntervalMath.secondsOrNull(typed, unitIndex) ?: return@doAfterTextChanged
            seconds = total
            updating = true
            binding.dialogIntervalSlider.value = IntervalMath.progressOf(seconds).toFloat()
            binding.dialogIntervalSummary.text = context.getString(
                R.string.interval_summary,
                Format.duration(seconds),
            )
            binding.dialogIntervalValueLayout.error = null
            updating = false
        }

        binding.dialogIntervalUnit.setOnItemClickListener { _, _, position, _ ->
            if (updating || position == unitIndex) return@setOnItemClickListener
            // Keep the typed number and let the new unit decide what it means; never
            // truncate 93 seconds into 1 minute (which would become 60 seconds).
            val typed = binding.dialogIntervalValue.text?.toString()?.toLongOrNull()
            val retyped = typed?.let { IntervalMath.secondsOrNull(it, position) }
            unitIndex = position
            seconds = retyped
                ?: IntervalMath.secondsOrNull(
                    IntervalMath.displayValue(seconds, position),
                    position,
                )
                ?: seconds
            refreshFields()
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.interval_exact)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_done, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = binding.dialogIntervalValue.text?.toString()?.toLongOrNull()
                val total = typed?.let { IntervalMath.secondsOrNull(it, unitIndex) }
                if (total == null) {
                    binding.dialogIntervalValueLayout.error = context.getString(
                        R.string.interval_invalid,
                        Format.duration(WidgetLimits.MIN_INTERVAL_SECONDS),
                        Format.duration(WidgetLimits.MAX_INTERVAL_SECONDS),
                    )
                    return@setOnClickListener
                }
                onPicked(total)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    
}
