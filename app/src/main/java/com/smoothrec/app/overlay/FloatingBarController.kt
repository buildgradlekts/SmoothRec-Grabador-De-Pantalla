package com.smoothrec.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.smoothrec.app.R
import com.smoothrec.app.SettingsActivity
import kotlin.math.roundToInt

/**
 * Barra compacta arrastrable sobre otras apps.
 *
 * La ventana usa FLAG_SECURE: MediaProjection no copia el contenido de la
 * barra al MP4. En Android el hueco queda como rectángulo negro, comportamiento
 * aceptado por el usuario a cambio de conservar la captura de pantalla completa.
 */
class FloatingBarController(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onStartRecording()
        fun onStopRecording()
        fun onOpenRecordings()
        fun onOpenSettings()
        fun onClose()
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var timerRunning = false
    private var timerStartedElapsed = 0L

    /** La barra lleva su propio reloj: no depende de que el servicio envíe ticks. */
    private val timerTick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val current = view ?: return
            current.findViewById<TextView>(R.id.overlayTimer).text =
                formatTime(SystemClock.elapsedRealtime() - timerStartedElapsed)
            handler.postDelayed(this, 250L)
        }
    }

    fun isShowing(): Boolean = view != null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (view != null) return true
        val inflated = LayoutInflater.from(context).inflate(R.layout.floating_bar, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // FLAG_SECURE excluye la ventana de MediaProjection; NOT_TOUCH_MODAL
            // deja pasar al juego los toques fuera de la barra.
            WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, 16)
            y = prefs.getInt(PREF_Y, 220)
        }
        bindActions(inflated)
        bindDrag(inflated.findViewById(R.id.overlayTimer), layout)
        windowManager.addView(inflated, layout)
        view = inflated
        params = layout
        return true
    }

    fun hide() {
        timerRunning = false
        handler.removeCallbacks(timerTick)
        val current = view ?: return
        runCatching { windowManager.removeViewImmediate(current) }
        view = null
        params = null
    }

    fun startTimer() {
        handler.post {
            timerRunning = true
            timerStartedElapsed = SystemClock.elapsedRealtime()
            val current = view ?: return@post
            current.findViewById<TextView>(R.id.overlayTimer).text = formatTime(0L)
            current.findViewById<View>(R.id.overlayRecord).alpha = 0.35f
            current.findViewById<View>(R.id.overlayStop).alpha = 1f
            handler.removeCallbacks(timerTick)
            handler.post(timerTick)
        }
    }

    fun stopTimer() {
        handler.post {
            timerRunning = false
            handler.removeCallbacks(timerTick)
            val current = view ?: return@post
            current.findViewById<TextView>(R.id.overlayTimer).text = formatTime(0L)
            current.findViewById<View>(R.id.overlayRecord).alpha = 1f
            current.findViewById<View>(R.id.overlayStop).alpha = 0.35f
        }
    }

    private fun bindActions(root: View) {
        root.findViewById<View>(R.id.overlayRecord).setOnClickListener {
            callbacks.onStartRecording()
        }
        root.findViewById<View>(R.id.overlayStop).setOnClickListener {
            callbacks.onStopRecording()
        }
        root.findViewById<View>(R.id.overlayFolder).setOnClickListener {
            callbacks.onOpenRecordings()
        }
        root.findViewById<View>(R.id.overlaySettings).setOnClickListener {
            callbacks.onOpenSettings()
        }
        root.findViewById<View>(R.id.overlayClose).setOnClickListener {
            callbacks.onClose()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindDrag(handle: View, layout: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            val current = view ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layout.x
                    startY = layout.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = startX + (event.rawX - touchX).roundToInt()
                    layout.y = startY + (event.rawY - touchY).roundToInt()
                    runCatching { windowManager.updateViewLayout(current, layout) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit()
                        .putInt(PREF_X, layout.x)
                        .putInt(PREF_Y, layout.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val PREFS = "smoothrec_overlay"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"

        fun formatTime(elapsedMs: Long): String {
            val total = (elapsedMs.coerceAtLeast(0L) / 1000L).toInt()
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val seconds = total % 60
            return String.format("%02d:%02d:%02d", hours, minutes, seconds)
        }

        fun openRecordings(context: Context) {
            val target = "primary%3AMovies%2FSmoothRec"
            val candidates = listOf(
                "content://com.android.externalstorage.documents/document/$target" to "resource/document",
                "content://com.android.externalstorage.documents/tree/$target" to "resource/document",
            )
            for ((uri, type) in candidates) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse(uri), type)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                } catch (_: Exception) {
                    // Probamos el siguiente URI.
                }
            }
        }

        fun openSettings(context: Context) {
            val intent = Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
    }
}
