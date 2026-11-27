package dev.photowidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.photowidget.model.ImageFit
import dev.photowidget.model.OverlayAppearance
import dev.photowidget.model.PhotoRef
import dev.photowidget.model.RenderQuality
import dev.photowidget.model.WidgetAppearance
import dev.photowidget.model.WidgetConfig
import dev.photowidget.model.WidgetMode
import dev.photowidget.photos.PhotoImageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class WidgetRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeLoader(private val images: Map<String, Bitmap>) : PhotoImageLoader {
        override fun load(
            uri: String,
            targetWidth: Int,
            targetHeight: Int,
            quality: RenderQuality,
        ): Bitmap? = images[uri]
    }

    private fun solid(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun appearance(
        fit: ImageFit = ImageFit.FILL,
        cornerRadius: Float = 0f,
        overlay: OverlayAppearance = OverlayAppearance(),
    ) = WidgetAppearance(
        cornerRadiusDp = cornerRadius,
        marginDp = 0f,
        paddingDp = 0f,
        borderWidthDp = 0f,
        // These tests measure how the photo is placed inside the card, so the card
        // itself must reach the bitmap edges.
        shadowEnabled = false,
        backgroundStyle = dev.photowidget.model.BackgroundStyle.SOLID,
        backgroundColor = 0xFF000000,
        imageFit = fit,
        overlay = overlay,
    )

    private fun config(
        appearance: WidgetAppearance = appearance(),
        mode: WidgetMode = WidgetMode.SINGLE,
    ) = WidgetConfig(widgetId = 1, mode = mode, appearance = appearance)

    @Test
    fun fillCropsInsteadOfStretching() {
        // A wide two-colour source: focusing left must show only the left colour,
        // which proves the photo is cropped proportionally, not squeezed.
        val wide = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
            for (x in 100 until 200) {
                for (y in 0 until 100) setPixel(x, y, Color.BLUE)
            }
        }
        val renderer = WidgetRenderer(context, FakeLoader(mapOf("wide" to wide)))
        val bitmap = renderer.render(
            config(appearance().copy(focusX = 0f)),
            listOf(PhotoRef(uri = "wide")),
            200,
            200,
        )
        assertEquals(Color.GREEN, bitmap.getPixel(100, 100))
        assertEquals(Color.GREEN, bitmap.getPixel(10, 100))
    }

    @Test
    fun blurFillKeepsPhotoWholeOverBlurredCopy() {
        val red = solid(100, 100, Color.RED)
        val loader = FakeLoader(mapOf("red" to red))
        val renderer = WidgetRenderer(context, loader)
        val fill = renderer.render(
            config(appearance(fit = ImageFit.FILL)),
            listOf(PhotoRef(uri = "red")),
            200,
            100,
        )
        val blur = renderer.render(
            config(appearance(fit = ImageFit.BLUR_FILL)),
            listOf(PhotoRef(uri = "red")),
            200,
            100,
        )
        // The centred original is visible in both.
        assertEquals(Color.RED, blur.getPixel(100, 50))
        // The sides differ: Fill covers everything, Blur Fill blurs/darkens behind.
        assertNotEquals(fill.getPixel(5, 50), blur.getPixel(5, 50))
        assertTrue(Color.alpha(blur.getPixel(5, 50)) == 255)
    }

    @Test
    fun fitShowsTheWholePhotoAtItsOwnShapeAndLeavesTheBarsTransparent() {
        // Four stacked bands: FILL would crop the outer ones away, FIT must not.
        val bands = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val tall = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 200) {
                for (x in 0 until 100) setPixel(x, y, bands[y / 50])
            }
        }
        val renderer = WidgetRenderer(context, FakeLoader(mapOf("tall" to tall)))
        val bitmap = renderer.render(
            config(appearance(fit = ImageFit.FIT)),
            listOf(PhotoRef(uri = "tall")),
            200,
            200,
        )
        // Every band of the photo is visible: nothing is cropped.
        assertEquals(Color.RED, bitmap.getPixel(100, 15))
        assertEquals(Color.GREEN, bitmap.getPixel(100, 75))
        assertEquals(Color.BLUE, bitmap.getPixel(100, 125))
        assertEquals(Color.YELLOW, bitmap.getPixel(100, 185))

        // Nothing is painted beside the photo: no wash, no bars, no card. The
        // widget bitmap is transparent there, so the wallpaper shows through.
        assertEquals(0, Color.alpha(bitmap.getPixel(10, 100)))
        assertEquals(0, Color.alpha(bitmap.getPixel(190, 100)))
    }

    @Test
    fun cardIsOptionalAndOffByDefault() {
        // A wide photo in a square widget leaves top and bottom strips.
        val wide = solid(200, 100, Color.RED)
        val renderer = WidgetRenderer(context, FakeLoader(mapOf("wide" to wide)))
        val photos = listOf(PhotoRef(uri = "wide"))
        val bare = renderer.render(config(appearance(fit = ImageFit.FIT)), photos, 200, 200)
        assertEquals(0, Color.alpha(bare.getPixel(100, 5)))

        val framed = renderer.render(
            config(
                appearance(fit = ImageFit.FIT).copy(
                    showCard = true,
                    backgroundStyle = dev.photowidget.model.BackgroundStyle.SOLID,
                    backgroundColor = 0xFF123456,
                ),
            ),
            photos,
            200,
            200,
        )
        assertEquals(0xFF123456.toInt(), framed.getPixel(100, 5))
    }

    @Test
    fun cornerRadiusClipsTheCard() {
        val red = solid(100, 100, Color.RED)
        val renderer = WidgetRenderer(context, FakeLoader(mapOf("red" to red)))
        val bitmap = renderer.render(
            config(appearance(cornerRadius = 40f)),
            listOf(PhotoRef(uri = "red")),
            200,
            200,
        )
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(255, Color.alpha(bitmap.getPixel(100, 100)))
    }

    @Test
    fun collageUsesOnePhotoPerCell() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val loader = FakeLoader(
            colors.mapIndexed { index, color -> "p$index" to solid(60, 60, color) }.toMap(),
        )
        val renderer = WidgetRenderer(context, loader)
        val bitmap = renderer.render(
            config(
                appearance = appearance().copy(collageGapDp = 0f),
                mode = WidgetMode.COLLAGE,
            ),
            colors.indices.map { PhotoRef(uri = "p$it") },
            200,
            200,
        )
        assertEquals(Color.RED, bitmap.getPixel(50, 50))
        assertEquals(Color.GREEN, bitmap.getPixel(150, 50))
        assertEquals(Color.BLUE, bitmap.getPixel(50, 150))
        assertEquals(Color.YELLOW, bitmap.getPixel(150, 150))
    }

    @Test
    fun overlayDrawsAScrimPlateOverThePhoto() {
        val grey = solid(100, 100, Color.rgb(128, 128, 128))
        val renderer = WidgetRenderer(context, FakeLoader(mapOf("grey" to grey)))
        val plain = renderer.render(
            config(appearance()),
            listOf(PhotoRef(uri = "grey")),
            300,
            200,
        )
        val withOverlay = renderer.render(
            config(
                appearance(
                    overlay = OverlayAppearance(
                        showDate = true,
                        position = dev.photowidget.model.OverlayPosition.TOP_START,
                        textColor = 0xFFFFFFFF,
                        scrim = true,
                        scrimStrength = 0.8f,
                        datePattern = "yyyy",
                    ),
                ),
            ),
            listOf(PhotoRef(uri = "grey")),
            300,
            200,
            now = 1_700_000_000_000L,
        )
        // The scrim darkens the top-left plate (text pixels are brighter), while the
        // rest of the photo stays untouched.
        var darkPlatePixels = 0
        for (x in 12..48 step 2) {
            for (y in 12..40 step 2) {
                if (Color.red(withOverlay.getPixel(x, y)) < 100) darkPlatePixels++
            }
        }
        assertTrue("scrim should darken the plate, found $darkPlatePixels", darkPlatePixels > 10)
        assertEquals(plain.getPixel(150, 100), withOverlay.getPixel(150, 100))
    }

    @Test
    fun missingPhotoDrawsFallbackWithoutCrash() {
        val renderer = WidgetRenderer(context, FakeLoader(emptyMap()))
        val bitmap = renderer.render(
            config(appearance()),
            listOf(PhotoRef(uri = "gone")),
            160,
            160,
        )
        assertEquals(255, Color.alpha(bitmap.getPixel(80, 80)))
    }
}
