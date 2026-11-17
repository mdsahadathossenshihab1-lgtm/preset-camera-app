package com.shihab.iphonecam

import android.Manifest
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Mode { VIDEO, PHOTO, REFERENCE }

private val Yellow = Color(0xFFFFD60A)
private val DarkButton = Color(0xFF2E2E2E)

@Composable
fun CameraScreen(onOpenWatcherSettings: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var selectedMode by remember { mutableStateOf(Mode.PHOTO) }
    var selectedZoom by remember { mutableStateOf(1.5f) }
    var frontCamera by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch { thumbnail = loadLatestThumbnail(context) }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        if (!context.hasMediaPermission()) {
            mediaPermissionLauncher.launch(context.mediaPermissionName())
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (context.hasMediaPermission()) thumbnail = loadLatestThumbnail(context)
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            // Cap capture at ~12 MP so the on-device preset stays fast
            // and memory-safe even on 50/108 MP sensors.
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(4032, 3024),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            controller.bindToLifecycle(lifecycleOwner)
            applyZoom(controller, 1.5f)
        }
    }
    DisposableEffect(Unit) { onDispose { controller.unbind() } }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun capture() {
        if (busy) return
        if (selectedMode != Mode.PHOTO) {
            toast("Coming soon")
            return
        }
        busy = true
        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        controller.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch(Dispatchers.Default) {
                        try {
                            // Grade on-device, then save ONLY the graded photo.
                            // The original is a private temp file and is deleted below.
                            val graded = processCapturedFile(file)
                            saveToGallery(context, graded)
                            graded.recycle()
                            withContext(Dispatchers.Main) {
                                busy = false
                                toast("Saved to gallery")
                                thumbnail = loadLatestThumbnail(context)
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                busy = false
                                toast("Could not save photo")
                            }
                        } finally {
                            file.delete()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    toast("Capture failed")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        this.controller = controller
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission is needed", color = Color.White)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
            }
        }

        // Bottom scrim so the floating controls stay readable.
        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(430.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                    )
                )
        )

        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Pro readouts row (display labels for v1).
            Row(
                Modifier.fillMaxWidth().padding(top = 54.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TopReadout("1.8") { ApertureIcon() }
                Spacer(Modifier.width(16.dp))
                TopReadout("1/95") { ClockIcon() }
                Spacer(Modifier.width(16.dp))
                TopReadout("5.0") { Text("WB", color = Color.White, fontSize = 10.sp) }
                Spacer(Modifier.width(16.dp))
                GridIcon()
                Spacer(Modifier.width(16.dp))
                TopReadout("AF") { AfIcon() }
                Spacer(Modifier.width(16.dp))
                MountainIcon()
                Spacer(Modifier.width(16.dp))
                TopReadout("-1.0", Yellow) { Text("EV", color = Yellow, fontSize = 10.sp) }
            }

            Column {
                if (busy) {
                    Text(
                        "Processing…",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .padding(bottom = 8.dp)
                    )
                }
                // Zoom row.
                Row(
                    Modifier.align(Alignment.CenterHorizontally)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ZoomLabel(".5", selectedZoom == 0.5f) {
                        selectedZoom = applyZoom(controller, 0.5f)
                    }
                    Spacer(Modifier.width(26.dp))
                    ZoomLabel("1.5x", selectedZoom == 1.5f, highlighted = true) {
                        selectedZoom = applyZoom(controller, 1.5f)
                    }
                    Spacer(Modifier.width(26.dp))
                    ZoomLabel("2", selectedZoom == 2f) {
                        selectedZoom = applyZoom(controller, 2f)
                    }
                    Spacer(Modifier.width(26.dp))
                    ZoomLabel("4", selectedZoom == 4f) {
                        selectedZoom = applyZoom(controller, 4f)
                    }
                    Spacer(Modifier.width(26.dp))
                    ZoomLabel("8", selectedZoom == 8f) {
                        selectedZoom = applyZoom(controller, 8f)
                    }
                }
                Spacer(Modifier.height(18.dp))
                // Shutter row: f button / shutter / controls button.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 44.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleButton(72.dp, DarkButton, onClick = { toast("Coming soon") }) {
                        Text(
                            "ƒ",
                            color = Color.White,
                            fontSize = 30.sp,
                            fontStyle = FontStyle.Italic
                        )
                    }
                    Box(
                        Modifier.size(92.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(4.dp, Color(0xFFD6D6D6), CircleShape)
                            .clickable(enabled = !busy) { capture() }
                    )
                    // 6-dot controls button opens the auto-preset (watcher) settings.
                    CircleButton(
                        72.dp,
                        DarkButton,
                        borderYellow = true,
                        onClick = onOpenWatcherSettings
                    ) {
                        DotsGrid()
                    }
                }
                Spacer(Modifier.height(22.dp))
                // Bottom row: round thumbnail / mode pill / flip camera.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3A3A3A))
                            .border(2.dp, Color.White, CircleShape)
                    ) {
                        thumbnail?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Last photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        }
                    }
                    Box(
                        Modifier.width(200.dp)
                            .height(46.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.35f))
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ModeLabel("VIDEO", selectedMode == Mode.VIDEO) {
                                selectedMode = Mode.VIDEO
                                toast("Coming soon")
                            }
                            Spacer(Modifier.width(18.dp))
                            ModeLabel("PHOTO", selectedMode == Mode.PHOTO, yellow = true) {
                                selectedMode = Mode.PHOTO
                            }
                            Spacer(Modifier.width(18.dp))
                            ModeLabel("REFERENCE", selectedMode == Mode.REFERENCE) {
                                selectedMode = Mode.REFERENCE
                                toast("Coming soon")
                            }
                        }
                    }
                    CircleButton(52.dp, DarkButton, onClick = {
                        frontCamera = !frontCamera
                        controller.cameraSelector = if (frontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                    }) {
                        FlipIcon()
                    }
                }
                // Home indicator.
                Box(
                    Modifier.align(Alignment.CenterHorizontally)
                        .padding(top = 14.dp, bottom = 8.dp)
                        .size(120.dp, 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun TopReadout(
    value: String,
    color: Color = Color.White,
    below: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        below()
    }
}

@Composable
private fun ZoomLabel(
    text: String,
    selected: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val mod = if (highlighted) {
        Modifier.background(DarkButton, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp)
    } else {
        Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    }
    Text(
        text,
        color = if (highlighted) Yellow else Color.White,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = mod.clickable { onClick() }
    )
}

@Composable
private fun ModeLabel(
    text: String,
    selected: Boolean,
    yellow: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (yellow) Yellow else Color.White,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
private fun CircleButton(
    size: Dp,
    bg: Color,
    borderYellow: Boolean = false,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var mod = Modifier.size(size)
        .clip(CircleShape)
        .background(bg)
    if (borderYellow) {
        mod = mod.border(3.dp, Yellow, CircleShape)
    }
    Box(
        mod.clickable { onClick() },
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun ApertureIcon() {
    Canvas(Modifier.size(15.dp)) {
        drawCircle(color = Color.White, radius = size.minDimension / 2 - 1, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.28f, size.height * 0.72f),
            end = Offset(size.width * 0.72f, size.height * 0.28f),
            strokeWidth = 2f
        )
    }
}

@Composable
private fun ClockIcon() {
    Canvas(Modifier.size(15.dp)) {
        drawCircle(color = Color.White, radius = size.minDimension / 2 - 1, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        drawLine(
            color = Color.White,
            start = Offset(size.width / 2, size.height / 2),
            end = Offset(size.width / 2, size.height * 0.22f),
            strokeWidth = 2f
        )
        drawLine(
            color = Color.White,
            start = Offset(size.width / 2, size.height / 2),
            end = Offset(size.width * 0.72f, size.height * 0.62f),
            strokeWidth = 2f
        )
    }
}

@Composable
private fun GridIcon() {
    Canvas(Modifier.size(20.dp)) {
        val step = size.width / 3
        for (r in 0..2) {
            for (c in 0..2) {
                drawCircle(
                    color = Color.White,
                    radius = 2.2f,
                    center = Offset(step * c + step / 2, step * r + step / 2)
                )
            }
        }
    }
}

@Composable
private fun AfIcon() {
    Canvas(Modifier.size(15.dp)) {
        val w = size.width
        val h = size.height
        val l = 5f
        val s = 2f
        // Four corner brackets.
        drawLine(Color.White, Offset(0f, l), Offset(0f, 0f), s)
        drawLine(Color.White, Offset(0f, 0f), Offset(l, 0f), s)
        drawLine(Color.White, Offset(w - l, 0f), Offset(w, 0f), s)
        drawLine(Color.White, Offset(w, 0f), Offset(w, l), s)
        drawLine(Color.White, Offset(0f, h - l), Offset(0f, h), s)
        drawLine(Color.White, Offset(0f, h), Offset(l, h), s)
        drawLine(Color.White, Offset(w - l, h), Offset(w, h), s)
        drawLine(Color.White, Offset(w, h - l), Offset(w, h), s)
    }
}

@Composable
private fun MountainIcon() {
    Canvas(Modifier.size(22.dp, 15.dp)) {
        val pts = listOf(
            Offset(0f, size.height),
            Offset(size.width * 0.28f, size.height * 0.25f),
            Offset(size.width * 0.45f, size.height * 0.6f),
            Offset(size.width * 0.62f, size.height * 0.15f),
            Offset(size.width, size.height)
        )
        for (i in 0 until pts.size - 1) {
            drawLine(Color.White, pts[i], pts[i + 1], strokeWidth = 2.5f)
        }
    }
}

@Composable
private fun DotsGrid() {
    Canvas(Modifier.size(34.dp, 24.dp)) {
        for (r in 0..1) {
            for (c in 0..2) {
                drawCircle(
                    color = Color.White,
                    radius = 4f,
                    center = Offset(size.width * (0.2f + 0.3f * c), size.height * (0.28f + 0.44f * r))
                )
            }
        }
    }
}

@Composable
private fun FlipIcon() {
    Canvas(Modifier.size(30.dp)) {
        val r = size.minDimension / 2 - 3
        drawArc(
            color = Color.White,
            startAngle = 40f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(size.width / 2 - r, size.height / 2 - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
        )
        // Arrowhead at the end of the arc.
        val tip = Offset(size.width / 2 + r * 0.72f, size.height / 2 - r * 0.72f)
        drawLine(Color.White, tip, Offset(tip.x - 12f, tip.y - 2f), strokeWidth = 4f)
        drawLine(Color.White, tip, Offset(tip.x + 2f, tip.y + 12f), strokeWidth = 4f)
    }
}

private fun applyZoom(controller: LifecycleCameraController, ratio: Float): Float {
    return try {
        val state = controller.cameraInfo?.zoomState?.value
        val clamped = if (state != null) {
            ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        } else {
            ratio
        }
        controller.setZoomRatio(clamped)
        clamped
    } catch (e: Exception) {
        ratio
    }
}
