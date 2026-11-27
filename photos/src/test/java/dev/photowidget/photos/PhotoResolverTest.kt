package dev.photowidget.photos

import dev.photowidget.model.AlbumInfo
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.PhotoSource
import dev.photowidget.model.PhotoSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoResolverTest {

    private class FakeQuery : MediaQuery {
        val albums = mutableMapOf<String, List<PhotoRef>>()
        val folders = mutableMapOf<String, List<PhotoRef>>()
        val photos = mutableMapOf<String, PhotoRef>()
        var failAlbums = false

        override fun albums(): List<AlbumInfo> = emptyList()

        override fun photosInAlbum(bucketId: String): List<PhotoRef> {
            if (failAlbums) throw PhotoAccessException("no access", permissionDenied = true)
            return albums[bucketId].orEmpty()
        }

        override fun photosInFolder(treeUri: String): List<PhotoRef> =
            folders[treeUri].orEmpty()

        override fun photo(uri: String): PhotoRef? = photos[uri]
    }

    private fun ref(uri: String, name: String = uri) =
        PhotoRef(uri = uri, key = uri, displayName = name)

    @Test
    fun combinesPhotosAlbumsAndFoldersInInsertionOrder() {
        val query = FakeQuery()
        query.photos["content://one"] = ref("content://one", "one")
        query.albums["bucket"] = listOf(ref("content://b1", "b1"), ref("content://b2", "b2"))
        query.folders["content://tree"] = listOf(ref("content://f1", "f1"))
        val resolver = PhotoResolver(query)

        val result = resolver.resolve(
            listOf(
                PhotoSource(type = PhotoSourceType.PHOTO, locator = "content://one", addedAt = 3),
                PhotoSource(type = PhotoSourceType.ALBUM, locator = "bucket", addedAt = 1),
                PhotoSource(type = PhotoSourceType.FOLDER, locator = "content://tree", addedAt = 2),
            ),
        )

        assertEquals(
            listOf("content://b1", "content://b2", "content://f1", "content://one"),
            result.photos.map { it.uri },
        )
        assertTrue(result.complete)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun deduplicatesTheSamePhotoAcrossSources() {
        val query = FakeQuery()
        query.albums["bucket"] = listOf(ref("content://shared"), ref("content://b"))
        query.folders["content://tree"] = listOf(ref("content://shared"))
        query.photos["content://shared"] = ref("content://shared")
        val resolver = PhotoResolver(query)

        val result = resolver.resolve(
            listOf(
                PhotoSource(type = PhotoSourceType.PHOTO, locator = "content://shared", addedAt = 1),
                PhotoSource(type = PhotoSourceType.ALBUM, locator = "bucket", addedAt = 2),
                PhotoSource(type = PhotoSourceType.FOLDER, locator = "content://tree", addedAt = 3),
            ),
        )

        assertEquals(2, result.photos.size)
        assertEquals(1, result.photos.count { it.key == "content://shared" })
    }

    @Test
    fun picksUpNewFilesAndDropsDeletedOnes() {
        val query = FakeQuery()
        query.albums["bucket"] = listOf(ref("content://a"))
        val resolver = PhotoResolver(query)
        val source = PhotoSource(
            type = PhotoSourceType.ALBUM,
            locator = "bucket",
            addedAt = 1,
        )

        assertEquals(listOf("content://a"), resolver.resolve(listOf(source)).photos.map { it.uri })

        query.albums["bucket"] = listOf(ref("content://a"), ref("content://new"))
        assertEquals(
            listOf("content://a", "content://new"),
            resolver.resolve(listOf(source)).photos.map { it.uri },
        )

        query.albums["bucket"] = listOf(ref("content://new"))
        assertEquals(
            listOf("content://new"),
            resolver.resolve(listOf(source)).photos.map { it.uri },
        )
    }

    @Test
    fun revokedAlbumAccessKeepsOtherSourcesAndReportsIssue() {
        val query = FakeQuery()
        query.photos["content://saved"] = ref("content://saved")
        query.albums["bucket"] = listOf(ref("content://album"))
        query.failAlbums = true
        val resolver = PhotoResolver(query)

        val result = resolver.resolve(
            listOf(
                PhotoSource(
                    type = PhotoSourceType.ALBUM,
                    locator = "bucket",
                    label = "Holidays",
                    addedAt = 1,
                ),
                PhotoSource(
                    type = PhotoSourceType.PHOTO,
                    locator = "content://saved",
                    addedAt = 2,
                ),
            ),
        )

        assertEquals(listOf("content://saved"), result.photos.map { it.uri })
        assertEquals(1, result.issues.size)
        assertEquals("Holidays", result.issues.first().sourceLabel)
        assertTrue(result.issues.first().permissionDenied)
        assertFalse(result.complete)
    }

    @Test
    fun deduplicatesTheSameFileReachedThroughAlbumAndFolderTree() {
        val query = FakeQuery()
        // Same physical files, different Android URIs: a MediaStore row and a SAF document.
        query.albums["bucket"] = listOf(
            PhotoRef(
                uri = "content://media/external/images/media/19",
                displayName = "holiday.jpg",
                dedupeKeys = setOf("media:19", "path:pictures/holiday/holiday.jpg"),
            ),
            PhotoRef(
                uri = "content://media/external/images/media/20",
                displayName = "other.jpg",
                dedupeKeys = setOf("media:20", "path:pictures/holiday/other.jpg"),
            ),
        )
        query.folders["content://tree"] = listOf(
            PhotoRef(
                uri = "content://com.android.externalstorage.documents/tree/x/document/holiday",
                displayName = "holiday.jpg",
                dedupeKeys = setOf("path:pictures/holiday/holiday.jpg"),
            ),
            PhotoRef(
                uri = "content://com.android.externalstorage.documents/tree/x/document/sub-only",
                displayName = "sub-only.jpg",
                dedupeKeys = setOf("path:pictures/holiday/sub/sub-only.jpg"),
            ),
        )
        val resolver = PhotoResolver(query)

        val result = resolver.resolve(
            listOf(
                PhotoSource(type = PhotoSourceType.ALBUM, locator = "bucket", addedAt = 1),
                PhotoSource(type = PhotoSourceType.FOLDER, locator = "content://tree", addedAt = 2),
            ),
        )

        assertEquals(
            listOf(
                "content://media/external/images/media/19",
                "content://media/external/images/media/20",
                "content://com.android.externalstorage.documents/tree/x/document/sub-only",
            ),
            result.photos.map { it.uri },
        )
    }

    @Test
    fun missingSinglePhotoIsSkippedButNotAnIssue() {
        val query = FakeQuery()
        val resolver = PhotoResolver(query)
        val result = resolver.resolve(
            listOf(PhotoSource(type = PhotoSourceType.PHOTO, locator = "content://gone")),
        )
        assertTrue(result.photos.isEmpty())
        assertTrue(result.complete)
    }
}
