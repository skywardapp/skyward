package dev.fritze.skyward.ui.settings

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import java.io.File

/**
 * Stands in for the system's document picker so §17.5's SAF round-trip can
 * run without one.
 *
 * `rememberLauncherForActivityResult` resolves its registry from
 * `LocalActivityResultRegistryOwner`, so overriding that composition local is
 * enough to intercept the launch: everything downstream of it -- the real
 * `CreateDocument`/`OpenDocument` contracts, `SyncScreen`'s own launcher
 * callbacks, `SyncUiController`'s `ContentResolver` IO and [SyncViewModel] --
 * is the production code, unchanged. Only the picker Activity is replaced.
 *
 * [lastInput] is recorded rather than discarded because the export contract's
 * input *is* §12.2's suggested filename, and nothing else asserts its shape.
 */
internal class RecordingActivityResultRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {

    override val activityResultRegistry: ActivityResultRegistry get() = this

    /** The Uri the next launch resolves to. Null models the user backing out of the picker. */
    var nextResult: Uri? = null

    var lastInput: Any? = null
        private set

    var launchCount: Int = 0
        private set

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        lastInput = input
        launchCount++
        // Dispatched inline: both contracts here parse an Intent's data into a
        // Uri?, so handing the result straight back is exactly what returning
        // from the picker does, minus the Activity.
        @Suppress("UNCHECKED_CAST")
        dispatchResult(requestCode, nextResult as O)
    }
}

/**
 * A genuinely writable document the production code can round-trip through.
 *
 * `MediaStore.Downloads` gives a real `content://` Uri and therefore a real
 * provider hop, which is the half of the SAF path worth exercising; below API
 * 29 it does not exist, so this falls back to a `file://` Uri in the app's own
 * cache. Both are fine for the code under test: `SyncUiController` only ever
 * calls `ContentResolver.openInputStream`/`openOutputStream`, and those handle
 * either scheme.
 */
internal fun Context.createDocumentUri(displayName: String): Uri =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        requireNotNull(contentResolver.insert(collection, values)) { "MediaStore refused to create $displayName" }
    } else {
        Uri.fromFile(File(cacheDir, displayName).apply { createNewFile() })
    }

/** Removes whatever [createDocumentUri] made; a MediaStore row outlives the process that inserted it. */
internal fun Context.deleteDocumentUri(uri: Uri) {
    runCatching {
        if (uri.scheme == "file") uri.path?.let { File(it).delete() } else contentResolver.delete(uri, null, null)
    }
}

internal fun Context.readDocument(uri: Uri): String =
    contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

internal fun Context.writeDocument(uri: Uri, text: String) {
    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
}
