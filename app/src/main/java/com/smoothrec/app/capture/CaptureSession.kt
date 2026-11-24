package com.smoothrec.app.capture

import android.content.Context
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import android.util.Log
import com.smoothrec.app.R
import com.smoothrec.app.audio.AacAudioEncoder
import com.smoothrec.app.audio.AudioCaptureFactory
import com.smoothrec.app.audio.AudioPlan
import com.smoothrec.app.audio.CaptureDiagnostics
import com.smoothrec.app.encoder.CaptureSizeResolver
import com.smoothrec.app.encoder.EncoderOutput
import com.smoothrec.app.encoder.EncoderFactory
import com.smoothrec.app.encoder.VideoEncoder
import com.smoothrec.app.muxer.Mp4Muxer
import com.smoothrec.app.recording.AudioSourceOption
import com.smoothrec.app.recording.RecordingPreferences

/**
 * Orquesta la sesión completa de grabación:
 * MediaProjection -> VirtualDisplay -> MediaCodec (H.264) -> MediaMuxer -> MediaStore.
 */
class CaptureSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val onProjectionStopped: () -> Unit,
    private val onNotice: (Int) -> Unit = {},
) {
    private val store = RecordingStore(context)

    private var pending: RecordingStore.PendingRecording? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var muxer: Mp4Muxer? = null
    private var encoder: VideoEncoder? = null
    private var capturer: ScreenCapturer? = null
    private var audioPlan: AudioPlan? = null
    private var audioEncoder: AacAudioEncoder? = null
    private var released = false

    /** Configuración efectivamente aplicada, disponible para la notificación y la UI. */
    var appliedResolution: CaptureSizeResolver.ResolutionResult? = null
        private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "Consentimiento retirado por el usuario")
            onProjectionStopped()
        }
    }

    init {
        projection.registerCallback(callback, null)
    }

    @Synchronized
    fun start() {
        // Si el servicio se detuvo mientras esperaba el hilo de arranque, no hay nada que iniciar.
        if (released) return
        // Tamaño, FPS y orientación se fijan al iniciar: el MP4 no admite cambiar de pista a mitad.
        val settings = RecordingPreferences.load(context)
        val resolution = CaptureSizeResolver.forContext(context, settings)
        val config = resolution.config
        appliedResolution = resolution
        try {
            pending = store.prepare()
            descriptor = store.openForWrite(pending!!)
            // Un único origen temporal para vídeo y audio. Se toma antes de
            // arrancar cualquier codificador para conservar el retardo real.
            val sessionStartNs = System.nanoTime()
            val newMuxer = Mp4Muxer(descriptor!!)
            muxer = newMuxer

            // El codificador AAC se prepara antes que el vídeo para que el muxer
            // conozca su formato. Los AudioRecord reales se crean después del
            // VirtualDisplay, siguiendo el orden de AOSP.
            val audioSetup = AudioCaptureFactory.create(
                context,
                projection,
                settings.audioSource,
                settings.channels,
            ) { onNotice(R.string.audio_internal_fallback) }
            audioSetup.noticeRes?.let(onNotice)
            val plan = audioSetup.plan
            newMuxer.prepare(if (plan == null) 1 else 2, sessionStartNs)

            if (plan != null) {
                val silentNotice = if (plan.option == AudioSourceOption.INTERNAL) {
                    R.string.audio_internal_silent
                } else {
                    R.string.audio_fallback_silent
                }
                val newAudioEncoder = AacAudioEncoder(plan.provider, plan.spec, newMuxer) {
                    onNotice(silentNotice)
                }
                if (newAudioEncoder.start()) {
                    audioEncoder = newAudioEncoder
                    audioPlan = plan
                    CaptureDiagnostics.log(
                        "Sesión: codificador AAC listo (${plan.spec.sampleRate} Hz, " +
                            "${plan.spec.channelCount} canal/es)",
                    )
                } else {
                    CaptureDiagnostics.log("Sesión: codificador AAC NO se pudo iniciar")
                    onNotice(R.string.audio_fallback_encoder)
                    newAudioEncoder.stop()
                    plan.release()
                    if (!newMuxer.hasTrack(EncoderOutput.TRACK_AUDIO)) {
                        newMuxer.prepare(1, sessionStartNs)
                    }
                }
            }
            if (plan == null || audioEncoder == null) {
                // Evita que el muxer espere eternamente un formato de audio que no llegará.
                newMuxer.startWithAvailableTracks()
            }

            val newEncoder = EncoderFactory.create(config)
            try {
                newEncoder.start(config, muxer!!)
            } catch (e: Exception) {
                newEncoder.release()
                throw e
            }
            encoder = newEncoder

            capturer = ScreenCapturer(
                context,
                projection,
                newEncoder.inputSurface(),
                config.width,
                config.height,
            ).also { it.start() }
            val displayCreatedNs = System.nanoTime()

            // AOSP crea el AudioRecord interno después de createVirtualDisplay.
            // Hacerlo antes puede registrar la política de audio sobre una
            // proyección todavía inactiva y recibir silencio en algunos OEM.
            if (plan != null && audioEncoder != null) {
                val sourceStart = plan.startSources()
                sourceStart.noticeRes?.let(onNotice)
                if (!sourceStart.success) {
                    Log.e(TAG, "No hay fuentes de audio: la grabación seguirá sin pista de audio")
                    audioEncoder?.stop()
                    audioEncoder = null
                    plan.release()
                    audioPlan = null
                    // El muxer ya tiene el formato de vídeo; arranca solo con él.
                    newMuxer.startWithAvailableTracks()
                } else {
                    audioEncoder?.resume()
                    Log.i(
                        TAG,
                        "Audio arrancado ${(System.nanoTime() - displayCreatedNs) / 1_000_000} ms " +
                            "después del VirtualDisplay",
                    )
                    CaptureDiagnostics.log(
                        "Sesión: audio arrancado ${(System.nanoTime() - displayCreatedNs) / 1_000_000} ms " +
                            "después del VirtualDisplay",
                    )
                    // Lista real de sesiones activas: aquí se ve isClientSilenced.
                    CaptureDiagnostics.logAudioConfigurations(context)
                }
            }

            Log.i(
                TAG,
                "Grabación iniciada: ${config.width}x${config.height}@${config.frameRate} " +
                    "(elegido ${resolution.requested.width}x${resolution.requested.height}@${resolution.requested.frameRate})",
            )
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar la grabación", e)
            release()
            throw e
        }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true

        capturer?.release()
        capturer = null

        // El fin de vídeo se señala antes de drenar el audio para que ambas
        // pistas terminen en el mismo instante real y no quede audio cortado.
        encoder?.signalEndOfStream()
        audioEncoder?.stop()
        audioEncoder = null
        audioPlan?.release()
        audioPlan = null

        encoder?.let {
            try {
                it.stop()
            } finally {
                it.release()
            }
        }
        encoder = null

        val currentMuxer = muxer
        currentMuxer?.stop()
        // Solo publicamos si se escribieron muestras y MediaMuxer cerró el
        // contenedor sin errores; así nunca queda un MP4 incompleto en MediaStore.
        val publish = currentMuxer?.hasData() == true && currentMuxer.finalizedOk()
        if (!publish && currentMuxer?.hasData() == true) {
            Log.w(TAG, "MP4 descartado: MediaMuxer no cerró correctamente")
        }
        muxer = null

        try {
            descriptor?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error al cerrar el descriptor", e)
        }
        descriptor = null

        pending?.let {
            if (publish) store.publish(it) else store.discard(it)
        }
        pending = null

        // Informe legible en Descargas para diagnosticar el audio interno sin
        // depender de logcat (los logs de la app no son visibles desde fuera).
        CaptureDiagnostics.write(context)

        try {
            projection.unregisterCallback(callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No se pudo desregistrar el callback", e)
        }
        // La proyección pertenece a la sesión del servicio, no a una sola
        // grabación: se conserva para poder iniciar otra sin pedir consentimiento.
    }

    private companion object {
        const val TAG = "CaptureSession"
    }
}
