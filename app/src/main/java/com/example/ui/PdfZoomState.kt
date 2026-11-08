package com.example.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pinch-zoom / pan state of the PDF page list.
 *
 * The list is zoomed visually with a graphicsLayer scaled about its top-left
 * corner and then translated: `screen = content * zoom + pan`. The pan keeps
 * the zoomed content covering the viewport; vertical movement beyond that is
 * turned into list scrolling divided by the zoom, so the content always moves
 * exactly with the fingers. (Before, the list scrolled in unzoomed pixels
 * under a scaled layer, so at 2.2x it moved 2.2x faster than the finger, and
 * pinches were taken by the list as a scroll + fling.)
 */
@Stable
class PdfZoomState(
    private val listState: LazyListState,
    private val scope: CoroutineScope
) {
    var zoom by mutableFloatStateOf(MIN_ZOOM)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set

    /** Last pointer-down position in viewport coordinates (focus of double-tap zoom). */
    var lastTouchPosition: Offset = Offset.Unspecified
        internal set

    private var viewportSize = IntSize.Zero
    private var motionJob: Job? = null

    val isZoomed: Boolean get() = zoom > MIN_ZOOM

    fun onViewportSizeChanged(size: IntSize) {
        viewportSize = size
        pan = clampPan(pan, zoom)
    }

    /**
     * Applies one pinch step: zooms by [zoomChange] keeping the content under
     * [focal] in place, then follows the fingers by [panChange].
     */
    fun applyTransform(focal: Offset, panChange: Offset, zoomChange: Float) {
        val oldZoom = zoom
        var newZoom = (oldZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Snap tiny residues so "not zoomed" is exact (it re-enables list scrolling).
        if (newZoom - MIN_ZOOM < ZOOM_SNAP) newZoom = MIN_ZOOM
        val scale = newZoom / oldZoom
        val desired = focal - (focal - pan) * scale + panChange
        zoom = newZoom
        val clamped = clampPan(desired, newZoom)
        // Vertical movement the viewport window cannot absorb scrolls the list,
        // so zooming near the top/bottom edge or panning with two fingers
        // keeps the content under the fingers.
        val overflowY = desired.y - clamped.y
        if (overflowY != 0f) listState.dispatchRawDelta(-overflowY / newZoom)
        pan = clamped
    }

    /** One-finger drag while zoomed, in screen pixels. */
    fun panBy(delta: Offset) {
        val z = zoom
        val consumed = if (delta.y != 0f) listState.dispatchRawDelta(-delta.y / z) else 0f
        // What the list could not scroll (document start/end) moves the window.
        val remainingY = delta.y + consumed * z
        pan = clampPan(Offset(pan.x + delta.x, pan.y + remainingY), z)
    }

    fun stopMotion() {
        motionJob?.cancel()
        motionJob = null
    }

    /** Continues a zoomed one-finger drag with inertia (list vertically, window horizontally). */
    fun fling(velocity: Velocity, decay: DecayAnimationSpec<Float>) {
        stopMotion()
        val z = zoom
        motionJob = scope.launch {
            launch {
                val contentVelocity = -velocity.y / z
                if (abs(contentVelocity) > MIN_FLING_VELOCITY) {
                    listState.scroll {
                        var last = 0f
                        AnimationState(initialValue = 0f, initialVelocity = contentVelocity).animateDecay(decay) {
                            val delta = value - last
                            last = value
                            val consumed = scrollBy(delta)
                            if (abs(delta - consumed) > 0.5f) cancelAnimation()
                        }
                    }
                }
            }
            launch {
                if (abs(velocity.x) > MIN_FLING_VELOCITY) {
                    var last = 0f
                    AnimationState(initialValue = 0f, initialVelocity = velocity.x).animateDecay(decay) {
                        val delta = value - last
                        last = value
                        val before = pan.x
                        pan = clampPan(Offset(pan.x + delta, pan.y), zoom)
                        if (pan.x == before) cancelAnimation()
                    }
                }
            }
        }
    }

    /** Double tap: animates to [DOUBLE_TAP_ZOOM] around the tap, or back to 1x. */
    fun toggleDoubleTapZoom() {
        val focal = lastTouchPosition.takeIf { it.isSpecified }
            ?: Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val target = if (zoom > DOUBLE_TAP_ZOOM_OUT_THRESHOLD) MIN_ZOOM else DOUBLE_TAP_ZOOM
        stopMotion()
        motionJob = scope.launch {
            animate(
                initialValue = zoom,
                targetValue = target,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            ) { value, _ ->
                applyTransform(focal, Offset.Zero, value / zoom)
            }
        }
    }

    private fun clampPan(p: Offset, z: Float): Offset {
        val w = viewportSize.width.toFloat()
        val h = viewportSize.height.toFloat()
        val minX = (w - w * z).coerceAtMost(0f)
        val minY = (h - h * z).coerceAtMost(0f)
        return Offset(p.x.coerceIn(minX, 0f), p.y.coerceIn(minY, 0f))
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val DOUBLE_TAP_ZOOM = 2.2f
        private const val DOUBLE_TAP_ZOOM_OUT_THRESHOLD = 1.1f
        private const val ZOOM_SNAP = 0.01f
        private const val MIN_FLING_VELOCITY = 50f
    }
}

/**
 * Zoom gestures for the page list container (place it before the
 * graphicsLayer that applies [state], so positions are viewport coordinates).
 *
 * - Pinch (two or more fingers) is handled in the Initial pass, i.e. before
 *   the list and the pages see the events, and every change is consumed from
 *   the moment the second finger lands until all fingers are up. The list's
 *   drag is then cancelled without velocity, so a pinch can no longer end up
 *   as a fast scroll/fling.
 * - One-finger dragging while zoomed is handled in the Main pass, after the
 *   pages' own gestures (long-press text selection, selection handles), and
 *   only for changes they did not consume. The list's own user scrolling
 *   must be disabled while zoomed.
 */
fun Modifier.pdfZoomGestures(
    state: PdfZoomState,
    onGestureStart: () -> Unit
): Modifier = this
    .pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.lastTouchPosition = down.position
            var pinching = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    if (!pinching) {
                        pinching = true
                        state.stopMotion()
                        onGestureStart()
                    }
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        state.applyTransform(event.calculateCentroid(useCurrent = true), panChange, zoomChange)
                    }
                }
                if (pinching) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(state) {
        val decay = splineBasedDecay<Float>(this)
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!state.isZoomed) return@awaitEachGesture
            state.stopMotion()
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var dragging = false
            var slop = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) break // pinch owns it
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break // a page gesture or the pinch took it
                tracker.addPointerInputChange(change)
                if (!change.pressed) {
                    if (dragging) state.fling(tracker.calculateVelocity(), decay)
                    break
                }
                val delta = change.positionChange()
                if (dragging) {
                    state.panBy(delta)
                } else {
                    slop += delta
                    val distance = slop.getDistance()
                    if (distance > touchSlop) {
                        dragging = true
                        onGestureStart()
                        // Only the movement past the slop, so the content does not jump.
                        state.panBy(slop * ((distance - touchSlop) / distance))
                    }
                }
                if (dragging) change.consume()
            }
        }
    }
