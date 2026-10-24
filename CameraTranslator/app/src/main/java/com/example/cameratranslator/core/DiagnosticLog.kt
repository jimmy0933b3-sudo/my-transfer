package com.example.cameratranslator.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-scoped diagnostic events, not unrestricted system logcat.
 * Never log photo content, OCR sentences, translations or credential values.
 * Stored in private no-backup storage, with automatic size bounds.
 */
object DiagnosticLog {
    private val lock = Any()
    private const val MAX_BYTES = 160_000
    private const val TRIM_CHARS = 95_000
    private var target: File? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (target == null) {
                target = File(context.noBackupFilesDir, "camera-translator-diagnostics.log")
            }
        }
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        record("APP", "啟動 v" + version + "；Android " + Build.VERSION.RELEASE +
            " (API " + Build.VERSION.SDK_INT + ")；裝置 " +
            Build.MANUFACTURER + " " + Build.MODEL +
            "；ABI " + Build.SUPPORTED_ABIS.joinToString())
    }

    fun record(category: String, message: String, cause: Throwable? = null) {
        synchronized(lock) {
            val file = target ?: return
            try {
                val trace = cause?.let { "\n" + it.stackTraceToString().take(6500) }.orEmpty()
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                    .format(Date())
                val safe = DiagnosticLogSanitizer.sanitize((message + trace).take(8000))
                file.appendText("[" + time + "] [" + category.take(24) +
                    "] " + safe + "\n")
                if (file.length() > MAX_BYTES) {
                    val tail = file.readText(Charsets.UTF_8).takeLast(TRIM_CHARS)
                    file.writeText("[較早的 Log 已自動清除]\n" +
                        tail.substringAfter('\n', tail), Charsets.UTF_8)
                }
            } catch (error: Exception) {
                Log.w("CameraTranslator", "Unable to write diagnostic event", error)
            }
        }
    }

    fun read(): String = synchronized(lock) {
        runCatching {
            target?.takeIf { it.exists() }?.readText(Charsets.UTF_8)
                ?.takeLast(MAX_BYTES) ?: "尚無診斷日誌，請先拍照或操作 PaddleOCR。"
        }.getOrElse { "無法讀取診斷日誌：" + it.javaClass.simpleName }
    }

    fun clear() {
        synchronized(lock) { runCatching { target?.writeText("", Charsets.UTF_8) } }
        record("LOG", "使用者已清除診斷日誌")
    }
}
