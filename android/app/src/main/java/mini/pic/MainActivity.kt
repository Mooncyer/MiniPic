package mini.pic

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.IOException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.*

private const val SIGNATURE = ".MiniPicGallerySignature.png"
private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
data class Pic(val path: String, val folder: String, val modified: Long)

enum class Page { ALBUMS, SETTINGS, VIEWER, ACTIONS, PICK_ALBUM, SELECT_ALBUMS }

class MainActivity : Activity() {
    // ── stable page container ──────────────────────────────────────────
    private lateinit var stableRoot: GestureFrameLayout

    private val io = Executors.newSingleThreadExecutor()
    // Album cover bitmap cache: path → Bitmap (soft refs allow GC to reclaim)
    private val coverCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 24
    }
    private val albums = linkedMapOf<String, MutableList<Pic>>()
    private var page = Page.ALBUMS
    private var previousPage = Page.ALBUMS
    private var albumGrid: ViewGroup? = null
    private var albumScroll: ScrollView? = null
    private var settingsScroll: ScrollView? = null
    private var pickerScroll: ScrollView? = null
    private var selectScroll: ScrollView? = null
    private var viewer: PhotoView? = null
    private var viewerPics = listOf<Pic>()
    private var viewerIndex = 0
    private var rotation = 0f
    private var scanning = false
    private var animating = false
    private var pendingFileOp: String? = null
    private val coverPrefs by lazy { getSharedPreferences("covers", MODE_PRIVATE) }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val isExternal get() = intent?.action == Intent.ACTION_VIEW && intent?.data != null
    private val dark get() = prefs.getBoolean("dark", true)
    private val bg get() = if (dark) Color.BLACK else Color.rgb(247, 249, 249)
    private val surface get() = if (dark) Color.rgb(28, 31, 32) else Color.WHITE
    private val text get() = if (dark) Color.rgb(232, 234, 234) else Color.rgb(25, 28, 29)
    private val muted get() = if (dark) Color.rgb(174, 178, 178) else Color.rgb(88, 94, 95)
    private val accent get() = if (dark) Color.rgb(185, 243, 236) else Color.rgb(0, 105, 98)
    private val animDuration = 220L

    // ══════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        stableRoot = GestureFrameLayout(this).apply {
            setBackgroundColor(bg)
            isFocusableInTouchMode = true
            requestFocus()
            swipeObserver = { dx, fromLeft -> onEdgeSwipe(dx, fromLeft) }
            touchObserver = { ev -> observeGestures(ev) }
        }
        setContentView(stableRoot)

        if (isExternal) {
            showExternal(intent.data!!)
            return
        }
        showAlbums()
        loadCache()
        ensurePermissionAndScan()
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        if (newIntent?.action == Intent.ACTION_VIEW && newIntent.data != null) {
            intent = newIntent
            showExternal(newIntent.data!!)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  PERMISSIONS & SCAN
    // ══════════════════════════════════════════════════════════════════

    private fun ensurePermissionAndScan() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 9)
        } else scan()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(code, p, g)
        if (code == 9 && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) scan()
    }

    // ══════════════════════════════════════════════════════════════════
    //  WRITE ACCESS  (Android 11 MANAGE_EXTERNAL_STORAGE)
    // ══════════════════════════════════════════════════════════════════

    private fun hasWriteAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager()
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureWriteAccess(onGranted: () -> Unit) {
        if (hasWriteAccess()) {
            onGranted()
        } else if (Build.VERSION.SDK_INT >= 30) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要文件访问权限")
                .setMessage("为了复制、移动和删除图片，请授予「所有文件访问权限」")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:mini.pic")))
                    } catch (_: Exception) {
                        toast("请手动在 设置-应用管理-图库 中授予存储权限")
                    }
                }.show()
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 10)
        }
    }

    /** Background scan: show current state first, then update in-place when done. */
    private fun scan() {
        if (scanning) return
        scanning = true
        io.execute {
            val found = linkedMapOf<String, MutableList<Pic>>()
            val base = File("/storage/emulated/0")
            if (base.exists()) walk(base, found)
            runOnUiThread {
                scanning = false
                val sameAsBefore =
                    albums.size == found.size && albums.all { (k, v) -> found[k]?.size == v.size }
                albums.clear(); albums.putAll(found)
                val allPaths = found.values.flatten().map { it.path }.toSet()
                coverPrefs.edit().apply {
                    for (folder in albums.keys) {
                        val saved = coverPrefs.getString(folder, null)
                        if (saved != null && saved !in allPaths) remove(folder)
                    }
                    apply()
                }
                saveCache()
                if (sameAsBefore && page == Page.ALBUMS) {
                    // Data unchanged — just remove hint if present
                    albumGrid?.findViewWithTag<View>("scanning_hint")?.let { albumGrid?.removeView(it) }
                } else if (page == Page.ALBUMS) {
                    replaceAlbumsGrid()
                    albumGrid?.findViewWithTag<View>("scanning_hint")?.let { albumGrid?.removeView(it) }
                } else if (page == Page.PICK_ALBUM) {
                    rebuildAlbumPicker()
                } else if (page == Page.SELECT_ALBUMS) {
                    rebuildAlbumSelection()
                }
            }
        }
    }

    private fun walk(dir: File, out: LinkedHashMap<String, MutableList<Pic>>) {
        val hidden = prefs.getBoolean("hidden", false)
        if (!dir.isDirectory || (!hidden && dir.name.startsWith("."))) return
        val files = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        val list = mutableListOf<Pic>()
        var signature = false
        for (file in files) {
            if (file.isDirectory) walk(file, out)
            else if (file.name == SIGNATURE) signature = true
            else if (file.extension.lowercase(Locale.US) in EXTENSIONS && (hidden || !file.name.startsWith(".")))
                list += Pic(file.absolutePath, dir.absolutePath, file.lastModified())
        }
        if (list.isNotEmpty() || signature) {
            list.sortByDescending { it.modified }
            out[dir.absolutePath] = list
        }
    }

    private val CACHE_FILE = "album_cache.json"

    private fun saveCache() {
        io.execute {
            try {
                val json = JSONArray()
                for ((folder, photos) in albums) {
                    val album = JSONObject()
                    album.put("folder", folder)
                    val pics = JSONArray()
                    for (pic in photos) {
                        val p = JSONObject()
                        p.put("path", pic.path)
                        p.put("folder", pic.folder)
                        p.put("modified", pic.modified)
                        pics.put(p)
                    }
                    album.put("photos", pics)
                    json.put(album)
                }
                openFileOutput(CACHE_FILE, MODE_PRIVATE).use { it.write(json.toString().toByteArray()) }
            } catch (_: Exception) {}
        }
    }

    private fun loadCache() {
        try {
            val text = openFileInput(CACHE_FILE).bufferedReader().readText()
            val json = JSONArray(text)
            albums.clear()
            for (i in 0 until json.length()) {
                val album = json.getJSONObject(i)
                val folder = album.getString("folder")
                val photos = mutableListOf<Pic>()
                val pics = album.getJSONArray("photos")
                for (j in 0 until pics.length()) {
                    val p = pics.getJSONObject(j)
                    photos += Pic(p.getString("path"), p.getString("folder"), p.getLong("modified"))
                }
                albums[folder] = photos
            }
            // Update the grid with cached data
            if (page == Page.ALBUMS) replaceAlbumsGrid()
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════
    //  EDGE SWIPE  (GestureFrameLayout → here)
    // ══════════════════════════════════════════════════════════════════

    /**
     *  user terminology:
     *    "左滑" = finger starts from LEFT edge, moves RIGHT → Android Back (global)
     *    "右滑" = finger starts from RIGHT edge, moves LEFT → enter sub-page with slide
     */
    private fun onEdgeSwipe(dx: Float, fromLeft: Boolean): Boolean {
        if (animating) return true
        val minSwipe = dp(42)

        // ── left edge → right → BACK ──────────────────────────────
        if (fromLeft && dx > minSwipe) {
            // Skip animation when back would exit the app (avoids flash)
            val willFinish = page == Page.ALBUMS || (page == Page.VIEWER && isExternal)
            if (willFinish) {
                onBackPressed()
            } else {
                animating = true
                animateSlideOutToRight(dx) {
                    onBackPressed()
                    animating = false
                }
            }
            return true
        }

        // ── right edge → left → ENTER ─────────────────────────────
        if (!fromLeft && dx < -minSwipe) {
            animating = true
            when (page) {
                Page.ALBUMS -> animateSlideToSettings()
                Page.VIEWER -> animateSlideToActions()
                else -> { animating = false; return false }
            }
            animating = false
            return true
        }
        return false
    }

    // ══════════════════════════════════════════════════════════════════
    //  TOUCH OBSERVER  (for vertical swipes in viewer only)
    // ══════════════════════════════════════════════════════════════════

    private var touchX = 0f
    private var touchY = 0f

    private fun observeGestures(e: MotionEvent) {
        if (page != Page.VIEWER) return
        if (viewer?.isAtFitScale() != true) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchX = e.x; touchY = e.y }
            MotionEvent.ACTION_UP -> {
                val dy = e.y - touchY
                val dx = e.x - touchX
                if (abs(dy) > dp(42) && abs(dy) > abs(dx) && !isExternal) {
                    val next = if (dy < 0) viewerIndex + 1 else viewerIndex - 1
                    if (next in viewerPics.indices) {
                        viewerIndex = next; rotation = 0f
                        val dir = if (dy < 0) -1f else 1f  // -1 = next (up), 1 = prev (down)
                        slideToImage(dir)
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  PAGE NAVIGATION
    // ══════════════════════════════════════════════════════════════════

    override fun onBackPressed() {
        when (page) {
            Page.VIEWER -> leaveViewer()
            Page.ACTIONS -> replaceContent(buildViewerPage())
            Page.PICK_ALBUM -> {
                pendingFileOp = null
                replaceContent(buildActionsPage())
            }
            Page.SELECT_ALBUMS -> replaceContent(buildSettingsPage())
            Page.SETTINGS -> replaceContent(buildAlbumsPage())
            else -> super.onBackPressed()
        }
        // page is updated inside the methods called above
    }

    private fun leaveViewer() {
        if (isExternal) finish()
        else if (previousPage == Page.ALBUMS) replaceContent(buildAlbumsPage())
        else replaceContent(buildSettingsPage())
    }

    // ── helpers that also set `page` ──────────────────────────────────

    private fun showAlbums() {
        page = Page.ALBUMS
        updateSystemUi(false)
        replaceContent(buildAlbumsPage())
    }

    private fun replaceContent(view: View) {
        stableRoot.removeAllViews()
        stableRoot.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun updateSystemUi(fullscreen: Boolean) {
        window.decorView.systemUiVisibility = if (fullscreen)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        else 0
    }

    // ══════════════════════════════════════════════════════════════════
    //  ANIMATED TRANSITIONS
    // ══════════════════════════════════════════════════════════════════

    /** Right-edge → left swipe → new page slides in from the right. */
    private fun animateSlideToSettings() {
        page = Page.SETTINGS
        updateSystemUi(false)
        // Build new page
        val newView = buildSettingsPage()
        animateSlideInFromX(newView)
    }

    private fun animateSlideToActions() {
        page = Page.ACTIONS
        updateSystemUi(false)
        val newView = buildActionsPage()
        animateSlideInFromX(newView)
    }

    /** Animate a view sliding in from the right (over a solid bg). */
    private fun animateSlideInFromX(newView: View) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        stableRoot.removeAllViews()
        newView.translationX = w
        newView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        stableRoot.addView(newView, FrameLayout.LayoutParams(-1, -1))
        newView.animate()
            .translationX(0f)
            .setDuration(animDuration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                newView.setLayerType(View.LAYER_TYPE_NONE, null)
                animating = false
            }
    }

    /** Left-edge → right (back): current page slides out to the right. */
    private fun animateSlideOutToRight(@Suppress("UNUSED_PARAMETER") _dx: Float, onDone: () -> Unit) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val current = stableRoot.getChildAt(0) ?: return
        current.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        current.animate()
            .translationX(w)
            .setDuration((animDuration * 0.7f).toLong())
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                current.setLayerType(View.LAYER_TYPE_NONE, null)
                onDone()
            }
    }

    /**
     * Vertical slide transition for viewer image switching.
     * @param direction  -1 = next (slides up), 1 = previous (slides down)
     */
    private fun slideToImage(direction: Float) {
        if (animating) return
        animating = true
        val oldView = stableRoot.getChildAt(0)
        val h = resources.displayMetrics.heightPixels.toFloat()

        // Build new viewer
        page = Page.VIEWER
        updateSystemUi(true)
        val newPhoto = PhotoView(this).apply { setBackgroundColor(Color.BLACK) }
        viewer = newPhoto
        val newHost = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        newHost.addView(newPhoto, FrameLayout.LayoutParams(-1, -1))

        // Pre-load bitmap, then animate
        io.execute {
            val drawable = decodeDrawable(currentUri())
            runOnUiThread {
                if (drawable == null) { animating = false; return@runOnUiThread }
                newPhoto.setDrawable(drawable)
                newPhoto.rotation = rotation
                (drawable as? Animatable)?.start()

                // New starts from the opposite side and slides into view
                newHost.translationY = -direction * h
                stableRoot.addView(newHost, FrameLayout.LayoutParams(-1, -1))

                // Animate old out, new in
                newHost.setLayerType(View.LAYER_TYPE_HARDWARE, null)

                newHost.animate()
                    .translationY(0f)
                    .setDuration(animDuration)
                    .setInterpolator(DecelerateInterpolator())

                oldView?.animate()
                    ?.translationY(direction * h)
                    ?.setDuration(animDuration)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.withEndAction {
                        stableRoot.removeView(oldView)
                        newHost.setLayerType(View.LAYER_TYPE_NONE, null)
                        animating = false
                    }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  BUILD PAGE VIEWS
    // ══════════════════════════════════════════════════════════════════

    private fun buildAlbumsPage(): View {
        page = Page.ALBUMS
        previousPage = Page.ALBUMS
        albumScroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(2), dp(7), dp(10))
        }
        column.addView(label("相册", 20f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(33)))

        val grid = GridLayout(this).apply { columnCount = 2; alignmentMode = GridLayout.ALIGN_BOUNDS }
        albumGrid = grid
        populateAlbumGrid(grid)
        column.addView(grid)

        if (albums.isEmpty()) {
            val hint = label(if (scanning) "正在扫描…" else "暂无相册", 14f).apply {
                gravity = Gravity.CENTER; tag = "scanning_hint"
            }
            grid.addView(hint, GridLayout.LayoutParams().apply {
                width = dp(175); height = dp(140)
                columnSpec = GridLayout.spec(0, 2)
            })
        }
        albumScroll!!.addView(column)
        return albumScroll!!
    }

    private fun populateAlbumGrid(grid: ViewGroup) {
        val showFolders = homeSelectedFolders()
        for ((folder, photos) in albums) {
            if (folder in showFolders) grid.addView(albumCard(folder, photos), gridParams())
        }
    }

    /** In-place grid update after background scan completes. */
    private fun replaceAlbumsGrid() {
        val grid = albumGrid ?: return
        grid.removeAllViews()
        populateAlbumGrid(grid)
    }

    private fun buildSettingsPage(): View {
        page = Page.SETTINGS
        settingsScroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(12)) }
        column.addView(label("设置", 20f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(34)))
        column.addView(settingCard("主页相册", "选择展示在主页的相册") { showAlbumSelection() })
        column.addView(settingCard("新建相册", "在 Pictures 中创建") { newAlbum() })
        column.addView(switchCard("表冠滚动缩放", prefs.getBoolean("crownZoom", true)) { prefs.edit().putBoolean("crownZoom", it).apply() })
        column.addView(switchCard("扫描隐藏文件", prefs.getBoolean("hidden", false)) { prefs.edit().putBoolean("hidden", it).apply(); scan() })
        column.addView(themeCard())
        column.addView(settingCard("重新扫描", "更新本地图片列表") { scan(); toast("正在扫描…") })
        settingsScroll!!.addView(column)
        return settingsScroll!!
    }

    private fun buildActionsPage(): View {
        page = Page.ACTIONS
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        val actions = mutableListOf("复制", "移动", "删除", "旋转")
        if (!isExternal) actions += "设为封面"
        actions.forEach { name ->
            column.addView(actionPill(name) { runAction(name) },
                LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        return column
    }

    private fun buildViewerPage(): View {
        page = Page.VIEWER
        updateSystemUi(true)
        val host = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        viewer = PhotoView(this).apply { setBackgroundColor(Color.BLACK) }
        host.addView(viewer, FrameLayout.LayoutParams(-1, -1))
        io.execute {
            val drawable = decodeDrawable(currentUri())
            runOnUiThread {
                viewer?.setDrawable(drawable); viewer?.rotation = rotation
                (drawable as? Animatable)?.start()
            }
        }
        return host
    }

    // ══════════════════════════════════════════════════════════════════
    //  VIEWER / EXTERNAL
    // ══════════════════════════════════════════════════════════════════

    private fun showExternal(uri: Uri) {
        previousPage = Page.ALBUMS

        // Try to resolve content:// URI to a real file path
        val realPath: String? = try {
            when {
                // content://media/external/images/media/N → query _data
                uri.authority == "media" -> {
                    val cursor = contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)
                    cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
                }
                // content://com.android.externalstorage.documents/... → DocumentsContract
                DocumentsContract.isDocumentUri(this, uri) -> {
                    val docId = DocumentsContract.getDocumentId(uri)
                    // docId = "primary:Pictures/photo.jpg" → extract path
                    val parts = docId.split(":", limit = 2)
                    if (parts.size == 2 && parts[0] == "primary")
                        "/storage/emulated/0/${parts[1]}"
                    else null
                }
                // file:///storage/... → already a path
                uri.scheme == "file" -> uri.path
                else -> null
            }
        } catch (_: Exception) { null }

        if (realPath != null && File(realPath).exists()) {
            // Use real path directly — operations via MediaStore will find it
            val folder = File(realPath).parentFile?.absolutePath ?: ""
            viewerPics = listOf(Pic(realPath, folder, File(realPath).lastModified()))
        } else {
            // Fallback: copy to cache so operations at least work on the copy
            val fileName = try {
                val cursor = contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
                cursor?.use { if (it.moveToFirst()) it.getString(0) ?: "image.jpg" else "image.jpg" } ?: "image.jpg"
            } catch (_: Exception) { uri.lastPathSegment ?: "image.jpg" }
            val cacheDir = File(cacheDir, "external")
            cacheDir.mkdirs()
            val cached = File(cacheDir, fileName)
            if (!cached.exists()) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        cached.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {}
            }
            viewerPics = listOf(Pic(cached.absolutePath, cached.parentFile?.absolutePath ?: "", cached.lastModified()))
        }

        viewerIndex = 0; rotation = 0f
        replaceContent(buildViewerPage())
    }

    private fun showViewer(list: List<Pic>, index: Int, from: Page) {
        previousPage = from; viewerPics = list; viewerIndex = index; rotation = 0f
        replaceContent(buildViewerPage())
    }

    private fun showPhotoView() {
        previousPage = page
        val building = buildViewerPage()
        replaceContent(building)
    }

    // ══════════════════════════════════════════════════════════════════
    //  ALBUM PICKER (for copy / move)
    // ══════════════════════════════════════════════════════════════════

    private fun showAlbumPicker() {
        if (albums.isEmpty()) {
            toast("正在扫描相册…")
            scan()
            // Show a placeholder, scan will trigger rebuild
        }
        replaceContent(buildAlbumPickerPage())
    }

    private fun rebuildAlbumPicker() {
        if (page != Page.PICK_ALBUM) return
        replaceContent(buildAlbumPickerPage())
    }

    private fun rebuildAlbumSelection() {
        if (page != Page.SELECT_ALBUMS) return
        replaceContent(buildAlbumSelectionPage())
    }

    private fun buildAlbumPickerPage(): View {
        page = Page.PICK_ALBUM
        val currentFolder = viewerPics.getOrNull(viewerIndex)?.folder ?: ""

        pickerScroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(12))
        }
        column.addView(label("选择目标相册", 20f, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, dp(34)))

        if (albums.isEmpty()) {
            column.addView(label("没有可选相册，请先创建", 13f).apply { gravity = Gravity.CENTER })
        } else {
            // Show each album as a selectable card, excluding current if moving
            for ((folder, photos) in albums) {
                if (pendingFileOp == "移动" && folder == currentFolder) continue
                column.addView(pickerAlbumCard(folder, photos, currentFolder))
            }
        }

        pickerScroll!!.addView(column)
        return pickerScroll!!
    }

    private fun pickerAlbumCard(folder: String, photos: List<Pic>, @Suppress("UNUSED_PARAMETER") currentFolder: String): View {
        val c = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); setCardBackgroundColor(surface); cardElevation = 0f
            setOnClickListener { executeFileOp(folder) }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(if (dark) Color.rgb(44, 48, 49) else Color.rgb(225, 231, 231))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(0, 0, dp(10), 0) }
            if (photos.isNotEmpty()) io.execute {
                val b = decodeSampled(photos[0].path, 72, 72)
                runOnUiThread { if (tag == folder) setImageBitmap(b) }
            }
            tag = folder
        }
        row.addView(cover)
        row.addView(label(File(folder).name.ifBlank { "存储" }, 14f, true), LinearLayout.LayoutParams(0, -1, 1f))
        c.addView(row)
        return c.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(3), 0, dp(3)) } }
    }

    private fun executeFileOp(targetFolder: String) {
        val op = pendingFileOp ?: return
        val pic = viewerPics.getOrNull(viewerIndex) ?: return
        pendingFileOp = null
        val srcPath = pic.path

        io.execute {
            val result = try {
                val srcName = if (srcPath.startsWith("content:")) {
                    try {
                        val cursor = contentResolver.query(Uri.parse(srcPath), arrayOf("_display_name"), null, null, null)
                        cursor?.use { if (it.moveToFirst()) it.getString(0) ?: "image.jpg" else "image.jpg" } ?: "image.jpg"
                    } catch (_: Exception) { "image.jpg" }
                } else File(srcPath).name

                when (op) {
                    "复制" -> {
                        if (!mediaStoreCopy(targetFolder, srcPath, srcName))
                            throw IOException("MediaStore 写入失败")
                        "已复制到 ${File(targetFolder).name}"
                    }
                    "移动" -> {
                        if (!mediaStoreCopy(targetFolder, srcPath, srcName))
                            throw IOException("MediaStore 写入失败")
                        val deleted = mediaStoreDelete(srcPath)
                        if (!deleted && srcPath.startsWith("content:")) {
                            // Copied successfully but can't delete source (no write URI permission)
                            "无法删除原始图片（无写入权限），但已复制到${File(targetFolder).name}"
                        } else if (!deleted) {
                            throw IOException("删除源文件失败")
                        } else {
                            "已移动到 ${File(targetFolder).name}"
                        }
                    }
                    else -> throw IOException("未知操作")
                }
            } catch (e: Exception) {
                "${op}失败: ${e.localizedMessage ?: "未知错误"}"
            }
            runOnUiThread {
                toast(result)
                if (!result.contains("失败")) scan()
                showPhotoView()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  MediaStore helpers  (bypasses ColorOS UID file isolation)
    // ══════════════════════════════════════════════════════════════════

    private fun pathToRelative(p: String): String? {
        val base = Environment.getExternalStorageDirectory().absolutePath + "/"
        return if (p.startsWith(base)) p.removePrefix(base) else null
    }

    private fun fileToMediaUri(path: String): Uri? {
        try {
            // Try 1: DATA column (deprecated but still available on most devices)
            try {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val cursor = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, MediaStore.Images.Media.DATA + "=?", arrayOf(path), null)
                cursor?.use {
                    if (it.moveToFirst())
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0))
                }
            } catch (_: Exception) {}

            // Try 2: DISPLAY_NAME + RELATIVE_PATH
            val relativePath = pathToRelative(path)?.let {
                val slash = it.lastIndexOf('/')
                if (slash >= 0) Pair(it.substring(0, slash), it.substring(slash + 1))
                else null
            }
            if (relativePath != null) {
                val (dir, name) = relativePath
                try {
                    val projection = arrayOf(MediaStore.Images.Media._ID)
                    val sel = "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?"
                    val cursor = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection, sel, arrayOf(name, dir), null)
                    cursor?.use {
                        if (it.moveToFirst())
                            return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0))
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    private fun mimeFromName(name: String) = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        else -> "image/jpeg"
    }

    /** Copy image bytes via MediaStore insert (bypasses ColorOS UID isolation). */
    private fun mediaStoreCopy(albumFolder: String, srcPath: String, fileName: String): Boolean {
        val folderName = File(albumFolder).name
        // Read source bytes
        val data = try {
            if (srcPath.startsWith("content:"))
                contentResolver.openInputStream(Uri.parse(srcPath))?.use { it.readBytes() }
            else File(srcPath).readBytes()
        } catch (_: Exception) { null } ?: return false

        // Approach: MediaStore insert with RELATIVE_PATH + IS_PENDING
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeFromName(fileName))
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folderName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(data) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                return true
            }
        } catch (_: Exception) {}

        // Fallback: direct file write (works on devices without UID isolation)
        try {
            val destDir = File(albumFolder).apply { mkdirs() }
            val dest = resolveDestName(destDir, fileName)
            dest.writeBytes(data)
            return true
        } catch (_: Exception) {}
        return false
    }

    private fun resolveDestName(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            val base = name.substringBeforeLast(".")
            val ext = name.substringAfterLast(".", "")
            file = File(dir, "${base}($n).$ext")
            n++
        }
        return file
    }

    private fun contentUriToMediaId(contentUri: String): Long? {
        // content://media/external/images/media/123 → 123
        try {
            val last = Uri.parse(contentUri).lastPathSegment
            return last?.toLongOrNull()
        } catch (_: Exception) { return null }
    }

    /** Delete via MediaStore content URI (bypasses ColorOS UID isolation). */
    private fun mediaStoreDelete(path: String): Boolean {
        // 1) fileToMediaUri → contentResolver.delete (finds by DATA or DISPLAY_NAME+RELATIVE_PATH)
        val mediaUri = fileToMediaUri(path)
        if (mediaUri != null) {
            try { if (contentResolver.delete(mediaUri, null, null) > 0) return true } catch (_: Exception) {}
        }
        // 2) If the path itself is a content:// URI, try deleting it directly
        if (path.startsWith("content:")) {
            val parsed = Uri.parse(path)
            // 2a) content://media/external/images/media/N → rebuild proper URI
            if (parsed.authority == "media") {
                contentUriToMediaId(path)?.let { id ->
                    try {
                        val msUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        if (contentResolver.delete(msUri, null, null) > 0) return true
                    } catch (_: Exception) {}
                }
            }
            // 2b) DocumentsContract content URIs
            try {
                if (DocumentsContract.isDocumentUri(this, parsed)) {
                    val deleteUri = DocumentsContract.buildDocumentUri(parsed.authority, DocumentsContract.getDocumentId(parsed))
                    if (contentResolver.delete(deleteUri, null, null) > 0) return true
                }
            } catch (_: Exception) {}
            // 2c) Try taking write permission then direct delete
            try {
                try { contentResolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                if (contentResolver.delete(parsed, null, null) > 0) return true
            } catch (_: Exception) {}
        }
        // 3) Last resort: direct file delete + truncate
        try { if (File(path).delete()) return true } catch (_: Exception) {}
        try {
            java.io.RandomAccessFile(path, "rw").use { it.setLength(0) }
            if (File(path).delete()) return true
        } catch (_: Exception) {}
        return false
    }

    // ══════════════════════════════════════════════════════════════════
    //  HOME ALBUM SELECTION
    // ══════════════════════════════════════════════════════════════════

    private val HOME_SEL_KEY = "home_sel"

    /** Folders to show on home page (JSON in prefs). */
    private fun homeSelectedFolders(): Set<String> {
        val all = prefs.all
        val raw = try { all[HOME_SEL_KEY] as? String } catch (_: Exception) { null }
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                return (0 until arr.length()).map { arr.getString(it) }.toSet()
            } catch (_: Exception) {}
        }
        // Migrate old StringSet or return all
        return try {
            (all[HOME_SEL_KEY] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: albums.keys
        } catch (_: Exception) { albums.keys }
    }

    private fun showAlbumSelection() {
        replaceContent(buildAlbumSelectionPage())
    }

    private fun buildAlbumSelectionPage(): View {
        page = Page.SELECT_ALBUMS
        val selected = homeSelectedFolders()
        val currentSel = selected.toMutableSet()

        selectScroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(12))
        }
        column.addView(label("选择主页相册", 20f, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, dp(34)))

        for ((folder, _) in albums) {
            val checked = folder in currentSel
            val card = selectionToggleCard(folder, checked) { nowChecked ->
                if (nowChecked) currentSel.add(folder) else currentSel.remove(folder)
                prefs.edit().putString(HOME_SEL_KEY, JSONArray(currentSel.toList()).toString()).apply()
                rebuildAlbumSelection()
            }
            column.addView(card)
        }

        selectScroll!!.addView(column)
        return selectScroll!!
    }

    private fun selectionToggleCard(folder: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val c = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); setCardBackgroundColor(surface); cardElevation = 0f
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        row.addView(label(File(folder).name.ifBlank { "存储" }, 13f, true),
            LinearLayout.LayoutParams(0, -1, 1f))
        val sw = SwitchMaterial(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
        row.addView(sw)
        c.addView(row)
        return c.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(3), 0, dp(3)) } }
    }

    private fun setAsCover() {
        val pic = viewerPics.getOrNull(viewerIndex) ?: return
        val folder = pic.folder
        coverPrefs.edit().putString(folder, pic.path).apply()
        toast("已设为相册封面")
    }

    private fun getCoverPath(folder: String): String? = coverPrefs.getString(folder, null)

    // ══════════════════════════════════════════════════════════════════
    //  currentUri()
    // ══════════════════════════════════════════════════════════════════

    private fun currentUri(): Uri {
        val p = viewerPics[viewerIndex].path
        return if (p.startsWith("content:")) Uri.parse(p) else Uri.fromFile(File(p))
    }

    // ══════════════════════════════════════════════════════════════════
    //  ACTIONS
    // ══════════════════════════════════════════════════════════════════

    private fun runAction(name: String) {
        when (name) {
            "旋转" -> { rotation = (rotation + 90f) % 360f; showPhotoView() }
            "删除" -> ensureWriteAccess { confirmDelete() }
            "复制" -> { pendingFileOp = "复制"; ensureWriteAccess { showAlbumPicker() } }
            "移动" -> { pendingFileOp = "移动"; ensureWriteAccess { showAlbumPicker() } }
            "设为封面" -> setAsCover()
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除这张图片？")
            .setMessage("删除后无法恢复")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val p = viewerPics[viewerIndex].path
                io.execute {
                    val ok = mediaStoreDelete(p)
                    runOnUiThread {
                        if (ok) { toast("已删除")
                            if (isExternal) finish()
                            else { scan(); showAlbums() }
                        } else toast("删除失败")
                    }
                }
            }.show()
    }

    // ══════════════════════════════════════════════════════════════════
    //  CROWN  (sensitivity halved: dp(7) → dp(3))
    // ══════════════════════════════════════════════════════════════════

    private var crownDelta = 0f
    private var crownSwitchMs = 0L
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) {
            var delta = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta == 0f) delta = e.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (delta == 0f) delta = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (delta != 0f) {
                val step = (-delta * dp(3)).roundToInt()
                if (page == Page.VIEWER) {
                    if (prefs.getBoolean("crownZoom", true)) {
                        viewer?.zoomBy(-delta)
                    } else {
                        // Time-gated image switching
                        if (crownDelta != 0f && (delta > 0f) != (crownDelta > 0f)) {
                            crownDelta = 0f
                        }
                        crownDelta += delta
                        val now = SystemClock.uptimeMillis()
                        if (abs(crownDelta) >= 8f) {
                            if (now - crownSwitchMs > 400) {
                                if (crownDelta < 0f && viewerIndex + 1 < viewerPics.size) {
                                    viewerIndex++; rotation = 0f
                                    slideToImage(-1f)
                                    crownSwitchMs = now
                                } else if (crownDelta > 0f && viewerIndex - 1 >= 0) {
                                    viewerIndex--; rotation = 0f
                                    slideToImage(1f)
                                    crownSwitchMs = now
                                }
                            }
                            crownDelta = 0f
                        }
                    }
                } else {
                    when (page) {
                        Page.ALBUMS -> albumScroll?.scrollBy(0, step)
                        Page.SETTINGS -> settingsScroll?.scrollBy(0, step)
                        Page.PICK_ALBUM -> pickerScroll?.scrollBy(0, step)
                        Page.SELECT_ALBUMS -> selectScroll?.scrollBy(0, step)
                        else -> Unit
                    }
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // ══════════════════════════════════════════════════════════════════
    //  UI BUILDERS
    // ══════════════════════════════════════════════════════════════════

    private fun albumCard(folder: String, photos: List<Pic>): View {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat(); cardElevation = 0f
            setCardBackgroundColor(surface); strokeWidth = 0; isClickable = true
            tag = folder
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val coverPath = getCoverPath(folder)?.takeIf { File(it).exists() } ?: photos.firstOrNull()?.path
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(if (dark) Color.rgb(44, 48, 49) else Color.rgb(225, 231, 231))
            tag = "cover_$folder"
        }
        // Load cover from cache or decode on IO thread
        if (coverPath != null) {
            synchronized(coverCache) {
                coverCache[coverPath]?.let { cover.setImageBitmap(it); return@let }
            }
            io.execute {
                val b = decodeSampled(coverPath, 180, 180)
                if (b != null) synchronized(coverCache) { coverCache[coverPath] = b }
                runOnUiThread {
                    if (cover.tag == "cover_$folder") cover.setImageBitmap(b)
                }
            }
        }
        body.addView(cover, LinearLayout.LayoutParams(-1, dp(68)))
        body.addView(label(File(folder).name.ifBlank { "存储" }, 12f, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(7), dp(3), dp(5), 0)
        }, LinearLayout.LayoutParams(-1, dp(26)))
        card.addView(body)
        card.setOnClickListener {
            if (photos.isNotEmpty()) showViewer(photos, 0, Page.ALBUMS)
            else toast("这个相册还是空的")
        }
        return card
    }

    private fun gridParams() = GridLayout.LayoutParams().apply {
        width = 0; height = dp(100)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun settingCard(title: String, subtitle: String, action: () -> Unit): View {
        val c = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); setCardBackgroundColor(surface); cardElevation = 0f
            setOnClickListener {
                if (title == "新建相册") ensureWriteAccess { action() }
                else action()
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(9), dp(10), dp(8))
        }
        box.addView(label(title, 14f, true))
        box.addView(label(subtitle, 10f).apply { setTextColor(muted) })
        c.addView(box)
        return c.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { setMargins(0, dp(3), 0, dp(3)) } }
    }

    private fun switchCard(title: String, value: Boolean, change: (Boolean) -> Unit): View {
        val c = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); setCardBackgroundColor(surface); cardElevation = 0f
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(8), 0)
        }
        row.addView(label(title, 13f, true), LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(SwitchMaterial(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, b -> change(b) }
        })
        c.addView(row)
        return c.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(3), 0, dp(3)) } }
    }

    private fun themeCard(): View {
        val c = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); setCardBackgroundColor(surface); cardElevation = 0f
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(8)) }
        box.addView(label("外观", 13f, true))
        val row = LinearLayout(this)
        row.addView(themeChoice("深色", true), LinearLayout.LayoutParams(0, dp(38), 1f))
        row.addView(themeChoice("浅色", false), LinearLayout.LayoutParams(0, dp(38), 1f))
        box.addView(row); c.addView(box)
        return c.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(66)).apply { setMargins(0, dp(3), 0, dp(3)) } }
    }

    private fun themeChoice(name: String, value: Boolean) = TextView(this).apply {
        text = if (dark == value) "●  $name" else "○  $name"
        textSize = 12f; gravity = Gravity.CENTER
        setTextColor(if (dark == value) accent else muted)
        setOnClickListener {
            if (dark != value) { prefs.edit().putBoolean("dark", value).apply(); recreate() }
        }
    }

    private fun actionPill(name: String, action: () -> Unit) = MaterialCardView(this).apply {
        radius = dp(22).toFloat(); setCardBackgroundColor(surface); cardElevation = 0f
        setOnClickListener { action() }
        addView(label(name, 15f, true).apply { gravity = Gravity.CENTER })
    }

    private fun newAlbum() {
        val input = EditText(this).apply { hint = "相册名称"; setSingleLine() }
        MaterialAlertDialogBuilder(this)
            .setTitle("新建相册")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name.contains(Regex("[\\\\/:*?\"<>|]")))
                    return@setPositiveButton toast("名称无效")
                val dir = File("/storage/emulated/0/Pictures", name)
                if (!dir.mkdirs())
                    return@setPositiveButton toast(if (dir.exists()) "相册已存在" else "创建失败")
                try {
                    resources.openRawResource(R.drawable.minipic_gallery_signature).use { ins ->
                        File(dir, SIGNATURE).outputStream().use { ins.copyTo(it) }
                    }
                } catch (_: Exception) {}
                scan()
            }.show()
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(this@MainActivity.text)
        gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun decodeUri(uri: Uri): Bitmap? = try {
        if (uri.scheme == "file") decodeSampled(uri.path!!, 1000, 1000)
        else contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    /** Decode a drawable — returns animated drawable for GIFs (API 28+). */
    private fun decodeDrawable(uri: Uri): Drawable? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = when {
                uri.scheme == "file" -> ImageDecoder.createSource(File(uri.path!!))
                else -> ImageDecoder.createSource(contentResolver, uri)
            }
            ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
            }
        } else {
            // API 23-27 fallback: decode as static bitmap
            decodeUri(uri)?.let { BitmapDrawable(resources, it) }
        }
    } catch (_: Exception) { null }

    private fun decodeSampled(path: String, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDestroy() {
        super.onDestroy()
        try { io.shutdownNow() } catch (_: Exception) {}
    }
}

// ══════════════════════════════════════════════════════════════════════
//  GESTURE FRAME LAYOUT  —  edge-swipe detection
// ══════════════════════════════════════════════════════════════════════

class GestureFrameLayout(context: Context) : FrameLayout(context) {
    /** Called for every touch event (observer, not consumer). */
    var touchObserver: ((MotionEvent) -> Unit)? = null

    /** Called when an edge-swipe is detected and consumed.
     *  @param dx  net horizontal displacement (positive = rightward)
     *  @param fromLeft  true if start position was left edge */
    var swipeObserver: ((dx: Float, fromLeft: Boolean) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private val edgeThresh get() = (24 * resources.displayMetrics.density).toInt()
    private val moveThresh get() = (20 * resources.displayMetrics.density).toInt()

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        touchObserver?.invoke(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                // Left edge → right swipe (back)
                if (downX < edgeThresh && dx > moveThresh) return true
                // Right edge → left swipe (enter)
                if (downX > width - edgeThresh && dx < -moveThresh) return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX
            val fromLeft = downX < edgeThresh
            swipeObserver?.invoke(dx, fromLeft)
        }
        return true
    }
}

// ══════════════════════════════════════════════════════════════════════
//  PHOTO VIEW
// ══════════════════════════════════════════════════════════════════════

class PhotoView(context: Context) : View(context) {
    private var drawable: Drawable? = null
    private var fitScale = 1f
    private var userScale = 1f
    private var tx = 0f; private var ty = 0f
    private var lastX = 0f; private var lastY = 0f
    private var dragging = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            userScale = (userScale * detector.scaleFactor).coerceIn(.1f, 20f); clamp(); invalidate()
            return true
        }
    })

    fun setDrawable(value: Drawable?) {
        drawable = value; userScale = 1f; tx = 0f; ty = 0f
        if (value != null) {
            fitScale = if (width > 0 && height > 0 && value.intrinsicWidth > 0 && value.intrinsicHeight > 0)
                min(width.toFloat() / value.intrinsicWidth, height.toFloat() / value.intrinsicHeight) else 1f
        }
        requestLayout(); invalidate()
    }
    fun isAtFitScale() = userScale <= 1.015f
    fun zoomBy(delta: Float) {
        userScale = (userScale * exp(delta * .055f)).coerceIn(.1f, 20f); clamp(); invalidate()
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c); val d = drawable ?: return
        val s = fitScale * userScale
        if (width > 0 && height > 0 && d.intrinsicWidth > 0 && d.intrinsicHeight > 0)
            fitScale = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        c.save(); c.translate(width / 2f + tx, height / 2f + ty); c.scale(s, s)
        d.setBounds(-d.intrinsicWidth / 2, -d.intrinsicHeight / 2, d.intrinsicWidth / 2, d.intrinsicHeight / 2)
        d.draw(c); c.restore()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; dragging = userScale > 1.015f }
            MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress) {
                tx += e.x - lastX; ty += e.y - lastY; lastX = e.x; lastY = e.y; clamp(); invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
    private fun clamp() {
        val d = drawable ?: return; val s = fitScale * userScale
        val maxX = max(0f, (d.intrinsicWidth * s - width) / 2f)
        val maxY = max(0f, (d.intrinsicHeight * s - height) / 2f)
        tx = tx.coerceIn(-maxX, maxX); ty = ty.coerceIn(-maxY, maxY)
    }
}
