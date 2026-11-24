package com.smoothrec.app.updater

import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.smoothrec.app.R

/**
 * Descarga el APK del release con DownloadManager y abre el instalador del
 * sistema al terminar. No deja al usuario buscando el archivo en Descargas.
 */
class UpdateInstallActivity : Activity() {

    private var apkUrl: String? = null
    private var downloadId = -1L
    private var waitingForPermission = false
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val manager = getSystemService(DownloadManager::class.java)
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = manager.query(query)
            val status = cursor.use {
                if (it.moveToFirst()) {
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                } else {
                    DownloadManager.STATUS_FAILED
                }
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(context, R.string.update_error, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val uri = manager.getUriForDownloadedFile(downloadId)
            if (uri == null) {
                Toast.makeText(context, R.string.update_error, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            try {
                startActivity(install)
            } catch (e: Exception) {
                Toast.makeText(context, R.string.update_error, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateManager.createChannel(this)
        apkUrl = intent.getStringExtra(UpdateManager.EXTRA_APK_URL)
        if (apkUrl.isNullOrBlank()) {
            Toast.makeText(this, R.string.update_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        getSystemService(NotificationManager::class.java).cancel(UpdateManager.NOTIFICATION_ID)
    }

    override fun onResume() {
        super.onResume()
        if (!canInstall()) {
            if (!waitingForPermission) {
                waitingForPermission = true
                Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
                val uri = Uri.parse("package:$packageName")
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri))
            }
            return
        }
        waitingForPermission = false
        if (downloadId < 0L) startDownload()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun startDownload() {
        val url = apkUrl ?: return
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(getString(R.string.update_downloading))
            setDescription(getString(R.string.app_name))
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "SmoothRec-update.apk",
            )
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val manager = getSystemService(DownloadManager::class.java)
        downloadId = manager.enqueue(request)
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        receiverRegistered = true
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(downloadReceiver)
        super.onDestroy()
    }
}
