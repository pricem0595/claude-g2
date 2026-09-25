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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException

private const val TAG = "SpeechTranscriber"

/** The glasses mic's format, which is also the recognizer's default: 16 kHz, 16-bit, mono. */
const val PCM_SAMPLE_RATE = 16_000
const val PCM_BYTES_PER_SECOND = PCM_SAMPLE_RATE * 2

/** After the audio ends, how long the recognizer may take to finish. */
private const val FINISH_TIMEOUT_MS = 20_000L

/** A session nobody finished (the glasses app went away mid-recording) is dropped after this. */
private const val ABANDONED_MS = 90_000L

/**
 * The fastest the recognizer is fed. 21 s of speech written at once came back as three words;
 * at twice real time it came back whole.
 */
private const val MAX_FEED_SPEED = 2
private const val FEED_CHUNK = PCM_BYTES_PER_SECOND / 10

/**
 * One recording being turned into text as it arrives. Audio is 16 kHz signed 16-bit
 * little-endian mono, written as it's recorded.
 */
interface SpeechSession {
    fun write(pcm: ByteArray)

    /** The audio has ended: waits for the text, "" when no words were heard. */
    suspend fun finish(): String

    fun cancel()
}

/** Turns speech into text. Tests and the desktop bridge use a fake. */
interface Transcriber {
    fun start(): SpeechSession
}

/**
 * Transcribes a whole recording at once, which takes half its length: the session feeds the
 * recognizer at most twice real time. The glasses stream instead; this is for testing over adb.
 */
suspend fun Transcriber.transcribe(pcm: ByteArray): String {
    val session = start()
    try {
        session.write(pcm)
        return session.finish()
    } catch (e: Throwable) {
        session.cancel()
        throw e
    }
}

/**
 * Android's own speech recognizer, preferring the on-device one, fed the glasses' audio through
 * a pipe (RecognizerIntent.EXTRA_AUDIO_SOURCE) instead of the phone's microphone.
 */
class SpeechTranscriber(private val context: Context) : Transcriber {
    private val main = Handler(Looper.getMainLooper())

    val onDeviceAvailable: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    val available: Boolean get() = onDeviceAvailable || SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(): SpeechSession {
        if (!available) throw BridgeException(503, "No speech recognizer on this phone")
        return RecognizerSession()
    }

    private inner class RecognizerSession : SpeechSession {
        private val readEnd: ParcelFileDescriptor
        private val out: ParcelFileDescriptor.AutoCloseOutputStream
        private val result = CompletableDeferred<String>()
        private var recognizer: SpeechRecognizer? = null
        private val abandon = Runnable { cancel() }

        // The recognizer splits speech at every pause, and each piece starts its partial
        // results afresh. Asked for a segmented session it hands over each piece as it's done.
        // Without one only the last piece survived (Galaxy Z Fold7): 21 s of speech came back
        // as its final word. So the pieces are collected, either way.
        private val segments = ArrayList<String>()
        // Fallback when segmented mode isn't supported: each piece's last partial, banked when
        // its speech ends. That recognizer's final result was empty.
        private val spoken = ArrayList<String>()
        private var lastPartial = ""

        init {
            val (read, write) = ParcelFileDescriptor.createPipe()
            readEnd = read
            out = ParcelFileDescriptor.AutoCloseOutputStream(write)
            main.post(::begin)
            main.postDelayed(abandon, ABANDONED_MS)
        }

        private fun begin() {
            if (result.isCompleted) return
            val r = if (onDeviceAvailable) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = r
            r.setRecognitionListener(listener)
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PCM_SAMPLE_RATE)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1),
            )
        }

        private var startedNs = 0L
        private var written = 0L

        /**
         * Feeds the recognizer no faster than [MAX_FEED_SPEED] times real time, however the audio
         * arrives: it's built for a live mic and drops what comes faster than that.
         */
        override fun write(pcm: ByteArray) {
            try {
                var at = 0
                while (at < pcm.size) {
                    if (written == 0L) startedNs = System.nanoTime()
                    val n = minOf(FEED_CHUNK, pcm.size - at)
                    val dueNs = startedNs + written * 1_000_000_000L / (PCM_BYTES_PER_SECOND * MAX_FEED_SPEED)
                    val waitMs = (dueNs - System.nanoTime()) / 1_000_000
                    if (waitMs > 0) Thread.sleep(waitMs)
                    out.write(pcm, at, n)
                    at += n
                    written += n
                }
            } catch (e: IOException) {
                // The recognizer stopped reading: it finished early or failed, and says which.
                Log.w(TAG, "Recognizer stopped taking audio", e)
            }
        }

        override suspend fun finish(): String {
            runCatching { out.close() }
            return try {
                withTimeout(FINISH_TIMEOUT_MS) { result.await() }
            } catch (e: TimeoutCancellationException) {
                throw BridgeException(502, "Speech recognition timed out")
            } finally {
                main.post(::release)
            }
        }

        override fun cancel() {
            runCatching { out.close() }
            result.cancel()
            main.post {
                recognizer?.cancel()
                release()
            }
        }

        // startListening() hands the intent over later, from the recognizer's own handler, so the
        // read end stays open until recognition is over. Closing it straight after the call
        // failed on a real phone with "Bad file descriptor".
        private fun release() {
            main.removeCallbacks(abandon)
            recognizer?.destroy()
            recognizer = null
            runCatching { readEnd.close() }
        }

        private fun done(text: Result<String>) {
            text.fold({ result.complete(it) }, { result.completeExceptionally(it) })
        }

        private fun bankPartial() {
            if (lastPartial.isNotEmpty()) spoken += lastPartial
            lastPartial = ""
        }

        private fun heard(finalText: String = ""): String {
            if (segments.isNotEmpty()) return segments.joinToString(" ")
            bankPartial()
            // A final result that isn't empty covers the last piece, which its partials did too.
            if (finalText.isNotEmpty()) {
                if (spoken.isNotEmpty()) spoken.removeAt(spoken.size - 1)
                spoken += finalText
            }
            return spoken.joinToString(" ")
        }

        private val listener = object : RecognitionListener {
            override fun onSegmentResults(segmentResults: Bundle) {
                best(segmentResults).takeIf { it.isNotEmpty() }?.let { segments += it }
                lastPartial = ""
            }

            override fun onEndOfSegmentedSession() = done(Result.success(heard()))

            override fun onResults(results: Bundle?) = done(Result.success(heard(best(results))))

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults).takeIf { it.isNotEmpty() }?.let { lastPartial = it }
            }

            override fun onEndOfSpeech() = bankPartial()

            override fun onError(error: Int) = when (error) {
                // Heard nothing it could make words of: not a failure, just nothing to send.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> done(Result.success(heard()))
                else -> done(Result.failure(BridgeException(502, describeError(error))))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    private fun best(bundle: Bundle?) =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun describeError(code: Int) = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The phone's speech recognizer refused (permissions)"
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
