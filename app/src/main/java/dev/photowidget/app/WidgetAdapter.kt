package dev.photowidget.app

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.photowidget.app.databinding.ItemWidgetBinding
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetMode

class WidgetAdapter(
    private val onEdit: (WidgetConfig) -> Unit,
    private val onMenu: (View, WidgetConfig) -> Unit,
) : ListAdapter<WidgetAdapter.Row, WidgetAdapter.Holder>(Diff) {

    data class Row(
        val config: WidgetConfig,
        val preview: Bitmap?,
        val onHome: Boolean,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemWidgetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: Holder) {
        holder.clearPreview()
    }

    inner class Holder(
        private val binding: ItemWidgetBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row) {
            val context = binding.root.context
            binding.name.text = row.config.name
            binding.summary.text = Format.widgetSummary(context, row.config)

            val home = context.getString(
                if (row.onHome) R.string.widget_on_home else R.string.widget_not_on_home,
            )
            binding.status.text = if (row.config.nextUpdateAt <= 0L || row.config.mode == WidgetMode.SINGLE) {
                home
            } else {
                "$home · ${context.getString(R.string.next_update_in, Format.nextUpdate(row.config.nextUpdateAt))}"
            }

            if (row.preview != null) {
                binding.preview.imageTintList = null
                // Clear the placeholder colour left behind by an earlier bind.
                binding.preview.setBackgroundColor(Color.TRANSPARENT)
                binding.preview.setImageBitmap(row.preview)
            } else {
                binding.preview.setImageDrawable(null)
                binding.preview.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.pw_surface_variant),
                )
                binding.preview.setImageResource(R.drawable.ic_photos)
                binding.preview.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.pw_on_surface_variant),
                )
            }

            binding.root.setOnClickListener { onEdit(row.config) }
            binding.menu.setOnClickListener { anchor -> onMenu(anchor, row.config) }
        }

        fun clearPreview() {
            binding.preview.setImageDrawable(null)
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
            oldItem.config.widgetId == newItem.config.widgetId

        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
            oldItem == newItem
    }
}
