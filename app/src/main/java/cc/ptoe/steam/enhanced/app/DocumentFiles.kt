package cc.ptoe.steam.enhanced.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import cc.ptoe.steam.enhanced.core.SdaManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SelectedMaFiles(
    val files: Map<String, String>,
    val manifest: SdaManifest?,
)

internal suspend fun readMaFiles(resolver: ContentResolver, uris: List<Uri>): SelectedMaFiles =
    withContext(Dispatchers.IO) {
        require(uris.size <= 512) { "Select at most 512 files at a time." }
        val files = linkedMapOf<String, String>()
        var total = 0
        for (uri in uris) {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: uri.lastPathSegment.orEmpty()
            if (!name.endsWith(".maFile", ignoreCase = true) && !name.equals("manifest.json", ignoreCase = true)) {
                continue
            }
            require(files.keys.none { it.equals(name, ignoreCase = true) }) { "Select only one file with each name." }
            val bytes = checkNotNull(resolver.openInputStream(uri)) { "Could not open the selected file." }.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    total += size
                    require(total <= 16 * 1024 * 1024 && output.size() + size <= 1024 * 1024) {
                        "The selected files are too large."
                    }
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            files[name] = bytes.decodeToString()
        }
        val manifestKey = files.keys.firstOrNull { it.equals("manifest.json", ignoreCase = true) }
        val manifest = manifestKey?.let { SdaManifest.parse(checkNotNull(files.remove(it))) }
        require(files.isNotEmpty()) { "Select at least one .maFile." }
        SelectedMaFiles(files, manifest)
    }

internal suspend fun writeDocument(resolver: ContentResolver, uri: Uri, text: String) =
    withContext(Dispatchers.IO) {
        checkNotNull(resolver.openOutputStream(uri, "wt")) { "Could not open the export destination." }.use {
            it.write(text.encodeToByteArray())
        }
    }
