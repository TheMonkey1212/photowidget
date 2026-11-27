package dev.photowidget.photos

import dev.photowidget.model.PhotoRef

/**
 * One node of a Storage Access Framework tree.
 *
 * [children] is a lambda so subdirectories are only listed when the walk reaches
 * them: a tree with thousands of files is never fully materialised at once.
 */
internal class FolderEntry(
    val name: String?,
    val uri: String,
    val isDirectory: Boolean,
    val isFile: Boolean,
    val mimeType: String?,
    val lastModified: Long = 0L,
    val children: (() -> List<FolderEntry>)? = null,
)

/**
 * Recursively collects images from a SAF tree, including nested subfolders.
 *
 * Rules: hidden files and folders are skipped, only files whose MIME type is
 * missing or starts with `image/` are kept, recursion stops at [maxDepth] and the whole walk
 * stops at [maxPhotos] so a hostile or huge tree cannot exhaust memory.
 *
 * Each photo carries a `path:` identity so the same file reached through
 * MediaStore is de-duplicated by [PhotoResolver].
 */
internal fun collectFolderPhotos(
    root: FolderEntry,
    label: String,
    maxDepth: Int = 16,
    maxPhotos: Int = 50_000,
): List<PhotoRef> {
    val out = ArrayList<PhotoRef>()
    collectInto(root, label, out, depth = 0, maxDepth = maxDepth, maxPhotos = maxPhotos)
    return out
}

private fun collectInto(
    entry: FolderEntry,
    label: String,
    out: MutableList<PhotoRef>,
    depth: Int,
    maxDepth: Int,
    maxPhotos: Int,
) {
    if (depth > maxDepth || out.size >= maxPhotos) return
    val children = entry.children?.invoke() ?: return
    for (child in children) {
        if (out.size >= maxPhotos) return
        val name = child.name ?: continue
        if (name.startsWith(".")) continue
        if (child.isDirectory) {
            collectInto(child, label, out, depth + 1, maxDepth, maxPhotos)
        } else if (child.isFile) {
            val type = child.mimeType
            if (type == null || type.startsWith("image/")) {
                val identities = buildSet {
                    PhotoIdentity.fromDocumentId(documentIdOf(child.uri))?.let(::add)
                }
                out += PhotoRef(
                    uri = child.uri,
                    key = child.uri,
                    displayName = name,
                    dateTaken = child.lastModified,
                    mimeType = type,
                    sourceLabel = label,
                    dedupeKeys = identities,
                )
            }
        }
    }
}

/** The document id inside a SAF document URI, if it can be extracted. */
internal fun documentIdOf(uri: String): String? = runCatching {
    android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(uri))
}.getOrNull()
