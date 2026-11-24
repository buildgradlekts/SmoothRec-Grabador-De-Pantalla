package com.smoothrec.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.smoothrec.app.recording.AudioSourceOption
import com.smoothrec.app.recording.CaptureModeOption
import com.smoothrec.app.recording.ChannelOption
import com.smoothrec.app.recording.FpsOption
import com.smoothrec.app.recording.RecordingPreferences
import com.smoothrec.app.recording.RecordingSettings
import com.smoothrec.app.recording.ResolutionOption
import com.smoothrec.app.updater.UpdateManager

/** Ajustes de grabación: resolución y FPS, guardados para sesiones futuras. */
class SettingsActivity : Activity() {

    private lateinit var settings: RecordingSettings

    private val resolutionButtons: Map<ResolutionOption, Button> by lazy {
        mapOf(
            ResolutionOption.SCREEN to findViewById(R.id.resolutionScreenButton),
            ResolutionOption.P1080 to findViewById(R.id.resolution1080Button),
            ResolutionOption.P720 to findViewById(R.id.resolution720Button),
        )
    }

    private val audioButtons: Map<AudioSourceOption, Button> by lazy {
        mapOf(
            AudioSourceOption.INTERNAL to findViewById(R.id.audioInternalButton),
            AudioSourceOption.MIC to findViewById(R.id.audioMicButton),
            AudioSourceOption.MIXED to findViewById(R.id.audioMixedButton),
        )
    }

    private val fpsButtons: Map<FpsOption, Button> by lazy {
        mapOf(
            FpsOption.FPS_60 to findViewById(R.id.fps60Button),
            FpsOption.FPS_30 to findViewById(R.id.fps30Button),
        )
    }

    private val channelButtons: Map<ChannelOption, Button> by lazy {
        mapOf(
            ChannelOption.MONO to findViewById(R.id.channelMonoButton),
            ChannelOption.STEREO to findViewById(R.id.channelStereoButton),
        )
    }

    private val captureModeButtons: Map<CaptureModeOption, Button> by lazy {
        mapOf(
            CaptureModeOption.SINGLE_APP to findViewById(R.id.captureSingleAppButton),
            CaptureModeOption.FULL_SCREEN to findViewById(R.id.captureFullScreenButton),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = RecordingPreferences.load(this)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        resolutionButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(resolution = option)
                persistAndRefresh()
            }
        }
        fpsButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(fps = option)
                persistAndRefresh()
            }
        }
        audioButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(audioSource = option)
                persistAndRefresh()
            }
        }
        channelButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(channels = option)
                persistAndRefresh()
            }
        }
        captureModeButtons.forEach { (option, button) ->
            button.setOnClickListener {
                settings = settings.copy(captureMode = option)
                persistAndRefresh()
            }
        }

        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.overlay_permission_granted, Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.updateCheckButton).setOnClickListener {
            val status = findViewById<TextView>(R.id.updateStatus)
            status.setText(R.string.update_checking)
            UpdateManager.checkNow(this) { message -> status.text = message }
        }

        findViewById<ImageButton>(R.id.tiktokButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@keinerxzv"))
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
            }
        }

        refreshSelection()
    }

    private fun persistAndRefresh() {
        RecordingPreferences.save(this, settings)
        refreshSelection()
    }

    private fun refreshSelection() {
        resolutionButtons.forEach { (option, button) ->
            button.setBackgroundResource(
                if (option == settings.resolution) R.drawable.chip_background_selected
                else R.drawable.chip_background,
            )
        }
        fpsButtons.forEach { (option, button) ->
            button.setBackgroundResource(
                if (option == settings.fps) R.drawable.chip_background_selected
                else R.drawable.chip_background,
            )
        }
        audioButtons.forEach { (option, button) ->
            button.setBackgroundResource(
                if (option == settings.audioSource) R.drawable.chip_background_selected
                else R.drawable.chip_background,
            )
        }
        channelButtons.forEach { (option, button) ->
            button.setBackgroundResource(
                if (option == settings.channels) R.drawable.chip_background_selected
                else R.drawable.chip_background,
            )
        }
        captureModeButtons.forEach { (option, button) ->
            button.setBackgroundResource(
                if (option == settings.captureMode) R.drawable.chip_background_selected
                else R.drawable.chip_background,
            )
        }
        findViewById<TextView>(R.id.appliedSummary).text = getString(
            R.string.settings_applied_summary,
        )
    }
}
