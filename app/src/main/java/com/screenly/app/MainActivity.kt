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
import androidx.core.view.WindowCompat
import com.screenly.app.ai.LocalModel
import com.screenly.app.ai.ModelProvisioner
import com.screenly.app.ai.VisionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/** Entry point for optional model provisioning and the floating accessibility assistant. */
class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var modelInstalled by mutableStateOf(false)
    private var visionStatus by mutableStateOf(VisionSetupStatus.MISSING)
    private var importing by mutableStateOf(false)
    private var importNotice by mutableStateOf<String?>(null)
    private var importVision = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || importing) return@registerForActivityResult
        importing = true
        importNotice = getString(R.string.model_importing)
        val vision = importVision
        scope.launch {
            try {
                ModelProvisioner(this@MainActivity).import(uri, vision)
                refreshModelStatus()
                importNotice = getString(if (vision) R.string.model_vision_imported else R.string.model_text_imported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                importNotice = getString(R.string.model_import_failed)
            } catch (_: Exception) {
                importNotice = getString(R.string.model_import_failed)
            } finally {
                importing = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ScreenlyHome(
                serviceEnabled = serviceEnabled,
                modelInstalled = modelInstalled,
                visionStatus = visionStatus,
                importingModel = importing,
                importNotice = importNotice,
                onEnableService = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onTryScreenly = { startActivity(Intent(Settings.ACTION_SETTINGS)) },
                onImportModel = {
                    importVision = false
                    modelPicker.launch(arrayOf("*/*"))
                },
                onImportVision = {
                    importVision = true
                    modelPicker.launch(arrayOf("*/*"))
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        notifyServiceVisibility(true)
        val manager = getSystemService(AccessibilityManager::class.java)
        val component = ComponentName(this, ScreenlyAccessibilityService::class.java)
        serviceEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == component
            }
        refreshModelStatus()
    }

    override fun onPause() {
        isForeground = false
        notifyServiceVisibility(false)
        super.onPause()
    }

    private fun refreshModelStatus() {
        val text = LocalModel.fileIn(noBackupFilesDir)
        modelInstalled = text.isFile && text.canRead() && text.length() == LocalModel.SIZE_BYTES
        val vision = VisionModel.fileIn(noBackupFilesDir)
        visionStatus = if (!vision.isFile || vision.length() != VisionModel.SIZE_BYTES) {
            VisionSetupStatus.MISSING
        } else if (runCatching { VisionModel.certificationIn(noBackupFilesDir).readText() == VisionModel.certification() }
                .getOrDefault(false)) {
            VisionSetupStatus.READY
        } else VisionSetupStatus.UNVERIFIED
    }

    private fun notifyServiceVisibility(visible: Boolean) {
        sendBroadcast(Intent(ACTION_APP_VISIBILITY).apply {
            setPackage(packageName)
            putExtra(EXTRA_VISIBLE, visible)
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_APP_VISIBILITY = "com.screenly.app.APP_VISIBILITY"
        internal const val EXTRA_VISIBLE = "visible"
        @Volatile internal var isForeground: Boolean = false
    }
}
