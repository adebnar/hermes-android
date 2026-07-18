package com.hermes.client.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** A captured voice note. */
data class Recording(val bytes: ByteArray, val mime: String) {
    override fun equals(other: Any?) =
        other is Recording && mime == other.mime && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * bytes.contentHashCode() + mime.hashCode()
}

/** Records a single voice note. Interface so RecordTaskViewModel is testable with a fake. */
interface AudioRecorder {
    fun start()
    fun stop(): Recording?
    fun cancel()
}

/** MediaRecorder-backed recorder writing audio/mp4 (AAC) to an app-cache temp file. */
class MediaAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun start() {
        if (recorder != null) return
        val file = File.createTempFile("rec_", ".m4a", context.cacheDir)
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                  else @Suppress("DEPRECATION") MediaRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioEncodingBitRate(96_000)
        rec.setAudioSamplingRate(44_100)
        rec.setOutputFile(file.absolutePath)
        try {
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            runCatching { rec.release() }
            file.delete()
            throw e
        }
        recorder = rec
        outputFile = file
    }

    override fun stop(): Recording? {
        val rec = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        val stopped = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        if (!stopped || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        return bytes?.let { Recording(it, "audio/mp4") }
    }

    override fun cancel() {
        val rec = recorder ?: return
        val file = outputFile
        recorder = null
        outputFile = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        file?.delete()
    }
}
