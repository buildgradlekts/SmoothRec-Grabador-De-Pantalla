package com.smoothrec.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.smoothrec.app.encoder.EncoderOutput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * Codificador AAC en un hilo propio. Durante el arranque alimenta silencio para
 * obtener el formato de salida (csd-0) antes de iniciar el vídeo; esas muestras
 * de relleno no se escriben en el MP4.
 */
class AacAudioEncoder(
    private val provider: PcmProvider,
    private val spec: AudioFormatSpec,
    private val output: EncoderOutput,
    private val onSilent: () -> Unit = {},
) {

    private val formatLatch = CountDownLatch(1)
    private val finishedLatch = CountDownLatch(1)
    private val stopping = AtomicBoolean(false)
    private val formatReady = AtomicBoolean(false)
    @Volatile
    private var forwarding = false
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    /** Bloquea hasta que el formato AAC está disponible; false si no se consiguió. */
    fun start(): Boolean {
        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            spec.sampleRate,
            spec.channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, if (spec.stereo) 96_000 else 64_000)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, spec.channelCount)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, spec.sampleRate)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
        }
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
        codec = mediaCodec
        thread = Thread({ pump(mediaCodec) }, "SmoothRecAudioEncoder").apply { start() }

        val ready = formatLatch.await(3, TimeUnit.SECONDS)
        if (!ready) {
            Log.w(TAG, "El encoder AAC no entregó su formato a tiempo")
            stop()
        }
        return ready
    }

    /** Empieza a enviar audio real; los PTS arrancan en cero en este momento. */
    fun resume() {
        forwarding = true
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) {
            // Otra llamada ya está esperando; no salimos hasta que el hilo haya
            // drenado y liberado el codec.
            try {
                finishedLatch.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return
        }
        try {
            finishedLatch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun pump(mediaCodec: MediaCodec) {
        val frameSamples = 1024
        val pcm = ShortArray(frameSamples * spec.channelCount)
        val info = MediaCodec.BufferInfo()

        // Reloj común con el vídeo: el PTS se deriva del instante real en que
        // AudioRecord capturó cada frame, no de un contador local reiniciado en 0.
        var rateHz = spec.sampleRate.coerceAtLeast(1)
        var framesQueued = 0L
        var anchorFrame = 0L
        var anchorNs = 0L
        var anchored = false
        var lastQueuedPtsUs = 0L
        var firstInputPtsUs = -1L
        var lastInputPtsUs = -1L
        var firstOutputPtsUs = -1L
        var lastOutputPtsUs = -1L
        var clockLogged = false
        var capacityLogged = false
        var silenceFramesToDetect = rateHz / frameSamples * 2
        var silentFrames = 0
        var silenceReported = false

        // Medición del PCM real entregado al codificador, antes de AAC.
        var statFrames = 0L
        var statReadSamples = 0L
        var statSamples = 0L
        var statNonZero = 0L
        var statSumSq = 0.0
        var statMax = 0
        var lastStatNs = System.nanoTime()

        fun deliverOutput(index: Int) {
            if (forwarding && info.size > 0 &&
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            ) {
                if (firstOutputPtsUs < 0) firstOutputPtsUs = info.presentationTimeUs
                if (info.presentationTimeUs <= lastOutputPtsUs) {
                    Log.w(
                        TAG,
                        "PTS AAC salida no creciente: ${info.presentationTimeUs} <= $lastOutputPtsUs",
                    )
                }
                lastOutputPtsUs = info.presentationTimeUs
                val buffer = mediaCodec.getOutputBuffer(index)
                if (buffer != null) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    output.onEncodedData(EncoderOutput.TRACK_AUDIO, buffer, info)
                }
            }
            mediaCodec.releaseOutputBuffer(index, false)
        }

        try {
            while (!stopping.get()) {
                // Tras obtener el formato AAC ya no se encola más silencio de
                // arranque: así no quedan frames de relleno al iniciar el audio.
                if (!forwarding && formatReady.get()) {
                    val pendingOutput = mediaCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                    if (pendingOutput >= 0) deliverOutput(pendingOutput)
                    continue
                }
                val inputIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = mediaCodec.getInputBuffer(inputIndex)
                    if (input != null) {
                        // El PTS nace del reloj monotónico y del frame capturado,
                        // nunca de un contador local que empieza en cero.
                        var ptsUs = 0L
                        if (forwarding) {
                            if (!anchored) {
                                val clock = provider.clock()
                                if (clock != null) {
                                    rateHz = clock.sampleRate
                                    silenceFramesToDetect = rateHz / frameSamples * 2
                                    // framePosition y framesQueued cuentan
                                    // muestras por canal (1 frame PCM = 1
                                    // muestra por canal), no bloques de 1024.
                                    val nsPerSample = 1_000_000_000L / rateHz
                                    anchorNs = clock.nanoTime -
                                        (clock.framePosition - framesQueued) * nsPerSample
                                    anchorFrame = framesQueued
                                    if (!clockLogged) {
                                        clockLogged = true
                                        val message = "Audio anclado a getTimestamp: " +
                                            "frame=${clock.framePosition} " +
                                            "nano=${clock.nanoTime} rate=$rateHz"
                                        Log.i(TAG, message)
                                        CaptureDiagnostics.log(message)
                                    }
                                } else {
                                    anchorNs = System.nanoTime()
                                    anchorFrame = framesQueued
                                    if (!clockLogged) {
                                        clockLogged = true
                                        val message = "Audio anclado a nanoTime (sin getTimestamp)"
                                        Log.w(TAG, message)
                                        CaptureDiagnostics.log(message)
                                    }
                                }
                                anchored = true
                            }

                            // Nota: getTimestamp() solo se consulta una vez al
                            // anclar. En este dispositivo las consultas
                            // posteriores pueden bloquearse; el PTS avanza con
                            // frames acumulados y el anclaje inicial basta.
                            // El ritmo lo marca la propia lectura PCM: el
                            // proveedor espera como máximo un frame y devuelve
                            // silencio si no hay datos. No se duplica el reloj
                            // con parkNanos aquí (bloqueaba el hilo en este
                            // dispositivo y deixaba pasar decenas de segundos
                            // entre frames AAC).
                            // Un frame PCM de 16 bits es una muestra por canal;
                            // el PTS avanza 1/rateHz por muestra acumulada.
                            val nsPerSample = 1_000_000_000L / rateHz
                            val frameNs = anchorNs +
                                (framesQueued - anchorFrame) * nsPerSample
                            // PTS absoluto del mismo reloj que el vídeo; el
                            // muxer restará el origen común a ambas pistas.
                            ptsUs = (frameNs / 1000).coerceAtLeast(0)
                        }

                        // PcmSource rellena el frame completo; si la fuente no
                        // entrega datos quedan ceros, pero con el PTS correcto.
                        java.util.Arrays.fill(pcm, 0)
                        val read = if (forwarding) provider.read(pcm) else pcm.size
                        if (forwarding) {
                            statFrames++
                            statReadSamples += read
                            for (sample in pcm) {
                                val value = abs(sample.toInt())
                                statSamples++
                                if (value > 0) statNonZero++
                                statSumSq += sample.toDouble() * sample.toDouble()
                                if (value > statMax) statMax = value
                            }
                            val now = System.nanoTime()
                            if (now - lastStatNs >= 2_000_000_000L && statSamples > 0) {
                                val avgRead = statReadSamples.toDouble() / statFrames
                                val nonZeroPct = 100.0 * statNonZero / statSamples
                                val rms = kotlin.math.sqrt(statSumSq / statSamples)
                                Log.i(
                                    TAG,
                                    "PCM frames=$statFrames leídas/frame=${"%.1f".format(avgRead)} " +
                                        "canales=${spec.channelCount} rate=$rateHz " +
                                        "no_cero=${"%.1f".format(nonZeroPct)}% " +
                                        "RMS=${"%.1f".format(rms)} pico=$statMax " +
                                        "PTS=${"%.3f".format(ptsUs / 1_000_000.0)}s",
                                )
                                CaptureDiagnostics.log(
                                    "PCM codificado: muestras=$statSamples leídas=$statReadSamples " +
                                        "frames=$statFrames canales=${spec.channelCount} " +
                                        "rate=$rateHz no_cero=${"%.1f".format(nonZeroPct)}% " +
                                        "RMS=${"%.1f".format(rms)} pico=$statMax " +
                                        "PTS=${"%.3f".format(ptsUs / 1_000_000.0)}s",
                                )
                                statFrames = 0
                                statReadSamples = 0
                                statSamples = 0
                                statNonZero = 0
                                statSumSq = 0.0
                                statMax = 0
                                lastStatNs = now
                            }
                        }
                        if (forwarding && !silenceReported) {
                            silentFrames = if (read > 0 && hasSignal(pcm, read)) {
                                0
                            } else {
                                silentFrames + 1
                            }
                            if (silentFrames >= silenceFramesToDetect) {
                                silenceReported = true
                                Log.w(TAG, "La fuente de audio seleccionada no entrega señal")
                                CaptureDiagnostics.log(
                                    "Encoder: $silentFrames frames consecutivos sin señal (PTS=${"%.2f".format(ptsUs / 1_000_000.0)}s)",
                                )
                                onSilent()
                            }
                        }
                        input.clear()
                        val shortBuffer = input.asShortBuffer()
                        val capacity = shortBuffer.remaining()
                        if (!capacityLogged) {
                            capacityLogged = true
                            val message = "AAC buffer de entrada=$capacity muestras, frame=${pcm.size}"
                            Log.i(TAG, message)
                            CaptureDiagnostics.log(message)
                        }
                        val count = min(pcm.size, capacity)
                        if (count > 0) {
                            shortBuffer.put(pcm, 0, count)
                            mediaCodec.queueInputBuffer(inputIndex, 0, count * 2, ptsUs, 0)
                            if (forwarding) {
                                if (firstInputPtsUs < 0) firstInputPtsUs = ptsUs
                                lastInputPtsUs = ptsUs
                                lastQueuedPtsUs = ptsUs
                                framesQueued += (count / spec.channelCount).toLong()
                            }
                        }
                    }
                }

                when (val outputIndex = mediaCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        output.onOutputFormatChanged(
                            EncoderOutput.TRACK_AUDIO,
                            mediaCodec.outputFormat,
                        )
                        formatReady.set(true)
                        formatLatch.countDown()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> deliverOutput(outputIndex)
                }
            }

            // Fin de stream: se drenan también las muestras finales al muxer.
            var eosQueued = false
            while (!eosQueued) {
                val index = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index >= 0) {
                    mediaCodec.queueInputBuffer(
                        index,
                        0,
                        0,
                        lastQueuedPtsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    eosQueued = true
                } else {
                    break
                }
            }
            var eosReceived = false
            var guard = 0
            while (!eosReceived && guard++ < 200) {
                val index = mediaCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                if (index >= 0) {
                    eosReceived = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    deliverOutput(index)
                }
            }
            if (!eosReceived) Log.w(TAG, "No se recibió el EOS del codificador AAC")
            val summary = "AAC resumen: rate=$rateHz frames=$framesQueued " +
                "PTS entrada $firstInputPtsUs..$lastInputPtsUs us, " +
                "PTS salida $firstOutputPtsUs..$lastOutputPtsUs us"
            Log.i(TAG, summary)
            CaptureDiagnostics.log(summary)
        } catch (e: Exception) {
            Log.e(TAG, "Error en el hilo del encoder AAC", e)
            CaptureDiagnostics.log("Encoder AAC: error ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                mediaCodec.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "El codec AAC ya estaba detenido", e)
            }
            try {
                mediaCodec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error al liberar el codec AAC", e)
            }
            codec = null
            finishedLatch.countDown()
        }
    }

    private fun hasSignal(buffer: ShortArray, count: Int): Boolean {
        for (i in 0 until count) {
            if (buffer[i].toInt() != 0) return true
        }
        return false
    }

    private companion object {
        const val TAG = "AacAudioEncoder"
        const val INPUT_TIMEOUT_US = 20_000L
        const val OUTPUT_TIMEOUT_US = 20_000L
    }
}
