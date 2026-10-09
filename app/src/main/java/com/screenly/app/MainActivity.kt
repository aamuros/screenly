package com.screenly.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.screenly.app.ai.LocalModel
import com.screenly.app.ai.verifyModelFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Main launcher and initial setup. Heavy model copying never runs on the UI thread. */
class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var modelInstalled by mutableStateOf(false)
    private var importingModel by mutableStateOf(false)
    private var importNotice by mutableStateOf<String?>(null)
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val chooseModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null || importingModel) return@registerForActivityResult
            importingModel = true
            importNotice = getString(R.string.home_importing)
            importScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val directory = File(noBackupFilesDir, "models")
                        if (!directory.isDirectory && !directory.mkdirs()) {
                            throw IOException("Cannot create private model directory")
                        }
                        val staging = File(directory, LocalModel.FILE_NAME + ".partial")
                        val target = LocalModel.fileIn(noBackupFilesDir)
                        try {
                            contentResolver.openInputStream(uri)?.use { input ->
                                staging.outputStream().buffered().use { output ->
                                    val bytes = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val n = input.read(bytes)
                                        if (n < 0) break
                                        output.write(bytes, 0, n)
                                    }
                                }
                            } ?: throw IOException("Cannot read selected file")
                            verifyModelFile(staging)
                            if (!staging.renameTo(target)) {
                                throw IOException("Cannot install verified model")
                            }
                        } finally {
                            staging.delete()
                        }
                    }
                    refreshModelState()
                    importNotice = getString(R.string.home_model_imported)
                } catch (_: Exception) {
                    importNotice = getString(R.string.home_model_import_failed)
                } finally {
                    importingModel = false
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ScreenlyHome(
                serviceEnabled = serviceEnabled,
                modelInstalled = modelInstalled,
                importingModel = importingModel,
                importNotice = importNotice,
                onEnableService = ::openAccessibilitySettings,
                onTryScreenly = {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                },
                onImportModel = { chooseModel.launch(arrayOf("*/*")) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        notifyServiceVisibility(true)
        refreshServiceState()
        refreshModelState()
    }

    override fun onPause() {
        isForeground = false
        notifyServiceVisibility(false)
        super.onPause()
    }

    override fun onDestroy() {
        importScope.cancel()
        super.onDestroy()
    }

    private fun refreshServiceState() {
        val manager = getSystemService(AccessibilityManager::class.java)
        val component = ComponentName(this, ScreenlyAccessibilityService::class.java)
        serviceEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { resolved ->
                val service = resolved.resolveInfo.serviceInfo
                ComponentName(service.packageName, service.name) == component
            }
    }

    private fun refreshModelState() {
        val file = LocalModel.fileIn(noBackupFilesDir)
        // Installation checks SHA-256; a cheap size check is sufficient for home-screen status.
        modelInstalled = file.isFile && file.canRead() && file.length() == LocalModel.SIZE_BYTES
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun notifyServiceVisibility(visible: Boolean) {
        // Package-scoped broadcast to Screenly's non-exported runtime receiver.
        sendBroadcast(Intent(ACTION_APP_VISIBILITY).apply {
            setPackage(packageName)
            putExtra(EXTRA_VISIBLE, visible)
        })
    }

    companion object {
        internal const val ACTION_APP_VISIBILITY = "com.screenly.app.APP_VISIBILITY"
        internal const val EXTRA_VISIBLE = "visible"
        @Volatile internal var isForeground: Boolean = false
    }
}
