package com.pwcontroller.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionUtils {

    const val REQUEST_OVERLAY_PERMISSION = 1001
    const val REQUEST_ACCESSIBILITY = 1002

    fun hasOverlayPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context)
        }
        return true
    }

    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        }
    }

    fun hasAccessibilityServiceEnabled(context: Context): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ) ?: return false
        return enabledServices.any { it.id?.contains(context.packageName) == true }
    }

    fun openAccessibilitySettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivityForResult(intent, REQUEST_ACCESSIBILITY)
    }

    fun checkAndRequestPermissions(activity: Activity): Boolean {
        val needsOverlay = !hasOverlayPermission(activity)
        val needsAccessibility = !hasAccessibilityServiceEnabled(activity)
        
        if (needsOverlay) {
            requestOverlayPermission(activity)
        }
        if (needsAccessibility) {
            openAccessibilitySettings(activity)
        }
        
        return !needsOverlay && !needsAccessibility
    }
}