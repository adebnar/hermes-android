package com.hermes.client.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Speaks text aloud; [speaking] is true while an utterance is playing. */
interface TextToSpeechController {
    val speaking: StateFlow<Boolean>
    fun speak(text: String)
    fun stop()
}

private const val UTTERANCE_ID = "hermes-read-aloud"

/** Android [TextToSpeech]-backed controller. Init is async; a speak before ready is queued. */
class AndroidTtsManager(context: Context) : TextToSpeechController {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var ready = false
    private var pending: String? = null

    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) { _speaking.value = false }
                @Deprecated("legacy") override fun onError(utteranceId: String?) { _speaking.value = false }
                override fun onError(utteranceId: String?, errorCode: Int) { _speaking.value = false }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { _speaking.value = false }
            })
            pending?.let { speak(it); pending = null }
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) { pending = text; return }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun stop() {
        pending = null
        engine.stop()
        _speaking.value = false
    }
}
