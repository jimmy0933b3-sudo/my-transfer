package com.example.cameratranslator.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Sends recognized text to the authenticated Worker, never the Azure key. */
class CloudTranslationProvider {
    companion object {
        const val WORKER_URL = "https://camera-translator-api.jimmy0933.workers.dev"
    }

    sealed class BatchResult {
        data class Success(val translations: List<String>) : BatchResult()
        object Unsupported : BatchResult()
        object Unavailable : BatchResult()
    }

    /**
     * One HTTP request for up to six independent OCR blocks of the same source
     * language. The Worker translates them as separate Azure items, preserving
     * sentence boundaries and response alignment. HTTP 404 means the old
     * production Worker is still deployed: callers can safely use single-item
     * /translate without requiring a coordinated rollout.
     */
    suspend fun translateBatch(
        texts: List<String>, source: String, target: String, token: String
    ): BatchResult = withContext(Dispatchers.IO) {
        if (token.length < 32 || texts.isEmpty() || texts.size > 6 ||
            texts.any { it.isBlank() || it.codePointCount(0, it.length) > 1500 } ||
            texts.sumOf { it.codePointCount(0, it.length) } > 6000
        ) return@withContext BatchResult.Unavailable

        var connection: HttpsURLConnection? = null
        try {
            connection = URL(WORKER_URL + "/translate-batch")
                .openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 4000
            connection.readTimeout = 9000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + token)
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            val payload = JSONObject()
                .put("texts", JSONArray(texts))
                .put("from", source)
                .put("to", target)
                .toString()
            connection.outputStream.use {
                it.write(payload.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            if (code == 404) return@withContext BatchResult.Unsupported
            if (code !in 200..299) {
                Log.w("CameraTranslator", "Cloud batch translation HTTP " + code)
                return@withContext BatchResult.Unavailable
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val items = JSONObject(response).optJSONArray("translations")
                ?: return@withContext BatchResult.Unavailable
            if (items.length() != texts.size) return@withContext BatchResult.Unavailable
            val translated = (0 until items.length()).map { items.optString(it, "") }
            if (translated.any { it.isBlank() }) return@withContext BatchResult.Unavailable
            BatchResult.Success(translated)
        } catch (e: Exception) {
            Log.w("CameraTranslator", "Cloud batch translation unavailable", e)
            BatchResult.Unavailable
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun translate(text: String, source: String, target: String, token: String): String? =
        withContext(Dispatchers.IO) {
            if (token.length < 32 || text.isBlank() || text.codePointCount(0, text.length) > 1500) {
                return@withContext null
            }
            var connection: HttpsURLConnection? = null
            try {
                connection = URL(WORKER_URL + "/translate").openConnection() as HttpsURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 4000
                connection.readTimeout = 9000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                val payload = JSONObject()
                    .put("text", text)
                    .put("from", source)
                    .put("to", target)
                    .toString()
                connection.outputStream.use {
                    it.write(payload.toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.w("CameraTranslator", "Cloud translation HTTP " + code)
                    return@withContext null
                }
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                JSONObject(response).optString("text").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.w("CameraTranslator", "Cloud translation unavailable", e)
                null
            } finally {
                connection?.disconnect()
            }
        }
}
