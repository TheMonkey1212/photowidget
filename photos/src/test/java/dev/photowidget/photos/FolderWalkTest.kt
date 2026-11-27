package dev.photowidget.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FolderWalkTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3APictures"

    private fun documentUri(documentId: String): String =
        "$tree/document/primary%3A" + documentId.replace("/", "%2F")

    private fun file(name: String, mime: String? = "image/jpeg", id: String = name) = FolderEntry(
        name = name,
        uri = documentUri(id),
        isDirectory = false,
        isFile = true,
        mimeType = mime,
    )

    private fun dir(name: String, vararg children: FolderEntry) = FolderEntry(
        name = name,
        uri = documentUri(name),
        isDirectory = true,
        isFile = false,
        mimeType = null,
        children = { children.toList() },
    )

    @Test
    fun listsPhotosInNestedSubfolders() {
        val root = dir(
            "Pictures",
            file("root.jpg", id = "Pictures/root.jpg"),
            dir(
                "Mix",
                file("mid.jpg", id = "Pictures/Mix/mid.jpg"),
                dir("Sub", file("deep.jpg", id = "Pictures/Mix/Sub/deep.jpg")),
            ),
        )

        val names = collectFolderPhotos(root, "Pictures").map { it.displayName }.sorted()

        assertEquals(listOf("deep.jpg", "mid.jpg", "root.jpg"), names)
    }

    @Test
    fun skipsHiddenEntriesAndNonImages() {
        val root = dir(
            "Pictures",
            file("keep.jpg"),
            file(".hidden.jpg"),
            file("notes.txt", mime = "text/plain"),
            dir(".thumbnails", file("cached.jpg")),
        )

        val names = collectFolderPhotos(root, "Pictures").map { it.displayName }

        assertEquals(listOf("keep.jpg"), names)
    }

    @Test
    fun stopsAtDepthLimit() {
        val deepest = dir("L5", file("too-deep.jpg", id = "L5/too-deep.jpg"))
        val root = dir(
            "root",
            file("top.jpg", id = "top.jpg"),
            dir("L1", dir("L2", dir("L3", dir("L4", deepest)))),
        )

        val shallow = collectFolderPhotos(root, "root", maxDepth = 2).map { it.displayName }
        val deep = collectFolderPhotos(root, "root", maxDepth = 10).map { it.displayName }

        assertEquals(listOf("top.jpg"), shallow)
        assertTrue(deep.contains("too-deep.jpg"))
    }

    @Test
    fun attachesPathIdentitySoMediaStoreDuplicatesCanBeRecognised() {
        val photo = collectFolderPhotos(
            dir("Pictures", file("holiday.jpg", id = "Pictures/Holiday/holiday.jpg")),
            "Pictures",
        ).single()

        assertTrue(photo.dedupeKeys.contains("path:pictures/holiday/holiday.jpg"))
    }

    @Test
    fun aDeletedFileSimplyDisappearsFromTheListing() {
        val before = collectFolderPhotos(
            dir("Pictures", file("a.jpg"), file("b.jpg")),
            "Pictures",
        ).map { it.displayName }
        val after = collectFolderPhotos(dir("Pictures", file("a.jpg")), "Pictures")
            .map { it.displayName }

        assertEquals(listOf("a.jpg", "b.jpg"), before.sorted())
        assertEquals(listOf("a.jpg"), after)
    }
}
