package com.screenly.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var modelStatus by mutableStateOf("No local model imported.")
    private var importingModel by mutableStateOf(false)
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chooseModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || importingModel) return@registerForActivityResult
        importingModel = true
        modelStatus = "Verifying local AI model..."
        importScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val directory = File(noBackupFilesDir, "models")
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create models directory")
                    val target = LocalModel.fileIn(noBackupFilesDir)
                    val staging = File(directory, "${LocalModel.FILE_NAME}.partial")
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            staging.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: throw IOException("Cannot open the selected file")
                        verifyModelFile(staging)
                        if (!staging.renameTo(target)) throw IOException("Unable to install the verified model")
                    } finally {
                        staging.delete()
                    }
                }
                modelStatus = "Offline AI model installed and verified."
            } catch (error: Exception) {
                modelStatus = "Import failed: ${error.message ?: "Unknown error"}"
            } finally {
                importingModel = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = stringResource(
                                if (serviceEnabled) R.string.service_enabled
                                else R.string.service_disabled
                            ),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Button(onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text(stringResource(R.string.open_accessibility_settings))
                        }
                        Text(stringResource(R.string.enable_instructions))
                        Text("Offline AI model", style = MaterialTheme.typography.titleMedium)
                        Text(modelStatus)
                        Button(onClick = {
                            chooseModel.launch(arrayOf("*/*"))
                        }, enabled = !importingModel) {
                            Text(if (importingModel) "Importing model..." else "Import local AI model")
                        }
                        Text("Choose the official Gemma 3 1B INT4 .litertlm file from your phone. You can transfer it without internet. Screenly verifies the file before installing. Without it, offline rules still work.")
                        Text(stringResource(R.string.testing_instructions))
                        Text(
                            text = stringResource(R.string.privacy_notice),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        importScope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!importingModel) modelStatus = if (LocalModel.fileIn(noBackupFilesDir).isFile)
            "Model file present. Integrity is checked when AI loads."
        else "No model imported. Accessibility-only assistance is available."
        val manager = getSystemService(AccessibilityManager::class.java)
        val service = ComponentName(this, ScreenlyAccessibilityService::class.java)
        serviceEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == service
            }
    }
}
