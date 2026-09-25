package mini.pic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun scannerGroupsImagesAndKeepsSignatureOnlyAlbums() {
        val root = temporaryFolder.newFolder("storage")
        val album = File(root, "Pictures/Trips").apply { mkdirs() }
        File(album, "one.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(album, "ignored.txt").writeText("not an image")
        val emptyAlbum = File(root, "Pictures/Empty").apply { mkdirs() }
        File(emptyAlbum, GALLERY_SIGNATURE).writeBytes(byteArrayOf(1))
        val hidden = File(root, ".private").apply { mkdirs() }
        File(hidden, "secret.png").writeBytes(byteArrayOf(1))

        val result = GalleryScanner(root, includeHidden = false).scan()

        assertEquals(listOf(File(album, "one.jpg").absolutePath), result[album.absolutePath]?.map { it.path })
        assertTrue(result[emptyAlbum.absolutePath]?.isEmpty() == true)
        assertFalse(result.containsKey(hidden.absolutePath))
    }

    @Test
    fun scannerCanIncludeHiddenFoldersAndImages() {
        val root = temporaryFolder.newFolder("storage")
        val hidden = File(root, ".private").apply { mkdirs() }
        File(hidden, ".secret.jpg").writeBytes(byteArrayOf(1))

        val result = GalleryScanner(root, includeHidden = true).scan()

        assertEquals(1, result[hidden.absolutePath]?.size)
    }

    @Test
    fun snapshotComparisonDetectsSameCountReplacementAndMetadataChange() {
        val old = mapOf("album" to listOf(Pic("old.jpg", "album", 10, 100)))
        val replacement = mapOf("album" to listOf(Pic("new.jpg", "album", 10, 100)))
        val changedSize = mapOf("album" to listOf(Pic("old.jpg", "album", 10, 101)))

        assertFalse(sameGallerySnapshot(old, replacement))
        assertFalse(sameGallerySnapshot(old, changedSize))
        assertTrue(sameGallerySnapshot(old, old.toMutableMap()))
    }

    @Test
    fun relativeFolderPathPreservesNestedDirectoriesAndRejectsOutsidePaths() {
        val root = temporaryFolder.newFolder("storage")
        val nested = File(root, "Pictures/Trips/2025").apply { mkdirs() }
        val outside = temporaryFolder.newFolder("outside")

        assertEquals("Pictures/Trips/2025/", relativeFolderPath(root, nested))
        assertEquals("", relativeFolderPath(root, root))
        assertNull(relativeFolderPath(root, outside))
    }

    @Test
    fun uniqueFileNameAddsSuffixBeforeExtension() {
        val existing = setOf("photo.jpg", "photo (1).jpg")

        assertEquals("photo (2).jpg", uniqueFileName("photo.jpg") { it in existing })
        assertEquals("no-extension (1)", uniqueFileName("no-extension") { it == "no-extension" })
        assertNotEquals("photo.jpg", uniqueFileName("photo.jpg") { it in existing })
    }
}
