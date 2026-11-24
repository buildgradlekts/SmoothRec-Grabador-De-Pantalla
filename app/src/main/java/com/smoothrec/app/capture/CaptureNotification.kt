package com.smoothrec.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.smoothrec.app.MainActivity
import com.smoothrec.app.R

/** Notificación persistente y acciones del servicio de captura. */
object CaptureNotification {

    const val CHANNEL_ID = "smoothrec_capture"
    const val NOTIFICATION_ID = 1001
    /** Detiene solo la grabación actual; la sesión y la barra permanecen. */
    const val ACTION_STOP = "com.smoothrec.app.action.STOP_CAPTURE"
    /** Cierra la sesión completa y libera MediaProjection. */
    const val ACTION_END_SESSION = "com.smoothrec.app.action.END_SESSION"
    const val ACTION_CAPTURE_STOPPED = "com.smoothrec.app.action.CAPTURE_STOPPED"
    const val ACTION_CONFIG_APPLIED = "com.smoothrec.app.action.CONFIG_APPLIED"
    const val EXTRA_CONFIG_MESSAGE = "com.smoothrec.app.extra.CONFIG_MESSAGE"
    const val EXTRA_SESSION_ACTIVE = "com.smoothrec.app.extra.SESSION_ACTIVE"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /** Actualiza el texto de la notificación con la resolución y FPS realmente aplicados. */
    fun update(context: Context, text: CharSequence) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(context, text))
    }

    @Suppress("DEPRECATION")
    fun build(context: Context, customText: CharSequence? = null): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            flags,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val closeIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_END_SESSION),
            flags,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }

        return builder
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(customText ?: context.getString(R.string.notification_session_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.notification_stop_action),
                stopIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.notification_close_action),
                closeIntent,
            )
            .build()
    }
}
