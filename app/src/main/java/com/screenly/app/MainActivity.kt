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
import com.screenly.app.ai.ModelProvisioner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var importing by mutableStateOf(false)
    private var importStatus by mutableStateOf(R.string.model_import_notice)
    private var importVision = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            importStatus = R.string.model_importing
            val vision = importVision
            scope.launch {
                try {
                    ModelProvisioner(this@MainActivity).import(uri, vision)
                    importStatus = if (vision) R.string.model_vision_imported else R.string.model_text_imported
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { importStatus = R.string.model_import_failed
                } finally { importing = false }
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
                        Text(stringResource(R.string.testing_instructions))
                        Text(stringResource(importStatus))
                        Button(enabled = !importing, onClick = {
                            importVision = false
                            modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        }) { Text(stringResource(R.string.model_import_text)) }
                        Button(enabled = !importing, onClick = {
                            importVision = true
                            modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        }) { Text(stringResource(R.string.model_import_vision)) }
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
        val manager = getSystemService(AccessibilityManager::class.java)
        val service = ComponentName(this, ScreenlyAccessibilityService::class.java)
        serviceEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == service
            }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
