package com.smoothrec.app.audio

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro ligero de la captura de audio. Se escribe en Descargas al terminar
 * la grabación para poder diagnosticar por qué el audio interno entrega cero
 * muestras en algunos dispositivos/OEM, sin depender de logcat.
 */
object CaptureDiagnostics {

    private val lines = mutableListOf<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(message: String) {
        if (lines.size >= 600) lines.removeAt(0)
        lines.add("${stamp.format(Date(System.currentTimeMillis()))} $message")
    }

    @Synchronized
    fun reset() {
        lines.clear()
    }

    /** Estado real de los permisos que afectan a la grabación. */
    fun logPermissions(context: Context) {
        fun granted(permission: String): String = if (context.checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
        ) "CONCEDIDO" else "DENEGADO"

        log(
            "Permisos: RECORD_AUDIO=${granted(Manifest.permission.RECORD_AUDIO)} " +
                "POST_NOTIFICATIONS=${granted(Manifest.permission.POST_NOTIFICATIONS)} " +
                "FOREGROUND_SERVICE=${granted(Manifest.permission.FOREGROUND_SERVICE)} " +
                "FGS_MEDIA_PROJECTION=${granted(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)} " +
                "FGS_MICROPHONE=${granted(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)} " +
                "WAKE_LOCK=${granted(Manifest.permission.WAKE_LOCK)}",
        )
    }

    /**
     * AudioManager expone las configuraciones activas de TODAS las grabaciones.
     * isClientSilenced=true significa que Android (o la política del OEM/app de
     * origen) ha silenciado esta captura: ningún código de la app puede evitarlo.
     */
    fun logAudioConfigurations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val manager = context.getSystemService(AudioManager::class.java)
            val configs = manager.activeRecordingConfigurations
            if (configs.isEmpty()) {
                log("CONFIGS de audio: ninguna grabación activa")
                return
            }
            for (config in configs) {
                log(
                    "CONFIG audio: fuente=${config.clientAudioSource} " +
                        "silenciado=${config.isClientSilenced} " +
                        "canales=${config.format.channelCount} " +
                        "rate=${config.format.sampleRate} " +
                        "formato=${config.format}",
                )
            }
        } catch (e: Exception) {
            log("CONFIGS de audio: error ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Synchronized
    fun write(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || lines.isEmpty()) return
        val text = lines.joinToString("\n") + "\n"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "SmoothRec-diagnostico.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo escribir el diagnóstico", e)
        }
    }

    private const val TAG = "CaptureDiagnostics"
}
