package com.pwcontroller.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InputAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: InputAccessibilityService? = null
        fun getInstance(): InputAccessibilityService? = instance
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var pendingGestures = mutableListOf<GestureDescription>()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle events, just need the service to be active for dispatchGesture
    }

    override fun onInterrupt() {
        // Service interrupted
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("PWController", "Accessibility service connected")
    }

    fun dispatchGesture(gesture: GestureDescription): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dispatchGesture(gesture, null, handler)
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun dispatchTap(x: Float, y: Float, duration: Long = 50): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture)
    }

    fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture)
    }

    fun dispatchMultiTouch(pointers: List<Pair<Float, Float>>, duration: Long = 50): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        
        val builder = GestureDescription.Builder()
        for ((x, y) in pointers) {
            val path = Path().apply { moveTo(x, y) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        }
        return dispatchGesture(builder.build())
    }

    fun dispatchHold(x: Float, y: Float, holdDuration: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, holdDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture)
    }

    fun queueGesture(gesture: GestureDescription) {
        pendingGestures.add(gesture)
        processQueue()
    }

    private fun processQueue() {
        if (pendingGestures.isNotEmpty()) {
            val gesture = pendingGestures.removeAt(0)
            dispatchGesture(gesture)
            // Process next after a small delay
            handler.postDelayed({ processQueue() }, 16)
        }
    }

    fun clearQueue() {
        pendingGestures.clear()
    }
}