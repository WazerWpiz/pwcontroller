package com.pwcontroller.util

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.pwcontroller.model.ButtonMapping
import com.pwcontroller.model.SwipeDirection

/** Real touch injection: holds stay down and receive a steady keep-alive move. */
class ShizukuDispatcher : InputDispatcher {

    interface ShizukuPermissionListener {
        fun onPermissionChanged(hasPermission: Boolean)
    }

    private val thread = HandlerThread("pw-input").also { it.start() }
    private val handler = Handler(thread.looper)
    private val touch = ShizukuInjector.MultiTouch()
    private val slotUsed = BooleanArray(InputConfig.MAX_TOUCH_SLOTS)
    private val holds = HashMap<String, Int>()
    private val holdPositions = HashMap<String, Pair<Float, Float>>()
    private val free = HashMap<String, Int>()
    private val holdPulses = HashMap<String, Runnable>()
    private val pressFollowSlots = HashMap<String, Int>()
    private val pressFollowDownTime = HashMap<String, Long>()
    private val pressFollowPendingUp = HashMap<String, Runnable>()
    private var permissionListener: ShizukuPermissionListener? = null
    private var hasPermissionCached = false
    private var released = false

    override fun getLastInjectTime(): Long = ShizukuInjector.lastInjectMs

    fun setPermissionListener(listener: ShizukuPermissionListener) {
        permissionListener = listener
        // Initial check
        checkPermissionAndNotify()
    }

    private fun checkPermissionAndNotify() {
        val hasPermission = try {
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) { false }
        if (hasPermission != hasPermissionCached) {
            hasPermissionCached = hasPermission
            permissionListener?.onPermissionChanged(hasPermission)
        }
    }

    @Synchronized private fun allocSlot(): Int {
        for (i in slotUsed.indices) if (!slotUsed[i]) {
            slotUsed[i] = true
            return i
        }
        return -1
    }

    @Synchronized private fun freeSlot(i: Int) {
        if (i in slotUsed.indices) slotUsed[i] = false
    }

    private fun px(m: ButtonMapping, w: Int, h: Int): Pair<Float, Float> =
        (m.screenX * w).coerceIn(0f, (w - 1).toFloat()) to
            (m.screenY * h).coerceIn(0f, (h - 1).toFloat())

    override fun dispatchTap(mapping: ButtonMapping, screenWidth: Int, screenHeight: Int) {
        val (x, y) = px(mapping, screenWidth, screenHeight)
        handler.post {
            val slot = allocSlot()
            if (slot < 0) return@post
            touch.down(slot, x, y)
            // A complete down/move/up sequence is more reliable for Unity controls.
            handler.postDelayed({ touch.move(slot, x, y) }, InputConfig.TAP_HOLD_MS / 10)
            handler.postDelayed({
                touch.up(slot)
                freeSlot(slot)
            }, InputConfig.TAP_HOLD_MS)
        }
    }

    override fun dispatchSwipe(mapping: ButtonMapping, screenWidth: Int, screenHeight: Int) {
        val (sx, sy) = px(mapping, screenWidth, screenHeight)
        val dx = when (mapping.swipeDirection) {
            SwipeDirection.LEFT -> -mapping.swipeDistance * screenWidth
            SwipeDirection.RIGHT -> mapping.swipeDistance * screenWidth
            else -> 0f
        }
        val dy = when (mapping.swipeDirection) {
            SwipeDirection.UP -> -mapping.swipeDistance * screenHeight
            SwipeDirection.DOWN -> mapping.swipeDistance * screenHeight
            else -> 0f
        }
        handler.post {
            val slot = allocSlot()
            if (slot < 0) return@post
            touch.down(slot, sx, sy)
            for (i in 1..InputConfig.SWIPE_STEPS) {
                val t = i / InputConfig.SWIPE_STEPS.toFloat()
                handler.postDelayed({
                    touch.move(
                        slot,
                        (sx + dx * t).coerceIn(0f, (screenWidth - 1).toFloat()),
                        (sy + dy * t).coerceIn(0f, (screenHeight - 1).toFloat())
                    )
                }, (InputConfig.SWIPE_DURATION_MS * t).toLong())
            }
            handler.postDelayed({
                touch.up(slot)
                freeSlot(slot)
            }, InputConfig.SWIPE_DURATION_MS + 20)
        }
    }

    /**
     * Hold a button down.
     *
     * v1.0.14: the old implementation released *every* hold before starting a new one, so
     * holding "left" and then pressing "right" produced a visible gap and the character
     * stopped moving. The new pointer is now pressed BEFORE the previous one is released
     * (no-gap direction change) and only the same key is restarted.
     */
    override fun startHold(key: String, mapping: ButtonMapping, screenWidth: Int, screenHeight: Int) {
        val (x, y) = px(mapping, screenWidth, screenHeight)
        handler.post {
            if (holds.containsKey(key)) return@post
            // 1. press the new pointer first (no gap)
            val slot = allocSlot()
            if (slot < 0) return@post
            holds[key] = slot
            holdPositions[key] = x to y
            touch.down(slot, x, y)
            startHoldPulse(key, slot, x, y)
            // 2. then release the pointer of the previous direction
            releaseOtherHolds(key)
        }
    }

    /**
     * Hold on a virtual joystick: press on the stick, then drag by (dx, dy) pixels.
     * The drag is ramped over DRAG_RAMP_MS so the game sees a real finger movement.
     */
    override fun startDragHold(
        key: String,
        mapping: ButtonMapping,
        screenWidth: Int,
        screenHeight: Int,
        dragDx: Float,
        dragDy: Float
    ) {
        val (x, y) = px(mapping, screenWidth, screenHeight)
        val endX = (x + dragDx).coerceIn(0f, (screenWidth - 1).toFloat())
        val endY = (y + dragDy).coerceIn(0f, (screenHeight - 1).toFloat())
        handler.post {
            if (holds.containsKey(key)) return@post
            val slot = allocSlot()
            if (slot < 0) return@post
            holds[key] = slot
            holdPositions[key] = endX to endY
            touch.down(slot, x, y)
            // ramp the drag
            for (i in 1..InputConfig.DRAG_RAMP_STEPS) {
                val t = i / InputConfig.DRAG_RAMP_STEPS.toFloat()
                handler.postDelayed({
                    touch.move(
                        slot,
                        x + (endX - x) * t,
                        y + (endY - y) * t
                    )
                }, (InputConfig.DRAG_RAMP_MS * t).toLong())
            }
            handler.postDelayed({
                if (holds[key] == slot) startHoldPulse(key, slot, endX, endY)
            }, InputConfig.DRAG_RAMP_MS + 10)
            releaseOtherHolds(key)
            Log.d(
                "PWController",
                "DragHold key=$key start=(${x.toInt()},${y.toInt()}) " +
                    "end=(${endX.toInt()},${endY.toInt()})"
            )
        }
    }

    override fun stopHold(key: String) {
        handler.post {
            cancelHoldPulse(key)
            holds.remove(key)?.let {
                touch.up(it)
                freeSlot(it)
            }
            holdPositions.remove(key)
        }
    }

    override fun stopAllHolds() {
        handler.post {
            releaseHolds()
            for ((_, slot) in free) {
                touch.up(slot)
                freeSlot(slot)
            }
            free.clear()
            // Cancel pending press-follow UPs first, otherwise they fire on a freed slot and
            // send a stray UP to the game.
            for ((_, runnable) in pressFollowPendingUp) {
                handler.removeCallbacks(runnable)
            }
            pressFollowPendingUp.clear()
            for ((_, slot) in pressFollowSlots) {
                touch.up(slot)
                freeSlot(slot)
            }
            pressFollowSlots.clear()
            pressFollowDownTime.clear()
        }
    }

    /** Press-follow for action buttons: DOWN on press, UP on release with min 40ms hold. */
    override fun pressFollow(key: String, mapping: ButtonMapping, screenWidth: Int, screenHeight: Int, isDown: Boolean) {
        val (x, y) = px(mapping, screenWidth, screenHeight)
        handler.post {
            if (isDown) {
                // If there's a pending UP for this key, execute it NOW (preempt)
                val pending = pressFollowPendingUp.remove(key)
                if (pending != null) {
                    handler.removeCallbacks(pending)
                    val pendingSlot = pressFollowSlots.remove(key)
                    pressFollowDownTime.remove(key)
                    if (pendingSlot != null) {
                        touch.up(pendingSlot)
                        freeSlot(pendingSlot)
                    }
                    Log.d("PWController", "PressFollow UP (preempted) key=$key slot=$pendingSlot")
                }

                // Allocate or reuse slot for this key
                val slot = pressFollowSlots[key] ?: allocSlot().also {
                    if (it >= 0) pressFollowSlots[key] = it
                }
                if (slot < 0) return@post

                pressFollowDownTime[key] = SystemClock.uptimeMillis()
                touch.down(slot, x, y)
                Log.d("PWController", "PressFollow DOWN key=$key slot=$slot x=$x y=$y")
            } else {
                // UP/CANCEL - enforce minimum 40ms hold
                val downTime = pressFollowDownTime[key] ?: 0L
                val heldMs = SystemClock.uptimeMillis() - downTime
                val slot = pressFollowSlots[key] ?: return@post

                if (heldMs < InputConfig.PRESS_FOLLOW_MIN_HOLD_MS) {
                    // Delay the UP to ensure minimum hold
                    val remaining = InputConfig.PRESS_FOLLOW_MIN_HOLD_MS - heldMs
                    val runnable = Runnable {
                        touch.up(slot)
                        freeSlot(slot)
                        pressFollowSlots.remove(key)
                        pressFollowDownTime.remove(key)
                        pressFollowPendingUp.remove(key)
                        Log.d("PWController", "PressFollow UP (delayed) key=$key slot=$slot heldMs=$heldMs")
                    }
                    pressFollowPendingUp[key] = runnable
                    handler.postDelayed(runnable, remaining)
                } else {
                    touch.up(slot)
                    freeSlot(slot)
                    pressFollowSlots.remove(key)
                    pressFollowDownTime.remove(key)
                    pressFollowPendingUp.remove(key)
                    Log.d("PWController", "PressFollow UP key=$key slot=$slot heldMs=$heldMs")
                }
            }
        }
    }

    /** Independent finger used by the virtual cursor; directional holds stay active. */
    fun pointerDown(key: String, x: Float, y: Float) {
        handler.post {
            if (free.containsKey(key)) return@post
            val slot = allocSlot()
            if (slot < 0) return@post
            free[key] = slot
            touch.down(slot, x, y)
        }
    }

    fun pointerMove(key: String, x: Float, y: Float) {
        handler.post { free[key]?.let { touch.move(it, x, y) } }
    }

    fun pointerUp(key: String) {
        handler.post {
            free.remove(key)?.let {
                touch.up(it)
                freeSlot(it)
            }
        }
    }

    private fun startHoldPulse(key: String, slot: Int, x: Float, y: Float) {
        cancelHoldPulse(key)
        val pulse = object : Runnable {
            override fun run() {
                if (holds[key] != slot) return
                touch.move(slot, x, y)
                if (DEBUG_INPUT_LOG) Log.d("PWController", "Hold pulse MOVE key=$key slot=$slot x=$x y=$y")
                handler.postDelayed(this, InputConfig.HOLD_PULSE_MS)
            }
        }
        holdPulses[key] = pulse
        handler.postDelayed(pulse, InputConfig.HOLD_PULSE_MS)
    }

    private fun cancelHoldPulse(key: String) {
        holdPulses.remove(key)?.let { handler.removeCallbacks(it) }
    }

    private fun releaseHolds() {
        val keys = holdPulses.keys.toList()
        keys.forEach { cancelHoldPulse(it) }
        for ((_, slot) in holds) {
            touch.up(slot)
            freeSlot(slot)
        }
        holds.clear()
        holdPositions.clear()
    }

    /** Release every hold except [keep] - used for the no-gap direction change. */
    private fun releaseOtherHolds(keep: String) {
        val others = holds.keys.filter { it != keep }
        for (other in others) {
            cancelHoldPulse(other)
            holds.remove(other)?.let {
                touch.up(it)
                freeSlot(it)
            }
            holdPositions.remove(other)
        }
    }

    /** Stop the worker thread; called when the overlay service is destroyed. */
    fun release() {
        handler.post {
            releaseHolds()
            for ((_, runnable) in pressFollowPendingUp) handler.removeCallbacks(runnable)
            pressFollowPendingUp.clear()
            for ((_, slot) in pressFollowSlots) {
                touch.up(slot)
                freeSlot(slot)
            }
            pressFollowSlots.clear()
            for ((_, slot) in free) {
                touch.up(slot)
                freeSlot(slot)
            }
            free.clear()
            released = true
            thread.quitSafely()
        }
    }

    companion object {
        private const val DEBUG_INPUT_LOG = false
    }
}
