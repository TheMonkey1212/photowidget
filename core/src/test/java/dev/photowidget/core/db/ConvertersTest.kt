package dev.photowidget.core.db

import dev.photowidget.model.ImageFit
import dev.photowidget.model.WidgetAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Appearance is stored as JSON, so rows written by earlier versions must keep
 * decoding after the model evolves.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun appearanceSavedWithTheOldFitNameStillDecodesAsFit() {
        val legacy = """{"cornerRadiusDp":8.0,"marginDp":8.0,"imageFit":"AMBIENT"}"""
        val decoded = converters.stringToAppearance(legacy)
        assertEquals(ImageFit.FIT, decoded.imageFit)
        assertEquals(8f, decoded.cornerRadiusDp, 0f)
    }

    @Test
    fun appearanceSavedBeforeTheCardExistedDecodesWithoutOne() {
        val legacy = """{"imageFit":"FILL","backgroundColor":4279242767}"""
        val decoded = converters.stringToAppearance(legacy)
        assertFalse(decoded.showCard)
        assertEquals(WidgetAppearance().cornerRadiusDp, decoded.cornerRadiusDp, 0f)
    }

    @Test
    fun showCardRoundTripsThroughStorage() {
        val saved = converters.appearanceToString(
            WidgetAppearance(showCard = true, imageFit = ImageFit.FIT),
        )
        val decoded = converters.stringToAppearance(saved)
        assertTrue(decoded.showCard)
        assertEquals(ImageFit.FIT, decoded.imageFit)
    }
}
