package dev.photowidget.photos

import dev.photowidget.model.AlbumInfo
import dev.photowidget.model.PhotoRef

/** A source could not be read. [permissionDenied] drives the recovery UI. */
class PhotoAccessException(
    message: String,
    val permissionDenied: Boolean = false,
) : Exception(message)

/**
 * Abstraction over the places photos can come from. Keeping it an interface lets
 * the resolver be unit tested with deterministic fakes and keeps MediaStore/SAF
 * details out of the widget and selection code.
 */
interface MediaQuery {
    fun albums(): List<AlbumInfo>
    fun photosInAlbum(bucketId: String): List<PhotoRef>
    fun photosInFolder(treeUri: String): List<PhotoRef>
    fun photo(uri: String): PhotoRef?
}
