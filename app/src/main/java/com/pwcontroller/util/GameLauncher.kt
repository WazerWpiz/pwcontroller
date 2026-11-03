package com.pwcontroller.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.Nullable
import com.pwcontroller.util.SandboxProvider.SandboxResult

object GameLauncher {

    const val TARGET_PACKAGE = "com.kukouri.wizworld"
    const val TARGET_ACTIVITY = "com.unity3d.player.UnityPlayerActivity"

    fun launchGame(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            
            // First check if game is directly installed
            try {
                val launchIntent = pm.getLaunchIntentForPackage(TARGET_PACKAGE)
                launchIntent?.also {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(it)
                    return true
                }
            } catch (e: Exception) {
                // Not directly installed, check sandboxes
            }

            // Check sandboxes via provider
            val result = detectSandboxViaProvider(context)
            if (result.isInSandbox) {
                launchInSandbox(context, result.sandboxPackage!!)
                return true
            }

            // Game not found
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun detectSandboxViaProvider(context: Context): SandboxResult {
        val providerUri = Uri.parse("content://${SandboxProvider.AUTHORITY}")
        val extras = Bundle().apply {
            putString(SandboxProvider.EXTRA_PACKAGE, TARGET_PACKAGE)
        }
        val resultBundle = context.contentResolver.call(
            providerUri,
            SandboxProvider.METHOD_DETECT_SANDBOX,
            null,
            extras
        )
        return if (resultBundle != null) {
            SandboxResult(
                sandboxPackage = resultBundle.getString(SandboxProvider.RESULT_SANDBOX_PACKAGE),
                isInSandbox = resultBundle.getBoolean(SandboxProvider.RESULT_IS_IN_SANDBOX)
            )
        } else {
            SandboxResult(null, false)
        }
    }

    private fun launchInSandbox(context: Context, sandboxPackage: String) {
        val pm = context.packageManager
        try {
            val sandboxLaunchIntent = pm.getLaunchIntentForPackage(sandboxPackage)
            sandboxLaunchIntent?.also {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Try to pass the target package as extra
                it.putExtra("target_package", TARGET_PACKAGE)
                it.putExtra("target_activity", TARGET_ACTIVITY)
                context.startActivity(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: just launch sandbox
            pm.getLaunchIntentForPackage(sandboxPackage)?.also {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        }
    }

    fun isGameInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun openGameSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", TARGET_PACKAGE, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}