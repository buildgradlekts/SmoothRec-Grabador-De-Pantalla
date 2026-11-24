package com.smoothrec.app.capture

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.smoothrec.app.R
import com.smoothrec.app.audio.CaptureDiagnostics
import com.smoothrec.app.overlay.FloatingBarController
import com.smoothrec.app.recording.AudioSourceOption
import com.smoothrec.app.recording.RecordingPreferences
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio en primer plano que mantiene viva la sesión de MediaProjection.
 *
 * Una sesión puede contener varias grabaciones: el token de consentimiento se
 * obtiene una sola vez desde MainActivity y se reutiliza legalmente mientras
 * la sesión siga activa. La barra flotante inicia y detiene grabaciones sin
 * volver a pedir permiso.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.smoothrec.app.action.START_CAPTURE"
        const val ACTION_START_RECORDING = "com.smoothrec.app.action.START_RECORDING"
        const val EXTRA_RESULT_CODE = "com.smoothrec.app.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.smoothrec.app.extra.RESULT_DATA"
        const val EXTRA_SESSION_ID = "com.smoothrec.app.extra.SESSION_ID"
        private const val TAG = "ScreenCaptureService"
    }

    @Volatile
    private var session: CaptureSession? = null

    /** Proyección de la sesión: se conserva entre grabaciones. */
    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var endRequested = false

    @Volatile
    private var finished = false

    @Volatile
    private var endAfterRecording = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recordingStopPosted = AtomicBoolean(false)

    /** Arranque, drenaje y cierre de MP4 se ejecutan en orden, fuera del hilo principal. */
    private val lifecycle: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SmoothRecLifecycle")
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: FloatingBarController? = null
    private var recordingStartedElapsed = 0L
    private var timerActive = false
    private var lastConsumedSessionId: String? = null

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!timerActive) return
            val elapsed = SystemClock.elapsedRealtime() - recordingStartedElapsed
            try {
                CaptureNotification.update(
                    this@ScreenCaptureService,
                    getString(
                        R.string.notification_recording_time,
                        FloatingBarController.formatTime(elapsed),
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo actualizar la notificación del temporizador", e)
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CaptureNotification.ACTION_STOP -> {
                CaptureDiagnostics.log("SERVICIO: detener grabación (sesión activa)")
                stopRecording()
                return START_NOT_STICKY
            }
            CaptureNotification.ACTION_END_SESSION -> {
                requestEndSession()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                startRecording()
                return START_NOT_STICKY
            }
        }

        if (endRequested || projection != null) {
            promoteToForeground()
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val consentData = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)

        if (consentData == null || resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Datos de consentimiento incompletos")
            CaptureDiagnostics.log("SERVICIO: datos de consentimiento incompletos")
            stopSelf()
            return START_NOT_STICKY
        }
        if (sessionId.isNullOrBlank() || sessionId == lastConsumedSessionId) {
            Log.w(TAG, "Intent de grabación duplicado o sin identificador nuevo")
            CaptureDiagnostics.log("SERVICIO: token duplicado rechazado ($sessionId)")
            stopSelf()
            return START_NOT_STICKY
        }
        lastConsumedSessionId = sessionId
        CaptureDiagnostics.log("SERVICIO: sesión $sessionId aceptada")

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            CaptureDiagnostics.log("SERVICIO: RECORD_AUDIO DENEGADO, no se inicia la captura")
            stopSelf()
            return START_NOT_STICKY
        }
        CaptureDiagnostics.logPermissions(this)

        CaptureNotification.createChannel(this)
        promoteToForeground()

        val newProjection = try {
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, consentData)
        } catch (e: SecurityException) {
            Log.e(TAG, "No se pudo obtener MediaProjection", e)
            CaptureDiagnostics.log("MediaProjection: error ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = newProjection
        CaptureDiagnostics.log("MediaProjection: obtenido correctamente")
        acquireWakeLock()
        showOverlay()
        CaptureNotification.update(this, getString(R.string.notification_session_text))
        // La primera grabación la inicia el usuario desde la barra, ya dentro
        // de la app que quiera grabar. Así nunca se captura la UI de SmoothRec.
        return START_NOT_STICKY
    }

    /** Inicia una grabación nueva reutilizando el MediaProjection de la sesión. */
    private fun startRecording() {
        val activeProjection = projection
        if (activeProjection == null) {
            CaptureDiagnostics.log("SERVICIO: no hay MediaProjection activo; abre SmoothRec para crear la sesión")
            return
        }
        if (session != null || endRequested) {
            CaptureDiagnostics.log("SERVICIO: ya hay grabación o la sesión está cerrándose")
            return
        }
        recordingStopPosted.set(false)
        val newSession = CaptureSession(
            applicationContext,
            activeProjection,
            { requestEndSession() },
            { messageRes -> notifyConfigApplied(getString(messageRes)) },
        )
        session = newSession
        CaptureDiagnostics.log("SERVICIO: nueva grabación con el mismo MediaProjection")

        lifecycle.execute {
            if (endRequested) {
                newSession.release()
                if (session === newSession) session = null
                return@execute
            }
            try {
                newSession.start()
                if (endRequested) {
                    newSession.release()
                    if (session === newSession) session = null
                    return@execute
                }
                mainHandler.post {
                    recordingStartedElapsed = SystemClock.elapsedRealtime()
                    timerActive = true
                    overlay?.startTimer()
                    CaptureDiagnostics.log("SERVICIO: temporizador de la barra iniciado")
                    mainHandler.removeCallbacks(timerRunnable)
                    mainHandler.post(timerRunnable)
                }
                newSession.appliedResolution?.let { result ->
                    val config = result.config
                    CaptureNotification.update(
                        this,
                        getString(
                            R.string.notification_text_dynamic,
                            config.width,
                            config.height,
                            config.frameRate,
                        ),
                    )
                    result.fallbackMessageRes?.let { res ->
                        notifyConfigApplied(getString(res, *result.fallbackFormatArgs.toTypedArray()))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al iniciar la captura", e)
                CaptureDiagnostics.log("SERVICIO: fallo al iniciar grabación: ${e.message}")
                mainHandler.post {
                    if (session === newSession) session = null
                    overlay?.stopTimer()
                    CaptureNotification.update(this, getString(R.string.notification_session_text))
                    notifyStopped()
                }
            }
        }
    }

    /** Detiene la grabación actual; el MP4 se cierra antes de publicar nada. */
    private fun stopRecording() {
        if (!recordingStopPosted.compareAndSet(false, true)) {
            CaptureDiagnostics.log("SERVICIO: parada ya en curso")
            return
        }
        timerActive = false
        mainHandler.removeCallbacks(timerRunnable)
        overlay?.stopTimer()
        lifecycle.execute {
            session?.release()
            session = null
            CaptureDiagnostics.log("SERVICIO: grabación detenida y MP4 cerrado")
            mainHandler.post {
                if (endAfterRecording) {
                    finishSession()
                } else {
                    CaptureNotification.update(this, getString(R.string.notification_session_text))
                    notifyStopped()
                }
            }
        }
    }

    /** Cierra la barra, libera MediaProjection y termina el servicio. */
    private fun requestEndSession() {
        if (endRequested) return
        endRequested = true
        endAfterRecording = true
        CaptureDiagnostics.log("SERVICIO: cerrando sesión completa")
        if (session == null) {
            finishSession()
        } else {
            stopRecording()
        }
    }

    private fun finishSession() {
        if (finished) return
        finished = true
        timerActive = false
        mainHandler.removeCallbacks(timerRunnable)
        overlay?.hide()
        overlay = null
        val activeProjection = projection
        projection = null
        if (activeProjection != null) {
            try {
                activeProjection.stop()
                CaptureDiagnostics.log("MediaProjection: liberado al cerrar la sesión")
            } catch (e: Exception) {
                CaptureDiagnostics.log("MediaProjection: error al liberar: ${e.message}")
            }
        }
        releaseWakeLock()
        notifyStopped()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun showOverlay() {
        if (overlay != null) return
        overlay = FloatingBarController(applicationContext, object : FloatingBarController.Callbacks {
            override fun onStartRecording() {
                startRecording()
            }
            override fun onStopRecording() {
                stopRecording()
            }
            override fun onOpenRecordings() {
                FloatingBarController.openRecordings(applicationContext)
            }
            override fun onOpenSettings() {
                FloatingBarController.openSettings(applicationContext)
            }
            override fun onClose() {
                requestEndSession()
            }
        })
        if (overlay?.show() == true) {
            CaptureDiagnostics.log("SERVICIO: barra flotante visible (FLAG_SECURE)")
        } else {
            CaptureDiagnostics.log("SERVICIO: no se pudo mostrar la barra (falta superposición)")
        }
    }

    private fun promoteToForeground() {
        val notification = CaptureNotification.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val usesMic = when (RecordingPreferences.load(this).audioSource) {
                AudioSourceOption.MIC, AudioSourceOption.MIXED -> true
                AudioSourceOption.INTERNAL -> false
            }
            if (usesMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            CaptureDiagnostics.log(
                "SERVICIO: fuente=${RecordingPreferences.load(this).audioSource.key} tipos FGS=$types",
            )
            try {
                startForeground(CaptureNotification.NOTIFICATION_ID, notification, types)
                CaptureDiagnostics.log("SERVICIO: primer plano ACTIVO tipos=$types")
            } catch (e: SecurityException) {
                Log.w(TAG, "Tipo microphone rechazado; se usa solo mediaProjection", e)
                CaptureDiagnostics.log("SERVICIO: tipos=$types rechazados, se reintenta solo mediaProjection")
                types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(CaptureNotification.NOTIFICATION_ID, notification, types)
                CaptureDiagnostics.log("SERVICIO: primer plano ACTIVO tipos=$types (reintento)")
            }
        } else {
            startForeground(CaptureNotification.NOTIFICATION_ID, notification)
            CaptureDiagnostics.log("SERVICIO: primer plano ACTIVO (sin tipos, API<29)")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val manager = getSystemService(PowerManager::class.java)
            wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmoothRec:capture",
            ).apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
            CaptureDiagnostics.log("SERVICIO: wake lock PARCIAL adquirido")
        } catch (e: Exception) {
            CaptureDiagnostics.log("SERVICIO: no se pudo adquirir wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        val current = wakeLock ?: return
        wakeLock = null
        try {
            if (current.isHeld) current.release()
            CaptureDiagnostics.log("SERVICIO: wake lock liberado")
        } catch (e: Exception) {
            CaptureDiagnostics.log("SERVICIO: error al liberar wake lock: ${e.message}")
        }
    }

    private fun notifyStopped() {
        sendBroadcast(
            Intent(CaptureNotification.ACTION_CAPTURE_STOPPED)
                .setPackage(packageName)
                .putExtra(CaptureNotification.EXTRA_SESSION_ACTIVE, projection != null),
        )
    }

    private fun notifyConfigApplied(message: String) {
        sendBroadcast(
            Intent(CaptureNotification.ACTION_CONFIG_APPLIED)
                .setPackage(packageName)
                .putExtra(CaptureNotification.EXTRA_CONFIG_MESSAGE, message),
        )
    }

    override fun onDestroy() {
        endRequested = true
        timerActive = false
        mainHandler.removeCallbacks(timerRunnable)
        val currentSession = session
        val currentProjection = projection
        session = null
        projection = null
        overlay?.hide()
        overlay = null
        releaseWakeLock()
        lifecycle.execute {
            currentSession?.release()
            if (currentProjection != null) {
                try {
                    currentProjection.stop()
                } catch (_: Exception) {
                    // La proyección ya estaba detenida.
                }
            }
            lifecycle.shutdown()
        }
        if (!finished) notifyStopped()
        super.onDestroy()
    }
}
