package com.homemeds.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homemeds.app.preferredMedicineCode
import com.homemeds.app.preferredGroupJoinCode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class ScanMode { MEDICINE, GROUP_JOIN }

private fun barcodeScanner(mode: ScanMode): BarcodeScanner {
    val options = BarcodeScannerOptions.Builder()
    if (mode == ScanMode.GROUP_JOIN) options.setBarcodeFormats(Barcode.FORMAT_QR_CODE)
    else options.setBarcodeFormats(
        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
        Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE)
    return BarcodeScanning.getClient(options.build())
}

@Composable fun ScannerDialog(dismiss: () -> Unit, mode: ScanMode = ScanMode.MEDICINE, scanned: (String) -> Unit) {
    ScannerContent(dismiss, mode, ::barcodeScanner, scanned)
}

@Composable internal fun ScannerContent(dismiss: () -> Unit, mode: ScanMode, createScanner: (ScanMode) -> BarcodeScanner,
    scanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val orientation = LocalConfiguration.current.orientation
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    val onScanned by rememberUpdatedState(scanned)
    val accepted = remember(mode) { AtomicBoolean(false) }
    val view = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        setBackgroundColor(android.graphics.Color.BLACK)
        clipToOutline = true
    } }
    Modal(if (mode == ScanMode.GROUP_JOIN) "扫描家庭分组码二维码" else "扫描药品条码 / 追溯码", dismiss) {
        Text(if (mode == ScanMode.GROUP_JOIN) "只对准一个分组码二维码。本机识别，不打开网址或更改服务器；识别后仍需确认注册。"
            else "只对准一个药盒，优先使用追溯码。支持商品条码、GS1、Data Matrix 和二维码，本机识别。",
            color = Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        if (permission) ScannerPreview(view, mode, cameraReady)
        else Action("允许相机扫码") { request.launch(Manifest.permission.CAMERA) }
        error?.let { Text(it, color = Red) }
        if (mode == ScanMode.GROUP_JOIN) Action("返回手动输入分组码", secondary = true, onClick = dismiss)
        else {
            Field("也可手动输入或粘贴", manual, { manual = it })
            Action("使用输入的码", enabled = manual.isNotBlank()) { if (accepted.compareAndSet(false, true)) onScanned(manual.trim()) }
        }
    }
    // Rebind camera use cases for the new display rotation, without resetting dialog input.
    DisposableEffect(permission, lifecycle, view, mode, orientation) {
        cameraReady = false
        // No decoder or camera resources are needed while permission is missing.
        if (!permission) return@DisposableEffect onDispose {}
        val scanner = try { createScanner(mode) }
        catch (_: Exception) {
            error = "无法初始化扫码，请使用手动输入"
            return@DisposableEffect onDispose {}
        }
        val executor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        val disposed = AtomicBoolean(false)
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed.get()) try {
                    provider = future.get()
                    preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { a ->
                        a.setAnalyzer(executor) { proxy -> analyze(proxy, scanner, mode, {
                            if (!disposed.get()) error = "不是有效的分组码二维码，或画面中有多个分组码，请重新对准"
                        }) { raw ->
                            if (!disposed.get() && accepted.compareAndSet(false, true)) onScanned(raw)
                        } }
                    }
                    provider?.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, *listOfNotNull(preview, analysis).toTypedArray())
                    cameraReady = true
                } catch (_: Exception) { error = "无法启动相机，请使用手动输入" }
            }, ContextCompat.getMainExecutor(context))
        } catch (_: Exception) { error = "无法启动相机，请使用手动输入" }
        onDispose {
            disposed.set(true)
            cameraReady = false
            try {
                analysis?.clearAnalyzer()
                listOfNotNull(preview, analysis).takeIf { it.isNotEmpty() }?.let { provider?.unbind(*it.toTypedArray()) }
            } finally { scanner.close(); executor.shutdown() }
        }
    }
}

@Composable internal fun ScannerPreview(view: PreviewView, mode: ScanMode = ScanMode.MEDICINE, active: Boolean = true) {
    // AndroidView does not clip by default. Bound and clip both the native view
    // and its Compose container so the camera cannot paint over scrolling controls.
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clipToBounds().testTag("scanner_preview")) {
        AndroidView({ view }, Modifier.matchParentSize().clipToBounds())
        if (active) ScannerOverlay(mode)
    }
}

@Composable internal fun ScannerOverlay(mode: ScanMode) {
    val animation = rememberInfiniteTransition(label = "scanner")
    val progress by animation.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "scan-line")
    Box(Modifier.fillMaxSize().testTag("scanner_overlay"), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxWidth(.82f).fillMaxHeight(.62f).clipToBounds().testTag("scanner_target")) {
            Canvas(Modifier.fillMaxSize()) {
                val corner = 20.dp.toPx().coerceAtMost(size.width / 4)
                val inset = 2.dp.toPx()
                val stroke = 3.dp.toPx()
                for ((x, y) in listOf(inset to inset, size.width - inset to inset,
                    inset to size.height - inset, size.width - inset to size.height - inset)) {
                    val dx = if (x < size.width / 2) corner else -corner
                    val dy = if (y < size.height / 2) corner else -corner
                    drawLine(Color.White, Offset(x, y), Offset(x + dx, y), stroke)
                    drawLine(Color.White, Offset(x, y), Offset(x, y + dy), stroke)
                }
            }
            val travel = with(LocalDensity.current) { (maxHeight - 8.dp).coerceAtLeast(0.dp).toPx() }
            Box(Modifier.offset { IntOffset(0, (travel * progress).roundToInt()) }.fillMaxWidth().height(8.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF66EE99), Color.Transparent)))
                .testTag("scanner_line"))
        }
        Text(if (mode == ScanMode.GROUP_JOIN) "对准分组码二维码，正在扫描…" else "对准条码或追溯码，正在扫描…",
            Modifier.align(Alignment.BottomCenter).padding(8.dp)
                .background(Color.Black.copy(alpha = .6f)).padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun analyze(proxy: ImageProxy, scanner: BarcodeScanner, mode: ScanMode,
    invalid: () -> Unit, scanned: (String) -> Unit) {
    val image = proxy.image
    if (image == null) { proxy.close(); return }
    scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
        .addOnSuccessListener { codes ->
            val values = codes.mapNotNull { it.rawValue }
            val selected = if (mode == ScanMode.GROUP_JOIN) preferredGroupJoinCode(values) else preferredMedicineCode(values)
            if (selected != null) scanned(selected)
            else if (mode == ScanMode.GROUP_JOIN && values.isNotEmpty()) invalid()
        }
        .addOnCompleteListener { proxy.close() }
}
