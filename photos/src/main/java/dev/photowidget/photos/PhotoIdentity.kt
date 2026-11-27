package dev.photowidget.photos

import android.net.Uri

/**
 * Identities for cross-source de-duplication.
 *
 * Android exposes the same file under different URIs depending on how it was
 * reached: MediaStore returns `content://media/external/images/media/<id>`, the
 * Photo Picker returns `content://media/picker/.../media/<id>` and a Storage Access
 * Framework tree returns a `document/<tree>/document/<doc>` URI. Without a shared
 * identity the same photo would be rotated twice in one widget.
 *
 * Two identities are derived, both without extra permissions:
 * - `media:<id>` for MediaStore / Photo Picker entries (same media row),
 * - `path:<relative path>` for MediaStore (RELATIVE_PATH + display name) and for
 *   SAF documents (the document id minus its volume prefix).
 */
internal object PhotoIdentity {

    fun mediaId(uri: Uri): String? {
        val last = uri.lastPathSegment ?: return null
        val digits = last.takeIf { segment -> segment.isNotEmpty() && segment.all(Char::isDigit) }
        return digits
    }

    fun media(id: String?): String? = id?.takeIf { it.isNotBlank() }?.let { "media:$it" }

    /** MediaStore: RELATIVE_PATH ("Pictures/Holiday/") plus the display name. */
    fun fromMediaStore(relativePath: String?, displayName: String?): String? {
        if (displayName.isNullOrBlank()) return null
        val relative = relativePath?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
        return path("$relative/$displayName")
    }

    /**
     * SAF: document ids look like `primary:Pictures/Holiday/photo.jpg` or
     * `1A2B-3C4D:DCIM/Camera/photo.jpg`; the part after the first colon is the
     * volume-relative path.
     */
    fun fromDocumentId(documentId: String?): String? {
        if (documentId.isNullOrBlank()) return null
        val separator = documentId.indexOf(':')
        val path = if (separator >= 0) documentId.substring(separator + 1) else documentId
        return path(path)
    }

    fun path(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val normalized = path.replace('\\', '/').trim('/')
        return normalized.takeIf { it.isNotEmpty() }?.let { "path:${it.lowercase()}" }
    }
}
