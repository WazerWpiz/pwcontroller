package com.pwcontroller.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small trace file so the state of the controller can be inspected without logcat.
 *
 * Location (app external files dir, no permission needed):
 *   /sdcard/Android/data/com.pwcontroller/files/pwcontroller-diag.txt
 * A copy is also attempted in the public Download folder when the system allows it.
 */
object DiagnosticsLogger {
    private const val TAG = "PWController"
    private const val FILE_NAME = "pwcontroller-diag.txt"
    private const val MAX_BYTES = 256 * 1024

    @Volatile private var appContext: Context? = null
    private val lock = Any()

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun diagFile(): File? = appContext?.let {
        File(it.getExternalFilesDir(null), FILE_NAME)
    }

    fun append(message: String) {
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + message
        Log.d(TAG, line)
        synchronized(lock) {
            try {
                val ctx = appContext ?: return
                val targets = mutableListOf<File>()
                ctx.getExternalFilesDir(null)?.let { targets += File(it, FILE_NAME) }
                try {
                    @Suppress("DEPRECATION")
                    val download = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    if (download != null) targets += File(download, FILE_NAME)
                } catch (_: Throwable) { }
                for (file in targets) {
                    try {
                        file.parentFile?.mkdirs()
                        if (file.exists() && file.length() > MAX_BYTES) file.delete()
                        file.appendText(line + "\n")
                    } catch (_: Throwable) { }
                }
            } catch (_: Throwable) { }
        }
    }

    /** One line summary of the current input backend, used by the diagnostics screen. */
    fun summary(backend: String?, screenWidth: Int, screenHeight: Int): String {
        val sb = StringBuilder()
        sb.append("backend=").append(backend ?: "-")
        sb.append(" screen=").append(screenWidth).append("x").append(screenHeight)
        sb.append(" shizukuRunning=").append(ShizukuInjector.isRunning())
        sb.append(" shizukuPerm=").append(ShizukuInjector.hasPermission())
        sb.append(" injectorError=").append(ShizukuInjector.lastError ?: "-")
        sb.append(" injectOk=").append(ShizukuInjector.lastInjectOk)
        sb.append(" injectCount=").append(ShizukuInjector.injectCount)
        sb.append(" injectFail=").append(ShizukuInjector.injectFailCount)
        sb.append(" lastPoint=").append(ShizukuInjector.lastInjectX.toInt())
            .append(",").append(ShizukuInjector.lastInjectY.toInt())
        sb.append(" lastDetail=").append(ShizukuInjector.lastInjectDetail ?: "-")
        sb.append(" touchscreenDeviceId=").append(ShizukuInjector.touchscreenDeviceId)
        return sb.toString()
    }
}
