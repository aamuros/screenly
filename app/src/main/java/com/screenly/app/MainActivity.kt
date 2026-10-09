package com.screenly.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.screenly.app.ai.LocalModel
import com.screenly.app.ai.verifyModelFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var consentGranted by mutableStateOf(false)
    private var modelStatus by mutableStateOf("Local model not installed.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentGranted = AccessibilityConsent.isGranted(this)
        refreshModelStatus()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val selectModel = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    modelStatus = "Importing and verifying offline model..."
                    lifecycleScope.launch {
                        modelStatus = try {
                            withContext(Dispatchers.IO) { importModel(uri) }
                            "Verified Gemma model installed. Offline text AI is available on supported devices."
                        } catch (error: Exception) {
                            "Import failed. Original model preserved. Check file format and available storage."
                        }
                    }
                }
            }
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
                        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        Text(
                            text = stringResource(R.string.accessibility_permission_heading),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(stringResource(R.string.accessibility_disclosure))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = consentGranted,
                                onCheckedChange = { accepted ->
                                    AccessibilityConsent.setGranted(this@MainActivity, accepted)
                                    consentGranted = accepted
                                }
                            )
                            Text(stringResource(R.string.accessibility_consent_label))
                        }
                        Button(
                            enabled = consentGranted,
                            onClick = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        ) {
                            Text(stringResource(R.string.open_accessibility_settings))
                        }
                        Text(stringResource(R.string.enable_instructions))
                        if (serviceEnabled && consentGranted) {
                            Text(
                                text = stringResource(R.string.accessibility_ready),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Button(onClick = {
                                startActivity(Intent(Settings.ACTION_SETTINGS))
                            }) {
                                Text(stringResource(R.string.open_settings_test))
                            }
                        }
                        Text("Local AI model", style = MaterialTheme.typography.titleLarge)
                        Text(modelStatus)
                        Text("To use the offline text model, select the exact Gemma 3 1B INT4 " +
                            ".litertlm file after accepting its license. The file is verified " +
                            "and kept in Screenly's private storage. No cloud processing.")
                        Button(
                            enabled = !serviceEnabled ||
                                !LocalModel.fileIn(noBackupFilesDir).isFile,
                            onClick = { selectModel.launch(arrayOf("*/*")) }
                        ) {
                            Text("Import verified offline model")
                        }
                        if (serviceEnabled && LocalModel.fileIn(noBackupFilesDir).isFile) {
                            Text("To replace a model already in use, temporarily disable " +
                                "Screenly's accessibility service first.")
                        }
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

    override fun onResume() {
        super.onResume()
        consentGranted = AccessibilityConsent.isGranted(this)
        val manager = getSystemService(AccessibilityManager::class.java)
        val service = ComponentName(this, ScreenlyAccessibilityService::class.java)
        serviceEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == service
            }
        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        val file = LocalModel.fileIn(noBackupFilesDir)
        modelStatus = if (file.isFile && file.length() == LocalModel.SIZE_BYTES)
            "Model file present. SHA-256 will be verified before native inference."
        else "No compatible offline AI model installed. Accessibility and OCR still work."
    }

    /** Copy a licensed model from SAF in bounded chunks, verify before atomic publication. */
    private fun importModel(uri: Uri) {
        val directory = File(noBackupFilesDir, "models")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create private model directory")
        }
        val target = LocalModel.fileIn(noBackupFilesDir)
        val partial = File(directory, LocalModel.FILE_NAME + ".partial")
        try {
            val source = contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open selected model")
            source.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > LocalModel.SIZE_BYTES) throw IOException("Model too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            verifyModelFile(partial)
            if (!partial.renameTo(target)) throw IOException("Unable to publish verified model")
        } finally {
            partial.delete()
        }
    }
}
