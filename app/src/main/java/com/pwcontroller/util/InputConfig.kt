package com.pwcontroller.util

/**
 * Centralized input timing & behavior constants for Pixel Worlds (Unity-based).
 * All values tuned for reliable touch injection on Android 7.0+ (API 24+).
 *
 * Design rationale:
 * - Unity InputSystem needs longer hold times than native Android views
 * - Shizuku (shell) and Accessibility (system) have different latency profiles
 * - Drag distance must exceed Unity joystick dead zone (~15-20% of stick radius)
 */
object InputConfig {

    // ============================================================
    // TIMING (milliseconds)
    // ============================================================

    /** Tap hold duration - Unity needs ~150-200ms to register a tap reliably */
    const val TAP_HOLD_MS = 180L

    /** Minimum hold for press-follow buttons (jump/punch) - prevents accidental short taps */
    const val PRESS_FOLLOW_MIN_HOLD_MS = 40L

    /** Hold pulse interval for keep-alive moves (30fps = smooth for Unity) */
    const val HOLD_PULSE_MS = 33L

    /** Hold chunk duration for continued strokes (API 26+) - 5 seconds per chunk */
    const val HOLD_CHUNK_MS = 5000L

    /** Swipe gesture duration */
    const val SWIPE_DURATION_MS = 300L

    /** Tap-to-click threshold for touchpad - must be < hold activation delay */
    const val TAP_TO_CLICK_MAX_MS = 180L

    /** Hold activation delay for touchpad - long press becomes hold/drag */
    const val HOLD_ACTIVATION_DELAY_MS = 200L

    /** Drag ramp duration for virtual joystick - smooth acceleration curve */
    const val DRAG_RAMP_MS = 120L

    /** Number of steps for drag ramp interpolation */
    const val DRAG_RAMP_STEPS = 6

    /** Swipe interpolation steps */
    const val SWIPE_STEPS = 10

    // ============================================================
    // JOYSTICK / DRAG DISTANCE
    // ============================================================

    /**
     * Drag distance as fraction of screen width.
     * Unity joystick typically has ~60-80px radius on 1080p, dead zone ~15%.
     * 0.055f = 5.5% screen width ≈ 59px on 1080p. May need 0.07-0.08 for reliability.
     */
    const val JOYSTICK_DRAG_FRACTION = 0.075f  // Increased from 0.055 for reliable Unity registration

    /** Joystick center position (normalized) - overwritten by calibration */
    const val JOYSTICK_CENTER_X = 0.105f
    const val JOYSTICK_CENTER_Y = 0.865f

    // ============================================================
    // ACTION BUTTONS (Jump/Punch)
    // ============================================================

    const val JUMP_X = 0.82f
    const val PUNCH_X = 0.92f
    const val ACTION_Y = 0.76f

    // ============================================================
    // TOUCHPAD / CURSOR
    // ============================================================

    /** Cursor ring size in dp */
    const val CURSOR_SIZE_DP = 36

    /** Base cursor movement gain (slow swipe precision) */
    const val CURSOR_BASE_GAIN = 2.2f

    /** Extra gain boost on fast swipe (max 1.0 = double speed) */
    const val CURSOR_BOOST_GAIN = 3.0f

    /** Touchpad slop threshold in dp (movement < this = tap) */
    const val TOUCHPAD_SLOP_DP = 8

    /** Touchpad window size in dp */
    const val TOUCHPAD_WIDTH_DP = 120
    const val TOUCHPAD_HEIGHT_DP = 88

    // ============================================================
    // OVERLAY POSITIONING
    // ============================================================

    /** Native target Y from top (0.76 = 24% from bottom, matches screenshot buttons) */
    const val NATIVE_TARGET_Y_FROM_TOP = 0.76f

    /** Button radius for 80dp circular buttons */
    const val BUTTON_RADIUS_DP = 40

    /** Margin between overlay and native target */
    const val OVERLAY_MARGIN_DP = 16

    /** Touch injection margin check around overlay windows */
    const val OVERLAY_COLLISION_MARGIN_DP = 40

    // ============================================================
    // VISUAL
    // ============================================================

    /** Default button alpha (0.5-0.6 range for visibility + passthrough) */
    const val DEFAULT_BUTTON_ALPHA = 0.55f

    /** Default button scale */
    const val DEFAULT_BUTTON_SCALE = 1.0f

    /** Cursor window alpha - must be <= 0.8 for Android 12+ touch passthrough */
    const val CURSOR_WINDOW_ALPHA = 0.5f

    // ============================================================
    // MULTI-TOUCH SLOT MANAGEMENT
    // ============================================================

    /** Max simultaneous touch pointers supported */
    const val MAX_TOUCH_SLOTS = 10

    /** Slot indices reserved for specific functions */
    const val SLOT_CURSOR = 0
    const val SLOT_DPAD_LEFT = 1
    const val SLOT_DPAD_RIGHT = 2
    const val SLOT_JUMP = 3
    const val SLOT_PUNCH = 4
    const val SLOT_HOTBAR_START = 5  // 5-9 available for hotbar
}