package com.screenly.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF171717)
private val SecondaryInk = Color(0xFF666666)
private val CanvasColor = Color(0xFFF7F7F5)
private val Hairline = Color(0xFFE8E8E5)
private val SoftWhite = Color(0xFFE6E6E3)
private val Success = Color(0xFF2F7558)

internal enum class VisionSetupStatus { MISSING, UNVERIFIED, READY }

/** The launcher is a friendly home and setup surface, not an Android debug readout. */
@Composable
internal fun ScreenlyHome(
    serviceEnabled: Boolean,
    modelInstalled: Boolean,
    visionStatus: VisionSetupStatus,
    importingModel: Boolean,
    importNotice: String?,
    onEnableService: () -> Unit,
    onTryScreenly: () -> Unit,
    onImportModel: () -> Unit,
    onImportVision: () -> Unit
) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink,
            onPrimary = Color.White,
            background = CanvasColor,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink
        )
    ) {
        Surface(color = CanvasColor, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                BrandHeader()
                HeroCard(
                    serviceEnabled = serviceEnabled,
                    onPrimaryAction = if (serviceEnabled) onTryScreenly else onEnableService
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(
                        stringResource(R.string.home_setup_title),
                        stringResource(R.string.home_setup_subtitle)
                    )
                    SetupCard(
                        title = stringResource(R.string.home_service_title),
                        detail = stringResource(
                            if (serviceEnabled) R.string.home_service_on_detail
                            else R.string.home_service_off_detail
                        ),
                        ready = serviceEnabled,
                        badge = stringResource(
                            if (serviceEnabled) R.string.home_enabled else R.string.home_needs_setup
                        ),
                        actionText = stringResource(
                            if (serviceEnabled) R.string.home_manage else R.string.home_enable
                        ),
                        onAction = onEnableService
                    )
                    SetupCard(
                        title = stringResource(R.string.home_model_title),
                        detail = stringResource(
                            if (modelInstalled) R.string.home_model_on_detail
                            else R.string.home_model_off_detail
                        ),
                        ready = modelInstalled,
                        badge = stringResource(
                            if (modelInstalled) R.string.home_installed else R.string.home_optional
                        ),
                        actionText = stringResource(
                            SetupCard(
                        title = stringResource(R.string.home_vision_title),
                        detail = stringResource(when (visionStatus) {
                            VisionSetupStatus.READY -> R.string.home_vision_ready_detail
                            VisionSetupStatus.UNVERIFIED -> R.string.home_vision_unverified_detail
                            VisionSetupStatus.MISSING -> R.string.home_vision_missing_detail
                        }),
                        ready = visionStatus == VisionSetupStatus.READY,
                        badge = stringResource(when (visionStatus) {
                            VisionSetupStatus.READY -> R.string.home_installed
                            VisionSetupStatus.UNVERIFIED -> R.string.home_needs_verification
                            VisionSetupStatus.MISSING -> R.string.home_optional
                        }),
                        actionText = stringResource(
                            if (importingModel) R.string.home_importing else R.string.home_import_vision
                        ),
                        enabled = !importingModel,
                        onAction = onImportVision
                    )
                    if (importingModel) R.string.home_importing
                            else if (modelInstalled) R.string.home_replace_model
                            else R.string.home_import_model
                        ),
                        enabled = !importingModel,
                        onAction = onImportModel
                    )
                    if (importingModel) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Ink, strokeWidth = 2.dp
                            )
                            Text(
                                stringResource(R.string.home_import_progress),
                                color = SecondaryInk, fontSize = 12.sp,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    } else if (importNotice != null) {
                        Text(importNotice, color = SecondaryInk, fontSize = 12.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(
                        stringResource(R.string.home_how_title),
                        stringResource(R.string.home_how_subtitle)
                    )
                    HowItWorksRow(1, R.string.home_step_one, R.string.home_step_one_detail)
                    HowItWorksRow(2, R.string.home_step_two, R.string.home_step_two_detail)
                    HowItWorksRow(3, R.string.home_step_three, R.string.home_step_three_detail)
                }
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Hairline)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier.size(38.dp).background(CanvasColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✦", color = Ink, fontSize = 20.sp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                stringResource(R.string.home_privacy_title),
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.home_privacy_body),
                                color = SecondaryInk, fontSize = 13.sp, lineHeight = 19.sp
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.home_footer),
                    color = SecondaryInk, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.screenly_bubble),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                contentScale = ContentScale.Fit
            )
            Text(
                stringResource(R.string.app_name),
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.7).sp
            )
        }
        Surface(
            color = SoftWhite,
            shape = RoundedCornerShape(100.dp)
        ) {
            Text(
                stringResource(R.string.home_offline_tag),
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = Ink, letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun HeroCard(serviceEnabled: Boolean, onPrimaryAction: () -> Unit) {
    Surface(
        color = Ink,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(7.dp).background(Color(0xFFC7E0D0), CircleShape)
                )
                Text(
                    stringResource(R.string.home_hero_eyebrow),
                    modifier = Modifier.padding(start = 8.dp),
                    color = Color(0xFFD1D1CE),
                    fontSize = 11.sp,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                stringResource(R.string.home_hero_title),
                color = Color.White,
                fontSize = 31.sp,
                lineHeight = 37.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.9).sp
            )
            Text(
                stringResource(R.string.home_hero_body),
                color = Color(0xFFCFCFCE),
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
            Button(
                onClick = onPrimaryAction,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White, contentColor = Ink
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    stringResource(
                        if (serviceEnabled) R.string.home_try_button else R.string.home_enable_button
                    ),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = SecondaryInk, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SetupCard(
    title: String,
    detail: String,
    ready: Boolean,
    badge: String,
    actionText: String,
    onAction: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 17.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Surface(
                    color = if (ready) Color(0xFFE9F3EB) else CanvasColor,
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        badge,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                        color = if (ready) Success else SecondaryInk,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Text(detail, fontSize = 13.sp, color = SecondaryInk, lineHeight = 19.sp)
            TextButton(
                onClick = onAction,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = Ink),
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text(actionText + "  →", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HowItWorksRow(step: Int, title: Int, detail: Int) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(SoftWhite, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(step.toString(), color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(
            modifier = Modifier.padding(top = 1.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(title),
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink
            )
            Text(
                stringResource(detail),
                color = SecondaryInk, fontSize = 13.sp, lineHeight = 19.sp
            )
        }
    }
}
