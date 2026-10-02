package com.yunyin.music.data.update

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Grants the system installer read access to a downloaded APK.
 *
 * A `FileProvider` rather than sharing the file path direct, because there is no other way: since Android 7
 * a `file://` URI in an intent is refused with `FileUriExposedException`, and it would in any case hand
 * another process a path inside this app's private storage. A content URI scoped to one file is the
 * supported mechanism, and `FLAG_GRANT_READ_URI_PERMISSION` on the intent is what makes the grant
 * temporary and single-purpose.
 *
 * The class exists solely to hold [uriFor]; the provider itself is declared in the manifest against
 * `res/xml/update_file_paths.xml`, which limits it to the `updates/` cache directory — not the whole cache,
 * so a mis-formed URI cannot be used to read anything else this app has written.
 */
class UpdateFileProvider : FileProvider() {

    companion object {
        /**
         * A content URI for [file].
         *
         * The authority is derived from the package name rather than hardcoded, so a debug build, a release
         * build and a renamed fork each address their own provider.
         */
        fun uriFor(context: Context, file: File): Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    }
}
