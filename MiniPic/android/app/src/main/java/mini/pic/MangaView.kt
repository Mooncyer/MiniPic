package mini.pic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.widget.OverScroller
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface ViewerSurface {
    fun isAtFitScale(): Boolean
    fun zoomBy(delta: Float)
}

data class MangaPageMetrics(val width: Int, val height: Int)

class MangaView(
    context: android.content.Context,
    private val pages: List<Pic>,
    private val executor: ExecutorService,
    private val readMetrics: (Pic) -> MangaPageMetrics?,
    private val loadBitmap: (Pic, Int) -> Bitmap?
) : View(context), ViewerSurface {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val metrics = arrayOfNulls<MangaPageMetrics>(pages.size)
    private val pageTops = FloatArray(pages.size + 1)
    private val bitmaps = hashMapOf<Int, Bitmap>()
    private val bitmapScales = hashMapOf<Int, Float>()
    private val loading = hashSetOf<Int>()
    private var contentOffset = 0f
    private var zoom = 1f
    private var panX = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var generation = 0L
    private var sizesRequested = false
    private var pendingMetrics = 0
    private var layoutReady = false
    var onPageChanged: ((Int) -> Unit)? = null
    private var currentPage = -1
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(.5f, 4f)
            clampPosition()
            invalidate()
            scheduleVisibleLoads()
            return true
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestMetrics()
        clampPosition()
        scheduleVisibleLoads()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        requestMetrics()
        if (!layoutReady) {
            paint.color = Color.WHITE
            paint.textSize = 16f * resources.displayMetrics.density
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("正在加载漫画…", width / 2f, height / 2f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.LEFT
            return
        }
        val visibleTop = contentOffset - height
        val visibleBottom = contentOffset + height * 2f
        var visiblePage = -1
        val first = findFirstIntersecting((visibleTop / zoom).coerceAtLeast(0f))
        var index = first
        while (index < pages.size && pageTops[index] * zoom <= visibleBottom) {
            val top = pageTops[index] * zoom
            val bottom = pageTops[index + 1] * zoom
            if (bottom >= contentOffset && visiblePage < 0) visiblePage = index
            bitmaps[index]?.let { bitmap ->
                val pageWidth = width * zoom
                val left = (width - pageWidth) / 2f + panX
                canvas.drawBitmap(bitmap, null, RectF(left, top - contentOffset, left + pageWidth, bottom - contentOffset), paint)
            }
            index++
        }
        if (visiblePage != currentPage) {
            currentPage = visiblePage
            if (visiblePage >= 0) onPageChanged?.invoke(visiblePage)
        }
        recycleFarBitmaps(visiblePage)
        scheduleVisibleLoads()
    }

    override fun isAtFitScale(): Boolean = false

    override fun zoomBy(delta: Float) {
        scroller.abortAnimation()
        zoom = (zoom * exp(delta * .055f)).coerceIn(.5f, 4f)
        clampPosition()
        invalidate()
        scheduleVisibleLoads()
    }

    fun scrollByPixels(delta: Int) {
        scrollByDistance(delta.toFloat())
    }

    fun scrollByDistance(delta: Float) {
        if (delta == 0f) return
        scroller.abortAnimation()
        contentOffset += delta
        clampPosition()
        invalidate()
        scheduleVisibleLoads()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress) {
                velocityTracker?.addMovement(event)
                val dx = event.x - lastX
                val dy = event.y - lastY
                panX += dx
                contentOffset -= dy
                lastX = event.x
                lastY = event.y
                clampPosition()
                invalidate()
                scheduleVisibleLoads()
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocityY = velocityTracker?.yVelocity?.toInt() ?: 0
                velocityTracker?.recycle()
                velocityTracker = null
                dragging = false
                val maxOffset = max(0f, totalHeight() - height).roundToInt()
                if (abs(velocityY) > 200 && maxOffset > 0) {
                    scroller.fling(0, contentOffset.roundToInt(), 0, -velocityY, 0, 0, 0, maxOffset)
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                dragging = false
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            contentOffset = scroller.currY.toFloat()
            clampPosition()
            scheduleVisibleLoads()
            postInvalidateOnAnimation()
        }
    }

    private fun requestMetrics() {
        if (sizesRequested || pages.isEmpty()) return
        sizesRequested = true
        pendingMetrics = pages.size
        val token = generation
        pages.forEachIndexed { index, page ->
            executor.execute {
                val value = try { readMetrics(page) } catch (_: Exception) { null }
                if (token != generation) return@execute
                post {
                    if (token != generation || !isAttachedToWindow) return@post
                    metrics[index] = value?.takeIf { it.width > 0 && it.height > 0 } ?: MangaPageMetrics(1, 1)
                    pendingMetrics--
                    if (pendingMetrics == 0) {
                        buildPageOffsets()
                        layoutReady = true
                        clampPosition()
                    }
                    invalidate()
                }
            }
        }
    }

    private fun buildPageOffsets() {
        pageTops[0] = 0f
        for (index in pages.indices) pageTops[index + 1] = pageTops[index] + pageHeight(metrics[index])
    }

    private fun findFirstIntersecting(contentY: Float): Int {
        var low = 0
        var high = pages.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (pageTops[mid + 1] < contentY) low = mid + 1 else high = mid
        }
        return low.coerceAtMost(pages.size)
    }


    private fun scheduleVisibleLoads() {
        if (width <= 0 || pages.isEmpty() || !layoutReady) return
        val minTop = (contentOffset - height).coerceAtLeast(0f) / zoom
        val maxBottom = (contentOffset + height * 2f) / zoom
        val first = findFirstIntersecting(minTop)
        var index = first
        while (index < pages.size && pageTops[index] <= maxBottom) {
            requestBitmap(index)
            index++
        }
    }

    private fun requestBitmap(index: Int) {
        val targetScale = zoom
        if (loading.contains(index)) return
        val existingScale = bitmapScales[index]
        if (bitmaps[index] != null && existingScale != null && existingScale >= targetScale * .85f) return
        loading += index
        val token = generation
        val page = pages[index]
        val targetWidth = (width * targetScale).roundToInt().coerceIn(1, 2400)
        executor.execute {
            val bitmap = try { loadBitmap(page, targetWidth) } catch (_: Exception) { null }
            post {
                loading -= index
                if (token != generation || !isAttachedToWindow) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap != null) {
                    if (abs(index - currentPage) > 3) {
                        bitmap.recycle()
                        return@post
                    }
                    bitmaps[index]?.recycle()
                    bitmaps[index] = bitmap
                    bitmapScales[index] = targetScale
                    invalidate()
                }
            }
        }
    }

    private fun recycleFarBitmaps(center: Int) {
        if (center < 0) return
        val iterator = bitmaps.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (abs(entry.key - center) > 3) {
                if (!entry.value.isRecycled) entry.value.recycle()
                bitmapScales.remove(entry.key)
                iterator.remove()
            }
        }
    }

    private fun pageHeight(value: MangaPageMetrics?): Float {
        if (width <= 0) return 1f
        val ratio = if (value == null || value.width <= 0) 1f else value.height.toFloat() / value.width
        return max(1f, width * ratio)
    }

    private fun totalHeight(): Float = if (layoutReady) pageTops.last() * zoom else 0f

    private fun clampPosition() {
        val maxOffset = max(0f, totalHeight() - height)
        contentOffset = contentOffset.coerceIn(0f, maxOffset)
        val pageWidth = width * zoom
        val maxPan = max(0f, (pageWidth - width) / 2f)
        panX = panX.coerceIn(-maxPan, maxPan)
    }

    override fun onDetachedFromWindow() {
        generation++
        for (bitmap in bitmaps.values) bitmap.recycle()
        bitmaps.clear()
        bitmapScales.clear()
        loading.clear()
        super.onDetachedFromWindow()
    }
}
