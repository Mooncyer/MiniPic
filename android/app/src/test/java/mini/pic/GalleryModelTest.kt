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
    fun naturalImageOrderSortsNumericNamesByValue() {
        val folder = "folder"
        val input = listOf("11.webp", "1.webp", "10.webp", "2.webp", "09.webp")
            .mapIndexed { index, name -> Pic("$name", folder, index.toLong()) }

        val names = naturalImageOrder(input).map { File(it.path).name }

        assertEquals(listOf("1.webp", "2.webp", "09.webp", "10.webp", "11.webp"), names)
    }

    @Test
    fun naturalFileNameCompareHandlesMixedTextAndNumbers() {
        assertTrue(naturalFileNameCompare("page2.webp", "page10.webp") < 0)
        assertTrue(naturalFileNameCompare("page10.webp", "page2.webp") > 0)
        assertTrue(naturalFileNameCompare("A02.webp", "a2.webp") > 0)
    }
    @Test
    fun mediaStoreImagesGroupByFolderAndKeepModifiedOrder() {
        val folder = "Pictures/Manga"
        val input = listOf(
            Pic("/storage/emulated/0/$folder/02.webp", folder, 10, 2),
            Pic("/storage/emulated/0/$folder/01.webp", folder, 20, 2),
            Pic("/storage/emulated/0/$folder/.hidden.webp", folder, 30, 2)
        )

        val result = groupGalleryImages(input, includeHidden = false)

        assertEquals(listOf("01.webp", "02.webp"), result[folder]?.map { File(it.path).name })
    }
    @Test
    fun crownSensitivityScalesEveryInputToFortyPercent() {
        assertEquals(4f, scaleCrownDelta(10f), 0f)
        assertEquals(-2f, scaleCrownDelta(-5f), 0f)
    }

    @Test
    fun crownSensitivityAcceptsConfiguredValuesAndClampsBounds() {
        assertEquals(0.4f, validCrownSensitivity(0.4f), 0f)
        assertEquals(1.25f, validCrownSensitivity(1.25f), 0f)
        assertEquals(0.1f, validCrownSensitivity(0.1f), 0f)
        assertEquals(2f, validCrownSensitivity(2f), 0f)
        assertEquals(0.1f, validCrownSensitivity(-1f), 0f)
        assertEquals(2f, validCrownSensitivity(3f), 0f)
    }

    @Test
    fun crownSensitivityFallsBackToDefaultForNonFiniteValues() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0.4f, validCrownSensitivity(value), 0f)
        }
    }

    @Test
    fun crownDeltaUsesConfiguredSensitivityForBothDirections() {
        assertEquals(12.5f, scaleCrownDelta(10f, 1.25f), 0f)
        assertEquals(-6.25f, scaleCrownDelta(-5f, 1.25f), 0f)
        assertEquals(0f, scaleCrownDelta(0f, 1.25f), 0f)
    }

    @Test
    fun crownDeltaClampsSensitivityBeforeScaling() {
        assertEquals(1f, scaleCrownDelta(10f, -1f), 0f)
        assertEquals(20f, scaleCrownDelta(10f, 3f), 0f)
        assertEquals(4f, scaleCrownDelta(10f, Float.NaN), 0f)
    }

    @Test
    fun crownDeltaIgnoresNonFiniteInput() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, scaleCrownDelta(value), 0f)
        }
    }

    @Test
    fun crownAccumulatorSupportsConfiguredHundredUnitThreshold() {
        val accumulator = CrownPageAccumulator(threshold = 100f)
        assertEquals(0, accumulator.consume(-99.6f, 0))
        assertEquals(1, accumulator.consume(-0.4f, 1))
        assertEquals(-1, accumulator.consume(100f, 2))
    }

    @Test
    fun crownAccumulatorCombinesFragmentsAndPreservesRemainder() {
        val accumulator = CrownPageAccumulator()
        assertEquals(0, accumulator.consume(3f, 0))
        assertEquals(-1, accumulator.consume(6f, 10))
        assertEquals(0, accumulator.consume(6f, 20))
        assertEquals(-1, accumulator.consume(1f, 30))
    }

    @Test
    fun crownAccumulatorConvertsLargeDeltasToMultiplePages() {
        val accumulator = CrownPageAccumulator()
        assertEquals(-3, accumulator.consume(27f, 0))
        assertEquals(-1, accumulator.consume(5f, 10))
        assertEquals(3, accumulator.consume(-27f, 20))
        assertEquals(1, accumulator.consume(-5f, 30))
    }

    @Test
    fun crownAccumulatorPreservesSlowFragmentsAcrossLongIdleGaps() {
        val accumulator = CrownPageAccumulator()
        assertEquals(0, accumulator.consume(7f, 100))
        assertEquals(-1, accumulator.consume(1f, 351))
        assertEquals(0, accumulator.consume(3f, 1000))
        assertEquals(-1, accumulator.consume(5f, 10000))
    }

    @Test
    fun crownAccumulatorPreservesRemainderAtExactTimeoutBoundary() {
        val accumulator = CrownPageAccumulator()
        assertEquals(0, accumulator.consume(7f, 100))
        assertEquals(-1, accumulator.consume(1f, 350))
    }

    @Test
    fun crownAccumulatorDoesNotThrottleRapidOrSameTimestampPages() {
        val accumulator = CrownPageAccumulator()
        assertEquals(-1, accumulator.consume(8f, 100))
        assertEquals(-1, accumulator.consume(8f, 100))
        assertEquals(-1, accumulator.consume(8f, 101))
        assertEquals(1, accumulator.consume(-8f, 102))
    }

    @Test
    fun crownAccumulatorClearsRemainderOnDirectionChange() {
        val accumulator = CrownPageAccumulator()
        assertEquals(0, accumulator.consume(7f, 0))
        assertEquals(0, accumulator.consume(-1f, 10))
        assertEquals(1, accumulator.consume(-7f, 20))
        assertEquals(0, accumulator.consume(-7f, 30))
        assertEquals(0, accumulator.consume(1f, 40))
        assertEquals(-1, accumulator.consume(7f, 50))
    }

    @Test
    fun crownAccumulatorResetClearsRemainder() {
        val accumulator = CrownPageAccumulator()
        assertEquals(0, accumulator.consume(7f, 1000))
        accumulator.reset()
        assertEquals(0, accumulator.consume(1f, 0))
        assertEquals(-1, accumulator.consume(7f, 10))
        accumulator.reset()
        assertEquals(1, accumulator.consume(-8f, 20))
    }

    @Test
    fun crownAccumulatorPreservesRemainderWhenEventTimeMovesBackward() {
        val accumulator = CrownPageAccumulator()
        assertEquals(0, accumulator.consume(7f, 100))
        assertEquals(-1, accumulator.consume(1f, 99))
        assertEquals(0, accumulator.consume(7f, 100))
        assertEquals(-1, accumulator.consume(1f, 0))
    }

    @Test
    fun crownAccumulatorIgnoresZeroAndNonFiniteEvents() {
        val accumulator = CrownPageAccumulator()
        assertEquals(0, accumulator.consume(7f, 100))
        for (value in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0, accumulator.consume(value, 90))
        }
        assertEquals(-1, accumulator.consume(1f, 110))
    }

    @Test
    fun requestedPageIndexClampsNegativeEmptyAndOversizedRequests() {
        assertEquals(0, requestedPageIndex(-3, 0, 10))
        assertEquals(0, requestedPageIndex(2, -5, 10))
        assertEquals(0, requestedPageIndex(2, 5, 0))
        assertEquals(0, requestedPageIndex(2, 5, -1))
        assertEquals(9, requestedPageIndex(20, 0, 10))
        assertEquals(9, requestedPageIndex(2, 100, 10))
        assertEquals(0, requestedPageIndex(0, 100, 1))
    }

    @Test
    fun requestedPageIndexAvoidsOverflowWithExtremeSteps() {
        assertEquals(9, requestedPageIndex(Int.MAX_VALUE, Int.MAX_VALUE, 10))
        assertEquals(0, requestedPageIndex(Int.MIN_VALUE, Int.MIN_VALUE, 10))
        assertEquals(9, requestedPageIndex(3, Int.MAX_VALUE, 10))
        assertEquals(0, requestedPageIndex(3, Int.MIN_VALUE, 10))
    }

    @Test
    fun requestedPageIndexUsesEachUpdatedIndexForContinuousReverseNavigation() {
        val accumulator = CrownPageAccumulator()
        var index = 5
        index = requestedPageIndex(index, accumulator.consume(24f, 0), 10)
        assertEquals(2, index)
        index = requestedPageIndex(index, accumulator.consume(-8f, 10), 10)
        assertEquals(3, index)
        index = requestedPageIndex(index, accumulator.consume(-16f, 20), 10)
        assertEquals(5, index)
        index = requestedPageIndex(index, accumulator.consume(-80f, 30), 10)
        assertEquals(9, index)
        index = requestedPageIndex(index, accumulator.consume(8f, 40), 10)
        assertEquals(8, index)
    }

    @Test
    fun fittedPreviewSizeFitsLargeLandscapeImagesWithoutDistortion() {
        assertEquals(600 to 450, fittedPreviewSize(4000, 3000, 600, 800))
        assertEquals(400 to 300, fittedPreviewSize(4000, 3000, 800, 300))
    }

    @Test
    fun fittedPreviewSizeFitsLargePortraitImagesWithoutDistortion() {
        assertEquals(450 to 600, fittedPreviewSize(3000, 4000, 800, 600))
        assertEquals(300 to 400, fittedPreviewSize(3000, 4000, 300, 800))
    }

    @Test
    fun fittedPreviewSizeDoesNotUpscaleSmallDecodedImages() {
        val fitted = fittedPreviewSize(100, 60, 600, 800)
        assertEquals(100 to 60, fitted)
        assertEquals(1, bitmapSampleSize(100, 60, fitted.first, fitted.second))
        assertEquals(1 to 1, fittedPreviewSize(1, 1, 600, 800))
    }

    @Test
    fun fittedPreviewSizeKeepsExtremeAspectRatiosAtLeastOnePixel() {
        assertEquals(600 to 1, fittedPreviewSize(Int.MAX_VALUE, 1, 600, 800))
        assertEquals(1 to 800, fittedPreviewSize(1, Int.MAX_VALUE, 600, 800))
    }

    @Test
    fun fittedPreviewSizeSanitizesInvalidSourceAndTargetDimensions() {
        assertEquals(1 to 1, fittedPreviewSize(0, -10, 0, -20))
        assertEquals(1 to 1, fittedPreviewSize(4000, 3000, 0, -20))
        assertEquals(1 to 50, fittedPreviewSize(0, 100, 50, 50))
        assertEquals(50 to 1, fittedPreviewSize(100, -10, 50, 50))
    }

    @Test
    fun bitmapSampleSizeRespectsTargetAndPixelBudget() {
        val sample = bitmapSampleSize(4000, 6000, 378, 567, 2_000_000)
        val pixels = ((4000L + sample - 1) / sample) * ((6000L + sample - 1) / sample)
        assertTrue(sample and (sample - 1) == 0)
        assertTrue(pixels <= 2_000_000L)
    }

    @Test
    fun regionSampleSizeKeepsViewportDetailWithinPixelBudget() {
        val sample = gallerySampleSize(8000, 6000)
        val decodedPixels = ((8000L + sample - 1) / sample) * ((6000L + sample - 1) / sample)

        assertTrue(sample > 1)
        assertTrue(sample and (sample - 1) == 0)
        assertTrue(decodedPixels <= 4_000_000L)
        assertTrue(8000 / sample >= 472)
        assertTrue(6000 / sample >= 620)
    }

    @Test
    fun exifOrientationMapsAllRawCornersIntoExpectedDisplayBounds() {
        val rawWidth = 400f
        val rawHeight = 300f
        for (orientation in 1..8) {
            val displaySize = orientedSize(rawWidth.toInt(), rawHeight.toInt(), orientation)
            val points = listOf(
                0f to 0f, rawWidth to 0f, rawWidth to rawHeight, 0f to rawHeight
            ).map { (x, y) -> mapRawPointToOriented(x, y, rawWidth, rawHeight, orientation) }
            assertEquals(0f, points.minOf { it.first }, 0f)
            assertEquals(0f, points.minOf { it.second }, 0f)
            assertEquals(displaySize.first.toFloat(), points.maxOf { it.first }, 0f)
            assertEquals(displaySize.second.toFloat(), points.maxOf { it.second }, 0f)
        }
    }

    @Test
    fun orientedViewportRectMapsBackToRawImageBounds() {
        val rect = ImageRect(20, 30, 120, 230)
        val displayCorners = setOf(
            rect.left to rect.top,
            rect.right to rect.top,
            rect.right to rect.bottom,
            rect.left to rect.bottom
        )
        for (orientation in 1..8) {
            val raw = orientedRectToRaw(rect, 300, 400, orientation)
            assertTrue(raw.left >= 0 && raw.top >= 0)
            assertTrue(raw.right <= 300 && raw.bottom <= 400)
            assertTrue(raw.width > 0 && raw.height > 0)
            val rawCorners = listOf(
                raw.left to raw.top,
                raw.right to raw.top,
                raw.right to raw.bottom,
                raw.left to raw.bottom
            ).map { (x, y) -> mapRawPointToOriented(x.toFloat(), y.toFloat(), 300f, 400f, orientation) }
                .map { it.first.toInt() to it.second.toInt() }.toSet()
            assertEquals(displayCorners, rawCorners)
        }
    }

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
