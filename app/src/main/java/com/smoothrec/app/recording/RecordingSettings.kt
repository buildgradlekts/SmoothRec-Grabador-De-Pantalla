package com.smoothrec.app.recording

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.smoothrec.app.R

/** Opciones de resolución. Las dimensiones se derivan siempre de la relación de aspecto real. */
enum class ResolutionOption(
    val key: String,
    val labelRes: Int, // R.string.*
) {
    SCREEN("screen", R.string.resolution_screen),
    P1080("1080p", R.string.resolution_1080p),
    P720("720p", R.string.resolution_720p),
    ;

    companion object {
        fun fromKey(key: String?): ResolutionOption = when (key) {
            P1080.key -> P1080
            P720.key -> P720
            // "original" era la clave de la versión anterior; se conserva la preferencia guardada.
            SCREEN.key, "original", null -> SCREEN
            else -> SCREEN
        }
    }
}

enum class FpsOption(val value: Int) {
    FPS_60(60),
    FPS_30(30),
    ;

    companion object {
        fun fromValue(value: Int): FpsOption =
            values().firstOrNull { it.value == value } ?: FPS_60
    }
}

/** Modalidad de captura de audio. Siempre se comprueba la compatibilidad real del dispositivo. */
enum class AudioSourceOption(
    val key: String,
    val labelRes: Int,
) {
    INTERNAL("internal", R.string.audio_internal),
    MIC("mic", R.string.audio_mic),
    MIXED("mixed", R.string.audio_mixed),
    ;

    companion object {
        fun fromKey(key: String?): AudioSourceOption =
            values().firstOrNull { it.key == key } ?: INTERNAL
    }
}

/** Canales que se solicitan a AudioRecord, al codificador AAC y al muxer. */
enum class ChannelOption(
    val key: String,
    val labelRes: Int,
    val channelCount: Int,
) {
    MONO("mono", R.string.channel_mono, 1),
    STEREO("stereo", R.string.channel_stereo, 2),
    ;

    companion object {
        fun fromKey(key: String?): ChannelOption =
            values().firstOrNull { it.key == key } ?: STEREO
    }
}

/**
 * Qué contenido captura MediaProjection.
 *
 * En Android 14+ la captura de una sola app excluye limpiamente las ventanas
 * de SmoothRec (barra flotante incluida). La captura de pantalla completa
 * obliga a usar FLAG_SECURE y deja un rectángulo negro donde está la barra.
 */
enum class CaptureModeOption(
    val key: String,
    val labelRes: Int,
) {
    SINGLE_APP("single_app", R.string.capture_single_app),
    FULL_SCREEN("full_screen", R.string.capture_full_screen),
    ;

    companion object {
        fun default(): CaptureModeOption =
            if (Build.VERSION.SDK_INT >= 34) SINGLE_APP else FULL_SCREEN

        fun fromKey(key: String?): CaptureModeOption = when (key) {
            SINGLE_APP.key -> SINGLE_APP
            FULL_SCREEN.key -> FULL_SCREEN
            else -> default()
        }
    }
}

data class RecordingSettings(
    val resolution: ResolutionOption = ResolutionOption.SCREEN,
    val fps: FpsOption = FpsOption.FPS_60,
    val audioSource: AudioSourceOption = AudioSourceOption.INTERNAL,
    val channels: ChannelOption = ChannelOption.STEREO,
    val captureMode: CaptureModeOption = CaptureModeOption.default(),
)

/** Persistencia sencilla sin dependencias: SharedPreferences privadas de la app. */
object RecordingPreferences {

    private const val FILE_NAME = "smoothrec_recording_settings"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_FPS = "fps"
    private const val KEY_AUDIO_SOURCE = "audio_source"
    private const val KEY_CHANNELS = "channels"
    private const val KEY_CAPTURE_MODE = "capture_mode"

    fun load(context: Context): RecordingSettings {
        val prefs: SharedPreferences = context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return RecordingSettings(
            resolution = ResolutionOption.fromKey(prefs.getString(KEY_RESOLUTION, null)),
            fps = FpsOption.fromValue(prefs.getInt(KEY_FPS, FpsOption.FPS_60.value)),
            audioSource = AudioSourceOption.fromKey(prefs.getString(KEY_AUDIO_SOURCE, null)),
            channels = ChannelOption.fromKey(prefs.getString(KEY_CHANNELS, null)),
            captureMode = CaptureModeOption.fromKey(prefs.getString(KEY_CAPTURE_MODE, null)),
        )
    }

    fun save(context: Context, settings: RecordingSettings) {
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RESOLUTION, settings.resolution.key)
            .putInt(KEY_FPS, settings.fps.value)
            .putString(KEY_AUDIO_SOURCE, settings.audioSource.key)
            .putString(KEY_CHANNELS, settings.channels.key)
            .putString(KEY_CAPTURE_MODE, settings.captureMode.key)
            .apply()
    }
}
