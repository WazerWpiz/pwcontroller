package com.pwcontroller.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.pwcontroller.model.ActionType
import com.pwcontroller.model.ButtonMapping
import com.pwcontroller.model.ControllerProfile

/**
 * Preset for the native Pixel Worlds (com.kukouri.wizworld) landscape controls.
 *
 * The coordinates are normalised (0..1) screen positions of the *native* game buttons.
 * They can be overridden per button by the in-app calibration (Edit mode -> long press a
 * virtual button -> tap the native button), which is the reliable way on any device.
 */
object PixelWorldsDefaults {
    /** Legacy controls that are not present in the minimal overlay. */
    val UNUSED_BUTTONS = setOf(
        "dpad_up", "dpad_down", "square", "l1", "l2", "r1", "r2",
        "select", "triangle", "start"
    )

    fun createProfile(): ControllerProfile = ControllerProfile(
        name = "default",
        mappings = defaultMappings(),
        buttonScale = InputConfig.DEFAULT_BUTTON_SCALE,
        buttonAlpha = InputConfig.DEFAULT_BUTTON_ALPHA
    )

    /**
     * Hotbar buttons are deliberately absent: the native hotbar is touched directly.
     * Left / right share the joystick position and differ only by the drag direction, because
     * a Unity joystick reacts to the drag - a tap on it does nothing.
     */
    fun defaultMappings(): List<ButtonMapping> = listOf(
        ButtonMapping(
            "dpad_left",
            InputConfig.JOYSTICK_CENTER_X,
            InputConfig.JOYSTICK_CENTER_Y,
            ActionType.HOLD,
            dragDx = -InputConfig.JOYSTICK_DRAG_FRACTION
        ),
        ButtonMapping(
            "dpad_right",
            InputConfig.JOYSTICK_CENTER_X,
            InputConfig.JOYSTICK_CENTER_Y,
            ActionType.HOLD,
            dragDx = InputConfig.JOYSTICK_DRAG_FRACTION
        ),
        ButtonMapping("cross", InputConfig.JUMP_X, InputConfig.ACTION_Y),  // Jump - press-follow
        ButtonMapping("circle", InputConfig.PUNCH_X, InputConfig.ACTION_Y) // Punch - press-follow
    )

    /**
     * Compute overlay vertical position (Y coordinate for window gravity BOTTOM).
     * bottomOffsetPx = screenHeight * (1 - 0.87) + buttonRadiusPx + 16dp
     * This places the overlay BELOW the native target so it doesn't cover it.
     */
    fun computeOverlayBottomOffsetPx(context: Context): Int {
        val screenHeight = realScreenSize(context).second
        val density = realScreenSize(context).third

        // Native target Y is at 0.87 from top, so 0.13 from bottom
        val nativeTargetBottomPx = (screenHeight * (1f - InputConfig.NATIVE_TARGET_Y_FROM_TOP)).toInt()
        // Button radius: 40dp for 80dp jump/punch buttons
        val buttonRadiusPx = (InputConfig.BUTTON_RADIUS_DP * density).toInt()
        // 16dp margin
        val marginPx = (InputConfig.OVERLAY_MARGIN_DP * density).toInt()

        return nativeTargetBottomPx + buttonRadiusPx + marginPx
    }

    /** Real display size (width, height, density) of the current display. */
    fun realScreenSize(context: Context): Triple<Int, Int, Float> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.density)
    }

    /** Check if a native target point falls inside any overlay window rect (+/- 40dp margin). */
    fun checkTargetInOverlay(
        context: Context,
        targetX: Float,
        targetY: Float,
        overlayRects: List<android.graphics.Rect>
    ): Boolean {
        val (screenWidth, screenHeight, density) = realScreenSize(context)
        val marginPx = (InputConfig.OVERLAY_COLLISION_MARGIN_DP * density).toInt()

        val tx = (targetX * screenWidth).toInt()
        val ty = (targetY * screenHeight).toInt()

        for (rect in overlayRects) {
            if (tx >= rect.left - marginPx && tx <= rect.right + marginPx &&
                ty >= rect.top - marginPx && ty <= rect.bottom + marginPx
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Normalize all pre-v1.0.10 profiles while retaining their visual settings and the
     * joystick drag values (they are part of the mapping, the calibrated target position is
     * stored separately in the preferences).
     */
    fun migrateLegacyProfile(profile: ControllerProfile): ControllerProfile {
        val legacy = profile.mappings.associateBy { it.buttonId }
        val clean = defaultMappings().map { def ->
            val old = legacy[def.buttonId]
            if (old == null) {
                def
            } else {
                def.copy(
                    dragDx = if (old.dragDx != 0f) old.dragDx else def.dragDx,
                    dragDy = if (old.dragDy != 0f) old.dragDy else def.dragDy
                )
            }
        }
        return profile.copy(
            mappings = clean,
            buttonAlpha = InputConfig.DEFAULT_BUTTON_ALPHA.coerceAtMost(profile.buttonAlpha)
        )
    }
}
