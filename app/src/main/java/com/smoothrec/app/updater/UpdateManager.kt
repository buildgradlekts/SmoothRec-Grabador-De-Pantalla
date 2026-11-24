package com.smoothrec.app.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import com.smoothrec.app.R

/**
 * Actualizaciones dentro de SmoothRec usando el release más reciente del repo
 * público. Android sigue exigiendo confirmar la instalación del APK, pero la
 * comprobación y la descarga ocurren desde la propia app.
 */
object UpdateManager {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
    )

    private const val TAG = "UpdateManager"
    private const val RELEASES_URL =
        "https://api.github.com/repos/buildgradlekts/SmoothRec/releases/latest"
    private const val CHANNEL_ID = "smoothrec_updates"
    private const val PREF_LAST_CHECK = "last_update_check_ms"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SmoothRecUpdate").apply { isDaemon = true }
    }

    /** Comprobación automática al abrir la app, como máximo cada 6 horas. */
    fun checkIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("smoothrec_updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
        checkNow(context)
    }

    /** Comprobación manual desde Ajustes. */
    fun checkNow(context: Context, onResult: ((String) -> Unit)? = null) {
        executor.execute {
            try {
                val current = currentVersion(context)
                val info = fetchLatest()
                if (info == null) {
                    Log.i(TAG, "No hay releases con APK disponibles")
                    onResult?.post(context, context.getString(R.string.update_none))
                    return@execute
                }
                if (info.versionCode <= current) {
                    onResult?.post(context, context.getString(R.string.update_current))
                    return@execute
                }
                onResult?.post(
                    context,
                    context.getString(R.string.update_found, info.versionName, info.notes),
                )
                notifyAvailable(context, info)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo comprobar la actualización", e)
                onResult?.post(context, context.getString(R.string.update_error))
            }
        }
    }

    private fun currentVersion(context: Context): Int {
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    }

    private fun fetchLatest(): UpdateInfo? {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "SmoothRec-Updater")
        val body = connection.inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        }
        val release = JSONObject(body)
        val tag = release.optString("tag_name", "")
        val code = tag.removePrefix("v").substringBefore("-").toIntOrNull() ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                return UpdateInfo(
                    versionCode = code,
                    versionName = tag,
                    apkUrl = asset.optString("browser_download_url"),
                    notes = release.optString("body", "").ifBlank { "-" },
                )
            }
        }
        return null
    }

    private fun notifyAvailable(context: Context, info: UpdateInfo) {
        createChannel(context)
        val intent = Intent(context, UpdateInstallActivity::class.java).apply {
            putExtra(EXTRA_APK_URL, info.apkUrl)
            putExtra(EXTRA_VERSION, info.versionName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(context, 1, intent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(context.getString(R.string.update_title, info.versionName))
            .setContentText(context.getString(R.string.update_tap))
            .setStyle(Notification.BigTextStyle().bigText(info.notes))
            .setContentIntent(pending)
            .setAutoCancel(false)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun ((String) -> Unit).post(context: Context, message: String) {
        android.os.Handler(context.mainLooper).post { this(message) }
    }

    internal const val EXTRA_APK_URL = "apk_url"
    internal const val EXTRA_VERSION = "version"
    internal const val NOTIFICATION_ID = 8002
}
