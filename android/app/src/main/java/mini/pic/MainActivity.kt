package mini.pic

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.*

private const val SIGNATURE = GALLERY_SIGNATURE
private const val CROWN_SENSITIVITY = 0.4f
private const val REGION_OVERSCAN = 1.25f
private const val REGION_PIXEL_BUDGET = 4_000_000

data class DetailRequest(
    val token: Long,
    val sourceRect: Rect,
    val sample: Int
)

data class ImageMetadata(
    val width: Int,
    val height: Int,
    val orientation: Int = ExifInterface.ORIENTATION_NORMAL
)

enum class Page { ALBUMS, SETTINGS, VIEWER, ACTIONS, PICK_ALBUM, SELECT_ALBUMS }

class MainActivity : Activity() {
    // ── stable page container ──────────────────────────────────────────
    private lateinit var stableRoot: GestureFrameLayout

    private val io = Executors.newSingleThreadExecutor()
    private val fileIo = Executors.newSingleThreadExecutor()
    private val coverIo = Executors.newFixedThreadPool(2)
    private val viewerIo = Executors.newSingleThreadExecutor()
    private val coverCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 24
    }
    private val albums = linkedMapOf<String, MutableList<Pic>>()
    private var page = Page.ALBUMS
    private var previousPage = Page.ALBUMS
    private var albumGrid: RecyclerView? = null
    private var albumAdapter: AlbumAdapter? = null
    private var settingsScroll: ScrollView? = null
    private var pickerScroll: ScrollView? = null
    private var selectScroll: ScrollView? = null
    private var viewer: PhotoView? = null
    private var mangaViewer: MangaView? = null
    private var viewerPics = listOf<Pic>()
    private var viewerIndex = 0
    private var rotation = 0f
    private var scanning = false
    private var animating = false
    private var pendingFileOp: String? = null
    private val coverPrefs by lazy { getSharedPreferences("covers", MODE_PRIVATE) }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val isExternal get() = intent?.action == Intent.ACTION_VIEW && intent?.data != null
    private val mangaMode get() = prefs.getBoolean("mangaMode", false)
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
        if (newIntent == null) return
        intent = newIntent
        if (newIntent.action == Intent.ACTION_VIEW && newIntent.data != null) {
            showExternal(newIntent.data!!)
        } else if (newIntent.action == Intent.ACTION_MAIN) {
            showAlbums()
            loadCache()
            ensurePermissionAndScan()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  PERMISSIONS & SCAN
    // ══════════════════════════════════════════════════════════════════

    private var permissionSettingsRequested = false
    private var pendingWriteAction: (() -> Unit)? = null
    private var permissionDenied = false

    private fun readImagesPermission(): String = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasGalleryReadAccess(): Boolean =
        (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) ||
            checkSelfPermission(readImagesPermission()) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionAndScan() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            val readPermission = readImagesPermission()
            if (checkSelfPermission(readPermission) == PackageManager.PERMISSION_GRANTED) {
                scan()
                return
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("需要图库访问权限")
                .setMessage("完整管理相册需要文件访问权限；也可以只授予图片读取权限。")
                .setNegativeButton("暂不") { _, _ -> showPermissionHint() }
                .setNeutralButton("仅浏览图片") { _, _ -> requestPermissions(arrayOf(readPermission), 9) }
                .setPositiveButton("完整访问") { _, _ ->
                    permissionSettingsRequested = true
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setOnCancelListener { showPermissionHint() }
                .show()
            return
        }
        if (!hasGalleryReadAccess()) {
            requestPermissions(arrayOf(readImagesPermission()), 9)
            return
        }
        scan()
    }

    private fun showPermissionHint() {
        permissionDenied = true
        if (page == Page.ALBUMS && albums.isEmpty()) albumAdapter?.notifyItemChanged(0)
        toast("未授予图库访问权限")
    }

    override fun onResume() {
        super.onResume()
        if (permissionSettingsRequested) {
            permissionSettingsRequested = false
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                scan()
                pendingWriteAction?.also { pendingWriteAction = null; it() }
            } else {
                showPermissionHint()
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(code, p, g)
        if (code == 9 && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false
            scan()
        } else if (code == 9) showPermissionHint()
        if (code == 10 && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingWriteAction?.also { pendingWriteAction = null; it() }
        } else if (code == 10) toast("未授予文件写入权限")
    }

    private fun hasWriteAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager()
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureWriteAccess(onGranted: () -> Unit) {
        if (hasWriteAccess()) {
            onGranted()
        } else if (Build.VERSION.SDK_INT >= 30) {
            pendingWriteAction = onGranted
            MaterialAlertDialogBuilder(this)
                .setTitle("需要文件访问权限")
                .setMessage("为了复制、移动和删除图片，请授予「所有文件访问权限」")
                .setNegativeButton("取消") { _, _ -> pendingWriteAction = null }
                .setPositiveButton("去设置") { _, _ ->
                    permissionSettingsRequested = true
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }.show()
        } else {
            pendingWriteAction = onGranted
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 10)
        }
    }

    private fun scan() {
        if (!hasGalleryReadAccess()) {
            ensurePermissionAndScan()
            return
        }
        if (scanning || isFinishing || isDestroyed) return
        permissionDenied = false
        scanning = true
        io.execute {
            val found = GalleryScanner(File("/storage/emulated/0"), prefs.getBoolean("hidden", false)).scan()
            saveCache(found)
            runOnUiThread {
                scanning = false
                val changed = !sameGallerySnapshot(albums, found)
                albums.clear()
                albums.putAll(found)
                val allPaths = found.values.asSequence().flatten().map { it.path }.toHashSet()
                coverPrefs.edit().apply {
                    for (folder in albums.keys) {
                        val saved = coverPrefs.getString(folder, null)
                        if (saved != null && saved !in allPaths) remove(folder)
                    }
                    apply()
                }
                if (page == Page.ALBUMS) {
                    if (changed) replaceAlbumsGrid() else if (albumAdapter?.itemCount == 1) albumAdapter?.notifyItemChanged(0)
                } else if (page == Page.PICK_ALBUM) {
                    rebuildAlbumPicker()
                } else if (page == Page.SELECT_ALBUMS) {
                    rebuildAlbumSelection()
                }
            }
        }
    }


    private val CACHE_FILE = "album_cache.json"

    private fun saveCache(snapshot: Map<String, List<Pic>>) {
        try {
            val json = JSONArray()
            for ((folder, photos) in snapshot) {
                val album = JSONObject().put("folder", folder)
                val pics = JSONArray()
                for (pic in photos) {
                    pics.put(JSONObject()
                        .put("path", pic.path)
                        .put("folder", pic.folder)
                        .put("modified", pic.modified)
                        .put("size", pic.size))
                }
                album.put("photos", pics)
                json.put(album)
            }
            val temp = File(filesDir, "$CACHE_FILE.tmp")
            temp.writeText(json.toString())
            val target = File(filesDir, CACHE_FILE)
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        } catch (_: Exception) {}
    }

    private fun loadCache() {
        io.execute {
            val cached = try {
                val json = JSONArray(openFileInput(CACHE_FILE).bufferedReader().use { it.readText() })
                linkedMapOf<String, MutableList<Pic>>().apply {
                    for (i in 0 until json.length()) {
                        val album = json.getJSONObject(i)
                        val folder = album.getString("folder")
                        val photos = mutableListOf<Pic>()
                        val pics = album.getJSONArray("photos")
                        for (j in 0 until pics.length()) {
                            val p = pics.getJSONObject(j)
                            val path = p.getString("path")
                            val file = File(path)
                            if (path.startsWith("content:")) continue
                            if (file.isFile && (p.optLong("size", -1L) == -1L ||
                                    (file.length() == p.optLong("size") && file.lastModified() == p.optLong("modified")))) {
                                photos += Pic(path, p.getString("folder"), p.getLong("modified"), file.length())
                            }
                        }
                        if (photos.isNotEmpty() || File(folder, SIGNATURE).exists()) put(folder, photos)
                    }
                }
            } catch (_: Exception) { linkedMapOf() }
            if (isFinishing || isDestroyed) return@execute
            runOnUiThread {
                if (albums.isEmpty() && cached.isNotEmpty()) {
                    albums.putAll(cached)
                    if (page == Page.ALBUMS) replaceAlbumsGrid()
                }
            }
        }
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
        if (page != Page.VIEWER || mangaMode || viewer?.isAtFitScale() != true) return
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
        mangaViewer = null
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

        val generation = ++imageLoadGeneration
        detailRequestGeneration++
        detailRequestFuture?.cancel(true)
        detailRequestFuture = null
        detailRequestHandler.removeCallbacksAndMessages(null)
        val uri = currentUri()
        val rotationForLoad = rotation
        viewerIo.execute {
            val metadata = readImageMetadata(uri)
            val drawable = decodeDrawable(uri, max(1, resources.displayMetrics.widthPixels),
                max(1, resources.displayMetrics.heightPixels), metadata.orientation)
            if (isFinishing || isDestroyed) return@execute
            runOnUiThread {
                if (generation != imageLoadGeneration || page != Page.VIEWER || isFinishing || isDestroyed) {
                    animating = false
                    return@runOnUiThread
                }
                if (drawable == null) { animating = false; return@runOnUiThread }
                bindViewerImage(newPhoto, uri, metadata, drawable, rotationForLoad)

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
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(2), dp(7), dp(10))
        }
        column.addView(label("相册", 20f, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, dp(33)))

        albumGrid = RecyclerView(this).apply {
            val manager = GridLayoutManager(this@MainActivity, 2)
            layoutManager = manager
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (albumAdapter?.itemCount == 1 && albums.isEmpty()) 2 else 1
            }
        }
        albumAdapter = AlbumAdapter().also { albumGrid!!.adapter = it }
        replaceAlbumsGrid()
        column.addView(albumGrid, LinearLayout.LayoutParams(-1, 0, 1f))

        return column
    }

    private fun visibleAlbums(): List<Pair<String, List<Pic>>> {
        val showFolders = homeSelectedFolders()
        return albums.entries
            .filter { it.key in showFolders }
            .map { it.key to it.value }
    }

    private fun replaceAlbumsGrid() {
        albumAdapter?.submitList(visibleAlbums())
    }

    private inner class AlbumAdapter : RecyclerView.Adapter<AlbumAdapter.Holder>() {
        private var items: List<Pair<String, List<Pic>>> = emptyList()

        inner class Holder(val host: FrameLayout) : RecyclerView.ViewHolder(host)

        fun submitList(next: List<Pair<String, List<Pic>>>) {
            val previous = items
            val oldCount = max(1, previous.size)
            val newCount = max(1, next.size)
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldCount
                override fun getNewListSize(): Int = newCount
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                    if (previous.isEmpty() || next.isEmpty()) return previous.isEmpty() && next.isEmpty()
                    return previous[oldPosition].first == next[newPosition].first
                }
                override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                    if (previous.isEmpty() || next.isEmpty()) return true
                    return previous[oldPosition] == next[newPosition]
                }
            })
            items = next
            diff.dispatchUpdatesTo(this)
            if (items.isEmpty()) notifyItemChanged(0)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(FrameLayout(this@MainActivity))

        override fun getItemCount(): Int = if (items.isEmpty()) 1 else items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.host.removeAllViews()
            if (items.isEmpty()) {
                holder.host.addView(label(
                    when {
                        permissionDenied -> "需要图片访问权限"
                        scanning -> "正在扫描…"
                        else -> "暂无相册"
                    }, 14f
                ).apply {
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(-1, dp(140))
                })
                return
            }
            val (folder, photos) = items[position]
            holder.host.addView(albumCard(folder, photos), FrameLayout.LayoutParams(-1, dp(100)))
        }
    }

    private fun buildSettingsPage(): View {
        page = Page.SETTINGS
        settingsScroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(12)) }
        column.addView(label("设置", 20f, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(34)))
        column.addView(settingCard("主页相册", "选择展示在主页的相册") { showAlbumSelection() })
        column.addView(settingCard("新建相册", "在 Pictures 中创建") { newAlbum() })
        column.addView(switchCard("表冠滚动缩放", prefs.getBoolean("crownZoom", true)) { prefs.edit().putBoolean("crownZoom", it).apply() })
        column.addView(switchCard("开启漫画模式", mangaMode) { prefs.edit().putBoolean("mangaMode", it).apply() })
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
        val actions = if (mangaViewer != null)
            mutableListOf("复制", "移动", "删除")
        else mutableListOf("复制", "移动", "删除", "旋转")
        if (!isExternal) actions += "设为封面"
        actions.forEach { name ->
            column.addView(actionPill(name) { runAction(name) },
                LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        return column
    }

    private var imageLoadGeneration = 0L
    private var detailRequestGeneration = 0L
    private var detailRequestFuture: java.util.concurrent.Future<*>? = null
    private val detailRequestHandler = Handler(Looper.getMainLooper())
    private var detailRequestRunnable: Runnable? = null

    private fun buildViewerPage(): View {
        if (mangaMode && !isExternal) return buildMangaViewerPage()
        mangaViewer = null
        page = Page.VIEWER
        updateSystemUi(true)
        val host = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val photo = PhotoView(this).apply { setBackgroundColor(Color.BLACK) }
        viewer = photo
        host.addView(photo, FrameLayout.LayoutParams(-1, -1))
        val generation = ++imageLoadGeneration
        detailRequestGeneration++
        detailRequestFuture?.cancel(true)
        detailRequestFuture = null
        detailRequestHandler.removeCallbacksAndMessages(null)
        val uri = currentUri()
        val rotationForLoad = rotation
        viewerIo.execute {
            val metadata = readImageMetadata(uri)
            val preview = decodeDrawable(uri, max(1, resources.displayMetrics.widthPixels),
                max(1, resources.displayMetrics.heightPixels), metadata.orientation)
            if (isFinishing || isDestroyed) return@execute
            runOnUiThread {
                if (generation != imageLoadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                bindViewerImage(photo, uri, metadata, preview, rotationForLoad)
            }
        }
        return host
    }

    private fun bindViewerImage(photo: PhotoView, uri: Uri, metadata: ImageMetadata, drawable: Drawable?, rotationForLoad: Float) {
        val supportsRegionDetail = drawable !is Animatable
        photo.setImageSource(metadata.width, metadata.height, metadata.orientation, supportsRegionDetail)
        photo.setDrawable(drawable)
        photo.rotation = rotationForLoad
        photo.onViewportChanged = if (supportsRegionDetail) {
            { request -> requestDetailRegion(photo, uri, request) }
        } else null
        if (supportsRegionDetail) photo.requestDetailUpdate()
        (drawable as? Animatable)?.start()
    }

    private fun readImageMetadata(uri: Uri): ImageMetadata {
        return try {
            openImageStream(uri)?.use { input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                val orientation = openImageStream(uri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
                ImageMetadata(bounds.outWidth.coerceAtLeast(1), bounds.outHeight.coerceAtLeast(1), orientation)
            } ?: ImageMetadata(1, 1)
        } catch (_: Exception) { ImageMetadata(1, 1) }
    }
    private fun requestDetailRegion(photo: PhotoView, uri: Uri, request: DetailRequest) {
        if (photo.hasDetailFor(request)) return
        detailRequestGeneration++
        val generation = detailRequestGeneration
        detailRequestRunnable?.let(detailRequestHandler::removeCallbacks)
        detailRequestFuture?.cancel(true)
        val task = Runnable {
            detailRequestFuture = viewerIo.submit {
                val bitmap = decodeRegion(uri, request.sourceRect, request.sample)
                if (bitmap == null) return@submit
                if (isFinishing || isDestroyed) {
                    bitmap.recycle()
                    return@submit
                }
                runOnUiThread {
                    if (generation != detailRequestGeneration || photo !== viewer || isFinishing || isDestroyed) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@runOnUiThread
                    }
                    photo.setDetailBitmap(bitmap, request.sourceRect, request.sample)
                }
            }
        }
        detailRequestRunnable = task
        detailRequestHandler.postDelayed(task, 90L)
    }

    private fun decodeRegion(uri: Uri, requested: Rect, sample: Int): Bitmap? {
        val stream = openImageStream(uri) ?: return null
        var decoder: BitmapRegionDecoder? = null
        return try {
            decoder = BitmapRegionDecoder.newInstance(stream, false)
            val current = decoder ?: return null
            val source = Rect(
                requested.left.coerceIn(0, current.width - 1),
                requested.top.coerceIn(0, current.height - 1),
                requested.right.coerceIn(1, current.width),
                requested.bottom.coerceIn(1, current.height)
            )
            if (source.width() <= 0 || source.height() <= 0) null else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                current.decodeRegion(source, options)
            }
        } catch (_: Exception) { null }
        finally {
            try { decoder?.recycle() } catch (_: Exception) {}
            try { stream.close() } catch (_: Exception) {}
        }
    }

    private fun decodeMangaBitmap(pic: Pic, targetWidth: Int): Bitmap? {
        val uri = Uri.fromFile(File(pic.path))
        val metadata = readImageMetadata(uri)
        val orientedSource = orientedSize(metadata.width, metadata.height, metadata.orientation)
        val targetHeight = max(1, (targetWidth.toFloat() * orientedSource.second / orientedSource.first).roundToInt())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openImageStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = bitmapSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight, 2_000_000)
        val raw = decodeSampled(uri, max(1, bounds.outWidth / sample), max(1, bounds.outHeight / sample)) ?: return null
        if (metadata.orientation == ExifInterface.ORIENTATION_NORMAL) return raw
        val orientedBitmap = orientedSize(raw.width, raw.height, metadata.orientation)
        val source = floatArrayOf(
            0f, 0f, raw.width.toFloat(), 0f,
            raw.width.toFloat(), raw.height.toFloat(), 0f, raw.height.toFloat()
        )
        val target = FloatArray(8)
        for (index in 0 until 4) {
            val point = mapRawPointToOriented(
                source[index * 2], source[index * 2 + 1], raw.width.toFloat(), raw.height.toFloat(), metadata.orientation
            )
            target[index * 2] = point.first
            target[index * 2 + 1] = point.second
        }
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(source, 0, target, 0, 4)) return raw
        val result = Bitmap.createBitmap(orientedBitmap.first, orientedBitmap.second, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(raw, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        raw.recycle()
        return result
    }

    private fun buildMangaViewerPage(): View {
        page = Page.VIEWER
        updateSystemUi(true)
        val host = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val pages = naturalImageOrder(viewerPics)
        viewerPics = pages
        viewerIndex = 0
        viewer = null
        val manga = MangaView(
            this,
            pages,
            viewerIo,
            readMetrics = { pic ->
                val metadata = readImageMetadata(Uri.fromFile(File(pic.path)))
                val oriented = orientedSize(metadata.width, metadata.height, metadata.orientation)
                MangaPageMetrics(oriented.first, oriented.second)
            },
            loadBitmap = { pic, targetWidth -> decodeMangaBitmap(pic, targetWidth) }
        )
        mangaViewer = manga
        manga.onPageChanged = { index ->
            if (index in viewerPics.indices) viewerIndex = index
        }
        host.addView(manga, FrameLayout.LayoutParams(-1, -1))
        return host
    }
    // ══════════════════════════════════════════════════════════════════

    private fun showExternal(uri: Uri) {
        previousPage = Page.ALBUMS
        viewerPics = listOf(Pic(uri.toString(), "", 0L, 0L))
        viewerIndex = 0
        rotation = 0f
        replaceContent(buildViewerPage())
    }

    private fun showViewer(list: List<Pic>, index: Int, from: Page) {
        previousPage = from
        viewerPics = list
        viewerIndex = index
        rotation = 0f
        replaceContent(buildViewerPage())
    }

    private fun showPhotoView() {
        if (page != Page.ACTIONS && page != Page.PICK_ALBUM) previousPage = page
        val building = buildViewerPage()
        replaceContent(building)
    }

    // ══════════════════════════════════════════════════════════════════
    //  ALBUM PICKER (for copy / move)
    // ══════════════════════════════════════════════════════════════════

    private fun showAlbumPicker() {
        scan()
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
            if (photos.isNotEmpty()) coverIo.execute {
                val bitmap = try {
                    decodeSampled(photos[0].path, 72, 72)
                } catch (_: Throwable) { null }
                if (isFinishing || isDestroyed) return@execute
                runOnUiThread { if (tag == folder) setImageBitmap(bitmap) }
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

        fileIo.execute {
            val result = try {
                val srcUri = Uri.parse(srcPath)
                val srcName = if (srcUri.scheme == "content") {
                    val cursor = contentResolver.query(srcUri, arrayOf("_display_name"), null, null, null)
                    cursor?.use { if (it.moveToFirst()) it.getString(0) ?: "image.jpg" else "image.jpg" }
                        ?: srcUri.lastPathSegment ?: "image.jpg"
                } else if (srcUri.scheme == "file") File(srcUri.path!!).name else File(srcPath).name

                when (op) {
                    "复制" -> {
                        if (!mediaStoreCopy(targetFolder, srcPath, srcName))
                            throw IOException("目标写入失败，原图未更改")
                        "已复制到 ${File(targetFolder).name}"
                    }
                    "移动" -> {
                        if (!mediaStoreCopy(targetFolder, srcPath, srcName))
                            throw IOException("目标写入失败，原图未更改")
                        if (!mediaStoreDelete(srcPath)) {
                            "复制成功，但无法删除原图；移动未完成"
                        } else {
                            "已移动到 ${File(targetFolder).name}"
                        }
                    }
                    else -> throw IOException("未知操作")
                }
            } catch (e: Exception) {
                "${op}失败: ${e.localizedMessage ?: "未知错误"}；原图未更改"
            }
            if (isFinishing || isDestroyed) return@execute
            runOnUiThread {
                toast(result)
                if (result.startsWith("已移动到")) {
                    if (isExternal) finish() else { scan(); showAlbums() }
                } else {
                    if (result.startsWith("已复制到") || result.startsWith("复制成功")) scan()
                    showPhotoView()
                }
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
                        projection, sel, arrayOf(name, if (dir.isBlank()) "" else "$dir/"), null)
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
        name.endsWith(".gif", true) -> "image/gif"
        else -> "image/jpeg"
    }

    private fun mediaStoreCopy(albumFolder: String, srcPath: String, fileName: String): Boolean {
        val relativeFolder = relativeFolderPath(
            Environment.getExternalStorageDirectory(), File(albumFolder)
        ) ?: return false
        val expectedLength = sourceLength(srcPath)
        val source = openSourceStream(srcPath) ?: return false
        if (Build.VERSION.SDK_INT >= 29) {
            var inserted: Uri? = null
            try {
                val safeName = uniqueMediaStoreName(relativeFolder, fileName)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeFromName(safeName))
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativeFolder)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                inserted = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("无法创建目标图片")
                val output = contentResolver.openOutputStream(inserted, "w")
                    ?: throw IOException("无法打开目标图片")
                val copied = source.use { input -> output.use { input.copyTo(it, 64 * 1024) } }
                if (copied <= 0L || (expectedLength != null && copied != expectedLength))
                    throw IOException("图片内容长度不匹配")
                val committed = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                if (contentResolver.update(inserted, committed, null, null) <= 0)
                    throw IOException("无法提交目标图片")
                return true
            } catch (_: Exception) {
                try { inserted?.let { contentResolver.delete(it, null, null) } } catch (_: Exception) {}
                try { source.close() } catch (_: Exception) {}
                return false
            }
        }

        var tempFile: File? = null
        return try {
            val dir = File(albumFolder)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建目标相册")
            val destination = resolveDestName(dir, fileName)
            val temp = File(dir, ".${destination.name}.${System.nanoTime()}.tmp")
            tempFile = temp
            val copied = source.use { input ->
                java.io.FileOutputStream(temp).use { output ->
                    val count = input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                    count
                }
            }
            if (copied <= 0L || temp.length() != copied ||
                (expectedLength != null && copied != expectedLength) || !temp.renameTo(destination))
                throw IOException("无法完成图片写入")
            true
        } catch (_: Exception) {
            try { tempFile?.delete() } catch (_: Exception) {}
            try { source.close() } catch (_: Exception) {}
            false
        }
    }

    private fun sourceLength(source: String): Long? = try {
        val uri = Uri.parse(source)
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            val length = cursor?.use {
                val index = it.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && it.moveToFirst()) it.getLong(index) else -1L
            } ?: -1L
            length.takeIf { it >= 0L }
        } else {
            val path = if (uri.scheme == "file") uri.path!! else source
            File(path).takeIf { it.isFile }?.length()
        }
    } catch (_: Exception) { null }

    private fun openSourceStream(source: String) = try {
        val uri = Uri.parse(source)
        when (uri.scheme) {
            "file" -> File(uri.path!!).inputStream()
            null -> File(source).inputStream()
            else -> contentResolver.openInputStream(uri)
        }
    } catch (_: Exception) { null }

    private fun uniqueMediaStoreName(relativeFolder: String, requested: String): String =
        uniqueFileName(requested) { candidate ->
            try {
                val cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
                    arrayOf(candidate, relativeFolder), null
                )
                cursor?.use { it.moveToFirst() } ?: false
            } catch (_: Exception) { false }
        }

    private fun resolveDestName(dir: File, name: String): File =
        File(dir, uniqueFileName(name) { File(dir, it).exists() })

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
                if (contentResolver.delete(parsed, null, null) > 0) return true
            } catch (_: Exception) {}
        }
        val parsed = Uri.parse(path)
        val filePath = if (parsed.scheme == "file") parsed.path ?: return false
            else if (parsed.scheme == null) path else return false
        if (filePath != path) {
            val fileUri = fileToMediaUri(filePath)
            if (fileUri != null) {
                try { if (contentResolver.delete(fileUri, null, null) > 0) return true } catch (_: Exception) {}
            }
        }
        return try {
            val file = File(filePath)
            file.isFile && file.delete()
        } catch (_: Exception) { false }
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
        val path = viewerPics[viewerIndex].path
        val parsed = Uri.parse(path)
        return if (parsed.scheme != null) parsed else Uri.fromFile(File(path))
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
                fileIo.execute {
                    val ok = mediaStoreDelete(p)
                    if (isFinishing || isDestroyed) return@execute
                    runOnUiThread {
                        if (ok) {
                            toast("已删除")
                            if (isExternal) finish() else { scan(); showAlbums() }
                        } else toast("删除失败，原图未修改")
                    }
                }
            }.show()
    }

    // ══════════════════════════════════════════════════════════════════
    //  CROWN  (sensitivity halved: dp(7) → dp(3))
    // ══════════════════════════════════════════════════════════════════

    private var crownDelta = 0f
    private var crownSwitchMs = 0L
    private var crownScrollRemainder = 0f
    @SuppressLint("InlinedApi")
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) {
            var delta = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta == 0f) delta = e.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (delta == 0f) delta = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (delta != 0f) {
                delta = scaleCrownDelta(delta, CROWN_SENSITIVITY)
                if (page == Page.VIEWER) {
                    val manga = mangaViewer
                    if (manga != null) {
                        if (prefs.getBoolean("crownZoom", true)) {
                            manga.zoomBy(-delta)
                        } else {
                            manga.scrollByDistance(-delta * dp(3f))
                        }
                    } else if (prefs.getBoolean("crownZoom", true)) {
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
                    val pixels = -delta * dp(3f) + crownScrollRemainder
                    val step = pixels.toInt()
                    crownScrollRemainder = pixels - step
                    when (page) {
                        Page.ALBUMS -> albumGrid?.scrollBy(0, step)
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
        if (coverPath != null) {
            val cached = synchronized(coverCache) { coverCache[coverPath] }
            if (cached != null) {
                cover.setImageBitmap(cached)
            } else {
                coverIo.execute {
                    val bitmap = decodeSampled(coverPath, 180, 180)
                    if (bitmap != null) synchronized(coverCache) { coverCache[coverPath] = bitmap }
                    if (isFinishing || isDestroyed) return@execute
                    runOnUiThread {
                        if (cover.tag == "cover_$folder") cover.setImageBitmap(bitmap)
                    }
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
                    resources.openRawResource(R.raw.minipic_gallery_signature).use { ins ->
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

    private fun decodeDrawable(uri: Uri, maxWidth: Int, maxHeight: Int, orientation: Int): Drawable? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = if (uri.scheme == "file")
                ImageDecoder.createSource(File(uri.path!!))
            else ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val factor = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
                if (factor < 1f) {
                    decoder.setTargetSize(max(1, (width * factor).roundToInt()),
                        max(1, (height * factor).roundToInt()))
                }
                decoder.isMutableRequired = false
            }
        } else {
            decodeLegacyPreview(uri, maxWidth, maxHeight, orientation)
        }
    } catch (_: Exception) { null }

    private fun decodeLegacyPreview(uri: Uri, width: Int, height: Int, orientation: Int): Drawable? {
        val bitmap = decodeSampled(uri, width, height) ?: return null
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return BitmapDrawable(resources, bitmap)
        val oriented = orientedSize(bitmap.width, bitmap.height, orientation)
        val sourcePoints = floatArrayOf(
            0f, 0f, bitmap.width.toFloat(), 0f,
            bitmap.width.toFloat(), bitmap.height.toFloat(), 0f, bitmap.height.toFloat()
        )
        val rawCorners = sourcePoints.copyOf()
        val targetPoints = FloatArray(8)
        for (i in 0 until 4) {
            val point = mapRawPointToOriented(
                rawCorners[i * 2], rawCorners[i * 2 + 1], bitmap.width.toFloat(), bitmap.height.toFloat(), orientation
            )
            targetPoints[i * 2] = point.first
            targetPoints[i * 2 + 1] = point.second
        }
        val transform = Matrix()
        if (!transform.setPolyToPoly(sourcePoints, 0, targetPoints, 0, 4))
            return BitmapDrawable(resources, bitmap)
        val output = Bitmap.createBitmap(oriented.first, oriented.second, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(bitmap, transform, Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
        return BitmapDrawable(resources, output)
    }

    private fun openImageStream(uri: Uri) = try {
        if (uri.scheme == "file") {
            val path = uri.path
            if (path == null) null else {
                val file = File(path)
                if (!file.isFile || !file.canRead()) null else file.inputStream()
            }
        } else {
            contentResolver.openInputStream(uri)
        }
    } catch (_: Exception) { null }

    private fun decodeSampled(uri: Uri, width: Int, height: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openImageStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            val targetScale = minOf(
                width.toFloat() / bounds.outWidth,
                height.toFloat() / bounds.outHeight
            )
            var sample = 1
            while (1f / (sample * 2) >= targetScale) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            openImageStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (_: Throwable) { null }

    private fun decodeSampled(path: String, width: Int, height: Int): Bitmap? =
        decodeSampled(Uri.fromFile(File(path)), width, height)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDestroy() {
        super.onDestroy()
        detailRequestGeneration++
        detailRequestFuture?.cancel(true)
        detailRequestHandler.removeCallbacksAndMessages(null)
        imageLoadGeneration++
        io.shutdownNow()
        fileIo.shutdownNow()
        coverIo.shutdownNow()
        viewerIo.shutdownNow()
        synchronized(coverCache) { coverCache.clear() }
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

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX
            val fromLeft = downX < edgeThresh
            swipeObserver?.invoke(dx, fromLeft)
            performClick()
        }
        return true
    }
}

// ══════════════════════════════════════════════════════════════════════
//  PHOTO VIEW
// ══════════════════════════════════════════════════════════════════════

class PhotoView(context: Context) : View(context) {
    private var drawable: Drawable? = null
    private var detailBitmap: Bitmap? = null
    private var detailSource = Rect()
    private var detailSample = Int.MAX_VALUE
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var rawWidth = 1
    private var rawHeight = 1
    private var sourceOrientation = ExifInterface.ORIENTATION_NORMAL
    private var detailEnabled = false
    private var fitScale = 1f
    private var userScale = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var detailToken = 0L

    var onViewportChanged: ((DetailRequest) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            userScale = (userScale * detector.scaleFactor).coerceIn(.1f, 20f)
            clamp()
            invalidate()
            requestDetailUpdate()
            return true
        }
    })

    fun setImageSource(width: Int, height: Int, orientation: Int, enableDetail: Boolean) {
        sourceOrientation = orientation
        rawWidth = width.coerceAtLeast(1)
        rawHeight = height.coerceAtLeast(1)
        val oriented = orientedSize(rawWidth, rawHeight, orientation)
        sourceWidth = oriented.first.coerceAtLeast(1)
        sourceHeight = oriented.second.coerceAtLeast(1)
        detailEnabled = enableDetail
        updateFitScale()
    }

    fun setDrawable(value: Drawable?) {
        drawable = value
        userScale = 1f
        tx = 0f
        ty = 0f
        clearDetailBitmap()
        updateFitScale()
        invalidate()
    }

    fun hasDetailFor(request: DetailRequest): Boolean =
        detailBitmap != null && detailSample <= request.sample &&
            detailSource.left <= request.sourceRect.left && detailSource.top <= request.sourceRect.top &&
            detailSource.right >= request.sourceRect.right && detailSource.bottom >= request.sourceRect.bottom

    fun setDetailBitmap(value: Bitmap, sourceRect: Rect, sample: Int) {
        clearDetailBitmap()
        detailBitmap = value
        detailSource = Rect(sourceRect)
        detailSample = sample.coerceAtLeast(1)
        invalidate()
    }

    private fun clearDetailBitmap() {
        detailBitmap?.let { if (!it.isRecycled) it.recycle() }
        detailBitmap = null
        detailSource.setEmpty()
        detailSample = Int.MAX_VALUE
    }

    private fun updateFitScale() {
        if (width > 0 && height > 0 && sourceWidth > 0 && sourceHeight > 0) {
            fitScale = min(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
            clamp()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFitScale()
        requestDetailUpdate()
    }

    fun isAtFitScale() = userScale <= 1.015f

    fun zoomBy(delta: Float) {
        userScale = (userScale * exp(delta * .055f)).coerceIn(.1f, 20f)
        clamp()
        invalidate()
        requestDetailUpdate()
    }

    fun requestDetailUpdate() {
        if (!detailEnabled || sourceWidth <= 1 || sourceHeight <= 1 || width <= 0 || height <= 0) return
        if (userScale <= 1.12f) {
            if (detailBitmap != null) {
                clearDetailBitmap()
                invalidate()
            }
            return
        }
        val totalScale = (fitScale * userScale).coerceAtLeast(.0001f)
        val viewportWidth = width / totalScale
        val viewportHeight = height / totalScale
        val centerX = sourceWidth / 2f - tx / totalScale
        val centerY = sourceHeight / 2f - ty / totalScale
        val overscanWidth = viewportWidth * REGION_OVERSCAN
        val overscanHeight = viewportHeight * REGION_OVERSCAN
        val displayRect = ImageRect(
            (centerX - overscanWidth / 2f).roundToInt().coerceIn(0, sourceWidth - 1),
            (centerY - overscanHeight / 2f).roundToInt().coerceIn(0, sourceHeight - 1),
            (centerX + overscanWidth / 2f).roundToInt().coerceIn(1, sourceWidth),
            (centerY + overscanHeight / 2f).roundToInt().coerceIn(1, sourceHeight)
        )
        if (displayRect.width <= 0 || displayRect.height <= 0) return
        val rawRectModel = orientedRectToRaw(displayRect, rawWidth, rawHeight, sourceOrientation)
        val rawRect = Rect(
            rawRectModel.left.coerceIn(0, rawWidth - 1),
            rawRectModel.top.coerceIn(0, rawHeight - 1),
            rawRectModel.right.coerceIn(1, rawWidth),
            rawRectModel.bottom.coerceIn(1, rawHeight)
        )
        val sample = gallerySampleSize(rawRect.width(), rawRect.height(), REGION_PIXEL_BUDGET)
        val request = DetailRequest(++detailToken, rawRect, sample)
        if (!hasDetailFor(request)) onViewportChanged?.invoke(request)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateFitScale()
        canvas.save()
        canvas.translate(width / 2f + tx, height / 2f + ty)
        canvas.scale(fitScale * userScale, fitScale * userScale)
        drawPreview(canvas)
        drawDetail(canvas)
        canvas.restore()
    }

    private fun drawPreview(canvas: Canvas) {
        val preview = drawable ?: return
        preview.setBounds(-sourceWidth / 2, -sourceHeight / 2, sourceWidth / 2, sourceHeight / 2)
        preview.draw(canvas)
    }

    private fun drawDetail(canvas: Canvas) {
        val bitmap = detailBitmap ?: return
        if (detailSource.isEmpty || bitmap.isRecycled) return
        val srcPoints = floatArrayOf(
            0f, 0f, bitmap.width.toFloat(), 0f,
            bitmap.width.toFloat(), bitmap.height.toFloat(), 0f, bitmap.height.toFloat()
        )
        val rawPoints = floatArrayOf(
            detailSource.left.toFloat(), detailSource.top.toFloat(),
            detailSource.right.toFloat(), detailSource.top.toFloat(),
            detailSource.right.toFloat(), detailSource.bottom.toFloat(),
            detailSource.left.toFloat(), detailSource.bottom.toFloat()
        )
        val displayPoints = FloatArray(8)
        for (i in 0 until 4) {
            val mapped = mapRawPointToOriented(
                rawPoints[i * 2], rawPoints[i * 2 + 1], rawWidth.toFloat(), rawHeight.toFloat(), sourceOrientation
            )
            displayPoints[i * 2] = mapped.first - sourceWidth / 2f
            displayPoints[i * 2 + 1] = mapped.second - sourceHeight / 2f
        }
        val matrix = Matrix()
        if (matrix.setPolyToPoly(srcPoints, 0, displayPoints, 0, 4)) {
            canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = userScale > 1.015f
            }
            MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress) {
                tx += event.x - lastX
                ty += event.y - lastY
                lastX = event.x
                lastY = event.y
                clamp()
                invalidate()
                requestDetailUpdate()
            }
            MotionEvent.ACTION_UP -> { dragging = false; performClick() }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun clamp() {
        val scale = fitScale * userScale
        val maxX = max(0f, (sourceWidth * scale - width) / 2f)
        val maxY = max(0f, (sourceHeight * scale - height) / 2f)
        tx = tx.coerceIn(-maxX, maxX)
        ty = ty.coerceIn(-maxY, maxY)
    }

    override fun onDetachedFromWindow() {
        clearDetailBitmap()
        super.onDetachedFromWindow()
    }
}
