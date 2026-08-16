package dev.cloudwalk

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CoverFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var tracks: List<Track> = emptyList()
        set(value) {
            field = value
            selectedIndex = selectedIndex.coerceIn(0, max(0, value.lastIndex))
            scrollOffset = selectedIndex * spacing
            rebuildColorCache()
            prefetchVisible()
            invalidate()
        }

    var artworkCache: ArtworkCache? = null
        set(value) {
            field = value
            prefetchVisible()
            invalidate()
        }

    var selectedIndex: Int = 0
        private set

    var onSelectionChanged: ((Int, Track) -> Unit)? = null
    var onTrackClick: ((Track) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val coverSize = 168f * density
    private val spacing = 66f * density
    private val sideScale = 0.77f
    private val maxAngle = 58f
    private val artworkTargetPx = min(320, (168f * density).toInt())
    private val previewArtworkTargetPx = min(96, artworkTargetPx)
    private var scrollOffset = 0f
    private var wasDragging = false
    private var lastPrefetchCenter = -99
    private var lastPrefetchHighQuality = false
    private val promoteArtworkRunnable = object : Runnable {
        override fun run() {
            if (!scroller.isFinished) {
                postDelayed(this, 120L)
                return
            }
            wasDragging = false
            lastPrefetchHighQuality = false
            prefetchVisible(highQuality = true)
        }
    }
    var lowPowerMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            lastPrefetchCenter = -99
            lastPrefetchHighQuality = false
            prefetchVisible(highQuality = true)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private val scroller = OverScroller(context)
    private val coverPath = Path()
    private val shadowPath = Path()
    private val imageMatrix = Matrix()
    private val srcPts = FloatArray(8)
    private val dstPts = FloatArray(8)
    private var colorA = IntArray(0)
    private var colorB = IntArray(0)

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            removeCallbacks(promoteArtworkRunnable)
            wasDragging = false
            if (!scroller.isFinished) scroller.abortAnimation()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (abs(distanceX) > abs(distanceY) * 0.7f) {
                wasDragging = true
                scrollOffset = (scrollOffset + distanceX).coerceIn(minOffset(), maxOffset())
                updateSelectionFromOffset()
                postInvalidateOnAnimation()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (tracks.size < 2) return true
            wasDragging = true
            scroller.fling(
                scrollOffset.toInt(), 0,
                (-velocityX * 0.72f).toInt(), 0,
                minOffset().toInt(), maxOffset().toInt(),
                0, 0
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (tracks.isEmpty() || wasDragging) return true
            val center = width / 2f
            val raw = (e.x - center + scrollOffset) / spacing
            val index = floor(raw + 0.5f).toInt().coerceIn(0, tracks.lastIndex)
            if (index == selectedIndex) onTrackClick?.invoke(tracks[index]) else setSelected(index, true)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (scroller.isFinished) snapToNearest()
            removeCallbacks(promoteArtworkRunnable)
            postDelayed(promoteArtworkRunnable, 220L)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currX.toFloat().coerceIn(minOffset(), maxOffset())
            updateSelectionFromOffset()
            postInvalidateOnAnimation()
        } else {
            snapToNearest()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tracks.isEmpty()) return

        val centerX = width * 0.5f
        val centerY = height * 0.48f
        val centerIndex = (scrollOffset / spacing).roundToInt().coerceIn(0, tracks.lastIndex)
        val radius = visibleRadius()
        val start = max(0, centerIndex - radius)
        val end = min(tracks.lastIndex, centerIndex + radius)

        var distance = radius
        while (distance >= 1) {
            val left = centerIndex - distance
            val right = centerIndex + distance
            if (left >= start) drawIndex(canvas, left, centerX, centerY)
            if (right <= end) drawIndex(canvas, right, centerX, centerY)
            distance--
        }
        drawIndex(canvas, centerIndex, centerX, centerY)
    }

    private fun drawIndex(canvas: Canvas, index: Int, centerX: Float, centerY: Float) {
        val delta = index * spacing - scrollOffset
        val normalized = (delta / spacing).coerceIn(-3f, 3f)
        val depth = abs(normalized)
        val scale = 1f - min(depth, 1.5f) * (1f - sideScale)
        val angle = -normalized.coerceIn(-1f, 1f) * maxAngle
        drawCover(canvas, index, tracks[index], centerX + delta, centerY, scale, angle, index == selectedIndex)
    }

    private fun drawCover(canvas: Canvas, index: Int, track: Track, cx: Float, cy: Float, scale: Float, angle: Float, selected: Boolean) {
        val size = coverSize * scale
        val half = size * 0.5f
        val skew = (angle / maxAngle) * half * 0.28f

        buildPath(coverPath, cx, cy, half, skew, 0f)
        val drawShadow = selected || resources.configuration.screenWidthDp > 400
        if (drawShadow) {
            buildPath(shadowPath, cx, cy, half, skew, 7f * density)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(if (selected) 74 else 38, 0, 0, 0)
            canvas.drawPath(shadowPath, paint)
        }

        val bitmap = artworkCache?.peek(track.artworkUrl, artworkTargetPx)
        if (bitmap != null && !bitmap.isRecycled) {
            paint.color = Color.WHITE
            paint.alpha = 255
            srcPts[0] = 0f; srcPts[1] = 0f
            srcPts[2] = bitmap.width.toFloat(); srcPts[3] = 0f
            srcPts[4] = bitmap.width.toFloat(); srcPts[5] = bitmap.height.toFloat()
            srcPts[6] = 0f; srcPts[7] = bitmap.height.toFloat()
            dstPts[0] = cx - half + skew; dstPts[1] = cy - half
            dstPts[2] = cx + half + skew; dstPts[3] = cy - half
            dstPts[4] = cx + half - skew; dstPts[5] = cy + half
            dstPts[6] = cx - half - skew; dstPts[7] = cy + half
            imageMatrix.reset()
            imageMatrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
            canvas.drawBitmap(bitmap, imageMatrix, paint)
        } else {
            paint.color = colorA.getOrElse(index) { colorFor(track.id, 0) }
            canvas.drawPath(coverPath, paint)
            val b = colorB.getOrElse(index) { colorFor(track.id, 1) }
            paint.color = Color.argb(38, Color.red(b), Color.green(b), Color.blue(b))
            canvas.drawPath(coverPath, paint)

            textPaint.textSize = (if (selected) 30f else 22f) * density * scale
            val initial = track.title.firstOrNull()?.uppercaseChar()?.toString() ?: "♪"
            canvas.drawText(initial, cx, cy + textPaint.textSize * 0.35f, textPaint)
        }

        if (!selected) {
            paint.color = Color.argb(72, 0, 0, 0)
            canvas.drawPath(coverPath, paint)
        }

        if (selected) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.25f * density
            paint.color = Color.argb(150, 255, 255, 255)
            canvas.drawPath(coverPath, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun buildPath(path: Path, cx: Float, cy: Float, half: Float, skew: Float, yOffset: Float) {
        path.rewind()
        path.moveTo(cx - half + skew, cy - half + yOffset)
        path.lineTo(cx + half + skew, cy - half + yOffset)
        path.lineTo(cx + half - skew, cy + half + yOffset)
        path.lineTo(cx - half - skew, cy + half + yOffset)
        path.close()
    }

    private fun prefetchVisible(highQuality: Boolean = !wasDragging && scroller.isFinished) {
        if (tracks.isEmpty()) return
        val cache = artworkCache ?: return
        val center = selectedIndex.coerceIn(0, tracks.lastIndex)
        if (center == lastPrefetchCenter && (!highQuality || lastPrefetchHighQuality)) return
        lastPrefetchCenter = center
        if (highQuality) lastPrefetchHighQuality = true else lastPrefetchHighQuality = false
        val radius = visibleRadius()
        val start = max(0, center - radius)
        val end = min(tracks.lastIndex, center + radius)
        for (i in start..end) {
            val url = tracks[i].artworkUrl ?: continue
            val target = if (highQuality && i == center) artworkTargetPx else previewArtworkTargetPx
            cache.prefetch(url, target) { postInvalidateOnAnimation() }
        }
    }

    private fun rebuildColorCache() {
        colorA = IntArray(tracks.size)
        colorB = IntArray(tracks.size)
        for (i in tracks.indices) {
            colorA[i] = colorFor(tracks[i].id, 0)
            colorB[i] = colorFor(tracks[i].id, 1)
        }
    }

    private fun colorFor(seed: String, offset: Int): Int {
        val h = seed.hashCode() * 31 + offset * 97
        return Color.rgb(
            70 + abs(h shr 16) % 150,
            70 + abs(h shr 8) % 150,
            70 + abs(h) % 150
        )
    }

    private fun visibleRadius(): Int = when {
        lowPowerMode -> 1
        resources.configuration.screenWidthDp <= 400 -> 2
        else -> 4
    }

    private fun minOffset() = 0f
    private fun maxOffset() = max(0f, (tracks.size - 1) * spacing)

    private fun updateSelectionFromOffset() {
        if (tracks.isEmpty()) return
        val next = (scrollOffset / spacing).roundToInt().coerceIn(0, tracks.lastIndex)
        if (next != selectedIndex) {
            selectedIndex = next
            if (wasDragging) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            prefetchVisible(highQuality = false)
            onSelectionChanged?.invoke(next, tracks[next])
        }
    }

    private fun snapToNearest() {
        if (tracks.isEmpty()) return
        val target = selectedIndex * spacing
        if (abs(scrollOffset - target) < 1f) return
        scroller.startScroll(scrollOffset.toInt(), 0, (target - scrollOffset).toInt(), 0, 155)
        postInvalidateOnAnimation()
    }

    fun setSelected(index: Int, animate: Boolean) {
        if (tracks.isEmpty()) return
        val safe = index.coerceIn(0, tracks.lastIndex)
        selectedIndex = safe
        lastPrefetchHighQuality = false
        prefetchVisible(highQuality = !animate)
        val target = safe * spacing
        if (animate) {
            wasDragging = true
            scroller.startScroll(scrollOffset.toInt(), 0, (target - scrollOffset).toInt(), 0, 180)
            removeCallbacks(promoteArtworkRunnable)
            postDelayed(promoteArtworkRunnable, 220L)
            postInvalidateOnAnimation()
        } else {
            scrollOffset = target
            invalidate()
        }
        onSelectionChanged?.invoke(safe, tracks[safe])
    }
}