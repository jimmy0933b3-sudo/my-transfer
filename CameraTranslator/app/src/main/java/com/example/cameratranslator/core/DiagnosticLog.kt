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
 * Photo text is OFF by default. User opt-in permits short OCR / translation\n * snippets only in the private file (never in Logcat).
 * Stored in private no-backup storage, with automatic size bounds.
 */
object DiagnosticLog {
    private val lock = Any()
    private const val MAX_BYTES = 1_500_000
    private const val TRIM_CHARS = 1_000_000
    private var target: File? = null
    @Volatile private var includePhotoText = false

    fun photoTextEnabled(): Boolean = includePhotoText

    fun setPhotoTextEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("photo_diagnostic_text", enabled).apply()
        includePhotoText = enabled
        record("LOG", if (enabled)
            "使用者開啟照片 OCR／譯文診斷：此模式會記下照片文字，分享前請檢查內容"
            else "使用者關閉照片 OCR／譯文文字診斷")
    }

    /**
     * Per-block correlation, with actual OCR/translated text only if the
     * user explicitly opts in. No raw photo bytes or credential content.
     */
    fun photoBlock(
        stage: String, index: Int, language: String, text: String, info: String
    ) {
        val count = text.codePointCount(0, text.length)
        val visible = if (includePhotoText) {
            "；文字=" + text.replace('\n', ' ').replace('\r', ' ')
                .replace('\t', ' ').take(240)
        } else "；文字=隱藏（需設定同意）"
        record("PHOTO_BLOCK", "階段=" + stage.take(24) + "；編號=" + index +
            "；語言=" + language + "；字數=" + count +
            "；" + info.take(400) + visible)
    }


    /**
     * Correlates a block's OCR INPUT to its RESULT with a stable context index.
     * [info] MUST contain only non-sensitive metrics and category IDs.
     * Text is redacted unless the user enables detailed diagnostics.
     * Unlike photoBlock, a single record shows before and after side-by-side.
     */
    fun photoResult(
        index: Int, language: String, original: String, translated: String?,
        stage: String, info: String
    ) {
        val originalCount = original.codePointCount(0, original.length)
        val resultCount = translated?.codePointCount(0, translated.length) ?: 0
        val content = if (includePhotoText) {
            fun excerpt(value: String) = value.replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", " ").take(650)
            "；OCR原文=" + excerpt(original) +
                "；最終結果=" + (translated?.let(::excerpt) ?: "無（保留原文）")
        } else "；OCR原文=隱藏（需設定同意）；最終結果=隱藏（需設定同意）"
        record("PHOTO_RESULT", "階段=" + stage.take(32) +
            "；編號=" + index + "；語言=" + language.take(8) +
            "；原文字數=" + originalCount +
            "；結果字數=" + resultCount +
            "；" + info.take(320) + content)
    }


    fun initialize(context: Context) {
        synchronized(lock) {
            includePhotoText = context.getSharedPreferences(
                "translator_settings", Context.MODE_PRIVATE
            ).getBoolean("photo_diagnostic_text", false)
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
                Unit
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
