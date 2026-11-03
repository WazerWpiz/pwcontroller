package com.pwcontroller.util

import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.Method

/**
 * Injects real touchscreen events through Shizuku (shell privilege).
 * Supports several simultaneous pointers (move + jump + punch at once).
 *
 * v1.0.14: the injection path is now observable and resilient.
 *  - init() reports a human readable error instead of a bare exception name.
 *  - inject() reports the real result (SecurityException / rejected event) and keeps counters.
 *  - The IInputManager.injectInputEvent signature is probed (2 args and 4 args) so the app
 *    keeps working on Android versions that changed the AIDL.
 *  - The touchscreen deviceId can optionally be reused (deviceId conflict experiments).
 */
object ShizukuInjector {
    private const val TAG = "PWController"
    const val REQUEST_CODE = 4711

    /** IInputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
    const val MODE_ASYNC = 0

    /** IInputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */
    const val MODE_WAIT_FOR_FINISH = 1

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var injectMethodHasExtraArgs = false

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastInjectMs: Long = 0
        private set

    @Volatile var lastInjectOk: Boolean = false
        private set

    @Volatile var lastInjectX: Float = 0f
        private set

    @Volatile var lastInjectY: Float = 0f
        private set

    @Volatile var lastInjectDetail: String? = null
        private set

    @Volatile var injectCount: Int = 0
        private set

    @Volatile var injectFailCount: Int = 0
        private set

    /**
     * When false (default) events are injected with deviceId = 0, i.e. as a separate virtual
     * device. This keeps the injected finger independent from the real finger that is pressing
     * the overlay button (a shared deviceId makes the system cancel one of the two streams).
     * Enable it only to test the opposite hypothesis (some games filter on deviceId).
     */
    @Volatile var useRealTouchscreenDeviceId: Boolean = false

    /** deviceId of the physical touchscreen, -1 when it cannot be resolved. */
    @Volatile var touchscreenDeviceId: Int = -1
        private set

    init {
        resolveTouchscreenDeviceId()
    }

    /** Shizuku is running (binder alive). */
    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    /** Shizuku is running AND this app was granted permission. */
    fun hasPermission(): Boolean = try {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) { false }

    fun requestPermission() {
        try { if (isRunning()) Shizuku.requestPermission(REQUEST_CODE) } catch (t: Throwable) {
            Log.w(TAG, "requestPermission gagal", t)
        }
    }

    @Synchronized
    fun init(): Boolean {
        if (injectMethod != null) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    if (!HiddenApiBypass.addHiddenApiExemptions("L")) {
                        Log.w(TAG, "HiddenApiBypass.addHiddenApiExemptions(L) mengembalikan false")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "HiddenApiBypass gagal: ${t.message}")
                }
            }

            val binder = getInputBinder()
            if (binder == null) return failInit("Binder 'input' tidak ditemukan")

            val im = asInputManager(binder)
            if (im == null) return failInit("IInputManager.asInterface gagal")

            val iface = try {
                Class.forName("android.hardware.input.IInputManager")
            } catch (t: Throwable) {
                return failInit("Kelas IInputManager tidak ditemukan: ${t.message}")
            }

            val found = findInjectMethod(iface)
            if (found == null) {
                return failInit("Metode injectInputEvent tidak ditemukan pada IInputManager")
            }

            injectMethod = found.first
            injectMethodHasExtraArgs = found.second
            inputManager = im
            lastError = null
            Log.d(
                TAG,
                "Injector siap: ${found.first.name} (extraArgs=${found.second}) " +
                    "touchscreenDeviceId=$touchscreenDeviceId"
            )
            true
        } catch (t: Throwable) {
            failInit("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun failInit(reason: String): Boolean {
        lastError = reason
        Log.e(TAG, "Init Shizuku injector gagal: $reason")
        return false
    }

    private fun getInputBinder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, "input") as? IBinder
    } catch (t: Throwable) {
        Log.e(TAG, "ServiceManager.getService(input) gagal", t)
        null
    }

    private fun asInputManager(binder: IBinder): Any? = try {
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        stub.getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
    } catch (t: Throwable) {
        Log.e(TAG, "IInputManager.asInterface gagal", t)
        null
    }

    /** Returns method + whether the AIDL needs the extra targetFlags/policyFlags arguments. */
    private fun findInjectMethod(iface: Class<*>): Pair<Method, Boolean>? {
        val two = try {
            iface.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) { null }
        if (two != null) return two to false

        val four = try {
            iface.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) { null }
        if (four != null) return four to true

        return null
    }

    internal fun inject(event: MotionEvent, mode: Int = MODE_ASYNC): Boolean {
        lastInjectMs = SystemClock.uptimeMillis()
        lastInjectX = event.x
        lastInjectY = event.y
        val method = injectMethod
        val im = inputManager
        if (method == null || im == null) {
            lastInjectOk = false
            lastInjectDetail = "Injector belum diinisialisasi"
            injectFailCount++
            return false
        }
        return try {
            val result = if (injectMethodHasExtraArgs) {
                method.invoke(im, event, mode, 0, 0) as? Boolean ?: false
            } else {
                method.invoke(im, event, mode) as? Boolean ?: false
            }
            lastInjectOk = result
            lastInjectDetail = if (result) "ok" else "ditolak oleh InputManagerService"
            injectCount++
            if (!result) injectFailCount++
            result
        } catch (e: SecurityException) {
            lastInjectOk = false
            lastInjectDetail = "INJECT_EVENTS ditolak"
            lastError = "INJECT_EVENTS ditolak oleh sistem: ${e.message}"
            injectFailCount++
            Log.e(TAG, "injectInputEvent: SecurityException", e)
            false
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            lastInjectOk = false
            lastInjectDetail = "${cause.javaClass.simpleName}: ${cause.message}"
            lastError = lastInjectDetail
            injectFailCount++
            Log.w(TAG, "injectInputEvent gagal", t)
            false
        }
    }

    /**
     * Diagnostic tap used by the "Tes Injeksi" action. Uses WAIT_FOR_FINISH so the result
     * reflects whether the input dispatcher actually accepted the event.
     */
    fun testInject(x: Float, y: Float): String {
        if (!init()) return "GAGAL init: ${lastError ?: "tidak diketahui"}"
        val down = SystemClock.uptimeMillis()
        val okDown = inject(
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 1, props(), coords(x, y),
                0, 0, 1f, 1f, deviceId(), 0, InputDevice.SOURCE_TOUCHSCREEN, 0),
            MODE_WAIT_FOR_FINISH
        )
        val up = SystemClock.uptimeMillis() + 40
        val okUp = inject(
            MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, 1, props(), coords(x, y),
                0, 0, 1f, 1f, deviceId(), 0, InputDevice.SOURCE_TOUCHSCREEN, 0),
            MODE_WAIT_FOR_FINISH
        )
        return "tap(${x.toInt()},${y.toInt()}) down=$okDown up=$okUp detail=${lastInjectDetail ?: "-"}"
    }

    private fun props(): Array<MotionEvent.PointerProperties> =
        arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })

    private fun coords(x: Float, y: Float): Array<MotionEvent.PointerCoords> =
        arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })

    private fun deviceId(): Int =
        if (useRealTouchscreenDeviceId && touchscreenDeviceId >= 0) touchscreenDeviceId else 0

    private fun resolveTouchscreenDeviceId() {
        try {
            val ids = InputDevice.getDeviceIds()
            val found = ids.firstOrNull { id ->
                val device = InputDevice.getDevice(id)
                device != null &&
                    (device.sources and InputDevice.SOURCE_TOUCHSCREEN) != 0 &&
                    (device.sources and InputDevice.SOURCE_MOUSE) == 0
            }
            touchscreenDeviceId = found ?: -1
            Log.d(TAG, "Touchscreen deviceId=$touchscreenDeviceId (semua id=$ids)")
        } catch (t: Throwable) {
            touchscreenDeviceId = -1
        }
    }

    /** One continuous touch sequence with up to 10 pointers. */
    class MultiTouch {
        private class P(val id: Int, var x: Float, var y: Float)
        private val ptrs = ArrayList<P>()
        private var downTime = 0L

        @Synchronized fun down(id: Int, x: Float, y: Float) {
            if (ptrs.any { it.id == id }) { move(id, x, y); return }
            if (ptrs.isEmpty()) downTime = SystemClock.uptimeMillis()
            ptrs.add(P(id, x, y))
            val action = if (ptrs.size == 1) MotionEvent.ACTION_DOWN
            else MotionEvent.ACTION_POINTER_DOWN or
                ((ptrs.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            send(action)
        }

        @Synchronized fun move(id: Int, x: Float, y: Float) {
            val p = ptrs.firstOrNull { it.id == id } ?: return
            p.x = x; p.y = y
            send(MotionEvent.ACTION_MOVE)
        }

        @Synchronized fun up(id: Int) {
            val idx = ptrs.indexOfFirst { it.id == id }
            if (idx < 0) return
            val action = if (ptrs.size == 1) MotionEvent.ACTION_UP
            else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            send(action)
            ptrs.removeAt(idx)
        }

        @Synchronized fun releaseAll() {
            while (ptrs.isNotEmpty()) up(ptrs.last().id)
        }

        private fun send(action: Int) {
            val n = ptrs.size
            val props = Array(n) { i ->
                MotionEvent.PointerProperties().apply {
                    id = ptrs[i].id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(n) { i ->
                MotionEvent.PointerCoords().apply {
                    x = ptrs[i].x
                    y = ptrs[i].y
                    pressure = 1f; size = 1f
                }
            }
            val ev = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, n, props, coords,
                0, 0, 1f, 1f, deviceId(), 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try { inject(ev) } finally { ev.recycle() }
        }
    }
}
