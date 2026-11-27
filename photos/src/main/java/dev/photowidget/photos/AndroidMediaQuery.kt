package dev.photowidget.photos

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.photowidget.model.AlbumInfo
import dev.photowidget.model.PhotoRef
import androidx.documentfile.provider.DocumentFile

/**
 * Reads photos from MediaStore (albums) and from Storage Access Framework trees
 * (folders). Queries run on every widget refresh, which is how newly added files
 * inside a chosen album or folder are detected automatically.
 */
class AndroidMediaQuery(private val context: Context) : MediaQuery {

    private val resolver: ContentResolver get() = context.contentResolver

    private val imagesUri: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    override fun albums(): List<AlbumInfo> {
        requireReadAccess()
        val accumulators = LinkedHashMap<String, Accumulator>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        try {
            resolver.query(
                imagesUri,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val bucketId = cursor.getLong(bucketCol).toString()
                    val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: "Unknown album"
                    val existing = accumulators[bucketId]
                    if (existing == null) {
                        accumulators[bucketId] = Accumulator(
                            name = name,
                            count = 1,
                            coverUri = Uri.withAppendedPath(
                                imagesUri,
                                cursor.getLong(idCol).toString(),
                            ).toString(),
                        )
                    } else {
                        existing.count += 1
                    }
                }
            }
        } catch (e: SecurityException) {
            throw PhotoAccessException("Photo access was revoked", permissionDenied = true)
        }
        return accumulators.map { (id, acc) ->
            AlbumInfo(bucketId = id, name = acc.name, photoCount = acc.count, coverUri = acc.coverUri)
        }.sortedBy { it.name.lowercase() }
    }

    override fun photosInAlbum(bucketId: String): List<PhotoRef> {
        requireReadAccess()
        val numericBucket = bucketId.toLongOrNull()
            ?: throw PhotoAccessException("Album identifier is invalid")
        val albumName = runCatching {
            queryAlbumName(numericBucket)
        }.getOrNull()
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
            add(MediaStore.Images.Media.BUCKET_ID)
            // Needed to recognise the same file when it also arrives through a SAF tree.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()
        val out = ArrayList<PhotoRef>()
        try {
            resolver.query(
                imagesUri,
                projection,
                "${MediaStore.Images.Media.BUCKET_ID} = ?",
                arrayOf(numericBucket.toString()),
                "${MediaStore.Images.Media.DATE_TAKEN} ASC, ${MediaStore.Images.Media._ID} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val relativeCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol).toString()
                    val uri = Uri.withAppendedPath(imagesUri, id)
                    val displayName = cursor.getString(nameCol) ?: "Photo"
                    val identities = buildSet {
                        PhotoIdentity.media(id)?.let(::add)
                        PhotoIdentity.fromMediaStore(
                            if (relativeCol >= 0) cursor.getString(relativeCol) else null,
                            displayName,
                        )?.let(::add)
                    }
                    out += PhotoRef(
                        uri = uri.toString(),
                        key = uri.toString(),
                        displayName = displayName,
                        dateTaken = cursor.getLong(takenCol).takeIf { it > 0 }
                            ?: cursor.getLong(addedCol) * 1000L,
                        mimeType = cursor.getString(mimeCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                        sourceLabel = albumName ?: "Album",
                        bucketId = numericBucket.toString(),
                        dedupeKeys = identities,
                    )
                }
            }
        } catch (e: SecurityException) {
            throw PhotoAccessException("Photo access was revoked", permissionDenied = true)
        }
        return out
    }

    override fun photosInFolder(treeUri: String): List<PhotoRef> {
        val tree = Uri.parse(treeUri)
        if (!DocumentAccess.hasPersistedRead(context, tree)) {
            throw PhotoAccessException("Folder access is no longer granted", permissionDenied = true)
        }
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: throw PhotoAccessException("Folder is no longer available")
        if (!root.exists()) throw PhotoAccessException("Folder is no longer available")

        val label = root.name?.takeIf { it.isNotBlank() } ?: "Folder"
        val photos = try {
            collectFolderPhotos(root.toFolderEntry(), label)
        } catch (e: SecurityException) {
            throw PhotoAccessException("Folder access was revoked", permissionDenied = true)
        }
        return photos.sortedWith(compareBy({ it.displayName.lowercase() }, { it.uri }))
    }

    private fun DocumentFile.toFolderEntry(): FolderEntry = FolderEntry(
        name = name,
        uri = uri.toString(),
        isDirectory = isDirectory,
        isFile = isFile,
        mimeType = type,
        lastModified = lastModified(),
        children = { listFiles().map { it.toFolderEntry() } },
    )

    override fun photo(uri: String): PhotoRef? {
        val parsed = Uri.parse(uri)
        val mime = runCatching { resolver.getType(parsed) }.getOrNull()
        if (mime != null && !mime.startsWith("image/")) return null
        val displayName = friendlyName(queryDisplayName(parsed), parsed)
            ?: parsed.lastPathSegment
            ?: "Photo"
        // Verify the document is still readable; deleted or revoked photos are skipped.
        val readable = runCatching {
            resolver.openFileDescriptor(parsed, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!readable) return null
        val identities = buildSet {
            PhotoIdentity.media(PhotoIdentity.mediaId(parsed))?.let(::add)
        }
        return PhotoRef(
            uri = uri,
            key = uri,
            displayName = displayName,
            mimeType = mime,
            dedupeKeys = identities,
        )
    }

    private fun requireReadAccess() {
        if (!PhotoPermissions.hasImageAccess(context)) {
            throw PhotoAccessException("Photo access is not granted", permissionDenied = true)
        }
    }

    private fun queryAlbumName(bucketId: Long): String? {
        resolver.query(
            imagesUri,
            arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            "${MediaStore.Images.Media.BUCKET_ID} = ?",
            arrayOf(bucketId.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    /**
     * The Photo Picker reports a synthetic "<id>.jpg" name for picked photos. Prefer the real
     * file name from MediaStore when this app can read it, otherwise keep what the picker gave.
     */
    private fun friendlyName(raw: String?, parsed: Uri): String? {
        if (raw == null) return null
        if (!raw.substringBeforeLast('.').all { it.isDigit() }) return raw
        val id = PhotoIdentity.mediaId(parsed) ?: return raw
        return runCatching {
            resolver.query(
                imagesUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf(id),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: raw
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private class Accumulator(val name: String, var count: Int, val coverUri: String?)
}
