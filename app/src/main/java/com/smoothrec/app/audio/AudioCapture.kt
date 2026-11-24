package com.smoothrec.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.locks.LockSupport
import com.smoothrec.app.R
import com.smoothrec.app.recording.AudioSourceOption
import com.smoothrec.app.recording.ChannelOption
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Formato PCM de destino de la pista de audio.
 *
 * La máscara de entrada se usa para AudioRecord del micrófono y para calcular
 * el búfer mínimo. La máscara de salida es la que usa AOSP para la captura de
 * reproducción (AudioPlaybackCaptureConfiguration + AudioMix). Para estéreo
 * ambas valen 0x0C, pero para mono no son iguales: CHANNEL_IN_MONO es 0x10 y
 * CHANNEL_OUT_MONO es 0x04. Usar la máscara correcta evita que algunos
 * dispositivos acepten el AudioRecord pero entreguen silencio.
 */
data class AudioFormatSpec(
    val sampleRate: Int,
    val channelCount: Int,
) {
    val inputChannelConfig: Int
        get() = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO
        else AudioFormat.CHANNEL_IN_STEREO

    val outputChannelConfig: Int
        get() = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO
        else AudioFormat.CHANNEL_OUT_STEREO

    val stereo: Boolean get() = channelCount == 2

    fun buildMicFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(inputChannelConfig)
        .build()

    fun buildPlaybackCaptureFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(outputChannelConfig)
        .build()
}

/** Correspondencia real entre el frame capturado y el reloj monotónico. */
data class PcmClock(
    val framePosition: Long,
    val nanoTime: Long,
    val sampleRate: Int,
)

interface PcmProvider {
    /** Número de muestras (no bytes) escritas en el buffer, ya en el formato destino. */
    fun read(buffer: ShortArray): Int

    /** Relación frame/nanoTime de AudioRecord; null si el dispositivo no la expone. */
    fun clock(): PcmClock? = null
}

/**
 * Una fuente PCM: audio interno de otras apps o micrófono.
 *
 * El número de canales que entrega AudioRecord puede no coincidir con el pedido
 * (algunos dispositivos ignoran la máscara). Por eso se consulta el canal real
 * y se convierte de forma explícita al formato destino antes de codificar.
 */
class PcmSource private constructor(
    private val record: AudioRecord,
    val spec: AudioFormatSpec,
    sourceChannelCount: Int,
    private val label: String,
    val isInternal: Boolean,
) : PcmProvider {

    private val sourceChannelCount: Int = sourceChannelCount.coerceAtLeast(1)
    private var started = false
    private var callbackRegistered = false
    private var conversionBuffer = ShortArray(0)
    @Volatile private var readErrorLogged = false
    @Volatile private var firstDataLogged = false
    @Volatile private var firstSignalLogged = false
    @Volatile private var clockLogged = false
    private var silentChunks = 0

    val actualSampleRate: Int get() = record.sampleRate
    val actualChannelCount: Int get() = sourceChannelCount

    private val callbackExecutor = Executor { command -> command.run() }

    // Se guarda como Any para no resolver AudioManager.AudioRecordingCallback
    // (API 29) en dispositivos antiguos donde este código nunca se ejecuta.
    private var recordingCallback: Any? = null

    fun start(): Boolean {
        if (started) return true
        return try {
            if (isInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val callback = object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(
                            configs: MutableList<AudioRecordingConfiguration>,
                        ) {
                            for (config in configs) {
                                Log.w(
                                    TAG,
                                    "$label cambio de configuración: " +
                                        "silenciado=${config.isClientSilenced} " +
                                        "fuente=${config.audioSource} " +
                                        "rate=${config.format.sampleRate} " +
                                        "canales=${config.format.channelCount} " +
                                        "formato=${config.format}",
                                )
                                CaptureDiagnostics.log(
                                    "$label config cambiada: silenciado=${config.isClientSilenced} " +
                                        "fuente=${config.audioSource} canales=${config.format.channelCount}",
                                )
                            }
                        }
                    }
                    recordingCallback = callback
                    record.registerAudioRecordingCallback(callbackExecutor, callback)
                    callbackRegistered = true
                } catch (e: Exception) {
                    Log.w(TAG, "$label no pudo registrar el callback de audio", e)
                }
            }
            record.startRecording()
            started = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            Log.i(
                TAG,
                "$label iniciado: estado=${record.state} grabando=${record.recordingState} " +
                    "canales=$sourceChannelCount->${spec.channelCount} " +
                    "rateReal=${record.sampleRate} rateDestino=${spec.sampleRate} " +
                    "bufferFrames=${record.bufferSizeInFrames} fuente=${record.audioSource}",
            )
            CaptureDiagnostics.log(
                "$label iniciado: grabando=$started canales=$sourceChannelCount->${spec.channelCount} " +
                    "rateReal=${record.sampleRate} rateDestino=${spec.sampleRate} " +
                    "bufferFrames=${record.bufferSizeInFrames}",
            )
            if (record.sampleRate != spec.sampleRate) {
                Log.w(TAG, "$label: rate real ${record.sampleRate} != destino ${spec.sampleRate}")
                CaptureDiagnostics.log(
                    "$label AVISO: rate real ${record.sampleRate} != destino ${spec.sampleRate}; " +
                        "los PTS usan el rate real",
                )
            }
            if (!started) {
                Log.w(TAG, "$label no quedó en estado RECORDING")
                unregisterCallback()
            } else {
                logActiveConfiguration()
            }
            started
        } catch (e: IllegalStateException) {
            Log.w(TAG, "No se pudo iniciar $label", e)
            unregisterCallback()
            false
        }
    }

    private fun logActiveConfiguration() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val active = record.activeRecordingConfiguration
            if (active == null) {
                CaptureDiagnostics.log("$label: activeRecordingConfiguration es null")
                return
            }
            Log.i(
                TAG,
                "$label configuración activa: silenciado=${active.isClientSilenced} " +
                    "fuente=${active.audioSource} rate=${active.format.sampleRate} " +
                    "canales=${active.format.channelCount} formato=${active.format}",
            )
            CaptureDiagnostics.log(
                "$label configuración activa: silenciado=${active.isClientSilenced} " +
                    "fuente=${active.audioSource} canales=${active.format.channelCount}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "$label no pudo leer la configuración activa", e)
            CaptureDiagnostics.log("$label: error al leer configuración activa: ${e.message}")
        }
    }

    override fun read(buffer: ShortArray): Int = readInto(buffer, 0, buffer.size)

    override fun clock(): PcmClock? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            val stamp = AudioTimestamp()
            var base = AudioTimestamp.TIMEBASE_MONOTONIC
            var ok = record.getTimestamp(stamp, base) == 0
            if (!ok) {
                base = AudioTimestamp.TIMEBASE_BOOTTIME
                ok = record.getTimestamp(stamp, base) == 0
            }
            if (!ok) return null
            // BOOTTIME incluye el sueño profundo; lo convertimos a la misma
            // base monotónica que usa System.nanoTime() y el PTS del vídeo.
            val nanoTime = if (base == AudioTimestamp.TIMEBASE_BOOTTIME) {
                stamp.nanoTime + (System.nanoTime() - stamp.nanoTime)
            } else {
                stamp.nanoTime
            }
            if (!clockLogged) {
                clockLogged = true
                val baseName = if (base == AudioTimestamp.TIMEBASE_MONOTONIC) {
                    "MONOTONIC"
                } else {
                    "BOOTTIME"
                }
                val message = "$label getTimestamp disponible ($baseName): " +
                    "frame=${stamp.framePosition} nano=$nanoTime rate=${record.sampleRate}"
                Log.i(TAG, message)
                CaptureDiagnostics.log(message)
            }
            PcmClock(stamp.framePosition, nanoTime, record.sampleRate.coerceAtLeast(1))
        } catch (e: Exception) {
            if (!clockLogged) {
                clockLogged = true
                val message = "$label getTimestamp NO disponible: " +
                    "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, message)
                CaptureDiagnostics.log(message)
            }
            null
        }
    }

    /** Drena lo que haya disponible sin esperar; mantiene el búfer al día. */
    fun readAvailable(buffer: ShortArray): Int = readIntoWithTimeout(buffer, 0, buffer.size, 0L)

    fun readInto(buffer: ShortArray, offset: Int, length: Int): Int =
        readIntoWithTimeout(buffer, offset, length, frameDurationNs(length))

    private fun readIntoWithTimeout(
        buffer: ShortArray,
        offset: Int,
        length: Int,
        timeoutNs: Long,
    ): Int {
        if (!started || length <= 0) return 0
        val targetChannels = spec.channelCount
        if (sourceChannelCount == targetChannels) {
            return readRaw(buffer, offset, length, timeoutNs)
        }

        // Conversión explícita entre canales. Se calculan los frames destino y
        // se leen exactamente las muestras de origen necesarias.
        val frames = length / targetChannels
        val sourceSamples = frames * sourceChannelCount
        if (frames <= 0 || sourceSamples <= 0) return 0
        if (conversionBuffer.size < sourceSamples) conversionBuffer = ShortArray(sourceSamples)
        val temp = conversionBuffer
        val read = readRaw(temp, 0, sourceSamples, timeoutNs)
        if (read <= 0) return 0
        val readFrames = read / sourceChannelCount

        return when {
            sourceChannelCount == 2 && targetChannels == 1 -> {
                // Estéreo -> mono: media de ambos canales, sin desbordar.
                for (i in 0 until readFrames) {
                    val left = temp[2 * i].toInt()
                    val right = temp[2 * i + 1].toInt()
                    buffer[offset + i] = ((left + right) / 2).toShort()
                }
                readFrames
            }
            sourceChannelCount == 1 && targetChannels == 2 -> {
                // Mono -> estéreo: el mismo sample en ambos canales.
                for (i in 0 until readFrames) {
                    val sample = temp[i]
                    buffer[offset + 2 * i] = sample
                    buffer[offset + 2 * i + 1] = sample
                }
                readFrames * 2
            }
            else -> {
                // Canales inesperados (>2): se replica/recorta de forma segura.
                for (i in 0 until readFrames) {
                    for (c in 0 until targetChannels) {
                        val sourceIndex = min(c, sourceChannelCount - 1)
                        buffer[offset + i * targetChannels + c] =
                            temp[i * sourceChannelCount + sourceIndex]
                    }
                }
                readFrames * targetChannels
            }
        }
    }

    private fun frameDurationNs(targetSamples: Int): Long =
        targetSamples.toLong() * 1_000_000_000L / (spec.sampleRate * spec.channelCount)

    /**
     * Lee hasta completar [length] muestras con un plazo máximo. Un read() no
     * bloqueante devuelve muy pocas muestras; sin este bucle el nivel cae
     * decenas de dB y los PTS avanzan sin reloj real.
     */
    private fun readRaw(
        buffer: ShortArray,
        offset: Int,
        length: Int,
        timeoutNs: Long,
    ): Int {
        val deadline = System.nanoTime() + timeoutNs
        var total = 0
        return try {
            while (total < length) {
                val count = record.read(
                    buffer,
                    offset + total,
                    length - total,
                    AudioRecord.READ_NON_BLOCKING,
                )
                if (count > 0) {
                    total += count
                    if (!firstDataLogged) {
                        firstDataLogged = true
                        Log.i(TAG, "$label entrega datos PCM (primer read=$count muestras)")
                        CaptureDiagnostics.log("$label primeros datos PCM: $count muestras")
                    }
                    checkFirstSignal(buffer, offset + total - count, count)
                    continue
                }
                if (count < 0 && !readErrorLogged) {
                    readErrorLogged = true
                    Log.w(TAG, "$label: AudioRecord.read devolvió error $count")
                    CaptureDiagnostics.log("$label: AudioRecord.read devolvió error $count")
                }
                if (count == AudioRecord.ERROR_DEAD_OBJECT) break
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                LockSupport.parkNanos(minOf(remaining, 1_000_000L))
            }
            total
        } catch (e: IllegalStateException) {
            if (!readErrorLogged) {
                readErrorLogged = true
                Log.w(TAG, "$label: AudioRecord.read lanzó IllegalStateException", e)
                CaptureDiagnostics.log("$label: AudioRecord.read lanzó IllegalStateException")
            }
            0
        }
    }

    /** Distingue "hay búfer" de "hay audio real": un búfer de ceros es silencio. */
    private fun checkFirstSignal(buffer: ShortArray, offset: Int, count: Int) {
        if (firstSignalLogged) return
        var maxSample = 0
        for (i in 0 until count) {
            val value = abs(buffer[offset + i].toInt())
            if (value > maxSample) maxSample = value
        }
        if (maxSample >= SIGNAL_THRESHOLD) {
            firstSignalLogged = true
            Log.i(TAG, "$label entrega señal real (pico=$maxSample)")
            CaptureDiagnostics.log("$label SEÑAL REAL detectada (pico=$maxSample)")
            return
        }
        silentChunks++
        if (silentChunks == 50 || silentChunks == 200 || silentChunks == 600) {
            CaptureDiagnostics.log(
                "$label: $silentChunks bloques leídos sin señal (último pico=$maxSample, muestras=$count)",
            )
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "$label ya estaba detenido", e)
        }
        unregisterCallback()
    }

    fun release() {
        stop()
        unregisterCallback()
        try {
            record.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar $label", e)
        }
    }

    private fun unregisterCallback() {
        if (!callbackRegistered) return
        callbackRegistered = false
        val callback = recordingCallback as? AudioManager.AudioRecordingCallback
        recordingCallback = null
        if (callback != null) {
            try {
                record.unregisterAudioRecordingCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "$label no pudo desregistrar el callback de audio", e)
            }
        }
    }

    companion object {
        private const val TAG = "PcmSource"
        private const val SIGNAL_THRESHOLD = 8

        fun createInternal(projection: MediaProjection, spec: AudioFormatSpec): PcmSource? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return try {
                // Igual que AOSP: todas las reproducciones capturables, sin
                // excluir UIDs. La app no reproduce audio propio durante la
                // grabación, así que no hay realimentación.
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val minBuffer = AudioRecord.getMinBufferSize(
                    spec.sampleRate,
                    spec.inputChannelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) {
                    Log.w(TAG, "Audio interno: búfer mínimo inválido ($minBuffer)")
                    return null
                }
                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(spec.buildPlaybackCaptureFormat())
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "Audio interno no inicializado: estado=${record.state}")
                    record.release()
                    return null
                }
                Log.i(
                    TAG,
                    "Audio interno creado: canales=${record.channelCount} rate=${record.sampleRate} " +
                        "máscara=0x${Integer.toHexString(spec.outputChannelConfig)} " +
                        "búfer=${record.bufferSizeInFrames} frames",
                )
                CaptureDiagnostics.log(
                    "Audio interno creado: canales=${record.channelCount} rate=${record.sampleRate} " +
                        "máscara=0x${Integer.toHexString(spec.outputChannelConfig)}",
                )
                PcmSource(record, spec, record.channelCount, "audio interno", isInternal = true)
            } catch (e: Exception) {
                Log.w(TAG, "Audio interno no disponible: $spec", e)
                CaptureDiagnostics.log("Audio interno NO disponible: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        fun createMic(spec: AudioFormatSpec): PcmSource? {
            return try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    spec.sampleRate,
                    spec.inputChannelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) {
                    Log.w(TAG, "Micrófono: búfer mínimo inválido ($minBuffer)")
                    return null
                }
                val record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(spec.buildMicFormat())
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "Micrófono no inicializado: estado=${record.state}")
                    record.release()
                    return null
                }
                Log.i(
                    TAG,
                    "Micrófono creado: canales=${record.channelCount} rate=${record.sampleRate} " +
                        "búfer=${record.bufferSizeInFrames} frames",
                )
                PcmSource(record, spec, record.channelCount, "micrófono", isInternal = false)
            } catch (e: Exception) {
                Log.w(TAG, "Micrófono no disponible: $spec", e)
                null
            }
        }
    }
}

/** Proveedor que empieza a entregar audio cuando las fuentes ya están creadas. */
class DeferredPcmProvider : PcmProvider {
    @Volatile private var delegate: PcmProvider? = null

    fun setDelegate(provider: PcmProvider?) {
        delegate = provider
    }

    override fun read(buffer: ShortArray): Int = delegate?.read(buffer) ?: 0

    override fun clock(): PcmClock? = delegate?.clock()
}

/** Mezcla dos fuentes PCM con limitación suave para evitar saturación. */
class AudioMixer(private val sources: List<PcmSource>) : PcmProvider {

    private var left = ShortArray(0)
    private var right = ShortArray(0)

    override fun read(buffer: ShortArray): Int {
        if (sources.size == 1) return sources[0].read(buffer)
        if (sources.size < 2) return 0

        if (left.size != buffer.size) {
            left = ShortArray(buffer.size)
            right = ShortArray(buffer.size)
        }
        // Ambas fuentes se drenan sin bloquear con la misma duración de frame.
        // Leer una en bloqueo hasta completarla podía esperar varios frames y
        // desalinear la mezcla; aquí cada bloque se alinea por índice de muestra.
        val leftCount = sources[0].readAvailable(left)
        val rightCount = sources[1].readAvailable(right)
        val count = minOf(buffer.size, maxOf(leftCount, rightCount))

        for (i in 0 until count) {
            val l = if (i < leftCount) left[i].toFloat() else 0f
            val r = if (i < rightCount) right[i].toFloat() else 0f
            buffer[i] = softLimit(l * MIX_GAIN + r * MIX_GAIN)
        }
        return count
    }

    override fun clock(): PcmClock? =
        sources.asSequence().mapNotNull { it.clock() }.firstOrNull()

    private fun softLimit(value: Float): Short {
        val normalized = value / 32768f
        val limited = if (abs(normalized) <= KNEE) {
            normalized
        } else {
            sign(normalized) * (KNEE + (1f - KNEE) * tanh((abs(normalized) - KNEE) / (1f - KNEE)))
        }
        return (limited * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }

    private companion object {
        const val MIX_GAIN = 0.7f
        const val KNEE = 0.75f
    }
}

/**
 * Usa la fuente principal (audio interno) cuando entrega señal y la alternativa
 * (micrófono) cuando la principal está en silencio. La decisión se toma frame a
 * frame y se mantienen ambas fuentes drenadas, de modo que:
 *  - si el audio interno empieza a sonar más tarde, se usa en ese momento;
 *  - si la app de origen no permite captura, el micrófono cubre el hueco;
 *  - nunca se queda un MP4 mudo por un fallo puntual de la fuente principal.
 *
 * La lectura de la fuente principal es no bloqueante para no añadir latencia.
 * La alternativa solo se lee en bloqueo cuando hace falta, con un plazo igual
 * a la duración del frame, para que el reloj del encoder siga siendo real.
 */
class SilenceFallbackProvider(
    private val primary: PcmSource,
    private val fallback: PcmSource?,
    private val onFallback: () -> Unit,
) : PcmProvider {

    private var primaryBuffer = ShortArray(0)
    private var fallbackBuffer = ShortArray(0)
    @Volatile private var announced = false
    private var usingPrimary = false
    private var warmupFrames = 0

    override fun read(buffer: ShortArray): Int {
        if (primaryBuffer.size != buffer.size) {
            primaryBuffer = ShortArray(buffer.size)
            fallbackBuffer = ShortArray(buffer.size)
        }

        // Al arrancar, AudioRecord tarda unos milisegundos en llenar su búfer.
        // Sin esta espera el primer frame saldría del micrófono aunque el audio
        // interno funcione. Solo se espera al principio, nunca en régimen normal.
        val warmingUp = !usingPrimary && !announced && warmupFrames < WARMUP_FRAMES
        if (warmingUp) warmupFrames++
        val primaryCount = if (warmingUp) {
            primary.readInto(primaryBuffer, 0, primaryBuffer.size)
        } else {
            primary.readAvailable(primaryBuffer)
        }
        if (primaryCount > 0 && hasSignal(primaryBuffer, primaryCount)) {
            if (!usingPrimary) {
                usingPrimary = true
                Log.i(TAG, "Fuente principal con señal: se usa el audio interno")
            }
            // Se drena la alternativa sin bloquear para que no acumule audio viejo.
            fallback?.readAvailable(fallbackBuffer)
            System.arraycopy(primaryBuffer, 0, buffer, 0, primaryCount)
            return primaryCount
        }

        val fallbackCount = fallback?.read(fallbackBuffer) ?: 0
        if (fallbackCount > 0) {
            if (!announced) {
                announced = true
                usingPrimary = false
                Log.w(TAG, "Fuente principal sin señal: se usa la alternativa")
                onFallback()
            }
            System.arraycopy(fallbackBuffer, 0, buffer, 0, fallbackCount)
            return fallbackCount
        }

        // Sin datos de la alternativa: se devuelven los ceros de la principal
        // para conservar el ritmo real del encoder (nunca se inventa audio).
        if (primaryCount > 0) {
            System.arraycopy(primaryBuffer, 0, buffer, 0, primaryCount)
            return primaryCount
        }
        return 0
    }

    override fun clock(): PcmClock? = primary.clock() ?: fallback?.clock()

    /** Umbral bajo pero real: evita tratar dither de ±1 como señal útil. */
    private fun hasSignal(buffer: ShortArray, count: Int): Boolean {
        for (i in 0 until count) {
            if (abs(buffer[i].toInt()) >= SIGNAL_THRESHOLD) return true
        }
        return false
    }

    private companion object {
        const val TAG = "SilenceFallbackProvider"
        const val SIGNAL_THRESHOLD = 8
        const val WARMUP_FRAMES = 3
    }
}

/** Fuente PCM lista para codificar. Las fuentes reales se crean tras el VirtualDisplay. */
class AudioPlan(
    val provider: DeferredPcmProvider,
    val spec: AudioFormatSpec,
    val option: AudioSourceOption,
    val initialNoticeRes: Int?,
    private val starter: () -> AudioSourceStart,
) {
    private var sources: List<PcmSource> = emptyList()

    /**
     * Crea y arranca los AudioRecord. Debe llamarse después de crear el
     * VirtualDisplay, igual que hace AOSP en su grabador de pantalla.
     */
    fun startSources(): AudioSourceStart {
        val result = starter()
        sources = result.sources
        provider.setDelegate(result.provider)
        return result
    }

    fun release() {
        sources.forEach { it.release() }
        sources = emptyList()
        provider.setDelegate(null)
    }
}

/** Resultado real de crear/arrancar las fuentes, con el aviso que corresponda. */
data class AudioSourceStart(
    val provider: PcmProvider?,
    val sources: List<PcmSource>,
    val noticeRes: Int? = null,
) {
    val success: Boolean get() = provider != null
}

/** Resultado de la detección real: puede incluir un aviso para el usuario. */
data class AudioSetupResult(
    val plan: AudioPlan?,
    val noticeRes: Int? = null,
)

/** Detecta qué modalidad y formato puede usar realmente este dispositivo. */
object AudioCaptureFactory {

    private const val TAG = "AudioCaptureFactory"

    /**
     * AOSP usa 44100 Hz para la captura interna. Se prueba primero esa tasa
     * porque es la más compatible con el AudioMix de reproducción; después
     * 48000 Hz, que es la tasa nativa habitual del dispositivo.
     */
    private val INTERNAL_RATES = intArrayOf(44_100, 48_000)
    private val MIC_RATES = intArrayOf(48_000, 44_100)

    fun create(
        context: Context,
        projection: MediaProjection,
        option: AudioSourceOption,
        channelOption: ChannelOption,
        onFallback: () -> Unit = {},
    ): AudioSetupResult {
        CaptureDiagnostics.reset()
        CaptureDiagnostics.log("Nueva grabación: fuente=${option.key} canales=${channelOption.key}")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AudioSetupResult(null, R.string.audio_fallback_permission)
        }

        // 1) Se intenta exactamente el número de canales elegido por el usuario.
        chooseSpec(option, channelOption)?.let { spec ->
            val plan = buildPlan(projection, option, spec, onFallback, initialNoticeRes = null)
            Log.i(
                TAG,
                "Audio ${option.key}: ${spec.sampleRate} Hz, " +
                    "${spec.channelCount} canal(es) [${channelOption.key}]",
            )
            CaptureDiagnostics.log(
                "Formato elegido: ${spec.sampleRate} Hz, ${spec.channelCount} canal(es)",
            )
            return AudioSetupResult(plan, null)
        }

        // 2) Si el dispositivo no admite esa máscara, se prueba la contraria.
        val alternative = if (channelOption == ChannelOption.STEREO) ChannelOption.MONO
        else ChannelOption.STEREO
        chooseSpec(option, alternative)?.let { spec ->
            val plan = buildPlan(
                projection,
                option,
                spec,
                onFallback,
                initialNoticeRes = R.string.audio_channels_fallback,
            )
            Log.w(
                TAG,
                "Canales ${channelOption.key} no disponibles: se usa ${alternative.key} " +
                    "(${spec.channelCount} canal/es, ${spec.sampleRate} Hz)",
            )
            return AudioSetupResult(plan, plan.initialNoticeRes)
        }

        val notice = when (option) {
            AudioSourceOption.INTERNAL -> R.string.audio_fallback_internal
            AudioSourceOption.MIC -> R.string.audio_fallback_mic
            AudioSourceOption.MIXED -> R.string.audio_fallback_mixed
        }
        return AudioSetupResult(null, notice)
    }

    /**
     * Comprueba el formato sin crear todavía el AudioRecord: así la creación
     * real puede hacerse después del VirtualDisplay sin arriesgar la pista AAC.
     */
    private fun chooseSpec(
        option: AudioSourceOption,
        channelOption: ChannelOption,
    ): AudioFormatSpec? {
        val rates = if (option == AudioSourceOption.MIC) MIC_RATES else INTERNAL_RATES
        for (rate in rates) {
            val spec = AudioFormatSpec(rate, channelOption.channelCount)
            if (AudioRecord.getMinBufferSize(
                    rate,
                    spec.inputChannelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                ) <= 0
            ) {
                continue
            }
            if (!aacEncoderAvailable(spec)) continue
            return spec
        }
        return null
    }

    private fun buildPlan(
        projection: MediaProjection,
        option: AudioSourceOption,
        spec: AudioFormatSpec,
        onFallback: () -> Unit,
        initialNoticeRes: Int?,
    ): AudioPlan {
        val provider = DeferredPcmProvider()
        return AudioPlan(provider, spec, option, initialNoticeRes) {
            startSources(projection, option, spec, onFallback)
        }
    }

    /**
     * Crea las fuentes reales. Se llama justo después de createVirtualDisplay:
     * AOSP también crea su AudioRecord interno en ese momento. El micrófono solo
     * se abre en los modos MIC y MIXED; en INTERNAL nunca se usa como respaldo.
     */
    private fun startSources(
        projection: MediaProjection,
        option: AudioSourceOption,
        spec: AudioFormatSpec,
        onFallback: () -> Unit,
    ): AudioSourceStart {
        when (option) {
            AudioSourceOption.INTERNAL -> {
                // El modo "Audio interno" NO debe abrir el micrófono: hacerlo
                // hace que el usuario crea que se capturó el audio del teléfono
                // cuando en realidad está oyendo el micrófono. Si la captura de
                // reproducción falla, se avisa y la grabación queda sin audio.
                val internal = PcmSource.createInternal(projection, spec)
                if (internal == null || !internal.start()) {
                    internal?.release()
                    Log.e(TAG, "Audio interno no disponible: no se usará el micrófono")
                    CaptureDiagnostics.log("RESULTADO: audio interno NO arrancó. Sin micrófono.")
                    return AudioSourceStart(null, emptyList(), R.string.audio_fallback_internal)
                }
                CaptureDiagnostics.log("RESULTADO: solo audio interno (sin micrófono)")
                return AudioSourceStart(internal, listOf(internal))
            }

            AudioSourceOption.MIC -> {
                val mic = PcmSource.createMic(spec)
                if (mic == null || !mic.start()) {
                    mic?.release()
                    Log.e(TAG, "No se pudo arrancar el micrófono")
                    CaptureDiagnostics.log("RESULTADO: micrófono NO arrancó")
                    return AudioSourceStart(null, emptyList(), R.string.audio_fallback_mic)
                }
                CaptureDiagnostics.log("RESULTADO: solo micrófono")
                return AudioSourceStart(mic, listOf(mic))
            }

            AudioSourceOption.MIXED -> {
                val internal = PcmSource.createInternal(projection, spec)
                val mic = PcmSource.createMic(spec)
                val started = mutableListOf<PcmSource>()

                if (internal != null) {
                    if (internal.start()) started.add(internal) else internal.release()
                }
                if (mic != null) {
                    if (mic.start()) started.add(mic) else mic.release()
                }

                if (started.isEmpty()) {
                    Log.e(TAG, "No se pudo arrancar ninguna fuente para la mezcla")
                    CaptureDiagnostics.log("RESULTADO: mezcla sin fuentes")
                    return AudioSourceStart(null, emptyList(), R.string.audio_fallback_mixed)
                }
                val sourceNames = started.joinToString { source ->
                    if (source.isInternal) "interno" else "micrófono"
                }
                CaptureDiagnostics.log("RESULTADO: mezcla con $sourceNames")
                val provider: PcmProvider = if (started.size == 1) {
                    started[0]
                } else {
                    AudioMixer(started.toList())
                }
                val notice = if (started.size == 1) {
                    if (started[0].isInternal) R.string.audio_fallback_mixed_internal
                    else R.string.audio_fallback_mixed_mic
                } else {
                    null
                }
                return AudioSourceStart(provider, started.toList(), notice)
            }
        }
    }

    private fun aacEncoderAvailable(spec: AudioFormatSpec): Boolean {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            spec.sampleRate,
            spec.channelCount,
        )
        return MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format) != null
    }
}
