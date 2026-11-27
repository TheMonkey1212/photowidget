package dev.photowidget.photos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Minimal permission model.
 *
 * - Individual photos use the system Photo Picker: no permission at all.
 * - Albums need read access to images (Android 13+) or storage (12 and below).
 * - Folders use the Storage Access Framework: no storage permission, only a
 *   persisted URI grant for the chosen folder.
 */
object PhotoPermissions {

    val imagePermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** Permissions to request before browsing albums. */
    fun albumPermissions(): Array<String> = buildList {
        add(imagePermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Lets the user grant access to a subset of photos ("Select photos").
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }.toTypedArray()

    fun hasImageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val full = granted(context, Manifest.permission.READ_MEDIA_IMAGES)
            val partial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            return full || partial
        }
        return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True when the app can see the whole library (not just selected photos). */
    fun hasFullImageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasPartialImageAccess(context: Context): Boolean =
        hasImageAccess(context) && !hasFullImageAccess(context)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Persisted Storage Access Framework grants for folders. */
object DocumentAccess {

    fun takePersistableRead(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun hasPersistedRead(context: Context, uri: Uri): Boolean {
        val target = uri.toString().trimEnd('/')
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri.toString().trimEnd('/') == target
        }
    }

    fun release(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}
