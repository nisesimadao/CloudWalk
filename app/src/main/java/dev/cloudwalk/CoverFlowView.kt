package dev.cloudwalk

import android.content.Context
import android.annotation.SuppressLint
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
            scrollOffset = selectedIndex * pageSpacing
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
    var onTrackLongClick: ((Track) -> Unit)? = null
    var playingTrackId: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val coverSize = 168f * density
    private val pageSpacing = 92f * density
    private val visualSpacing = 112f * density
    private val sideScale = 0.83f
    private val maxAngle = 42f
    private val artworkTargetPx = min(320, (168f * density).toInt())
    private val previewArtworkTargetPx = min(96, artworkTargetPx)
    private var scrollOffset = 0f
    private var wasDragging = false
    private var flingHandled = false
    private var lastSelectionHapticAt = 0L
    private var lastPrefetchCenter = -99
    private var lastPrefetchHighQuality = false
    private var windowActive = false
    private var surfaceActive = true
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
            flingHandled = false
            if (!scroller.isFinished) scroller.abortAnimation()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (abs(distanceX) > abs(distanceY) * 0.72f) {
                wasDragging = true
                // 0.82 keeps the rendered cover almost 1:1 with the finger because
                // visualSpacing is wider than pageSpacing. Only resist at the real ends.
                val proposed = scrollOffset + distanceX * 0.82f
                val min = minOffset()
                val max = maxOffset()
                val overscroll = pageSpacing * 0.28f
                scrollOffset = when {
                    proposed < min -> (min + (proposed - min) * 0.26f).coerceAtLeast(min - overscroll)
                    proposed > max -> (max + (proposed - max) * 0.26f).coerceAtMost(max + overscroll)
                    else -> proposed
                }
                updateSelectionFromOffset()
                postInvalidateOnAnimation()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (tracks.size < 2 || abs(velocityX) <= abs(velocityY)) return false
            flingHandled = true

            // Project from where the cover actually is now, not where the gesture began.
            // This avoids the old "drag two covers, flick, then jump backwards" behavior.
            val currentPage = scrollOffset / pageSpacing
            val direction = if (velocityX < 0f) 1 else -1
            val velocityDp = velocityX / density
            val maxMomentumPages = if (resources.configuration.screenWidthDp <= 400) 3f else 4f
            val projectedMomentum = (-velocityDp / 1350f).coerceIn(-maxMomentumPages, maxMomentumPages)
            val nearest = currentPage.roundToInt()
            var target = (currentPage + projectedMomentum).roundToInt()
            if (target == nearest && abs(velocityDp) >= 550f) target = nearest + direction
            target = target.coerceIn(0, tracks.lastIndex)

            // Momentum scrolling should not buzz once per intermediate cover.
            wasDragging = false
            val pages = abs(target - currentPage)
            val duration = (150f + pages * 38f).roundToInt().coerceIn(150, 285)
            settleToIndex(target, duration)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (tracks.isEmpty() || wasDragging) return true
            val index = indexAt(e.x)
            if (index == selectedIndex) performClick() else setSelected(index, true)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (tracks.isEmpty() || wasDragging) return
            val index = indexAt(e.x)
            if (index != selectedIndex) setSelected(index, false)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onTrackLongClick?.invoke(tracks[index])
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!flingHandled && scroller.isFinished) snapToNearest()
            flingHandled = false
            removeCallbacks(promoteArtworkRunnable)
            postDelayed(promoteArtworkRunnable, 190L)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        val track = tracks.getOrNull(selectedIndex) ?: return false
        onTrackClick?.invoke(track)
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windowActive = windowVisibility == View.VISIBLE
        if (windowActive) {
            lastPrefetchCenter = -99
            lastPrefetchHighQuality = false
            prefetchVisible(highQuality = true)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowActive = visibility == View.VISIBLE
        if (!windowActive) {
            removeCallbacks(promoteArtworkRunnable)
            if (!scroller.isFinished) scroller.abortAnimation()
            wasDragging = false
        } else if (isAttachedToWindow) {
            lastPrefetchCenter = -99
            lastPrefetchHighQuality = false
            prefetchVisible(highQuality = true)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        windowActive = false
        removeCallbacks(promoteArtworkRunnable)
        if (!scroller.isFinished) scroller.abortAnimation()
        super.onDetachedFromWindow()
    }

    fun setSurfaceActive(active: Boolean) {
        if (surfaceActive == active) return
        surfaceActive = active
        if (!active) {
            removeCallbacks(promoteArtworkRunnable)
            if (!scroller.isFinished) scroller.abortAnimation()
            wasDragging = false
            return
        }
        if (windowActive) {
            lastPrefetchCenter = -99
            lastPrefetchHighQuality = false
            prefetchVisible(highQuality = true)
            invalidate()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currX.toFloat().coerceIn(minOffset(), maxOffset())
            updateSelectionFromOffset()
            postInvalidateOnAnimation()
        }
        // Do not call snapToNearest() here. computeScroll() is invoked while the view
        // is otherwise idle too, including between MOVE events, which used to start a
        // competing snap animation and pull the cover back under the user's finger.
        // ACTION_UP/ACTION_CANCEL already own snapping when a drag actually ends.
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tracks.isEmpty()) return

        val centerX = width * 0.5f
        val centerY = height * 0.48f
        val centerIndex = (scrollOffset / pageSpacing).roundToInt().coerceIn(0, tracks.lastIndex)
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
        val page = scrollOffset / pageSpacing
        val normalized = (index - page).coerceIn(-3f, 3f)
        val depth = abs(normalized)
        val scale = 1f - min(depth, 1.5f) * (1f - sideScale)
        val angle = -normalized.coerceIn(-1f, 1f) * maxAngle
        val track = tracks[index]
        drawCover(
            canvas,
            index,
            track,
            centerX + normalized * visualSpacing,
            centerY + min(depth, 1f) * 5f * density,
            scale,
            angle,
            index == selectedIndex,
            track.id == playingTrackId
        )
    }

    private fun drawCover(canvas: Canvas, index: Int, track: Track, cx: Float, cy: Float, scale: Float, angle: Float, selected: Boolean, playing: Boolean) {
        val size = coverSize * scale
        val half = size * 0.5f
        val turn = (angle / maxAngle).coerceIn(-1f, 1f)

        buildPath(coverPath, cx, cy, half, turn, 0f)
        val drawShadow = selected || playing || resources.configuration.screenWidthDp > 400
        if (drawShadow) {
            buildPath(shadowPath, cx, cy, half, turn, 7f * density)
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
            fillCoverPoints(dstPts, cx, cy, half, turn, 0f)
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
            paint.color = Color.argb(if (playing) 38 else 72, 0, 0, 0)
            canvas.drawPath(coverPath, paint)
        }

        if (playing) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = Color.rgb(255, 106, 0)
            canvas.drawPath(coverPath, paint)
        }
        if (selected) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.15f * density
            paint.color = Color.argb(210, 255, 255, 255)
            canvas.drawPath(coverPath, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun buildPath(path: Path, cx: Float, cy: Float, half: Float, turn: Float, yOffset: Float) {
        fillCoverPoints(dstPts, cx, cy, half, turn, yOffset)
        path.rewind()
        path.moveTo(dstPts[0], dstPts[1])
        path.lineTo(dstPts[2], dstPts[3])
        path.lineTo(dstPts[4], dstPts[5])
        path.lineTo(dstPts[6], dstPts[7])
        path.close()
    }

    private fun fillCoverPoints(out: FloatArray, cx: Float, cy: Float, half: Float, turn: Float, yOffset: Float) {
        val amount = abs(turn)
        val farXInset = half * 0.29f * amount
        val farYInset = half * 0.12f * amount
        val leftIsFar = turn > 0f

        if (leftIsFar) {
            out[0] = cx - half + farXInset; out[1] = cy - half + farYInset + yOffset
            out[2] = cx + half;             out[3] = cy - half + yOffset
            out[4] = cx + half;             out[5] = cy + half + yOffset
            out[6] = cx - half + farXInset; out[7] = cy + half - farYInset + yOffset
        } else if (turn < 0f) {
            out[0] = cx - half;             out[1] = cy - half + yOffset
            out[2] = cx + half - farXInset; out[3] = cy - half + farYInset + yOffset
            out[4] = cx + half - farXInset; out[5] = cy + half - farYInset + yOffset
            out[6] = cx - half;             out[7] = cy + half + yOffset
        } else {
            out[0] = cx - half; out[1] = cy - half + yOffset
            out[2] = cx + half; out[3] = cy - half + yOffset
            out[4] = cx + half; out[5] = cy + half + yOffset
            out[6] = cx - half; out[7] = cy + half + yOffset
        }
    }

    private fun prefetchVisible(highQuality: Boolean = !wasDragging && scroller.isFinished) {
        if (!surfaceActive || !windowActive || tracks.isEmpty()) return
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
        resources.configuration.screenWidthDp <= 360 -> 1
        resources.configuration.screenWidthDp <= 400 -> 2
        else -> 4
    }


    private fun indexAt(x: Float): Int {
        val center = width * 0.5f
        val page = scrollOffset / pageSpacing
        val raw = page + (x - center) / visualSpacing
        return floor(raw + 0.5f).toInt().coerceIn(0, tracks.lastIndex)
    }

    private fun minOffset() = 0f
    private fun maxOffset() = max(0f, (tracks.size - 1) * pageSpacing)

    private fun updateSelectionFromOffset() {
        if (tracks.isEmpty()) return
        val next = (scrollOffset / pageSpacing).roundToInt().coerceIn(0, tracks.lastIndex)
        if (next != selectedIndex) {
            selectedIndex = next
            if (wasDragging) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastSelectionHapticAt >= 55L) {
                    lastSelectionHapticAt = now
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            prefetchVisible(highQuality = false)
            onSelectionChanged?.invoke(next, tracks[next])
        }
    }

    private fun snapToNearest() {
        if (tracks.isEmpty()) return
        val nearest = (scrollOffset / pageSpacing).roundToInt().coerceIn(0, tracks.lastIndex)
        settleToIndex(nearest, 145)
    }

    private fun settleToIndex(index: Int, durationMs: Int) {
        if (tracks.isEmpty()) return
        val safe = index.coerceIn(0, tracks.lastIndex)
        val target = safe * pageSpacing
        val dx = target - scrollOffset
        if (abs(dx) < 1f) {
            scrollOffset = target
            updateSelectionFromOffset()
            invalidate()
            return
        }
        scroller.startScroll(scrollOffset.toInt(), 0, dx.toInt(), 0, durationMs)
        postInvalidateOnAnimation()
    }

    fun setSelected(index: Int, animate: Boolean) {
        if (tracks.isEmpty()) return
        val safe = index.coerceIn(0, tracks.lastIndex)
        val changed = selectedIndex != safe
        lastPrefetchHighQuality = false
        val target = safe * pageSpacing
        if (animate) {
            wasDragging = false
            flingHandled = false
            settleToIndex(safe, 170)
            removeCallbacks(promoteArtworkRunnable)
            postDelayed(promoteArtworkRunnable, 190L)
            // updateSelectionFromOffset() owns selection callbacks while moving.
            return
        }

        if (!scroller.isFinished) scroller.abortAnimation()
        selectedIndex = safe
        scrollOffset = target
        prefetchVisible(highQuality = true)
        invalidate()
        if (changed) onSelectionChanged?.invoke(safe, tracks[safe])
    }
}