package dev.photowidget.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.photowidget.app.databinding.FragmentLibraryBinding
import dev.photowidget.app.databinding.ItemAlbumBinding
import dev.photowidget.model.AlbumInfo
import dev.photowidget.model.PhotoSource
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.WidgetConfig
import dev.photowidget.photos.DocumentAccess
import dev.photowidget.photos.PhotoPermissions
import dev.photowidget.widget.UpdateReason
import dev.photowidget.widget.WidgetRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse what is on the device and add it straight to a widget.
 *
 * Individual photos use the system Photo Picker (no permission), folders use the
 * Storage Access Framework (persisted grant only), and albums need the single
 * media read permission.
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val graph get() = WidgetRuntime.get(requireContext())
    private var lastAccessKey: String? = null

    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100),
    ) { uris -> addPhotos(uris) }

    private val openFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> addFolder(uri) }

    private val requestAccess = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshAccessState(force = true) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLibraryBinding.bind(view)

        binding.addPhotos.setOnClickListener {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.addFolder.setOnClickListener { openFolder.launch(null) }
        binding.grantAccess.setOnClickListener {
            requestAccess.launch(PhotoPermissions.albumPermissions())
        }

        refreshAccessState(force = true)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshAccessState(force = false)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refreshAccessState(force: Boolean) {
        val context = requireContext()
        val full = PhotoPermissions.hasFullImageAccess(context)
        val partial = PhotoPermissions.hasPartialImageAccess(context)
        val any = PhotoPermissions.hasImageAccess(context)

        binding.albumAccessStatus.setText(
            when {
                full -> R.string.library_album_access_granted
                partial -> R.string.library_album_access_partial
                else -> R.string.library_album_access_denied
            },
        )
        binding.grantAccess.isVisible = !full

        val key = when {
            full -> "full"
            partial -> "partial"
            any -> "any"
            else -> "none"
        }
        if (force || key != lastAccessKey) {
            lastAccessKey = key
            loadAlbums()
        }
    }

    private fun loadAlbums() {
        val context = requireContext()
        binding.albumsContainer.removeAllViews()
        if (!PhotoPermissions.hasImageAccess(context)) {
            binding.albumsProgress.isVisible = false
            binding.albumsEmpty.isVisible = true
            binding.albumsEmpty.setText(R.string.library_permission_needed)
            return
        }
        binding.albumsProgress.isVisible = true
        binding.albumsEmpty.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            val albums = withContext(Dispatchers.IO) {
                runCatching { graph.resolver.albums() }.getOrNull()
            }
            val view = _binding ?: return@launch
            view.albumsProgress.isVisible = false
            if (albums == null) {
                view.albumsEmpty.isVisible = true
                view.albumsEmpty.setText(R.string.library_permission_needed)
                return@launch
            }
            if (albums.isEmpty()) {
                view.albumsEmpty.isVisible = true
                view.albumsEmpty.setText(R.string.library_albums_empty)
                return@launch
            }
            view.albumsEmpty.isVisible = false
            albums.forEach { album -> view.albumsContainer.addView(albumRow(album)) }
        }
    }

    private fun albumRow(album: AlbumInfo): View {
        val row = ItemAlbumBinding.inflate(layoutInflater, binding.albumsContainer, false)
        row.albumName.text = album.name.ifBlank { getString(R.string.library_albums) }
        row.albumCount.text = resources.getQuantityString(
            R.plurals.album_count,
            album.photoCount,
            album.photoCount,
        )
        row.root.setOnClickListener { chooseWidget { widget -> addAlbum(widget, album) } }
        return row.root
    }

    private fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val fallbackLabel = getString(R.string.source_type_photo)
        chooseWidget { widget ->
            viewLifecycleOwner.lifecycleScope.launch {
                val appContext = requireContext().applicationContext
                val added = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    uris.mapIndexedNotNull { index, uri ->
                        runCatching {
                            appContext.contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        val ref = runCatching { graph.mediaQuery.photo(uri.toString()) }.getOrNull()
                            ?: return@mapIndexedNotNull null
                        PhotoSource(
                            type = PhotoSourceType.PHOTO,
                            locator = uri.toString(),
                            label = ref.displayName.ifBlank { fallbackLabel },
                            addedAt = now + index,
                        )
                    }
                }
                if (added.isEmpty()) {
                    snack(getString(R.string.photo_add_error))
                    return@launch
                }
                added.forEach { source ->
                    graph.repository.addSource(
                        widget.widgetId,
                        source.type,
                        source.locator,
                        source.label,
                    )
                }
                graph.coordinator.update(widget.widgetId, UpdateReason.CONFIG_CHANGED)
                snack(
                    resources.getQuantityString(
                        R.plurals.library_added_photos,
                        added.size,
                        added.size,
                        widget.name,
                    ),
                )
            }
        }
    }

    private fun addAlbum(widget: WidgetConfig, album: AlbumInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            graph.repository.addSource(
                widget.widgetId,
                PhotoSourceType.ALBUM,
                album.bucketId,
                album.name,
            )
            graph.coordinator.update(widget.widgetId, UpdateReason.CONFIG_CHANGED)
            snack(getString(R.string.library_added, widget.name))
        }
    }

    private fun addFolder(uri: Uri?) {
        if (uri == null) return
        val context = requireContext()
        DocumentAccess.takePersistableRead(context, uri)
        val label = DocumentFile.fromTreeUri(context, uri)?.name
            ?: getString(R.string.source_type_folder)
        chooseWidget { widget ->
            viewLifecycleOwner.lifecycleScope.launch {
                graph.repository.addSource(
                    widget.widgetId,
                    PhotoSourceType.FOLDER,
                    uri.toString(),
                    label,
                )
                graph.coordinator.update(widget.widgetId, UpdateReason.CONFIG_CHANGED)
                snack(getString(R.string.library_added, widget.name))
            }
        }
    }

    private fun chooseWidget(action: (WidgetConfig) -> Unit) {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val widgets = withContext(Dispatchers.IO) { graph.repository.getAllWidgets() }
            if (widgets.isEmpty()) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.library_no_widgets_title)
                    .setMessage(R.string.library_no_widgets_body)
                    .setPositiveButton(R.string.action_done, null)
                    .show()
                return@launch
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.library_choose_widget)
                .setItems(widgets.map { it.name }.toTypedArray()) { _, which ->
                    action(widgets[which])
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun snack(message: String) {
        val root = _binding?.root ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show()
    }
}
