package dev.photowidget.model

/**
 * Resolves a widget's sources into displayable photos.
 * Implemented by the photo module, faked in tests.
 */
fun interface PhotoSourceProvider {
    fun resolve(sources: List<PhotoSource>): ResolvedPhotos
}
