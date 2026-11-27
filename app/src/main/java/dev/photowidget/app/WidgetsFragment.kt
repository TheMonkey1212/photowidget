package dev.photowidget.app

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import androidx.appcompat.view.menu.MenuBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.photowidget.app.databinding.FragmentWidgetsBinding
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.WidgetConfig
import dev.photowidget.widget.PhotoWidgetProvider
import dev.photowidget.widget.UpdateReason
import dev.photowidget.widget.WidgetIntents
import dev.photowidget.widget.WidgetPreviewCache
import dev.photowidget.widget.WidgetRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetsFragment : Fragment(R.layout.fragment_widgets) {

    private var _binding: FragmentWidgetsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: WidgetAdapter
    private val graph get() = WidgetRuntime.get(requireContext())
    private val previews = mutableMapOf<Int, CachedPreview>()

    private data class CachedPreview(val updatedAt: Long, val bitmap: Bitmap?)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentWidgetsBinding.bind(view)

        adapter = WidgetAdapter(
            onEdit = ::openEditor,
            onMenu = { anchor, config -> showMenu(anchor, config) },
        )
        binding.widgetList.layoutManager = LinearLayoutManager(requireContext())
        binding.widgetList.adapter = adapter
        binding.addWidget.setOnClickListener { requestWidget() }
        binding.emptyAdd.setOnClickListener { requestWidget() }

        observeWidgets()
    }

    override fun onDestroyView() {
        binding.widgetList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun observeWidgets() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val ticker = flow {
                    while (true) {
                        emit(Unit)
                        delay(30_000L)
                    }
                }
                combine(graph.repository.observeWidgets(), ticker) { widgets, _ -> widgets }
                    .collectLatest { widgets ->
                        val (rows, active) = withContext(Dispatchers.IO) {
                            val activeIds = activeWidgetIds(appContext)
                            widgets.map { config -> toRow(appContext, config, activeIds) } to activeIds
                        }
                        adapter.submitList(rows)
                        val empty = rows.isEmpty()
                        binding.widgetList.visibility = if (empty) View.GONE else View.VISIBLE
                        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                        if (rows.any { it.preview == null }) {
                            // A widget that was just added publishes its first image a
                            // moment after its row appears, so look again instead of
                            // leaving the card without a preview until the next update.
                            delay(1_500L)
                            val retried = withContext(Dispatchers.IO) {
                                widgets.map { config -> toRow(appContext, config, active) }
                            }
                            if (retried != rows) adapter.submitList(retried)
                        }
                    }
            }
        }
    }

    private fun toRow(
        context: Context,
        config: WidgetConfig,
        active: Set<Int>,
    ): WidgetAdapter.Row {
        val cached = previews[config.widgetId]
        val preview = if (cached != null && cached.updatedAt == config.updatedAt) {
            cached.bitmap
        } else {
            WidgetPreviewCache.load(context, config.widgetId).also { bitmap ->
                // Only remember hits: a miss usually means the image is still being
                // written, and caching it would hide the preview until the widget
                // changed again.
                if (bitmap != null) {
                    previews[config.widgetId] = CachedPreview(config.updatedAt, bitmap)
                } else {
                    previews.remove(config.widgetId)
                }
            }
        }
        return WidgetAdapter.Row(config, preview, config.widgetId in active)
    }

    private fun activeWidgetIds(context: Context): Set<Int> = runCatching {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PhotoWidgetProvider::class.java))
            .toSet()
    }.getOrDefault(emptySet())

    private fun requestWidget() {
        val context = requireContext()
        // AppWidgetHost hands out monotonically increasing ids, so a pinned widget never
        // inherits a deleted id: the deleted markers must stay, or a late update for a
        // widget the user removed would recreate its row.
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, PhotoWidgetProvider::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            val requested = runCatching { manager.requestPinAppWidget(provider, null, null) }
                .getOrDefault(false)
            if (requested) {
                snack(R.string.widgets_pin_confirmed)
                return
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.widgets_add)
            .setMessage(R.string.widgets_pin_unsupported)
            .setPositiveButton(R.string.action_done, null)
            .show()
    }

    private fun openEditor(config: WidgetConfig) {
        startActivity(
            Intent(requireContext(), WidgetEditActivity::class.java)
                .putExtra(WidgetIntents.EXTRA_WIDGET_ID, config.widgetId),
        )
    }

    // MenuBuilder.setOptionalIconsVisible is restricted to AppCompat's own libraries, but
    // it is the only way to show the item icons in an AppCompat PopupMenu: the call is
    // cast-safe (PopupMenu always builds a MenuBuilder) and the method has been stable
    // since AppCompat 1.0, so suppressing the restriction is deliberate.
    @SuppressLint("RestrictedApi")
    private fun showMenu(anchor: View, config: WidgetConfig) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_widget_item, popup.menu)
        (popup.menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> openEditor(config)
                R.id.action_update_now -> updateNow(config)
                R.id.action_duplicate -> duplicate(config)
                R.id.action_reset_history -> confirmResetHistory(config)
                R.id.action_remove -> confirmRemove(config)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun updateNow(config: WidgetConfig) {
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = graph.coordinator.update(config.widgetId, UpdateReason.MANUAL)
            previews.remove(config.widgetId)
            snack(if (outcome.hasPhoto) R.string.updated else R.string.updated_no_photo)
        }
    }

    private fun duplicate(config: WidgetConfig) {
        val context = requireContext()
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, PhotoWidgetProvider::class.java)
        if (!manager.isRequestPinAppWidgetSupported) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.action_duplicate)
                .setMessage(R.string.duplicate_needs_launcher)
                .setPositiveButton(R.string.action_done, null)
                .show()
            return
        }
        graph.preferences.pendingDuplicateSource = config.widgetId
        graph.preferences.pendingDuplicateAt = System.currentTimeMillis()
        val requested = runCatching { manager.requestPinAppWidget(provider, null, null) }
            .getOrDefault(false)
        if (requested) {
            snack(R.string.duplicate_prompt)
        } else {
            graph.preferences.clearPendingDuplicate()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.action_duplicate)
                .setMessage(R.string.duplicate_needs_launcher)
                .setPositiveButton(R.string.action_done, null)
                .show()
        }
    }

    private fun confirmResetHistory(config: WidgetConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_reset_history)
            .setMessage(R.string.history_reset_warning)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_reset_history) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    graph.repository.resetHistory(config.widgetId)
                    previews.remove(config.widgetId)
                    snack(R.string.history_reset)
                }
            }
            .show()
    }

    private fun confirmRemove(config: WidgetConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_widget_title)
            .setMessage(R.string.delete_widget_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val folders = graph.repository.getWidget(config.widgetId)
                        ?.sources
                        .orEmpty()
                        .filter { it.type == PhotoSourceType.FOLDER }
                        .map { it.locator }
                    graph.coordinator.deleteWidget(config.widgetId)
                    graph.releaseUnusedFolderGrants(folders)
                    previews.remove(config.widgetId)
                    snack(R.string.widget_deleted)
                }
            }
            .show()
    }

    private fun snack(messageRes: Int) {
        val root = _binding?.root ?: return
        Snackbar.make(root, messageRes, Snackbar.LENGTH_SHORT).show()
    }
}
