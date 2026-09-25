package mini.pic

import java.io.File
import java.util.Locale

const val GALLERY_SIGNATURE = ".MiniPicGallerySignature.png"
val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

data class Pic(val path: String, val folder: String, val modified: Long, val size: Long = 0L)

internal class GalleryScanner(
    private val root: File,
    private val includeHidden: Boolean
) {
    fun scan(): LinkedHashMap<String, MutableList<Pic>> {
        val result = linkedMapOf<String, MutableList<Pic>>()
        walk(root, result, hashSetOf())
        return result
    }

    private fun walk(
        dir: File,
        result: LinkedHashMap<String, MutableList<Pic>>,
        visited: MutableSet<String>
    ) {
        if (!dir.isDirectory || (!includeHidden && dir.name.startsWith("."))) return
        val canonical = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
        if (!visited.add(canonical)) return
        val files = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        val photos = mutableListOf<Pic>()
        var hasSignature = false
        for (file in files) {
            if (file.isDirectory) {
                walk(file, result, visited)
            } else if (file.name == GALLERY_SIGNATURE) {
                hasSignature = true
            } else if (file.extension.lowercase(Locale.US) in SUPPORTED_IMAGE_EXTENSIONS &&
                (includeHidden || !file.name.startsWith("."))) {
                photos += Pic(file.absolutePath, dir.absolutePath, file.lastModified(), file.length())
            }
        }
        if (photos.isNotEmpty() || hasSignature) {
            photos.sortWith(compareByDescending<Pic> { it.modified }.thenBy { it.path })
            result[dir.absolutePath] = photos
        }
    }
}

internal fun sameGallerySnapshot(
    first: Map<String, List<Pic>>,
    second: Map<String, List<Pic>>
): Boolean = first == second

internal fun relativeFolderPath(storageRoot: File, folder: File): String? {
    val rootPath = (try { storageRoot.canonicalPath } catch (_: Exception) { storageRoot.absolutePath })
        .trimEnd(File.separatorChar)
    val folderPath = try { folder.canonicalPath } catch (_: Exception) { folder.absolutePath }
    if (folderPath == rootPath) return ""
    val prefix = rootPath + File.separator
    if (!folderPath.startsWith(prefix)) return null
    return folderPath.removePrefix(prefix).replace(File.separatorChar, '/') + "/"
}

internal fun uniqueFileName(requested: String, exists: (String) -> Boolean): String {
    val dot = requested.lastIndexOf('.')
    val base = if (dot > 0) requested.substring(0, dot) else requested
    val extension = if (dot > 0) requested.substring(dot) else ""
    var candidate = requested
    var suffix = 1
    while (exists(candidate)) {
        candidate = "$base ($suffix)$extension"
        suffix++
    }
    return candidate
}
