package com.smoothrec.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.smoothrec.app.audio.CaptureDiagnostics
import com.smoothrec.app.capture.CaptureNotification
import com.smoothrec.app.capture.ScreenCaptureService
import com.smoothrec.app.recording.RecordingPreferences
import com.smoothrec.app.updater.UpdateManager
import java.util.UUID

/**
 * Interfaz de grabación minimalista.
 *
 * La capa visual solo refleja el estado de la sesión existente de Fase 0;
 * no introduce codificador ni cambia el servicio de captura.
 */
class MainActivity : Activity() {

    private enum class UiState { READY, REQUESTING, SESSION, RECORDING, STOPPED }

    private lateinit var stateLabel: TextView
    private lateinit var stateHint: TextView
    private lateinit var recordButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var currentSettingsValue: TextView

    private var uiState: UiState = UiState.READY
    private var currentSessionId: String? = null

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val active = intent.getBooleanExtra(CaptureNotification.EXTRA_SESSION_ACTIVE, false)
            applyState(if (active) UiState.SESSION else UiState.STOPPED)
        }
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra(CaptureNotification.EXTRA_CONFIG_MESSAGE)
            if (!message.isNullOrEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateLabel = findViewById(R.id.stateLabel)
        stateHint = findViewById(R.id.stateHint)
        recordButton = findViewById(R.id.recordButton)
        settingsButton = findViewById(R.id.settingsButton)
        currentSettingsValue = findViewById(R.id.currentSettingsValue)

        savedInstanceState?.getString(KEY_UI_STATE)?.let { saved ->
            runCatching { UiState.valueOf(saved) }.getOrNull()
        }?.let { uiState = it }

        recordButton.setOnClickListener { onRecordButtonClicked() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        UpdateManager.checkIfNeeded(this)
        applyState(uiState)
    }

    override fun onStart() {
        super.onStart()
        val stoppedFilter = IntentFilter(CaptureNotification.ACTION_CAPTURE_STOPPED)
        val configFilter = IntentFilter(CaptureNotification.ACTION_CONFIG_APPLIED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stoppedReceiver, stoppedFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(configReceiver, configFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stoppedReceiver, stoppedFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(configReceiver, configFilter)
        }
        refreshSettingsLabel()
    }

    override fun onStop() {
        unregisterReceiver(stoppedReceiver)
        unregisterReceiver(configReceiver)
        super.onStop()
    }

    private fun refreshSettingsLabel() {
        val settings = RecordingPreferences.load(this)
        currentSettingsValue.text = getString(
            R.string.current_settings_format,
            getString(settings.resolution.labelRes),
            settings.fps.value,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_UI_STATE, uiState.name)
        super.onSaveInstanceState(outState)
    }

    private fun onRecordButtonClicked() {
        when (uiState) {
            UiState.SESSION, UiState.RECORDING -> {
                // Cierra la sesión completa (barra + MediaProjection), no solo el MP4.
                startService(
                    Intent(this, ScreenCaptureService::class.java).apply {
                        action = CaptureNotification.ACTION_END_SESSION
                    },
                )
                applyState(UiState.STOPPED)
            }
            UiState.REQUESTING -> Unit
            UiState.READY, UiState.STOPPED -> requestPermissionsThenConsent()
        }
    }

    private fun requestPermissionsThenConsent() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        if (ensureOverlayPermission()) launchProjectionConsent()
    }

    /** Sin superposición no se podría controlar la grabación desde otras apps. */
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        Toast.makeText(this, R.string.overlay_permission_hint, Toast.LENGTH_LONG).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        val audioGranted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!audioGranted) {
            CaptureDiagnostics.log("Permisos: RECORD_AUDIO DENEGADO por el usuario")
            Toast.makeText(this, R.string.audio_permission_denied, Toast.LENGTH_LONG).show()
            applyState(UiState.STOPPED)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
        }
        CaptureDiagnostics.log("Permisos: RECORD_AUDIO CONCEDIDO")
        if (ensureOverlayPermission()) launchProjectionConsent()
    }

    private fun launchProjectionConsent() {
        applyState(UiState.REQUESTING)
        CaptureDiagnostics.reset()
        CaptureDiagnostics.logPermissions(this)
        // Se genera un identificador nuevo para cada consentimiento. El servicio
        // lo consume una sola vez, así un intent antiguo no puede reutilizar un
        // token de MediaProjection de una sesión anterior.
        currentSessionId = UUID.randomUUID().toString()
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            CaptureDiagnostics.log("MediaProjection: consentimiento DENEGADO o sin datos")
            applyState(UiState.STOPPED)
            return
        }

        CaptureDiagnostics.log("MediaProjection: consentimiento ACEPTADO")
        applyState(UiState.SESSION)
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_SESSION_ID, currentSessionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun applyState(state: UiState) {
        uiState = state

        val labelRes: Int
        val hintRes: Int
        val iconRes: Int
        val backgroundRes: Int
        val descriptionRes: Int
        val colorRes: Int

        when (state) {
            UiState.READY -> {
                labelRes = R.string.state_ready
                hintRes = R.string.hint_ready
                iconRes = R.drawable.ic_record
                backgroundRes = R.drawable.fab_background
                descriptionRes = R.string.record_start_description
                colorRes = R.color.text_primary
            }
            UiState.REQUESTING -> {
                labelRes = R.string.state_requesting
                hintRes = R.string.hint_requesting
                iconRes = R.drawable.ic_record
                backgroundRes = R.drawable.fab_background
                descriptionRes = R.string.record_start_description
                colorRes = R.color.accent_end
            }
            UiState.SESSION -> {
                labelRes = R.string.state_session
                hintRes = R.string.hint_session
                iconRes = R.drawable.ic_stop
                backgroundRes = R.drawable.fab_background_stop
                descriptionRes = R.string.record_stop_description
                colorRes = R.color.recording_accent
            }
            UiState.RECORDING -> {
                labelRes = R.string.state_recording
                hintRes = R.string.hint_recording
                iconRes = R.drawable.ic_stop
                backgroundRes = R.drawable.fab_background_stop
                descriptionRes = R.string.record_stop_description
                colorRes = R.color.recording_accent
            }
            UiState.STOPPED -> {
                labelRes = R.string.state_stopped
                hintRes = R.string.hint_stopped
                iconRes = R.drawable.ic_record
                backgroundRes = R.drawable.fab_background
                descriptionRes = R.string.record_start_description
                colorRes = R.color.text_primary
            }
        }

        stateLabel.setText(labelRes)
        stateLabel.setTextColor(getColor(colorRes))
        stateHint.setText(hintRes)
        recordButton.setImageResource(iconRes)
        recordButton.setBackgroundResource(backgroundRes)
        recordButton.contentDescription = getString(descriptionRes)
        recordButton.isEnabled = state != UiState.REQUESTING
        recordButton.alpha = if (state == UiState.REQUESTING) 0.6f else 1f
    }

    private companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        const val REQUEST_PERMISSIONS = 1002
        const val KEY_UI_STATE = "ui_state"
    }
}
