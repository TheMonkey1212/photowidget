package dev.photowidget.photos

import dev.photowidget.model.AlbumInfo
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoSource
import dev.photowidget.model.PhotoSourceProvider
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.ResolvedPhotos
import dev.photowidget.model.SourceIssue

/**
 * Resolves the sources of one widget into a de-duplicated, ordered photo list.
 *
 * Order is source insertion order, then the order the source returns (album:
 * capture date ascending; folder: file name). That order is what SEQUENTIAL mode
 * walks, and it is stable across refreshes so new files are picked up in place.
 */
class PhotoResolver(private val query: MediaQuery) : PhotoSourceProvider {

    /** Throws [PhotoAccessException] when album access is missing. */
    fun albums(): List<AlbumInfo> = query.albums()

    override fun resolve(sources: List<PhotoSource>): ResolvedPhotos {
        if (sources.isEmpty()) return ResolvedPhotos()
        val ordered = sources.sortedWith(compareBy({ it.addedAt }, { it.id }))
        val unique = LinkedHashMap<String, PhotoRef>()
        // Identities already claimed by a photo. A file reachable as both a
        // MediaStore row and a SAF document is only added once.
        val seenIdentities = HashSet<String>()
        val issues = mutableListOf<SourceIssue>()

        for (source in ordered) {
            try {
                val photos: List<PhotoRef> = when (source.type) {
                    PhotoSourceType.PHOTO -> listOfNotNull(query.photo(source.locator))
                    PhotoSourceType.ALBUM -> query.photosInAlbum(source.locator)
                    PhotoSourceType.FOLDER -> query.photosInFolder(source.locator)
                }
                for (photo in photos) {
                    if (photo.uri.isBlank()) continue
                    if (photo.key in unique) continue
                    val identities = photo.dedupeKeys.ifEmpty { setOf(photo.key) }
                    if (identities.any { it in seenIdentities }) continue
                    seenIdentities += identities
                    unique[photo.key] = photo
                }
            } catch (e: PhotoAccessException) {
                issues += SourceIssue(
                    sourceId = source.id,
                    sourceLabel = source.label.ifBlank { source.locator },
                    message = e.message ?: "Cannot read this source",
                    permissionDenied = e.permissionDenied,
                )
            } catch (e: Exception) {
                issues += SourceIssue(
                    sourceId = source.id,
                    sourceLabel = source.label.ifBlank { source.locator },
                    message = e.message ?: e.javaClass.simpleName,
                )
            }
        }
        return ResolvedPhotos(photos = unique.values.toList(), issues = issues)
    }
}
