package dev.photowidget.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.photowidget.model.RenderQuality
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The decoder runs a bounds-only pass first. BitmapFactory returns null for that
 * pass by design, and that null used to be treated as "the photo cannot be loaded",
 * so every widget rendered the unavailable placeholder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class PhotoBitmapLoaderTest {

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun loadsDecodablePhoto() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = jpegBytes(400, 300)

        // Guard rail: the bounds pass must actually see the image in this environment.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
        assertTrue(
            "test environment cannot read JPEG bounds (${bounds.outWidth}x${bounds.outHeight})",
            bounds.outWidth > 0 && bounds.outHeight > 0,
        )

        val uri = Uri.parse("content://media/external/images/media/1")
        val loader = PhotoBitmapLoader(context) { ByteArrayInputStream(bytes) }

        val loaded = loader.load(uri.toString(), 200, 150, RenderQuality.NORMAL)

        assertNotNull("a readable photo must decode", loaded)
        assertTrue(loaded!!.width > 0 && loaded.height > 0)
    }

    @Test
    fun missingPhotoReturnsNull() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://media/external/images/media/404")

        val loaded = PhotoBitmapLoader(context) { null }
            .load(uri.toString(), 200, 150, RenderQuality.NORMAL)

        assertTrue("an unreadable photo must fail without crashing", loaded == null)
    }
}
