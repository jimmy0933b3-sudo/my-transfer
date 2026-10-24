package com.example.cameratranslator.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.cameratranslator.core.PhotoSpeechPhrase
import java.util.Locale

/**
 * Photo-only speech controller. Text never leaves the app through this class:
 * Android's selected TTS engine voices the text, and engine/network behavior
 * follows the user's system TTS settings.
 */
internal class PhotoSpeechPlayer(context: Context) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var failedInitialization = false
    private var closed = false
    private var generation = 0L
    private var phrases: List<PhotoSpeechPhrase> = emptyList()
    private var nextPhrase = 0
    private var utteranceId: String? = null

    var isSpeaking by mutableStateOf(false)
        private set
    var isPreparing by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        engine = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        main.post {
            if (closed) return@post
            ready = status == TextToSpeech.SUCCESS
            failedInitialization = !ready
            if (!ready) {
                fail("語音引擎無法啟動，請檢查 Android 文字轉語音設定")
                return@post
            }
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    main.post {
                        if (!closed && utteranceId == this@PhotoSpeechPlayer.utteranceId) {
                            playNext()
                        }
                    }
                }

                @Deprecated("Legacy Android TTS error callback")
                override fun onError(utteranceId: String?) {
                    reportError(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    reportError(utteranceId)
                }
            })
            if (isPreparing && phrases.isNotEmpty()) playNext()
        }
    }

    fun toggle(request: List<PhotoSpeechPhrase>) {
        if (isSpeaking || isPreparing) {
            stop()
            return
        }
        stop()
        if (request.isEmpty()) {
            errorMessage = "目前沒有可朗讀的文字或無法判斷語言"
            return
        }
        if (failedInitialization) {
            errorMessage = "語音引擎未就緒，請在系統設定安裝或啟用文字轉語音"
            return
        }
        phrases = request
        isPreparing = !ready
        if (ready) playNext()
    }

    fun stop() {
        generation++
        utteranceId = null
        phrases = emptyList()
        nextPhrase = 0
        isSpeaking = false
        isPreparing = false
        errorMessage = null
        engine?.stop()
    }

    fun shutdown() {
        if (closed) return
        stop()
        closed = true
        engine?.shutdown()
        engine = null
    }

    private fun playNext() {
        if (closed || !ready) return
        if (nextPhrase >= phrases.size) {
            isSpeaking = false
            isPreparing = false
            utteranceId = null
            phrases = emptyList()
            return
        }
        val phrase = phrases[nextPhrase++]
        val tts = engine ?: return fail("語音引擎已關閉")
        val locale = Locale.forLanguageTag(phrase.languageTag)
        val supported = tts.setLanguage(locale)
        if (supported == TextToSpeech.LANG_MISSING_DATA ||
            supported == TextToSpeech.LANG_NOT_SUPPORTED) {
            fail("缺少 ${locale.displayLanguage} 語音，請在系統文字轉語音設定下載")
            return
        }
        val id = "photo-${generation}-${nextPhrase}"
        utteranceId = id
        isPreparing = false
        isSpeaking = true
        if (tts.speak(phrase.text, TextToSpeech.QUEUE_FLUSH, null, id) ==
            TextToSpeech.ERROR) {
            fail("文字轉語音播放失敗，請檢查系統語音引擎")
        }
    }

    private fun reportError(id: String?) {
        main.post {
            if (!closed && id == utteranceId) {
                fail("語音播放中斷，請檢查 Android 文字轉語音設定")
            }
        }
    }

    private fun fail(message: String) {
        stop()
        errorMessage = message
    }
}
