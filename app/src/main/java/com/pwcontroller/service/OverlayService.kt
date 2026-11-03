package com.pwcontroller.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.pwcontroller.PWControllerApplication
import com.pwcontroller.model.ActionType
import com.pwcontroller.model.ButtonMapping
import com.pwcontroller.model.ControllerProfile
import com.pwcontroller.util.DiagnosticsLogger
import com.pwcontroller.util.GestureDispatcher
import com.pwcontroller.util.InputConfig
import com.pwcontroller.util.PreferenceManager
import com.pwcontroller.util.ShizukuDispatcher
import com.pwcontroller.util.ShizukuInjector
import com.pwcontroller.util.PixelWorldsDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "PWControllerOverlay"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_VISIBILITY = "com.pwcontroller.TOGGLE_VISIBILITY"
        const val ACTION_HIDE = "com.pwcontroller.HIDE"
        const val ACTION_SHOW = "com.pwcontroller.SHOW"
        const val ACTION_UPDATE_PROFILE = "com.pwcontroller.UPDATE_PROFILE"
        private const val DEBUG_INPUT_LOG = false
        private const val LONG_PRESS_MS = 600L

        private var instance: OverlayService? = null
        @Volatile
        private var lastError: String? = null

        fun getInstance(): OverlayService? = instance
        fun getLastError(): String? = lastError
    }

    private lateinit var windowManager: WindowManager
    private val overlayViews = mutableListOf<View>()
    private var currentProfile: ControllerProfile? = null
    var isVisible = true
        private set
    private var isEditMode = false
    private var shizukuDispatcher: com.pwcontroller.util.ShizukuDispatcher? = null

    // Virtual cursor / touchpad
    private var cursorWindow: View? = null          // MATCH_PARENT container window
    private var cursorView: View? = null            // Inner cursor ring view
    private var cursorParams: WindowManager.LayoutParams? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorHeld = false
    private val tapToClick = true
    private var screenWidth = 0
    private var screenHeight = 0
    private var touchTracker = mutableMapOf<Int, ButtonMapping>()
    private var inputWarningShown = false
    private var loggedInputBackend: String? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    // Cached dispatcher - updated via listener, not per-touch
    private var cachedDispatcher: com.pwcontroller.util.InputDispatcher? = null

    // Track all overlay windows for touch passthrough checks
    private val allOverlayWindows = mutableListOf<View>()

    private var shizukuPermissionListener: com.pwcontroller.util.ShizukuDispatcher.ShizukuPermissionListener? = null

    // Shizuku binder listeners (stored to remove with same instances)
    private var shizukuBinderReceivedListener: rikka.shizuku.Shizuku.OnBinderReceivedListener? = null
    private var shizukuBinderDeadListener: rikka.shizuku.Shizuku.OnBinderDeadListener? = null
    private var shizukuPermissionResultListener: rikka.shizuku.Shizuku.OnRequestPermissionResultListener? = null

    // Calibration: full screen catcher used to pick the native target of a virtual button
    private var targetPickerView: View? = null
    private var pickingTargetFor: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        lastError = null
        DiagnosticsLogger.attach(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        try {
            startForeground()
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e("PWController", "Gagal memulai foreground service", e)
            stopSelf()
            return
        }

        // Initialize cached dispatcher with Shizuku permission listener
        initializeDispatcher()
        currentProfile = createDefaultProfile()

        if (!ensureOverlayView()) {
            stopSelf()
            return
        }
        setEditMode(PWControllerApplication.instance.preferenceManager.isEditMode())
        loadProfile()
    }

    private fun initializeDispatcher() {
        // Register Shizuku binder listeners once
        setupShizukuBinderListeners()
        // Initial backend selection
        refreshBackend()
    }

    /** Idempotent backend selection: prefers Shizuku if permitted, falls back to Accessibility. */
    private fun refreshBackend() {
        // 1. Try Shizuku first if permission granted
        if (ShizukuInjector.hasPermission()) {
            if (ShizukuInjector.init()) {
                // Reuse existing ShizukuDispatcher or create new one
                if (shizukuDispatcher == null) {
                    shizukuDispatcher = ShizukuDispatcher().also { setupShizukuListeners(it) }
                }
                cachedDispatcher = shizukuDispatcher
                logInputBackend("Shizuku")
                inputWarningShown = false
                DiagnosticsLogger.append("Backend input = Shizuku")
                return
            }
            DiagnosticsLogger.append("Shizuku init gagal: ${ShizukuInjector.lastError}")
        }

        // 2. Fallback: lazy accessibility check (reuse the existing dispatcher instance)
        val service = InputAccessibilityService.getInstance()
        if (service != null) {
            if (cachedDispatcher !is GestureDispatcher) {
                cachedDispatcher = GestureDispatcher(service)
            }
            logInputBackend("Accessibility")
            DiagnosticsLogger.append("Backend input = Accessibility")
        } else {
            cachedDispatcher = null
            inputWarningShown = true
            DiagnosticsLogger.append("Backend input = tidak ada (Shizuku tanpa izin, Accessibility mati)")
        }
    }

    private fun setupShizukuListeners(dispatcher: ShizukuDispatcher) {
        dispatcher.setPermissionListener(object : ShizukuDispatcher.ShizukuPermissionListener {
            override fun onPermissionChanged(hasPermission: Boolean) {
                if (hasPermission) {
                    if (ShizukuInjector.init()) {
                        logInputBackend("Shizuku")
                        inputWarningShown = false
                        cachedDispatcher = dispatcher
                    }
                } else if (cachedDispatcher == dispatcher) {
                    // Fallback to accessibility
                    val service = InputAccessibilityService.getInstance()
                    if (service != null) {
                        cachedDispatcher = GestureDispatcher(service)
                        logInputBackend("Accessibility")
                    } else {
                        cachedDispatcher = null
                        inputWarningShown = true
                    }
                }
            }
        })
    }

    private fun setupShizukuBinderListeners() {
        // Binder received: Shizuku service started/restarted
        shizukuBinderReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener { refreshBackend() }
        rikka.shizuku.Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener!!)

        // Binder dead: Shizuku service stopped
        shizukuBinderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
            if (cachedDispatcher is ShizukuDispatcher) {
                val service = InputAccessibilityService.getInstance()
                cachedDispatcher = service?.let { GestureDispatcher(it) }
                logInputBackend(if (service != null) "Accessibility" else "None")
            }
        }
        rikka.shizuku.Shizuku.addBinderDeadListener(shizukuBinderDeadListener!!)

        // Permission result: user granted/denied Shizuku permission.
        // NOTE: the Shizuku callback is (requestCode, grantResult) - both Int. The previous
        // version read the first parameter as a Boolean, which did not even compile.
        shizukuPermissionResultListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuInjector.REQUEST_CODE &&
                grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                refreshBackend()
            }
        }
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener!!)
    }

    fun isOverlayReady(): Boolean = overlayViews.isNotEmpty()
    fun getCurrentProfile(): ControllerProfile? = currentProfile

    /**
     * Mapping of a virtual button including the calibrated target position.
     * The calibration wins over the preset: it is the only way to be sure the tap lands on
     * the native Pixel Worlds button on the current device / game version.
     */
    fun targetMapping(buttonId: String): ButtonMapping? {
        val base = currentProfile?.mappings?.find { it.buttonId == buttonId } ?: return null
        val calibrated = PWControllerApplication.instance.preferenceManager.getTargetPosition(buttonId)
        return if (calibrated != null) {
            base.copy(screenX = calibrated.first, screenY = calibrated.second)
        } else {
            base
        }
    }

    /** Diagnostics snapshot for the UI / trace file. */
    fun diagnosticsSummary(): String =
        DiagnosticsLogger.summary(loggedInputBackend, screenWidth, screenHeight)

    /** Diagnostic: inject a tap in the middle of the screen and report the result. */
    fun testInjection(): String {
        val x = screenWidth / 2f
        val y = screenHeight / 2f
        val result = ShizukuInjector.testInject(x, y)
        DiagnosticsLogger.append("Tes injeksi di (${x.toInt()},${y.toInt()}): $result")
        return result
    }

    private fun <T : View> findOverlayView(id: Int): T? {
        for (root in overlayViews) {
            val view = root.findViewById<T>(id)
            if (view != null) return view
        }
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ensureOverlayView()) {
            stopSelf()
            return START_NOT_STICKY
        }

        intent?.action?.let { action ->
            when (action) {
                ACTION_TOGGLE_VISIBILITY -> toggleVisibility()
                ACTION_HIDE -> setVisibility(false)
                ACTION_SHOW -> setVisibility(true)
                ACTION_UPDATE_PROFILE -> loadProfile()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PW Controller Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Virtual controller overlay for Pixel Worlds"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val hideIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_HIDE
        }
        val hidePendingIntent = PendingIntent.getService(
            this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_TOGGLE_VISIBILITY
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PW Controller")
            .setContentText(if (isVisible) "Overlay aktif - Tap untuk sembunyikan" else "Overlay tersembunyi - Tap untuk tampilkan")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Sembunyikan", hidePendingIntent)
            .addAction(android.R.drawable.ic_media_play, "Tampilkan", togglePendingIntent)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            Log.w("PWController", "Izin notifikasi belum diberikan", e)
        }
    }

    private fun updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    private fun ensureOverlayView(): Boolean {
        if (overlayViews.isNotEmpty()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            lastError = "Izin tampil di atas aplikasi lain belum aktif"
            return false
        }

        return try {
            updateScreenMetrics()
            createOverlayView()
            setVisibility(true)
            setEditMode(PWControllerApplication.instance.preferenceManager.isEditMode())
            lastError = null
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e("PWController", "Gagal membuat tampilan overlay", e)
            removeOverlayViews()
            false
        }
    }

    private fun createOverlayView() {
        // Small windows are required here: one fullscreen window would consume touches
        // even on empty pixels and prevent the user from swiping or using other apps.

        // Compute vertical position using formula: bottomOffsetPx = screenHeight*(1-0.87) + buttonRadiusNative + 16dp
        val bottomOffsetPx = PixelWorldsDefaults.computeOverlayBottomOffsetPx(this)
        val density = resources.displayMetrics.density

        // Left controls (dpad left/right) - positioned at computed bottom offset
        addOverlayWindow(
            com.pwcontroller.R.layout.overlay_left_controls,
            Gravity.START or Gravity.BOTTOM, 16, (bottomOffsetPx / density).toInt(), "left"
        )
        // Right controls (jump/punch 80dp circular) - positioned at computed bottom offset
        addOverlayWindow(
            com.pwcontroller.R.layout.overlay_right_controls,
            Gravity.END or Gravity.BOTTOM, 16, (bottomOffsetPx / density).toInt(), "right"
        )
        // Touchpad - positioned at same bottom but with horizontal gap from right controls (>=24dp from jump)
        // Right controls (80dp + 80dp + 12dp margin + 12dp padding = ~184dp) at x=16dp from right edge
        // Touchpad at x=224dp from right edge gives >=24dp gap
        addOverlayWindow(
            com.pwcontroller.R.layout.overlay_cursor_pad,
            Gravity.END or Gravity.BOTTOM, 224, (bottomOffsetPx / density).toInt(), "cursor_pad"
        )
        createCursorWindow()
        addOverlayWindow(
            com.pwcontroller.R.layout.overlay_toggle,
            Gravity.START or Gravity.TOP, 16, 16, "toggle"
        )
        addOverlayWindow(
            com.pwcontroller.R.layout.overlay_edit_indicator,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0, 16
        )
        setupButtonListeners()
        applyProfile()
        logOverlayLayout()
    }

    private fun logOverlayLayout() {
        val density = resources.displayMetrics.density
        Log.d("PWController", "Screen: ${screenWidth}px x ${screenHeight}px, density=$density")

        // Log overlay window rects
        val loc = IntArray(2)
        for (v in allOverlayWindows) {
            if (!v.isShown || v.width == 0 || v.height == 0) continue
            v.getLocationOnScreen(loc)
            Log.d("PWController", "Overlay window ${v.id}: rect=[${loc[0]},${loc[1]}] [${loc[0]+v.width},${loc[1]+v.height}] size=${v.width}x${v.height}")
        }

        // Log native targets
        currentProfile?.mappings?.forEach { mapping ->
            val effective = targetMapping(mapping.buttonId) ?: mapping
            val tx = (effective.screenX * screenWidth).toInt()
            val ty = (effective.screenY * screenHeight).toInt()
            var insideOverlay = false
            for (v in allOverlayWindows) {
                if (!v.isShown || v.width == 0 || v.height == 0) continue
                v.getLocationOnScreen(loc)
                val margin = (InputConfig.OVERLAY_COLLISION_MARGIN_DP * density).toInt()
                if (tx >= loc[0] - margin && tx <= loc[0] + v.width + margin &&
                    ty >= loc[1] - margin && ty <= loc[1] + v.height + margin) {
                    insideOverlay = true
                    break
                }
            }
            Log.d("PWController", "Native target ${mapping.buttonId}: (${effective.screenX},${effective.screenY}) -> (${tx},${ty}) insideOverlay=$insideOverlay")
        }
    }

    private fun addOverlayWindow(
        layoutRes: Int,
        windowGravity: Int,
        xDp: Int,
        yDp: Int,
        windowKey: String? = null
    ) {
        val view = LayoutInflater.from(this).inflate(layoutRes, null, false)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = windowGravity
            val density = resources.displayMetrics.density
            val savedOffset = windowKey?.let {
                PWControllerApplication.instance.preferenceManager.getWindowOffset(it)
            }
            x = savedOffset?.first ?: (xDp * density).toInt()
            y = savedOffset?.second ?: (yDp * density).toInt()
        }
        windowManager.addView(view, params)
        overlayViews += view
        allOverlayWindows += view  // Track for touch passthrough checks
    }

    private fun removeOverlayViews() {
        cursorWindow?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {
                Log.w("PWController", "Cursor window sudah dilepas", e)
            }
        }
        cursorWindow = null
        cursorView = null
        cursorParams = null
        for (view in overlayViews.toList()) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w("PWController", "Overlay sudah dilepas", e)
            }
        }
        overlayViews.clear()
        allOverlayWindows.clear()
    }

    private fun setupButtonListeners() {
        findOverlayView<Button>(com.pwcontroller.R.id.btnDpadLeft)?.let {
            setupDpadButton(it, "dpad_left", -1f, 0f)
        }
        findOverlayView<Button>(com.pwcontroller.R.id.btnDpadRight)?.let {
            setupDpadButton(it, "dpad_right", 1f, 0f)
        }

        findOverlayView<Button>(com.pwcontroller.R.id.btnCross)?.let { setupSimpleButton(it, "cross") }
        findOverlayView<Button>(com.pwcontroller.R.id.btnCircle)?.let { setupSimpleButton(it, "circle") }
        setupCursorPad()

        findOverlayView<Button>(com.pwcontroller.R.id.btnToggleOverlayVisibility)?.setOnClickListener {
            toggleVisibility()
        }
        setupDragListeners()
    }

    private fun setupDpadButton(button: Button, buttonId: String, dirX: Float, dirY: Float) {
        button.setOnTouchListener { v, event ->
            if (isEditMode) return@setOnTouchListener false
            val action = event.actionMasked
            val pointerId = event.getPointerId(0)
            val deviceId = event.deviceId
            val eventTime = event.eventTime
            if (DEBUG_INPUT_LOG) Log.d("PWController", "Btn $buttonId action=$action pointerId=$pointerId deviceId=$deviceId eventTime=$eventTime")
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    dispatchDirectionalInput(buttonId, dirX, dirY, true, action, eventTime)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    dispatchDirectionalInput(buttonId, dirX, dirY, false, action, eventTime)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSimpleButton(button: Button, buttonId: String) {
        button.setOnTouchListener { v, event ->
            if (isEditMode) return@setOnTouchListener false
            val action = event.actionMasked
            val pointerId = event.getPointerId(0)
            val deviceId = event.deviceId
            val eventTime = event.eventTime
            if (DEBUG_INPUT_LOG) Log.d("PWController", "Btn $buttonId action=$action pointerId=$pointerId deviceId=$deviceId eventTime=$eventTime")
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    dispatchButtonPress(buttonId, true, action, eventTime)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    dispatchButtonPress(buttonId, false, action, eventTime)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDragListeners() {
        val draggableViews = listOfNotNull(
            findOverlayView<View>(com.pwcontroller.R.id.layoutLeftSide),
            findOverlayView<View>(com.pwcontroller.R.id.layoutRightSide),
            findOverlayView<View>(com.pwcontroller.R.id.layoutCursorPad),
            findOverlayView<Button>(com.pwcontroller.R.id.btnToggleOverlayVisibility)
        )

        for (draggable in draggableViews) {
            var downAt = 0L
            var downButton: String? = null
            draggable.setOnTouchListener { v, event ->
                if (!isEditMode) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.tag = event.rawX to event.rawY
                        downAt = event.eventTime
                        downButton = buttonAt(event.rawX, event.rawY)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val start = v.tag as? Pair<*, *> ?: return@setOnTouchListener false
                        val startX = start.first as? Float ?: return@setOnTouchListener false
                        val startY = start.second as? Float ?: return@setOnTouchListener false
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val params = v.layoutParams as WindowManager.LayoutParams
                        val horizontalDirection =
                            if ((params.gravity and Gravity.RIGHT) == Gravity.RIGHT) -1 else 1
                        val verticalDirection =
                            if ((params.gravity and Gravity.BOTTOM) == Gravity.BOTTOM) -1 else 1
                        params.x += dx.toInt() * horizontalDirection
                        params.y += dy.toInt() * verticalDirection
                        windowManager.updateViewLayout(v, params)
                        v.tag = event.rawX to event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val heldMs = event.eventTime - downAt
                        val button = downButton
                        if (event.actionMasked == MotionEvent.ACTION_UP &&
                            button != null && heldMs >= LONG_PRESS_MS
                        ) {
                            // Long press on a virtual button -> pick the native target position
                            startTargetPicking(button)
                        } else {
                            val key = windowKeyForView(v)
                            val params = v.layoutParams as WindowManager.LayoutParams
                            if (key != null) {
                                PWControllerApplication.instance.preferenceManager.saveWindowOffset(
                                    key, params.x, params.y
                                )
                            }
                        }
                        v.tag = null
                        downButton = null
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /** Which virtual button is located at the given screen position (if any). */
    private fun buttonAt(x: Float, y: Float): String? {
        val loc = IntArray(2)
        val candidates = listOf(
            com.pwcontroller.R.id.btnDpadLeft to "dpad_left",
            com.pwcontroller.R.id.btnDpadRight to "dpad_right",
            com.pwcontroller.R.id.btnCross to "cross",
            com.pwcontroller.R.id.btnCircle to "circle"
        )
        for ((id, buttonId) in candidates) {
            val v = findOverlayView<View>(id) ?: continue
            if (!v.isShown || v.width == 0 || v.height == 0) continue
            v.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + v.width &&
                y >= loc[1] && y <= loc[1] + v.height
            ) return buttonId
        }
        return null
    }

    /**
     * Opens a full screen catcher. The next tap is stored as the native target of the button,
     * which is the reliable way to place the virtual buttons on the real Pixel Worlds buttons.
     */
    private fun startTargetPicking(buttonId: String) {
        stopTargetPicking()
        pickingTargetFor = buttonId
        val container = FrameLayout(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        container.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                val w = screenWidth.coerceAtLeast(1).toFloat()
                val h = screenHeight.coerceAtLeast(1).toFloat()
                val nx = (e.rawX / w).coerceIn(0f, 1f)
                val ny = (e.rawY / h).coerceIn(0f, 1f)
                PWControllerApplication.instance.preferenceManager
                    .saveTargetPosition(buttonId, nx, ny)
                DiagnosticsLogger.append(
                    "Target $buttonId disetel: norm=($nx,$ny) px=(${e.rawX.toInt()},${e.rawY.toInt()})"
                )
                Toast.makeText(
                    this,
                    "Target $buttonId = ${e.rawX.toInt()}, ${e.rawY.toInt()}",
                    Toast.LENGTH_SHORT
                ).show()
                stopTargetPicking()
            }
            true
        }
        try {
            windowManager.addView(container, params)
            targetPickerView = container
            Toast.makeText(
                this,
                "Ketuk tombol asli '$buttonId' di Pixel Worlds",
                Toast.LENGTH_LONG
            ).show()
            DiagnosticsLogger.append("Mode kalibrasi dibuka untuk $buttonId")
        } catch (e: Exception) {
            Log.e("PWController", "Gagal membuka kalibrasi target", e)
            pickingTargetFor = null
        }
    }

    private fun stopTargetPicking() {
        targetPickerView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {
                Log.w("PWController", "Jendela kalibrasi sudah dilepas", e)
            }
        }
        targetPickerView = null
        pickingTargetFor = null
    }

    private fun windowKeyForView(view: View): String? = when (view.id) {
        com.pwcontroller.R.id.layoutLeftSide -> "left"
        com.pwcontroller.R.id.layoutRightSide -> "right"
        com.pwcontroller.R.id.layoutCursorPad -> "cursor_pad"
        com.pwcontroller.R.id.btnToggleOverlayVisibility -> "toggle"
        else -> null
    }

    private fun getGestureDispatcher(): com.pwcontroller.util.InputDispatcher? {
        if (cachedDispatcher == null) {
            // Lazy fallback to accessibility if backend not yet initialized
            val service = InputAccessibilityService.getInstance()
            if (service != null) {
                cachedDispatcher = GestureDispatcher(service)
                logInputBackend("Accessibility")
            }
        }
        return cachedDispatcher
    }

    private fun logInputBackend(backend: String) {
        if (loggedInputBackend == backend) return
        loggedInputBackend = backend
        Log.d("PWController", "Backend input aktif: $backend")
    }

    // ---------- Virtual cursor / touchpad ----------

    private fun createCursorWindow() {
        val density = resources.displayMetrics.density
        val size = (InputConfig.CURSOR_SIZE_DP * density).toInt()

        // Container window: MATCH_PARENT, NOT_TOUCHABLE, alpha 0.5
        val container = FrameLayout(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ blocks touches passing through overlays with alpha > 0.8
            // Use 0.5 for better passthrough reliability
            alpha = InputConfig.CURSOR_WINDOW_ALPHA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Inner cursor view: ring, centered via translationX/Y
        val view = View(this).apply {
            setBackgroundResource(com.pwcontroller.R.drawable.cursor_ring)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }

        container.addView(view)
        windowManager.addView(container, params)

        cursorWindow = container
        cursorView = view
        cursorParams = params
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f

        // Track cursor window for touch passthrough checks
        allOverlayWindows += container

        // Cursor view has Gravity.CENTER in container, so translationX/Y=0 puts it at container center
        // Container is MATCH_PARENT with gravity TOP|START, so its center = screen center
        view.translationX = 0f
        view.translationY = 0f
    }

    private fun moveCursorTo(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, (screenWidth - 1).toFloat().coerceAtLeast(0f))
        cursorY = y.coerceIn(0f, (screenHeight - 1).toFloat().coerceAtLeast(0f))
        val view = cursorView ?: return
        val size = view.width
        if (size == 0) return

        // Cursor view has Gravity.CENTER in container (MATCH_PARENT, TOP|START at 0,0)
        // View's natural position is container center = (screenWidth/2, screenHeight/2)
        // translationX/Y = target screen coords - container center
        view.translationX = cursorX - screenWidth / 2f
        view.translationY = cursorY - screenHeight / 2f
    }

    /** True if (x, y) lies under one of our own overlay windows (a tap there would hit us). */
    private fun isOverOverlay(x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        // Check all overlay windows including cursor window
        for (v in allOverlayWindows) {
            if (!v.isShown || v.width == 0 || v.height == 0) continue
            v.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) return true
        }
        return false
    }

    private fun cursorMapping() = ButtonMapping(
        "cursor_click",
        cursorX / screenWidth.coerceAtLeast(1),
        cursorY / screenHeight.coerceAtLeast(1)
    )

    private fun cursorTap(durationMs: Long = InputConfig.TAP_HOLD_MS) {
        if (isOverOverlay(cursorX, cursorY)) {
            Log.d("PWController", "Klik kursor diabaikan: kursor berada di atas overlay")
            return
        }
        Log.d("PWController", "Klik kursor x=$cursorX y=$cursorY duration=${durationMs}ms")
        getGestureDispatcher()?.let { dispatcher ->
            val mapping = cursorMapping()
            if (dispatcher is ShizukuDispatcher) {
                // For Shizuku, use pressFollow with short hold
                dispatcher.pressFollow("cursor_click", mapping, screenWidth, screenHeight, true)
                // Schedule UP after duration
                Handler(Looper.getMainLooper()).postDelayed({
                    dispatcher.pressFollow("cursor_click", mapping, screenWidth, screenHeight, false)
                }, durationMs)
            } else {
                // For accessibility, use dispatchTap which has fixed duration
                dispatcher.dispatchTap(mapping, screenWidth, screenHeight)
            }
        }
    }

    private fun cursorPress() {
        if (isOverOverlay(cursorX, cursorY)) return
        val dispatcher = getGestureDispatcher() ?: return
        if (dispatcher is com.pwcontroller.util.ShizukuDispatcher) {
            // finger stays down while the button is held (hold-to-attack / drag)
            cursorHeld = true
            dispatcher.pointerDown("cursor_click", cursorX, cursorY)
        } else {
            dispatcher.dispatchTap(cursorMapping(), screenWidth, screenHeight)
        }
    }

    private fun releaseCursorHold() {
        if (!cursorHeld) return
        cursorHeld = false
        shizukuDispatcher?.pointerUp("cursor_click")
    }

    private fun setupCursorPad() {
        val density = resources.displayMetrics.density
        val slop = InputConfig.TOUCHPAD_SLOP_DP * density
        val holdDelayMs = InputConfig.HOLD_ACTIVATION_DELAY_MS
        val tapClickDurationMs = InputConfig.TAP_HOLD_MS
        val pad = findOverlayView<View>(com.pwcontroller.R.id.viewCursorPad) ?: return
        var lastX = 0f
        var lastY = 0f
        var travel = 0f
        var moved = false
        var holdActivated = false
        var downAt = 0L
        var holdRunnable: Runnable? = null
        var lastPointerMoveTime = 0L  // Throttle pointerMove to ~16ms (1x per frame)

        fun cancelPendingHold() {
            holdRunnable?.let { pad.removeCallbacks(it) }
            holdRunnable = null
        }

        pad.setOnTouchListener { _, e ->
            if (isEditMode) {
                cancelPendingHold()
                releaseCursorHold()
                return@setOnTouchListener false
            }
            val action = e.actionMasked
            val pointerId = e.getPointerId(0)
            val deviceId = e.deviceId
            // DOWN/UP/CANCEL always logged; MOVE only when DEBUG_INPUT_LOG
            if (action != MotionEvent.ACTION_MOVE || DEBUG_INPUT_LOG) {
                Log.d("PWController", "Touchpad action=$action pointerId=$pointerId deviceId=$deviceId x=${e.rawX} y=${e.rawY}")
            }
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingHold()
                    lastX = e.rawX
                    lastY = e.rawY
                    travel = 0f
                    moved = false
                    holdActivated = false
                    downAt = e.eventTime
                    val runnable = Runnable {
                        if (pad.isAttachedToWindow && !moved) {
                            holdActivated = true
                            cursorPress()
                        }
                    }
                    holdRunnable = runnable
                    pad.postDelayed(runnable, holdDelayMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastX
                    val dy = e.rawY - lastY
                    lastX = e.rawX
                    lastY = e.rawY
                    val step = kotlin.math.hypot(dx, dy)
                    travel += step
                    if (travel > slop && !moved) {
                        moved = true
                        cancelPendingHold()
                    }
                    if (moved) {
                        val boost = (step / (6 * density)).coerceAtMost(1f)
                        val gain = InputConfig.CURSOR_BASE_GAIN + boost * InputConfig.CURSOR_BOOST_GAIN
                        moveCursorTo(cursorX + dx * gain, cursorY + dy * gain)
                        if (cursorHeld) {
                            // Throttle pointerMove to ~1x per frame (16ms)
                            val now = SystemClock.uptimeMillis()
                            if (now - lastPointerMoveTime >= 16) {
                                lastPointerMoveTime = now
                                shizukuDispatcher?.pointerMove("cursor_click", cursorX, cursorY)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelPendingHold()
                    if (holdActivated || cursorHeld) {
                        releaseCursorHold()
                    } else if (tapToClick && !moved && e.eventTime - downAt < holdDelayMs) {
                        // Quick tap: dispatch tap with consistent duration
                        cursorTap(tapClickDurationMs)
                    }
                    holdActivated = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingHold()
                    releaseCursorHold()
                    holdActivated = false
                    true
                }
                else -> false
            }
        }
    }

    private fun dispatchDirectionalInput(buttonId: String, dirX: Float, dirY: Float, isDown: Boolean, action: Int, eventTime: Long) {
        if (buttonId in com.pwcontroller.util.PixelWorldsDefaults.UNUSED_BUTTONS) return
        val mapping = targetMapping(buttonId) ?: return
        val dispatcher = getGestureDispatcher() ?: run {
            DiagnosticsLogger.append("$buttonId dilewati: tidak ada backend input (Shizuku tanpa izin & Accessibility mati)")
            return
        }
        when (buttonId) {
            "dpad_left", "dpad_right" -> {
                if (isDown) {
                    val dx = mapping.dragDx * screenWidth
                    val dy = mapping.dragDy * screenHeight
                    if (dx != 0f || dy != 0f) {
                        // Virtual joystick: the game only reacts to the drag, not to a tap.
                        dispatcher.startDragHold(buttonId, mapping, screenWidth, screenHeight, dx, dy)
                    } else {
                        dispatcher.startHold(buttonId, mapping, screenWidth, screenHeight)
                    }
                    DiagnosticsLogger.append(
                        "$buttonId DOWN target=(${mapping.screenX},${mapping.screenY}) " +
                            "px=(${(mapping.screenX * screenWidth).toInt()},${(mapping.screenY * screenHeight).toInt()}) drag=($dx,$dy)"
                    )
                } else {
                    dispatcher.stopHold(buttonId)
                    DiagnosticsLogger.append("$buttonId UP")
                }
            }
            else -> {
                // Pixel Worlds uses the up arrow as jump; avoid the old release-time extra tap.
                if (isDown) dispatcher.dispatchTap(mapping, screenWidth, screenHeight)
            }
        }
        if (!isDown && eventTime > 0) {
            val actionStr = when (action) {
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                else -> "UP/CANCEL"
            }
            val msSinceLastInjection = SystemClock.uptimeMillis() - dispatcher.getLastInjectTime()
            Log.d("PWController", "$actionStr $buttonId msSinceLastInjection=$msSinceLastInjection")
        }
    }

    private fun dispatchButtonPress(buttonId: String, isDown: Boolean, action: Int, eventTime: Long) {
        if (buttonId in com.pwcontroller.util.PixelWorldsDefaults.UNUSED_BUTTONS) return
        val mapping = targetMapping(buttonId)
        if (mapping != null) {
            val dispatcher = getGestureDispatcher() ?: run {
                DiagnosticsLogger.append("$buttonId dilewati: tidak ada backend input")
                return
            }
            when (mapping.actionType) {
                ActionType.TAP -> {
                    if (isDown) {
                        Log.d(
                            "PWController",
                            "TAP $buttonId x=${mapping.screenX} y=${mapping.screenY} eventTime=$eventTime"
                        )
                        DiagnosticsLogger.append(
                            "TAP $buttonId target=(${mapping.screenX},${mapping.screenY}) " +
                                "px=(${(mapping.screenX * screenWidth).toInt()},${(mapping.screenY * screenHeight).toInt()})"
                        )
                        // For jump/punch, use pressFollow (hold until release)
                        if (buttonId == "cross" || buttonId == "circle") {
                            dispatcher.pressFollow(buttonId, mapping, screenWidth, screenHeight, true)
                        } else {
                            dispatcher.dispatchTap(mapping, screenWidth, screenHeight)
                        }
                    } else {
                        // UP/CANCEL for press-follow buttons
                        if (buttonId == "cross" || buttonId == "circle") {
                            dispatcher.pressFollow(buttonId, mapping, screenWidth, screenHeight, false)
                        }
                    }
                }
                ActionType.SWIPE -> {
                    if (isDown) {
                        dispatcher.dispatchSwipe(mapping, screenWidth, screenHeight)
                    }
                }
                ActionType.HOLD -> {
                    // Handle hold if needed
                }
            }
        }
        if (!isDown && eventTime > 0) {
            val actionStr = when (action) {
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                else -> "UP/CANCEL"
            }
            val dispatcher = getGestureDispatcher()
            val msSinceLastInjection = SystemClock.uptimeMillis() - (dispatcher?.getLastInjectTime() ?: 0L)
            Log.d("PWController", "$actionStr $buttonId msSinceLastInjection=$msSinceLastInjection")
        }
    }

    fun setVisibility(visible: Boolean) {
        isVisible = visible
        if (!visible) {
            cachedDispatcher?.stopAllHolds()
        }
        val controlsVisibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) releaseCursorHold()
        findOverlayView<View>(com.pwcontroller.R.id.layoutCursorPad)?.visibility = controlsVisibility
        cursorWindow?.visibility = if (visible) View.VISIBLE else View.GONE
        findOverlayView<View>(com.pwcontroller.R.id.layoutLeftSide)?.visibility = controlsVisibility
        findOverlayView<View>(com.pwcontroller.R.id.layoutRightSide)?.visibility = controlsVisibility
        findOverlayView<TextView>(com.pwcontroller.R.id.tvEditModeIndicator)?.visibility =
            if (isEditMode && visible) View.VISIBLE else View.GONE
        findOverlayView<Button>(com.pwcontroller.R.id.btnToggleOverlayVisibility)?.let { toggle ->
            toggle.visibility = View.VISIBLE
            toggle.alpha = if (visible) 1f else 0.65f
        }
        updateNotification()
    }

    fun toggleVisibility() {
        setVisibility(!isVisible)
    }

    fun setEditMode(enabled: Boolean) {
        isEditMode = enabled
        if (enabled && !isVisible) setVisibility(true)
        val indicator = findOverlayView<TextView>(com.pwcontroller.R.id.tvEditModeIndicator)
        indicator?.visibility = if (enabled && isVisible) View.VISIBLE else View.GONE
        if (enabled) {
            Toast.makeText(
                this,
                "Mode Edit: seret untuk memindah, tahan tombol >0,6 dtk untuk mengganti target",
                Toast.LENGTH_LONG
            ).show()
        }

        // Let touches fall through to the draggable parent windows while editing.
        val buttons = listOfNotNull(
            findOverlayView<Button>(com.pwcontroller.R.id.btnDpadLeft),
            findOverlayView<Button>(com.pwcontroller.R.id.btnDpadRight),
            findOverlayView<Button>(com.pwcontroller.R.id.btnCross),
            findOverlayView<Button>(com.pwcontroller.R.id.btnCircle)
        )
        for (button in buttons) {
            button.isClickable = !enabled
            button.isLongClickable = !enabled
        }

        val alpha = if (enabled) 0.5f else 1f
        findOverlayView<View>(com.pwcontroller.R.id.layoutDpad)?.alpha = alpha
        findOverlayView<View>(com.pwcontroller.R.id.layoutFaceButtons)?.alpha = alpha
    }

    fun applyProfile() {
        currentProfile?.let { profile ->
            val scale = profile.buttonScale
            val alpha = profile.buttonAlpha
            val scaledViews = listOfNotNull(
                findOverlayView<View>(com.pwcontroller.R.id.layoutDpad),
                findOverlayView<View>(com.pwcontroller.R.id.layoutFaceButtons),
            )
            for (view in scaledViews) {
                view.scaleX = scale
                view.scaleY = scale
                view.alpha = alpha
            }
        }
    }

    private fun loadProfile() {
        scope.launch {
            try {
                val repo = com.pwcontroller.profile.ProfileRepository.create(this@OverlayService)
                val loaded = repo.getDefaultProfile() ?: createDefaultProfile()
                val migrated = com.pwcontroller.util.PixelWorldsDefaults.migrateLegacyProfile(loaded)
                if (migrated !== loaded) repo.saveProfile(migrated)
                currentProfile = migrated
            } catch (e: Exception) {
                Log.e("PWController", "Gagal memuat profil; memakai default", e)
                currentProfile = createDefaultProfile()
            }
            if (overlayViews.isNotEmpty()) applyProfile()
        }
    }

    fun reloadProfile() {
        loadProfile()
    }

    fun resetOverlayLayout() {
        releaseCursorHold()
        cachedDispatcher?.stopAllHolds()
        currentProfile = createDefaultProfile()

        val preferences = PWControllerApplication.instance.preferenceManager
        preferences.clearWindowOffsets()
        preferences.clearTargetPositions()
        preferences.saveEditMode(false)
        removeOverlayViews()
        if (!ensureOverlayView()) {
            stopSelf()
            return
        }
        setVisibility(true)
        setEditMode(false)
        DiagnosticsLogger.append("Reset preset: target kalibrasi ikut dihapus")
    }

    private fun createDefaultProfile(): ControllerProfile =
        com.pwcontroller.util.PixelWorldsDefaults.createProfile()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cachedDispatcher?.stopAllHolds()
        if (::windowManager.isInitialized) {
            updateScreenMetrics()
            // The overlay windows are placed with gravity + pixel offsets, so they have to be
            // rebuilt for the new orientation. Recreating keeps the injected coordinates and
            // the drawn buttons in sync.
            stopTargetPicking()
            removeOverlayViews()
            try {
                createOverlayView()
                setVisibility(isVisible)
            } catch (e: Exception) {
                Log.e("PWController", "Gagal membuat ulang overlay setelah rotasi", e)
            }
            if (cursorWindow != null) moveCursorTo(screenWidth / 2f, screenHeight / 2f)
            DiagnosticsLogger.append(
                "Orientasi berubah: ${screenWidth}x$screenHeight"
            )
        }
    }

    override fun onDestroy() {
        cachedDispatcher?.stopAllHolds()
        stopTargetPicking()
        try {
            shizukuBinderReceivedListener?.let { rikka.shizuku.Shizuku.removeBinderReceivedListener(it) }
            shizukuBinderDeadListener?.let { rikka.shizuku.Shizuku.removeBinderDeadListener(it) }
            shizukuPermissionResultListener?.let { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(it) }
        } catch (e: Exception) {
            Log.w("PWController", "Failed to remove Shizuku listeners", e)
        }
        shizukuDispatcher?.release()
        shizukuDispatcher = null
        removeOverlayViews()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}