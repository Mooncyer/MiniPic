package mini.pic

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ViewerRegressionTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val target get() = ins.targetContext
    private lateinit var activity: MainActivity
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private lateinit var savedPrefs: Map<String, *>
    private lateinit var savedVmPolicy: StrictMode.VmPolicy
    private lateinit var pics: List<Pic>
    private val started = mutableListOf<Activity>()

    @Before fun setUp() {
        prefs = target.getSharedPreferences("settings", 0)
        savedPrefs = HashMap(prefs.all)
        savedVmPolicy = StrictMode.getVmPolicy()
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        prefs.edit().putBoolean("mangaMode", false).putBoolean("crownZoom", false)
            .putFloat("crownSensitivity", .4f).commit()
        dir = File(target.cacheDir, "viewer-regression-${UUID.randomUUID()}")
        assertTrue(dir.mkdirs())
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)
        pics = colors.mapIndexed { i, color ->
            val file = File(dir, "frame-$i.png")
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(color)
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            Pic(file.absolutePath, dir.absolutePath, 0L, file.length())
        }
        listOf("fit-small.gif", "fit-large.gif").forEach { name ->
            ins.context.assets.open(name).use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        activity = ins.startActivitySync(
            Intent(target, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.fromFile(File(pics[0].path))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        started += activity
        main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        await { field("pendingViewerIndex") == null && photo().hasImage() }
        main { activity.intent = Intent(Intent.ACTION_MAIN); show(pics, 0) }
        awaitImage(0, pics)
    }

    @After fun tearDown() {
        started.asReversed().forEach { launched -> main { launched.finish() } }
        ins.waitForIdleSync()
        if (::prefs.isInitialized) {
            val editor = prefs.edit().clear()
            savedPrefs.forEach { (key, value) -> when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            assertTrue(editor.commit())
        }
        if (::savedVmPolicy.isInitialized) StrictMode.setVmPolicy(savedVmPolicy)
        if (::dir.isInitialized) {
            assertEquals(target.cacheDir.canonicalFile, dir.canonicalFile.parentFile)
            assertTrue(dir.name.startsWith("viewer-regression-"))
            dir.deleteRecursively()
        }
    }

    @Test fun rapidStepsUseLatestPendingTarget() {
        main { show(pics, 0); step(1); step(-1) }
        awaitImage(0, pics) // Return to the initial target before its first decode has completed.
        main { show(pics, 0); step(1); step(1); step(-1); step(1) }
        awaitImage(2, pics)
        main { step(1000) }; awaitImage(5, pics)
        main { step(-1000) }; awaitImage(0, pics)
    }

    @Test fun imageTransitionsSlideWithDirectionAndCanBeInterrupted() {
        main { step(1) }
        await { field("viewerTransitionDirection") != 0 }
        assertEquals(1, main { field("viewerTransitionDirection") })
        assertTrue(main { photo().translationY > 0f })
        main { step(1) }
        awaitImage(2, pics)
        await { field("viewerTransitionDirection") == 0 }
        main { step(-1) }
        await { field("viewerTransitionDirection") != 0 }
        assertEquals(-1, main { field("viewerTransitionDirection") })
        assertTrue(main { photo().translationY < 0f })
        awaitImage(1, pics)
        await { field("viewerTransitionDirection") == 0 }
    }

    @Test fun crownUsesHundredThresholdAndSwitchesImmediately() {
        main { dispatchScroll(MotionEvent.AXIS_VSCROLL, -249f) }
        assertEquals(0, main { field("viewerIndex") })
        assertNull(main { field("pendingViewerIndex") })
        main { dispatchScroll(MotionEvent.AXIS_VSCROLL, -2f) }
        awaitImage(1, pics)
        await { field("viewerTransitionDirection") == 0 }
        main {
            dispatchScroll(MotionEvent.AXIS_VSCROLL, -250f)
            assertEquals(2, field("pendingViewerIndex"))
        }
        awaitImage(2, pics)
    }

    @Test fun crownAndTouchUseSameImmediateSwitchPath() {
        main { dispatchScroll(MotionEvent.AXIS_VSCROLL, -250f) }
        awaitImage(1, pics)
        main { swipe() }
        awaitImage(2, pics)
    }

    @Test fun rapidTouchSwipesAndCrownShareLatestTarget() {
        main {
            swipe()
            swipe()
            dispatchScroll(MotionEvent.AXIS_VSCROLL, -250f)
            dispatchScroll(MotionEvent.AXIS_HSCROLL, 250f)
        }
        awaitImage(2, pics)
    }

    @Test fun cancelledSwipeDoesNotNavigate() {
        main {
            val now = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, now, 350f)
            touch(MotionEvent.ACTION_CANCEL, now, 150f)
            touch(MotionEvent.ACTION_UP, now, 150f)
        }
        assertEquals(0, main { field("viewerIndex") })
        assertNull(main { field("pendingViewerIndex") })
    }

    private fun touch(action: Int, time: Long, y: Float) {
        val event = MotionEvent.obtain(time, time + 10, action, activity.resources.displayMetrics.widthPixels / 2f, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun swipe() {
        val now = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, now, 350f)
        touch(MotionEvent.ACTION_MOVE, now, 150f)
        touch(MotionEvent.ACTION_UP, now, 150f)
    }

    @Test fun failedLoadKeepsVisibleImageAndUri() {
        val list = pics + Pic(File(dir, "missing.png").absolutePath, dir.absolutePath, 0L)
        main { show(list, 0) }
        awaitImage(0, list)
        main { step(6) }
        await { field("pendingViewerIndex") == null }
        assertEquals(0, main { field("viewerIndex") })
        assertTrue(main { photo().hasImage() })
        assertEquals("frame-0.png", main { photo().contentDescription })
        assertEquals(Uri.fromFile(File(pics[0].path)), main { invoke("currentUri") })
    }

    @Test fun leavingDuringLoadRejectsLateCommit() {
        main { show(pics, 0); activity.onBackPressed() }
        val executor = main { field("imageIo") as java.util.concurrent.ThreadPoolExecutor }
        executor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        ins.waitForIdleSync()
        await { field("pendingViewerIndex") == null && field("page") == Page.ALBUMS }
        assertEquals(Page.ALBUMS, main { field("page") })
    }

    @Test fun mouseScrollAxesNavigateByDefaultScaledDelta() {
        listOf(MotionEvent.AXIS_VSCROLL, MotionEvent.AXIS_SCROLL, MotionEvent.AXIS_HSCROLL)
            .forEachIndexed { index, axis ->
                assertTrue(main { dispatchScroll(axis, -250f) })
                awaitImage(index + 1, pics)
            }
    }

    @Test fun gifFramesFitAndPlaybackPauseResume() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 28)
        listOf("fit-small.gif" to 0.5f, "fit-large.gif" to (2f / 3f)).forEach { (name, heightRatio) ->
            val list = listOf(Pic(File(dir, name).absolutePath, dir.absolutePath, 0L))
            main { show(list, 0) }
            awaitImage(0, list)
            val view = main { photo() }
            val drawable = main { drawable(view) }
            assertTrue(drawable is Animatable)
            val animation = drawable as Animatable
            assertSame(view, main { drawable.callback })
            assertTrue(drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
            assertEquals(1f / heightRatio, drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight, .05f)
            await { animation.isRunning }
            val first = screenshotCenter()
            val deadline = SystemClock.uptimeMillis() + 3000L
            var changed = false
            while (!changed && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
                changed = screenshotCenter() != first
            }
            assertTrue("$name did not change frames", changed)
            val shot = ins.uiAutomation.takeScreenshot()
            val expectedHeight = (shot.width * heightRatio).toInt()
            val rows = (0 until shot.height).filter { !black(shot.getPixel(shot.width / 2, it)) }
            assertTrue(rows.isNotEmpty())
            assertTrue("GIF fit height ${rows.size}, expected $expectedHeight", kotlin.math.abs(expectedHeight - rows.size) <= 5)
            assertFalse(black(shot.getPixel(1, shot.height / 2)))
            assertFalse(black(shot.getPixel(shot.width - 2, shot.height / 2)))
            assertTrue(black(shot.getPixel(shot.width / 2, (rows.first() - 1).coerceAtLeast(0))))
            shot.recycle()
            main { view.setPlaybackActive(false) }
            assertFalse(main { animation.isRunning })
            main { view.setPlaybackActive(true) }
            await { animation.isRunning }
        }
    }

    @Test fun settingsSliderPersistsAcrossRebuild() {
        val first = main { invoke("buildSettingsPage") as View }
        val slider = findSeekBar(first)
        main { slider.progress = 12 }
        assertEquals(12, slider.progress)
        val saved = prefs.getFloat("crownSensitivity", 0f)
        assertTrue(saved > .1f)
        val rebuilt = main { invoke("buildSettingsPage") as View }
        assertEquals(12, findSeekBar(rebuilt).progress)
    }

    @Test fun albumsTitleFinishesOwnActivity() {
        main { invoke("showAlbums") }
        val title = findView(main { field("stableRoot") as View }) { it.contentDescription == "相册，退出应用" }
        assertNotNull(title)
        main { title!!.performClick() }
        await { activity.isFinishing || activity.isDestroyed }
    }

    private fun dispatchScroll(axis: Int, value: Float): Boolean {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { x = 1f; y = 1f; setAxisValue(axis, value) }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
            arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        return try { activity.dispatchGenericMotionEvent(event) } finally { event.recycle() }
    }

    private fun screenshotCenter(): Int {
        val image = ins.uiAutomation.takeScreenshot()
        return try { image.getPixel(image.width / 2, image.height / 2) } finally { image.recycle() }
    }
    private fun black(pixel: Int) = Color.red(pixel) < 20 && Color.green(pixel) < 20 && Color.blue(pixel) < 20

    private fun show(list: List<Pic>, index: Int) = invoke("showViewer", list, index, Page.ALBUMS)
    private fun step(value: Int) = invoke("requestImageStep", value)
    private fun photo() = field("viewer") as PhotoView
    private fun drawable(view: PhotoView): Drawable = PhotoView::class.java.getDeclaredField("drawable")
        .apply { isAccessible = true }.get(view) as Drawable
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun invoke(name: String, vararg args: Any?): Any? {
        val method = when (name) {
            "showViewer" -> MainActivity::class.java.getDeclaredMethod(name, List::class.java,
                Int::class.javaPrimitiveType, Page::class.java)
            "requestImageStep" -> MainActivity::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            else -> MainActivity::class.java.getDeclaredMethod(name)
        }
        return method.apply { isAccessible = true }.invoke(activity, *args)
    }
    private fun awaitImage(index: Int, list: List<Pic>) = await {
        field("pendingViewerIndex") == null && field("viewerIndex") == index && photo().hasImage() &&
            photo().contentDescription == File(list[index].path).name
    }
    private fun await(condition: () -> Boolean) {
        assertTrue(waitUntil(5000, condition))
    }
    private fun waitUntil(timeout: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            ins.waitForIdleSync()
            if (main(condition)) return true
            SystemClock.sleep(20)
        }
        return false
    }
    private fun <T> main(action: () -> T): T {
        var result: Any? = null
        var failure: Throwable? = null
        ins.runOnMainSync {
            try { result = action() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private fun findSeekBar(view: View): SeekBar = findView(view) { it is SeekBar } as SeekBar
    private fun findView(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount)
            findView(view.getChildAt(i), predicate)?.let { return it }
        return null
    }
}
