package dev.photowidget.widget

import dev.photowidget.model.PhotoSource
import dev.photowidget.model.PhotoSourceType
import dev.photowidget.model.WidgetConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher's onUpdate creates an empty row as soon as a widget is pinned. A
 * duplicate request must still be applied over that stub, but never over a widget
 * the user has already configured.
 */
class DuplicateAdoptionTest {

    private val requestedAt = 1_700_000_000_000L

    private fun config(
        createdAt: Long = requestedAt + 500L,
        sources: List<PhotoSource> = emptyList(),
        singlePhotoUri: String? = null,
    ) = WidgetConfig(
        widgetId = 7,
        createdAt = createdAt,
        updatedAt = createdAt,
        sources = sources,
        singlePhotoUri = singlePhotoUri,
    )

    @Test
    fun missingRowIsAlwaysAdopted() {
        assertTrue(isUnconfiguredStub(null, requestedAt))
    }

    @Test
    fun emptyRowCreatedByThePinIsAdopted() {
        assertTrue(isUnconfiguredStub(config(), requestedAt))
    }

    @Test
    fun emptyRowThatPredatesTheRequestIsAnExistingWidget() {
        assertFalse(isUnconfiguredStub(config(createdAt = requestedAt - 60_000L), requestedAt))
    }

    @Test
    fun configuredWidgetsAreNeverOverwritten() {
        val withSources = config(
            sources = listOf(
                PhotoSource(type = PhotoSourceType.ALBUM, locator = "42", label = "Album"),
            ),
        )
        assertFalse(isUnconfiguredStub(withSources, requestedAt))
        assertFalse(isUnconfiguredStub(config(singlePhotoUri = "content://one"), requestedAt))
    }
}
