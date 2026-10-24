package com.example.cameratranslator.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Official PaddlePaddle ONNX models downloaded on demand to private app
 * storage; v5 and v6 detection/recognition/vocabulary are kept separate.
 */
class PaddleMobileModelStore(context: Context) {
    enum class Version(val label: String, val detRepo: String, val recRepo: String) {
        V5("PP-OCRv5 Mobile", "PP-OCRv5_mobile_det_onnx", "PP-OCRv5_mobile_rec_onnx"),
        V6("PP-OCRv6 Small", "PP-OCRv6_small_det_onnx", "PP-OCRv6_small_rec_onnx");
        companion object {
            fun fromId(value: String): Version? = values().firstOrNull {
                it.name.equals(value, ignoreCase = true)
            }
        }
    }

    data class ModelFiles(val det: File, val rec: File, val vocab: File)
    private val root = File(context.filesDir, "paddle-mobile-ocr")

    fun files(version: Version): ModelFiles {
        val dir = File(root, version.name.lowercase())
        return ModelFiles(
            File(dir, "det/inference.onnx"),
            File(dir, "rec/inference.onnx"),
            File(dir, "rec/inference.yml")
        )
    }

    fun ready(version: Version): Boolean {
        val f = files(version)
        return f.det.length() > 1_000_000 &&
            f.rec.length() > 1_000_000 && f.vocab.length() > 10_000
    }

    suspend fun download(version: Version, report: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val f = files(version)
            val parts = listOf(
                Triple(f.det, version.detRepo, "inference.onnx"),
                Triple(f.rec, version.recRepo, "inference.onnx"),
                Triple(f.vocab, version.recRepo, "inference.yml")
            )
            try {
                for ((index, item) in parts.withIndex()) {
                    val (dest, repository, name) = item
                    val minimum = if (name.endsWith(".yml")) 10_000L else 1_000_000L
                    if (dest.length() >= minimum) continue
                    val url = "https://huggingface.co/PaddlePaddle/" + repository +
                        "/resolve/main/" + name + "?download=true"
                    report(version.label + " 第 " + (index + 1) + "/3 項下載中…")
                    downloadFile(url, dest) { bytes, total ->
                        val message = if (total > 0) {
                            (bytes * 100L / total).coerceIn(0L, 100L).toString() + "%"
                        } else (bytes / 1024).toString() + " KB"
                        report(version.label + " 第 " + (index + 1) + "/3：" + message)
                    }
                }
                ready(version)
            } catch (e: Exception) {
                Log.w("CameraTranslator", "Paddle model download failed", e)
                report(version.label + " 下載失敗：" + (e.message ?: "網路中斷，請重試"))
                false
            }
        }

    private fun downloadFile(url: String, destination: File, progress: (Long, Long) -> Unit) {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        val start = part.takeIf { it.exists() }?.length() ?: 0L
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 15_000
            con.readTimeout = 30_000
            con.instanceFollowRedirects = true
            if (start > 0) con.setRequestProperty("Range", "bytes=" + start + "-")
            val status = con.responseCode
            if (status !in 200..299) error("HTTP " + status)
            val resume = status == HttpURLConnection.HTTP_PARTIAL && start > 0
            val initial = if (resume) start else 0L
            val remaining = con.contentLengthLong
            val total = if (remaining > 0) initial + remaining else -1L
            val hardLimit = 120_000_000L
            if (total > hardLimit) error("下載模型超出容量限制")
            var downloaded = initial
            FileOutputStream(part, resume).use { output ->
                con.inputStream.use { input ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > hardLimit) error("下載模型超出容量限制")
                        output.write(buffer, 0, count)
                        progress(downloaded, total)
                    }
                }
            }
            if (total > 0 && downloaded != total) error("模型未下載完整，可重新下載")
            val minimum = if (destination.extension == "yml") 10_000L else 1_000_000L
            if (part.length() < minimum) error("下載回應不像有效的模型")
            if (destination.exists() && !destination.delete()) error("無法替換舊模型")
            if (!part.renameTo(destination)) error("儲存模型失敗")
        } finally {
            con.disconnect()
        }
    }
}
