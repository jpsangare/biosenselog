package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.BiometricRecord
import com.example.sensor.FingerDetectionStatus
import com.example.sensor.PPGAnalyzer
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BiometricDashboard(
    viewModel: BiometricViewModel,
    modifier: Modifier = Modifier
) {
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val isSimulatorEnabled by viewModel.isSimulatorEnabled.collectAsStateWithLifecycle()
    val isMeasuring by viewModel.isMeasuring.collectAsStateWithLifecycle()
    val countdown by viewModel.countdown.collectAsStateWithLifecycle()

    val liveBPM by viewModel.liveBPM.collectAsStateWithLifecycle()
    val liveBloodVolume by viewModel.liveBloodVolume.collectAsStateWithLifecycle()
    val liveVibeRMS by viewModel.liveVibeRMS.collectAsStateWithLifecycle()

    val ppgWaveform by viewModel.ppgWaveform.collectAsStateWithLifecycle()
    val vibeWaveform by viewModel.vibeWaveform.collectAsStateWithLifecycle()

    val isFingerPresent by viewModel.isFingerOnLens.collectAsStateWithLifecycle()
    val fingerStatus by viewModel.fingerStatus.collectAsStateWithLifecycle()
    val isSimulatedFingerTouch by viewModel.isSimulatedFingerTouch.collectAsStateWithLifecycle()
    val recentRecords by viewModel.recentRecords.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Set up camera permission state
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = "BIO-ANALYTICS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            letterSpacing = 1.5.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Quick locale info styled as elegant button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = Locale.getDefault().language.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Avatar container
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = "Profile",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Tab Switcher
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp)
                ) {
                    val activeColor = MaterialTheme.colorScheme.primary
                    val inactiveColor = Color.Transparent
                    val onActiveColor = MaterialTheme.colorScheme.onPrimary
                    val onInactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

                    Card(
                        onClick = { viewModel.setTab("PPG") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_ppg"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (currentTab == "PPG") activeColor else inactiveColor,
                            contentColor = if (currentTab == "PPG") onActiveColor else onInactiveColor
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Analytics, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.title_blood_flow),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    Card(
                        onClick = { viewModel.setTab("VIBRATION") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_vibration"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (currentTab == "VIBRATION") activeColor else inactiveColor,
                            contentColor = if (currentTab == "VIBRATION") onActiveColor else onInactiveColor
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Vibration, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.title_chest_vibe),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // Mode Selecor (Sensor vs Simulator)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isSimulatorEnabled) Icons.Rounded.LinearScale else Icons.Rounded.FlashlightOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isSimulatorEnabled) stringResource(R.string.lbl_simulator) else stringResource(R.string.lbl_hardware),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Switch(
                                checked = isSimulatorEnabled,
                                onCheckedChange = { viewModel.setSimulatorEnabled(it) },
                                modifier = Modifier.testTag("simulator_toggle")
                            )
                        }

                        // Interactive tests inside Simulator Mode
                        if (isSimulatorEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.triggerAbnormalSimulate(false) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text(
                                        text = stringResource(R.string.normal_test),
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Button(
                                    onClick = { viewModel.triggerAbnormalSimulate(true) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text(
                                        text = stringResource(R.string.abnormal_test),
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Core Measurement Dashboard Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (currentTab == "PPG") {
                            // BLOOD FLOW (PPG) USER JOURNEY IN SLEEK INTERFACE THEME
                            Text(
                                text = stringResource(R.string.title_blood_flow).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            // 1. Dark Aspect Ratio Container (Mocking live camera display + waveform visual)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(4f / 3f)
                                    .clip(RoundedCornerShape(32.dp))
                                    .background(Color(0xFF0F292F)) // SleekTeal950 dark container
                                    .border(4.dp, Color.White, RoundedCornerShape(32.dp))
                                    .padding(20.dp)
                            ) {
                                // Title Inside Live Card
                                Column(modifier = Modifier.align(Alignment.TopStart)) {
                                    Text(
                                        text = "VITALPULSE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2DD4BF), // SleekTealAccent
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        text = "PPG ANALYTICS",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.5f),
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                // Interactive Pulsing "REC. LIVE" indicator
                                val isMeasuringActive = isMeasuring
                                if (isMeasuringActive) {
                                    val pulseDotTransition = rememberInfiniteTransition(label = "pulseDot")
                                    val pulseDotAlpha by pulseDotTransition.animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(600),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "pulseDotAlpha"
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFEF4444).copy(alpha = 0.9f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .alpha(pulseDotAlpha)
                                                .background(Color.White)
                                        )
                                        Text(
                                            text = "REC. LIVE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White,
                                            fontSize = 9.sp
                                        )
                                    }
                                }

                                // Interactive heart rate values & heartbeat transition
                                Row(
                                    modifier = Modifier.align(Alignment.Center),
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    val heartBeatTransition = rememberInfiniteTransition(label = "pulse")
                                    val scale by heartBeatTransition.animateFloat(
                                        initialValue = 1f,
                                        targetValue = if (liveBPM > 0) 1.25f else 1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(
                                                durationMillis = if (liveBPM > 0) (60000 / liveBPM) else 800,
                                                easing = LinearEasing
                                            ),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "heartScale"
                                    )

                                    Text(
                                        text = "❤️",
                                        modifier = Modifier
                                            .scale(scale)
                                            .padding(end = 8.dp),
                                        style = MaterialTheme.typography.headlineLarge
                                    )

                                    Text(
                                        text = if (liveBPM > 0) "$liveBPM" else "--",
                                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
                                        fontWeight = FontWeight.Light,
                                        color = Color.White,
                                        letterSpacing = (-1.5).sp
                                    )
                                    Text(
                                        text = " " + stringResource(R.string.unit_bpm),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                }

                                // Graph Waveform directly overlaying bottom of live view
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(75.dp)
                                ) {
                                    CardWaveformPlot(
                                        waveform = ppgWaveform,
                                        color = Color(0xFF2DD4BF),
                                        containerColor = Color.Transparent,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Secondary metrics below live preview space
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = String.format(stringResource(R.string.label_amplitude), liveBloodVolume),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // 1. Pulse indicator limits
                                Text(
                                    text = stringResource(R.string.limit_pulse),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    textAlign = TextAlign.Start
                                )

                                ColoredIndicatorBar(
                                    value = liveBPM.toFloat(),
                                    minVal = 40f,
                                    maxVal = 140f,
                                    lowLimit = 60f,
                                    highLimit = 100f,
                                    unit = "BPM",
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // 2. Blood volume limits
                                Text(
                                    text = stringResource(R.string.limit_volume),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    textAlign = TextAlign.Start
                                )

                                ColoredIndicatorBar(
                                    value = liveBloodVolume.toFloat(),
                                    minVal = 0f,
                                    maxVal = 8f,
                                    lowLimit = 1f,
                                    highLimit = 4.5f,
                                    unit = "%",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Instruction for Camera
                            if (!isSimulatorEnabled) {
                                if (cameraPermissionState.status.isGranted) {
                                    // Finger sensor status banner with dynamic diagnostic feedback
                                    val bannerBgColor = if (isFingerPresent) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                                    val bannerTextColor = if (isFingerPresent) Color(0xFF2E7D32) else Color(0xFFE65100)
                                    val bannerText = when {
                                        isFingerPresent -> stringResource(R.string.finger_detected)
                                        fingerStatus == FingerDetectionStatus.TOO_DARK -> stringResource(R.string.finger_status_too_dark)
                                        fingerStatus == FingerDetectionStatus.TOO_BRIGHT -> stringResource(R.string.finger_status_too_bright)
                                        fingerStatus == FingerDetectionStatus.COVER_LENS -> stringResource(R.string.finger_status_adjust)
                                        else -> stringResource(R.string.finger_not_detected)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(bannerBgColor)
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = bannerText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = bannerTextColor,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Camera Lens Preview & Testing Controls
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val ppgAnalyzer = remember {
                                            PPGAnalyzer { ppgSignal, isDetected, status ->
                                                viewModel.onCameraFrameUpdated(ppgSignal, isDetected, status)
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(60.dp)
                                                .clip(CircleShape)
                                                .border(
                                                    3.dp,
                                                    if (isFingerPresent) Color(0xFF22C55E) else Color(0xFFEF4444),
                                                    CircleShape
                                                )
                                        ) {
                                            AndroidView(
                                                factory = { ctx ->
                                                    val previewView = PreviewView(ctx).apply {
                                                        scaleType = PreviewView.ScaleType.FILL_CENTER
                                                    }
                                                    val executor = Executors.newSingleThreadExecutor()
                                                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                                    cameraProviderFuture.addListener({
                                                        try {
                                                            val cameraProvider = cameraProviderFuture.get()
                                                            val preview = CameraPreview.Builder().build().also {
                                                                it.setSurfaceProvider(previewView.surfaceProvider)
                                                            }

                                                            val imageAnalysis = ImageAnalysis.Builder()
                                                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                                .build()

                                                            imageAnalysis.setAnalyzer(executor, ppgAnalyzer)

                                                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                                                            cameraProvider.unbindAll()
                                                            val cameraInstance = cameraProvider.bindToLifecycle(
                                                                lifecycleOwner,
                                                                cameraSelector,
                                                                preview,
                                                                imageAnalysis
                                                            )
                                                            if (cameraInstance.cameraInfo.hasFlashUnit()) {
                                                                cameraInstance.cameraControl.enableTorch(true)
                                                            }
                                                        } catch (e: Exception) {
                                                            e.printStackTrace()
                                                        }
                                                    }, ctx.mainExecutor)

                                                    previewView
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        // Status & Emulator test trigger
                                        Column(
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = if (isFingerPresent) "Camera Active • Flash ON" else "Optical Sensor Active",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            FilledTonalButton(
                                                onClick = { viewModel.setSimulatedFingerTouch(!isSimulatedFingerTouch) },
                                                modifier = Modifier.testTag("simulate_finger_touch_btn"),
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = if (isSimulatedFingerTouch) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                                ),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Person,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isSimulatedFingerTouch) "Simulate Touch (Active)" else stringResource(R.string.test_simulate_finger),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Handle missing camera permissions
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(R.string.camera_permission_denied),
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = { cameraPermissionState.launchPermissionRequest() },
                                            modifier = Modifier.testTag("grant_permission_btn")
                                        ) {
                                            Text(stringResource(R.string.btn_request_permission))
                                        }
                                    }
                                }
                            } else {
                                // Default guide in simulation mode
                                Text(
                                    text = "💡 " + stringResource(R.string.guide_finger_ppg),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        } else {
                            // CHEST VIBRATIONS (SCG) USER JOURNEY IN SLEEK INTERFACE THEME
                            Text(
                                text = stringResource(R.string.title_chest_vibe).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            // 1. Dark Aspect Ratio Container (Mocking live reading display + waveform visual)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(4f / 3f)
                                    .clip(RoundedCornerShape(32.dp))
                                    .background(Color(0xFF0F292F)) // SleekTeal950 dark container
                                    .border(4.dp, Color.White, RoundedCornerShape(32.dp))
                                    .padding(20.dp)
                            ) {
                                // Title Inside Live Card
                                Column(modifier = Modifier.align(Alignment.TopStart)) {
                                    Text(
                                        text = "VITALPULSE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2DD4BF), // SleekTealAccent
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        text = "MICRO-SEISMOGRAPHY (SCG)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.5f),
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                // Interactive Pulsing "REC. LIVE" indicator
                                val isMeasuringActive = isMeasuring
                                if (isMeasuringActive) {
                                    val pulseDotTransition = rememberInfiniteTransition(label = "pulseDot")
                                    val pulseDotAlpha by pulseDotTransition.animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(600),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "pulseDotAlpha"
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFEF4444).copy(alpha = 0.9f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .alpha(pulseDotAlpha)
                                                .background(Color.White)
                                        )
                                        Text(
                                            text = "REC. LIVE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White,
                                            fontSize = 9.sp
                                        )
                                    }
                                }

                                // Live reading display value center layout
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.3f", liveVibeRMS),
                                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 58.sp),
                                        fontWeight = FontWeight.Light,
                                        color = Color.White,
                                        letterSpacing = (-1.5).sp
                                    )
                                    Text(
                                        text = "m/s² RMS",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2DD4BF)
                                    )
                                }

                                // Seismography wave graph inside dark card
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(75.dp)
                                ) {
                                    CardWaveformPlot(
                                        waveform = vibeWaveform,
                                        color = Color(0xFF2DD4BF),
                                        containerColor = Color.Transparent,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Secondary metrics / Limit indicator container below
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                                    .padding(16.dp)
                            ) {
                                // Vibration indicator limit header
                                Text(
                                    text = stringResource(R.string.limit_vibration),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    textAlign = TextAlign.Start
                                )

                                ColoredIndicatorBar(
                                    value = liveVibeRMS.toFloat(),
                                    minVal = 0.00f,
                                    maxVal = 0.50f,
                                    lowLimit = 0.05f,
                                    highLimit = 0.25f,
                                    unit = "m/s²",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Usage guide
                            Text(
                                text = "👤 " + stringResource(R.string.guide_vibe),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Controls: Start, Stop, Save
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isMeasuring) {
                                Button(
                                    onClick = { viewModel.startMeasurementSecured() },
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .height(54.dp)
                                        .testTag("start_measurement_btn"),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.btn_start),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.stopMeasurement() },
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .height(54.dp)
                                        .testTag("stop_measurement_btn"),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${stringResource(R.string.btn_stop)} (${countdown}s)",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }

                            // Direct quick-save
                            Button(
                                onClick = { viewModel.logCurrentReadingToDb() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .testTag("save_now_btn"),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.btn_save),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        // Progress Countdown overlay during active test
                        AnimatedVisibility(visible = isMeasuring) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(top = 16.dp)
                            ) {
                                LinearProgressIndicator(
                                    progress = { (10f - countdown) / 10f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = String.format(stringResource(R.string.info_countdown), countdown),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Database log history panel
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.measurement_history),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    if (recentRecords.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearLogHistory() },
                            modifier = Modifier.testTag("clear_history_btn")
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.btn_clear_history),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (recentRecords.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.empty_history),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            } else {
                items(recentRecords, key = { it.id }) { record ->
                    HistoryItemRow(
                        record = record,
                        onDeleteClick = { viewModel.deleteRecord(record) }
                    )
                }
            }
        }
    }
}

@Composable
fun CardWaveformPlot(
    waveform: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (waveform.isNotEmpty()) {
                    val path = Path()
                    val width = size.width
                    val height = size.height
                    val stepX = width / (waveform.size - 1)

                    // Find min & max amplitude bounds
                    val minY = waveform.minOrNull() ?: -0.1f
                    val maxY = waveform.maxOrNull() ?: 0.1f
                    val rangeY = if (maxY - minY == 0f) 1f else (maxY - minY)

                    waveform.forEachIndexed { index, value ->
                        val x = index * stepX
                        // Normalize to range 0..1
                        val normalizedY = (value - minY) / rangeY
                        // Map 0..1 to canvas coordinates (and flip upside down so positive is up)
                        val y = height - (normalizedY * height * 0.8f + height * 0.1f).toFloat()

                        if (index == 0) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                    }

                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}

@Composable
fun ColoredIndicatorBar(
    value: Float,
    minVal: Float,
    maxVal: Float,
    lowLimit: Float,
    highLimit: Float,
    unit: String,
    modifier: Modifier = Modifier
) {
    val animatedValue by animateFloatAsState(
        targetValue = value.coerceIn(minVal, maxVal),
        animationSpec = tween(durationMillis = 400),
        label = "indicator"
    )

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Calculate size fractions representing Low (yellow), Normal (green), High (red)
                val totalRange = maxVal - minVal
                val lowFraction = (lowLimit - minVal) / totalRange
                val normalFraction = (highLimit - lowLimit) / totalRange
                val highFraction = (maxVal - highLimit) / totalRange

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(maxOf(0.01f, lowFraction))
                        .background(Color(0xFFFFB300)) // Low alerts/Gold
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(maxOf(0.01f, normalFraction))
                        .background(Color(0xFF43A047)) // Safe/Green
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(maxOf(0.01f, highFraction))
                        .background(Color(0xFFE53935)) // High alerts/Red
                )
            }

            // Draw current value's indicator pointer
            val positionFraction = ((animatedValue - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(positionFraction)
                    .background(Color.Transparent)
            ) {
                // Slide anchor bar representing value position
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${minVal.toInt()} $unit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${lowLimit.toInt()}-${highLimit.toInt()} $unit",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF43A047)
            )
            Text(
                text = "${maxVal.toInt()} $unit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@SuppressLint("SimpleDateFormat")
@Composable
fun HistoryItemRow(
    record: BiometricRecord,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPpg = record.type == "PPG"
    val dateString = remember(record.timestamp) {
        val format = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        format.format(Date(record.timestamp))
    }

    val statusColor by animateColorAsState(
        targetValue = when (record.status) {
            "NORMAL" -> Color(0xFF2E7D32)
            "LOW" -> Color(0xFFE65100)
            else -> Color(0xFFC62828)
        }, label = "statusColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("history_item_${record.id}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isPpg) Icons.Rounded.Analytics else Icons.Rounded.Vibration,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isPpg) stringResource(R.string.title_blood_flow) else stringResource(R.string.title_chest_vibe),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isPpg) {
                            "${record.value.toInt()} BPM (${String.format(Locale.getDefault(), "%.1f%%", record.secondaryValue)})"
                        } else {
                            String.format(Locale.getDefault(), "%.3f m/s²", record.value)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = when (record.status) {
                                "NORMAL" -> stringResource(R.string.status_normal)
                                "LOW" -> stringResource(R.string.status_low)
                                else -> stringResource(R.string.status_high)
                            },
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = dateString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.testTag("delete_item_btn")
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
