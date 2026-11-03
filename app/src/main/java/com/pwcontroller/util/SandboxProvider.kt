package com.pwcontroller.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.annotation.Nullable

class SandboxProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.pwcontroller.sandbox"
        const val METHOD_DETECT_SANDBOX = "detect_sandbox"
        const val EXTRA_PACKAGE = "package_name"
        const val RESULT_SANDBOX_PACKAGE = "sandbox_package"
        const val RESULT_IS_IN_SANDBOX = "is_in_sandbox"
    }

    override fun onCreate(): Boolean = true

    @Nullable
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    @Nullable
    override fun getType(uri: Uri): String? = null

    @Nullable
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    @Nullable
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        return when (method) {
            METHOD_DETECT_SANDBOX -> {
                val packageName = extras?.getString(EXTRA_PACKAGE) ?: "com.kukouri.wizworld"
                val result = detectSandbox(context!!, packageName)
                Bundle().apply {
                    putString(RESULT_SANDBOX_PACKAGE, result.sandboxPackage)
                    putBoolean(RESULT_IS_IN_SANDBOX, result.isInSandbox)
                }
            }
            else -> null
        }
    }

    data class SandboxResult(
        val sandboxPackage: String?,
        val isInSandbox: Boolean
    )

    private fun detectSandbox(context: Context, targetPackage: String): SandboxResult {
        val pm = context.packageManager
        val knownSandboxes = listOf(
            "com.termux",
            "com.termux.api",
            "io.neoterm",
            "com.termux.boot",
            "com.github.uiautomator",
            "com.github.uiautomator2",
            "com.github.uiautomator2.server",
            "com.github.uiautomator2.test",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.miui.home",
            "com.sec.android.app.launcher",
            "com.oneplus.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.huawei.android.launcher",
            "com.google.android.googlequicksearchbox",
            "com.android.shell",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.play.games",
            "com.pixelworlds.sandbox",
            "com.kukouri.wizworld.sandbox",
            "com.virtualspace",
            "com.parallelspace",
            "com.dualspace",
            "com.multiaccount",
            "com.cloneapp",
            "com.appcloner",
            "io.virtualapp",
            "com.ludashi.virtual",
            "com.vphonegaga",
            "com.f1vm",
            "com.vmos",
            "com.vmos.pro"
        )

        for (sandbox in knownSandboxes) {
            try {
                val info = pm.getPackageInfo(sandbox, PackageManager.GET_META_DATA)
                if (info != null) {
                    // Check if target app is installed in this sandbox
                    try {
                        val targetInfo = pm.getPackageInfo(targetPackage, PackageManager.GET_META_DATA)
                        if (targetInfo != null) {
                            return SandboxResult(sandbox, true)
                        }
                    } catch (e: PackageManager.NameNotFoundException) {
                        // Target not in this sandbox
                    }
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // Sandbox not installed
            }
        }

        // Check if target is directly installed (not in sandbox)
        try {
            pm.getPackageInfo(targetPackage, PackageManager.GET_META_DATA)
            return SandboxResult(null, false)
        } catch (e: PackageManager.NameNotFoundException) {
            return SandboxResult(null, false)
        }
    }
}