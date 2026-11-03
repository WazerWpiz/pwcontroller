package com.pwcontroller.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pwcontroller.model.ControllerProfile

class PreferenceManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("PWControllerPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val profileType = TypeToken.get(ControllerProfile::class.java).type

    companion object {
        const val KEY_CURRENT_PROFILE = "current_profile"
        const val KEY_OVERLAY_VISIBLE = "overlay_visible"
        const val KEY_EDIT_MODE = "edit_mode"
        const val KEY_BUTTON_SCALE = "button_scale"
        const val KEY_BUTTON_ALPHA = "button_alpha"
        const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_WINDOW_OFFSET_PREFIX = "window_offset_"
        private const val KEY_TARGET_PREFIX = "target_"
        private const val KEY_TARGET_X_SUFFIX = "_x"
        private const val KEY_TARGET_Y_SUFFIX = "_y"
    }

    fun saveCurrentProfile(profile: ControllerProfile) {
        val json = gson.toJson(profile, profileType)
        prefs.edit().putString(KEY_CURRENT_PROFILE, json).apply()
    }

    fun getCurrentProfile(): ControllerProfile? {
        val json = prefs.getString(KEY_CURRENT_PROFILE, null)
        return json?.let { gson.fromJson(it, profileType) }
    }

    fun saveOverlayVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_VISIBLE, visible).apply()
    }

    fun isOverlayVisible(): Boolean {
        return prefs.getBoolean(KEY_OVERLAY_VISIBLE, true)
    }

    fun saveEditMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EDIT_MODE, enabled).apply()
    }

    fun isEditMode(): Boolean {
        return prefs.getBoolean(KEY_EDIT_MODE, false)
    }

    fun saveButtonScale(scale: Float) {
        prefs.edit().putFloat(KEY_BUTTON_SCALE, scale).apply()
    }

    fun getButtonScale(): Float {
        return prefs.getFloat(KEY_BUTTON_SCALE, 1.0f)
    }

    fun saveButtonAlpha(alpha: Float) {
        prefs.edit().putFloat(KEY_BUTTON_ALPHA, alpha).apply()
    }

    fun getButtonAlpha(): Float {
        return prefs.getFloat(KEY_BUTTON_ALPHA, 0.8f)
    }

    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun setFirstLaunchComplete() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    fun saveWindowOffset(windowKey: String, x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_WINDOW_OFFSET_PREFIX + windowKey + "_x", x)
            .putInt(KEY_WINDOW_OFFSET_PREFIX + windowKey + "_y", y)
            .apply()
    }

    fun getWindowOffset(windowKey: String): Pair<Int, Int>? {
        val xKey = KEY_WINDOW_OFFSET_PREFIX + windowKey + "_x"
        val yKey = KEY_WINDOW_OFFSET_PREFIX + windowKey + "_y"
        if (!prefs.contains(xKey) || !prefs.contains(yKey)) return null
        return prefs.getInt(xKey, 0) to prefs.getInt(yKey, 0)
    }

    fun clearWindowOffsets() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_WINDOW_OFFSET_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Calibrated target of a virtual button: the position of the *native* Pixel Worlds button
     * that has to be pressed. Stored as a normalised (0..1) screen position.
     */
    fun saveTargetPosition(buttonId: String, x: Float, y: Float) {
        prefs.edit()
            .putFloat(KEY_TARGET_PREFIX + buttonId + KEY_TARGET_X_SUFFIX, x)
            .putFloat(KEY_TARGET_PREFIX + buttonId + KEY_TARGET_Y_SUFFIX, y)
            .apply()
    }

    fun getTargetPosition(buttonId: String): Pair<Float, Float>? {
        val xKey = KEY_TARGET_PREFIX + buttonId + KEY_TARGET_X_SUFFIX
        val yKey = KEY_TARGET_PREFIX + buttonId + KEY_TARGET_Y_SUFFIX
        if (!prefs.contains(xKey) || !prefs.contains(yKey)) return null
        return prefs.getFloat(xKey, 0f) to prefs.getFloat(yKey, 0f)
    }

    fun clearTargetPositions() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_TARGET_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }
}