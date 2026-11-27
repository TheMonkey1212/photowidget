package dev.photowidget.photos

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhotoIdentityTest {

    @Test
    fun mediaStoreAndFolderPathsProduceTheSameIdentity() {
        val media = PhotoIdentity.fromMediaStore("Pictures/Holiday/", "Holiday.JPG")
        val folder = PhotoIdentity.fromDocumentId("primary:Pictures/Holiday/Holiday.JPG")

        assertEquals("path:pictures/holiday/holiday.jpg", media)
        assertEquals(media, folder)
    }

    @Test
    fun secondaryVolumeDocumentIdsKeepTheirPath() {
        val folder = PhotoIdentity.fromDocumentId("1A2B-3C4D:DCIM/Camera/shot.jpg")

        assertEquals("path:dcim/camera/shot.jpg", folder)
    }

    @Test
    fun photoPickerAndMediaStoreShareTheMediaIdentity() {
        val picker = Uri.parse(
            "content://media/picker/0/com.android.providers.media.photopicker/media/1000000019",
        )
        val store = Uri.parse("content://media/external/images/media/1000000019")

        assertEquals("1000000019", PhotoIdentity.mediaId(picker))
        assertEquals(PhotoIdentity.mediaId(store), PhotoIdentity.mediaId(picker))
        assertEquals("media:1000000019", PhotoIdentity.media(PhotoIdentity.mediaId(picker)))
    }

    @Test
    fun blankInputsHaveNoIdentity() {
        assertNull(PhotoIdentity.fromMediaStore(null, "photo.jpg"))
        assertNull(PhotoIdentity.fromMediaStore("Pictures/", "   "))
        assertNull(PhotoIdentity.fromDocumentId(null))
        assertNull(PhotoIdentity.path(""))
        assertTrue(PhotoIdentity.path("Pictures").orEmpty().startsWith("path:"))
    }
}
