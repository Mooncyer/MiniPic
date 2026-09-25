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

internal fun scaleCrownDelta(delta: Float, sensitivity: Float = 0.4f): Float = delta * sensitivity

internal fun gallerySampleSize(sourceWidth: Int, sourceHeight: Int, pixelBudget: Int = 4_000_000): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    var sample = 1
    while (true) {
        val width = (sourceWidth.toLong() + sample - 1L) / sample
        val height = (sourceHeight.toLong() + sample - 1L) / sample
        if (width * height <= pixelBudget) return sample
        val next = sample shl 1
        if (next <= 0) return sample
        sample = next
    }
}

internal fun mapRawPointToOriented(
    x: Float,
    y: Float,
    rawWidth: Float,
    rawHeight: Float,
    orientation: Int
): Pair<Float, Float> = when (orientation) {
    2 -> rawWidth - x to y
    3 -> rawWidth - x to rawHeight - y
    4 -> x to rawHeight - y
    5 -> y to x
    6 -> rawHeight - y to x
    7 -> rawHeight - y to rawWidth - x
    8 -> y to rawWidth - x
    else -> x to y
}

internal data class ImageRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

internal fun orientedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
    if (orientation in 5..8) height to width else width to height

internal fun orientedRectToRaw(
    rect: ImageRect,
    rawWidth: Int,
    rawHeight: Int,
    orientation: Int
): ImageRect = when (orientation) {
    2 -> ImageRect(rawWidth - rect.right, rect.top, rawWidth - rect.left, rect.bottom)
    3 -> ImageRect(rawWidth - rect.right, rawHeight - rect.bottom,
        rawWidth - rect.left, rawHeight - rect.top)
    4 -> ImageRect(rect.left, rawHeight - rect.bottom, rect.right, rawHeight - rect.top)
    5 -> ImageRect(rect.top, rect.left, rect.bottom, rect.right)
    6 -> ImageRect(rect.top, rawHeight - rect.right, rect.bottom, rawHeight - rect.left)
    7 -> ImageRect(rawWidth - rect.bottom, rawHeight - rect.right,
        rawWidth - rect.top, rawHeight - rect.left)
    8 -> ImageRect(rawWidth - rect.bottom, rect.left, rawWidth - rect.top, rect.right)
    else -> rect
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
