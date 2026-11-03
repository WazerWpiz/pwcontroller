package com.pwcontroller.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pwcontroller.model.ButtonMapping
import com.pwcontroller.model.SwipeDirection

class GestureDispatcher(private val service: AccessibilityService) : InputDispatcher {

    private val handler = Handler(Looper.getMainLooper())
    private val activeHolds = mutableMapOf<String, HoldSession>()
    private val pressFollowSessions = mutableMapOf<String, PressFollowSession>()
    @Volatile var lastInjectMs: Long = 0

    override fun getLastInjectTime(): Long = lastInjectMs

    private fun android.accessibilityservice.GestureDescription.Builder.dispatch(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            service.dispatchGesture(build(), null, handler)
        } catch (e: Exception) {
            Log.w("PWController", "Gesture tidak dapat dikirim", e)
            false
        }
    }

    override fun dispatchTap(mapping: ButtonMapping, screenWidth: Int, screenHeight: Int) {
        val x = (mapping.screenX * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        val y = (mapping.screenY * screenHeight).toInt().coerceIn(0, screenHeight - 1)
        dispatchGesture(x, y, InputConfig.TAP_HOLD_MS)
    }

    override fun dispatchSwipe(mapping: ButtonMapping, screenWidth: Int, screenHeight: Int) {
        val startX = (mapping.screenX * screenWidth).toInt()
        val startY = (mapping.screenY * screenHeight).toInt()

        val (rawEndX, rawEndY) = when (mapping.swipeDirection) {
            SwipeDirection.UP -> startX to (startY - mapping.swipeDistance * screenHeight).toInt()
            SwipeDirection.DOWN -> startX to (startY + mapping.swipeDistance * screenHeight).toInt()
            SwipeDirection.LEFT -> (startX - mapping.swipeDistance * screenWidth).toInt() to startY
            SwipeDirection.RIGHT -> (startX + mapping.swipeDistance * screenWidth).toInt() to startY
            null -> startX to startY
        }
        val endX = rawEndX.coerceIn(0, screenWidth - 1)
        val endY = rawEndY.coerceIn(0, screenHeight - 1)
        dispatchGesture(
            startX.coerceIn(0, screenWidth - 1),
            startY.coerceIn(0, screenHeight - 1),
            endX,
            endY,
            InputConfig.SWIPE_DURATION_MS
        )
    }

    /**
     * Pixel Worlds moves while its native arrow is held. A continued stroke (API 26+)
     * keeps ONE finger down across chunks instead of tapping repeatedly.
     */
    override fun startHold(key: String, mapping: ButtonMapping, screenWidth: Int, screenHeight: Int) {
        stopAllHolds()
        val x = (mapping.screenX * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        val y = (mapping.screenY * screenHeight).toInt().coerceIn(0, screenHeight - 1)
        val session = HoldSession(key, x.toFloat(), y.toFloat())
        activeHolds[key] = session
        session.send()
    }

    /**
     * Hold on a virtual joystick: press on the stick and drag to (x + dragDx, y + dragDy).
     * Unity joysticks only react to the drag, a plain tap does nothing.
     */
    override fun startDragHold(
        key: String,
        mapping: ButtonMapping,
        screenWidth: Int,
        screenHeight: Int,
        dragDx: Float,
        dragDy: Float
    ) {
        stopAllHolds()
        val x = (mapping.screenX * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        val y = (mapping.screenY * screenHeight).toInt().coerceIn(0, screenHeight - 1)
        val endX = (x + dragDx.toInt()).coerceIn(0, screenWidth - 1).toFloat()
        val endY = (y + dragDy.toInt()).coerceIn(0, screenHeight - 1).toFloat()
        val session = HoldSession(key, x.toFloat(), y.toFloat(), PointF(endX, endY))
        activeHolds[key] = session
        session.send()
    }

    override fun stopHold(key: String) {
        activeHolds.remove(key)?.release()
    }

    override fun stopAllHolds() {
        val all = activeHolds.values.toList()
        activeHolds.clear()
        all.forEach { it.release() }
        // Also release press-follow sessions
        for ((_, session) in pressFollowSessions) {
            session.release()
        }
        pressFollowSessions.clear()
    }

    /** Press-follow for action buttons: DOWN on press, UP on release with min 40ms hold. */
    override fun pressFollow(key: String, mapping: ButtonMapping, screenWidth: Int, screenHeight: Int, isDown: Boolean) {
        val x = (mapping.screenX * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        val y = (mapping.screenY * screenHeight).toInt().coerceIn(0, screenHeight - 1)

        if (isDown) {
            val session = PressFollowSession(key, x.toFloat(), y.toFloat())
            pressFollowSessions[key] = session
            session.sendDown()
            Log.d("PWController", "PressFollow DOWN key=$key x=$x y=$y")
        } else {
            pressFollowSessions[key]?.release()
            pressFollowSessions.remove(key)
        }
    }

    private inner class HoldSession(
        val key: String,
        val x: Float,
        val y: Float,
        /** When set the finger is dragged to this point (virtual joystick). */
        val dragTo: PointF? = null
    ) {
        private var stroke: android.accessibilityservice.GestureDescription.StrokeDescription? = null

        private fun path() = Path().apply {
            moveTo(x, y)
            dragTo?.let { lineTo(it.x, it.y) }
        }

        /** Continuation path: stay at the end of the previous stroke. */
        private fun continuePath() = Path().apply {
            val end = dragTo ?: PointF(x, y)
            moveTo(end.x, end.y)
        }

        fun send() {
            if (activeHolds[key] !== this) return
            val canContinue = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            val prev = stroke
            val s = if (canContinue && prev != null) {
                prev.continueStroke(continuePath(), 0, InputConfig.HOLD_CHUNK_MS, true)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path(), 0, InputConfig.HOLD_CHUNK_MS, true
                    )
                } else {
                    // API < 26: no willContinue, use shorter duration
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path(), 0, 140
                    )
                }
            }
            stroke = s
            val ok = try {
                service.dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder().addStroke(s).build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                            send()
                        }
                        override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                            Log.w("PWController", "Hold gesture dibatalkan")
                            stroke = null
                            handler.postDelayed({ send() }, 30)
                        }
                    },
                    handler
                )
            } catch (e: Exception) {
                Log.w("PWController", "Hold gagal", e)
                false
            }
            if (!ok) Log.w("PWController", "dispatchGesture return false (canPerformGestures aktif?)")
        }

        fun release() {
            val prev = stroke ?: return
            stroke = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            try {
                service.dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(prev.continueStroke(continuePath(), 0, 1, false)).build(),
                    null, handler
                )
            } catch (e: Exception) {
                Log.w("PWController", "Release hold gagal", e)
            }
        }
    }

    /** Press-follow session for accessibility gestures (jump/punch). */
    private inner class PressFollowSession(val key: String, val x: Float, val y: Float) {
        private var stroke: android.accessibilityservice.GestureDescription.StrokeDescription? = null
        private var downTime: Long = 0
        private var pendingUpRunnable: Runnable? = null

        private fun path() = Path().apply { moveTo(x, y) }

        fun sendDown() {
            downTime = SystemClock.uptimeMillis()
            val s = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path(), 0, 5000, true // Long duration, can continue
                )
            } else {
                // API < 26: no willContinue, use shorter duration and rely on release()
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path(), 0, 5000
                )
            }
            stroke = s
            val ok = try {
                service.dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder().addStroke(s).build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                            // Gesture completed unexpectedly, don't auto-restart for press-follow
                        }
                        override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                            Log.w("PWController", "PressFollow gesture dibatalkan")
                            stroke = null
                        }
                    },
                    handler
                )
            } catch (e: Exception) {
                Log.w("PWController", "PressFollow DOWN gagal", e)
                false
            }
            if (!ok) Log.w("PWController", "PressFollow dispatchGesture return false")
        }

        fun release() {
            val prev = stroke ?: return
            stroke = null
            val heldMs = SystemClock.uptimeMillis() - downTime

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val doRelease = {
                try {
                    service.dispatchGesture(
                        android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(prev.continueStroke(path(), 0, 1, false)).build(),
                        null, handler
                    )
                    Log.d("PWController", "PressFollow UP key=$key heldMs=$heldMs")
                } catch (e: Exception) {
                    Log.w("PWController", "PressFollow Release gagal", e)
                }
            }

            if (heldMs < InputConfig.PRESS_FOLLOW_MIN_HOLD_MS) {
                val remaining = InputConfig.PRESS_FOLLOW_MIN_HOLD_MS - heldMs
                pendingUpRunnable = Runnable { doRelease() }
                handler.postDelayed(pendingUpRunnable!!, remaining)
            } else {
                doRelease()
            }
        }
    }

    private fun dispatchGesture(x: Int, y: Int, duration: Long): Boolean {
        lastInjectMs = SystemClock.uptimeMillis()
        val builder = GestureBuilder()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        builder.addStroke(path)
        builder.setDuration(duration)
        return builder.build().dispatch()
    }

    private fun dispatchGesture(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
        lastInjectMs = SystemClock.uptimeMillis()
        val builder = GestureBuilder()
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        builder.addStroke(path)
        builder.setDuration(duration)
        return builder.build().dispatch()
    }

    fun dispatchMultiTouch(pointers: List<PointF>, action: Int, screenWidth: Int, screenHeight: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || pointers.isEmpty()) return
        lastInjectMs = SystemClock.uptimeMillis()
        val builder = GestureBuilder()
        for (point in pointers) {
            val path = Path().apply {
                moveTo(
                    (point.x * screenWidth).coerceIn(0f, (screenWidth - 1).toFloat()),
                    (point.y * screenHeight).coerceIn(0f, (screenHeight - 1).toFloat())
                )
            }
            builder.addStroke(path)
        }
        builder.setDuration(60)
        builder.build().dispatch()
    }

    inner class GestureBuilder {
        private val strokes = mutableListOf<Path>()
        private var duration: Long = 60

        fun addStroke(path: Path) {
            strokes.add(path)
        }

        fun setDuration(duration: Long) {
            this.duration = duration
        }

        fun build(): android.accessibilityservice.GestureDescription.Builder {
            val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
            for (stroke in strokes) {
                gestureBuilder.addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        stroke, 0, duration
                    )
                )
            }
            return gestureBuilder
        }
    }

    companion object {
        // Timing optimized for Pixel Worlds (Unity-based)
        // Hold chunks: 5 seconds per chunk (API 26+ continueStroke)
        private const val HOLD_CHUNK_MS = 5000L
        // Tap duration for accessibility gestures
        private const val TAP_DURATION_MS = 180L
    }
}