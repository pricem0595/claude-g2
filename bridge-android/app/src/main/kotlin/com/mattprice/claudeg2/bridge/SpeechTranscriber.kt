package com.mattprice.claudeg2.bridge

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SpeechTranscriber"
private const val TRANSCRIBE_TIMEOUT_MS = 20_000L

/** The glasses mic's format, which is also the recognizer's default: 16 kHz, 16-bit, mono. */
const val PCM_SAMPLE_RATE = 16_000
const val PCM_BYTES_PER_SECOND = PCM_SAMPLE_RATE * 2

/** Turns recorded speech into text. Tests and the desktop bridge use a fake. */
fun interface Transcriber {
    /** [pcm] is 16 kHz signed 16-bit little-endian mono. Returns "" when no words were heard. */
    suspend fun transcribe(pcm: ByteArray): String
}

/**
 * Android's own speech recognizer, preferring the on-device one, fed the glasses' recording
 * through a pipe (RecognizerIntent.EXTRA_AUDIO_SOURCE) instead of the phone's microphone.
 * A recognizer is allowed to ignore that extra; if it does, it listens to the phone's mic.
 */
class SpeechTranscriber(private val context: Context) : Transcriber {
    private val main = Handler(Looper.getMainLooper())

    /** One recognition at a time: the recognizer service rejects overlapping ones as busy. */
    private val lock = Mutex()

    val onDeviceAvailable: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val available: Boolean get() = onDeviceAvailable || SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun transcribe(pcm: ByteArray): String = lock.withLock {
        if (!available) throw BridgeException(503, "No speech recognizer on this phone")
        try {
            withTimeout(TRANSCRIBE_TIMEOUT_MS) { recognize(pcm) }
        } catch (e: TimeoutCancellationException) {
            throw BridgeException(502, "Speech recognition timed out")
        }
    }

    private suspend fun recognize(pcm: ByteArray): String = suspendCancellableCoroutine { cont ->
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        var recognizer: SpeechRecognizer? = null

        // startListening() hands the intent over later, from the recognizer's own handler, so the
        // read end must stay open until recognition is over. Closing it straight after the call
        // failed on a real phone with "Bad file descriptor".
        fun release() {
            recognizer?.destroy()
            recognizer = null
            runCatching { readEnd.close() }
        }

        fun finish(result: Result<String>) {
            main.post(::release)
            if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }

        cont.invokeOnCancellation { main.post { recognizer?.cancel(); release() } }

        main.post {
            if (!cont.isActive) return@post
            val r = if (onDeviceAvailable) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = r
            // Google's on-device recognizer, reading from a pipe, streams the words as partial
            // results and then sends an empty final result (seen on a Galaxy Z Fold7). So keep
            // the latest partial and fall back to it.
            var lastPartial = ""
            fun best(bundle: Bundle?) =
                bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    finish(Result.success(best(results).ifEmpty { lastPartial }))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    best(partialResults).takeIf { it.isNotEmpty() }?.let { lastPartial = it }
                }

                override fun onError(error: Int) {
                    when (error) {
                        // Heard nothing it could make words of: not a failure, just nothing to send.
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(Result.success(lastPartial))
                        else -> finish(Result.failure(BridgeException(502, describeError(error))))
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PCM_SAMPLE_RATE)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            r.startListening(intent)

            // A pipe holds only ~64 KB, so write from another thread while the recognizer reads.
            // Closing the write end is the end of the speech.
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(pcm) }
                } catch (e: java.io.IOException) {
                    // The recognizer stopped reading (it finished early, or failed and says so).
                    Log.w(TAG, "Pipe closed before all audio was written", e)
                }
            }, "speech-pipe").start()
        }
    }

    private fun describeError(code: Int) = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Speech needs the microphone permission: open Claude G2 Bridge"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Speech: this language isn't supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech: the language isn't downloaded yet"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs a network connection"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech recognizer stopped"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech: too many requests"
        SpeechRecognizer.ERROR_AUDIO -> "Speech recognizer couldn't read the audio"
        else -> "Speech recognition failed ($code)"
    }
}
