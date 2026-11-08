package com.homemeds.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homemeds.app.preferredMedicineCode
import com.homemeds.app.preferredGroupJoinCode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class ScanMode { MEDICINE, GROUP_JOIN }

@Composable fun ScannerDialog(dismiss: () -> Unit, mode: ScanMode = ScanMode.MEDICINE, scanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val onScanned by rememberUpdatedState(scanned)
    val accepted = remember(mode) { AtomicBoolean(false) }
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    Modal(if (mode == ScanMode.GROUP_JOIN) "扫描家庭分组码二维码" else "扫描药品条码 / 追溯码", dismiss) {
        Text(if (mode == ScanMode.GROUP_JOIN) "只对准一个分组码二维码。本机识别，不打开网址或更改服务器；识别后仍需确认注册。"
            else "只对准一个药盒，优先使用追溯码。支持商品条码、GS1、Data Matrix 和二维码，本机识别。",
            color = Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        if (permission) AndroidView({ view }, Modifier.fillMaxWidth().height(240.dp))
        else Action("允许相机扫码") { request.launch(Manifest.permission.CAMERA) }
        error?.let { Text(it, color = Red) }
        if (mode == ScanMode.GROUP_JOIN) Action("返回手动输入分组码", secondary = true, onClick = dismiss)
        else {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("也可手动输入或粘贴", manual, { manual = it }, Modifier.weight(1f))
                Action("使用输入的码", enabled = manual.isNotBlank()) { if (accepted.compareAndSet(false, true)) onScanned(manual.trim()) }
            }
        }
    }
    DisposableEffect(permission, lifecycle, view, mode) {
        val executor = Executors.newSingleThreadExecutor()
        val options = BarcodeScannerOptions.Builder()
        if (mode == ScanMode.GROUP_JOIN) options.setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        else options.setBarcodeFormats(
            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE)
        val scanner = BarcodeScanning.getClient(options.build())
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        val disposed = AtomicBoolean(false)
        if (permission) {
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
                } catch (_: Exception) { error = "无法启动相机，请使用手动输入" }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            disposed.set(true)
            analysis?.clearAnalyzer()
            listOfNotNull(preview, analysis).takeIf { it.isNotEmpty() }?.let { provider?.unbind(*it.toTypedArray()) }
            scanner.close(); executor.shutdown()
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun analyze(proxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner, mode: ScanMode,
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
