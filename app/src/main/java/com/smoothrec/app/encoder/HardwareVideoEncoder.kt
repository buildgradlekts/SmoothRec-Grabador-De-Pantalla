package com.smoothrec.app.encoder

import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encoder H.264 asíncrono con entrada Surface, pensado para alimentarse desde
 * un VirtualDisplay de MediaProjection.
 */
class HardwareVideoEncoder(private val codecName: String) : VideoEncoder {

    private val thread = HandlerThread("SmoothRecEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val eosLatch = CountDownLatch(1)
    private val released = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var output: EncoderOutput? = null
    private var buffersDelivered = 0
    private var firstRawPts = -1L
    private var lastRawPts = -1L

    override fun start(config: EncoderConfig, output: EncoderOutput) {
        this.output = output
        val mediaCodec = MediaCodec.createByCodecName(codecName)

        mediaCodec.configure(
            config.toMediaFormat(),
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        val inputSurface = mediaCodec.createInputSurface()

        mediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // La entrada se hace mediante Surface; no se usan buffers de entrada.
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                val buffer: ByteBuffer? = try {
                    codec.getOutputBuffer(index)
                } catch (e: IllegalStateException) {
                    null
                }

                if (buffer != null && info.size > 0 &&
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    buffersDelivered++
                    if (firstRawPts < 0) firstRawPts = info.presentationTimeUs
                    lastRawPts = info.presentationTimeUs
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    try {
                        this@HardwareVideoEncoder.output?.onEncodedData(EncoderOutput.TRACK_VIDEO, buffer, info)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error al entregar paquete codificado", e)
                    }
                }

                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Buffer ya liberado", e)
                }

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    eosLatch.countDown()
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: android.media.MediaFormat) {
                this@HardwareVideoEncoder.output?.onOutputFormatChanged(EncoderOutput.TRACK_VIDEO, format)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Error del MediaCodec", e)
                eosLatch.countDown()
            }
        }, handler)

        mediaCodec.start()
        codec = mediaCodec
        surface = inputSurface
    }

    override fun inputSurface(): Surface =
        surface ?: throw IllegalStateException("El encoder todavía no está iniciado")

    override fun signalEndOfStream() {
        val mediaCodec = codec ?: return
        try {
            mediaCodec.signalEndOfInputStream()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "No se pudo señalar fin de stream", e)
        }
    }

    override fun stop() {
        if (codec == null) return
        try {
            eosLatch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Log.i(
            TAG,
            "Vídeo drenado: buffers=$buffersDelivered PTS crudos $firstRawPts..$lastRawPts us",
        )
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return

        try {
            codec?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "El codec ya estaba detenido", e)
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar el codec", e)
        }
        try {
            surface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar la Surface", e)
        }

        codec = null
        surface = null
        output = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        try {
            thread.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val TAG = "HardwareVideoEncoder"
    }
}
