package com.homemeds.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.homemeds.app.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun AuthScreen(vm: AppViewModel, state: AppState) {
    if (state.bindingOnly) { TotpSetup(vm) { vm.bound() }; return }
    var tab by remember { mutableIntStateOf(0) }
    var username by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf(false) }
    Page("赛博药箱") {
        Text("把家人的用药，记在一起。", style = MaterialTheme.typography.titleLarge)
        Text("原生 Android · 本地日历 · 到点提醒", color = Muted)
        Panel {
            Tabs(listOf("成员登录", "注册成员", "管理员"), tab) { if (!state.busy) { tab = it; credential = "" } }
            if (tab == 1) MemberRegistrationForm(state.busy, { vm.authenticate("/auth/register", it) })
            else {
                Field("用户名", username, { username = it }, enabled = !state.busy)
                if (tab == 2) {
                    Check("使用一次性密码或恢复码（需重新绑定）", recovery, { recovery = it; credential = "" })
                    Field(if (recovery) "一次性密码 / 恢复码" else "验证器 6 位验证码", credential, { credential = it }, secret = true)
                    if (recovery) Text("恢复码会重置已有验证器与通行密钥。登录后请重新绑定。", color = Red)
                }
                Action("登录", enabled = !state.busy && username.isNotBlank() && (tab != 2 || credential.isNotBlank())) {
                    vm.authenticate("/auth/login", json("username" to username.trim()).apply {
                        if (tab == 2) put(if (recovery) "secret" else "code", credential.trim())
                    })
                    credential = ""
                }
            }
        }
        Text("成员账号遵循现有服务端登录规则。管理员凭证仅发送至赛博药箱 API。", color = Muted)
    }
}

@Composable fun TotpSetup(vm: AppViewModel, done: () -> Unit) {
    val context = LocalContext.current
    var setup by remember { mutableStateOf<JSONObject?>(null) }
    var code by remember { mutableStateOf("") }
    var codes by remember { mutableStateOf<List<String>?>(null) }
    val state by vm.state.collectAsState()
    Page("绑定验证器") {
        Panel {
            Text("二维码在本机生成，不会发送到第三方二维码服务。")
            if (setup == null) Action("生成验证器二维码", enabled = !state.busy) {
                vm.perform { setup = vm.post("/auth/totp/setup") }
            }
            setup?.let { data ->
                val uri = data.getString("uri")
                val bitmap = remember(uri) {
                    val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 512, 512)
                    Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                        setPixels(IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 512, 0, 0, 512, 512)
                    }
                }
                Image(bitmap.asImageBitmap(), "验证器绑定二维码", Modifier.fillMaxWidth().height(260.dp))
                SelectionContainer { Text("手动密钥：${data.getString("secret")}") }
                Action("复制手动密钥", secondary = true) {
                    (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("验证器密钥", data.getString("secret")))
                }
                Action("打开本机验证器", secondary = true) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }.onFailure { vm.error("未找到支持的验证器，请扫描二维码或手动输入密钥") }
                }
                Field("验证器当前 6 位验证码", code, { code = it.filter(Char::isDigit).take(6) }, secret = true)
                Action("确认绑定", enabled = !state.busy && code.length == 6) {
                    vm.perform {
                        val response = vm.post("/auth/totp/bind", json("code" to code))
                        code = ""
                        val issued = response.optJSONArray("recovery_codes")?.strings().orEmpty()
                        if (issued.isEmpty()) done() else { codes = issued; setup = null }
                    }
                }
            }
            Action("退出登录", secondary = true) { vm.logout() }
        }
    }
    codes?.let { RecoveryCodes(it) { codes = null; done() } }
}

@Composable fun RecoveryCodes(codes: List<String>, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri) ?: throw UserError("无法保存文件")
                    stream.bufferedWriter().use { it.write("赛博药箱恢复码，仅显示一次，请离线妥善保存。\n\n" + codes.joinToString("\n")) }
                }
                saved = true
            } catch (_: Exception) { error = "恢复码保存失败，请重试或手动抄写" }
        }
    }
    Modal("请离线保存恢复码", {}) {
        Text("恢复码仅显示一次，每个只能使用一次。重新生成后旧码全部失效。不要发给他人。", color = Red)
        SelectionContainer { Text(codes.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")) }
        Action("保存到文件") { save.launch("cyber-meds-recovery.txt") }
        Check("我已离线保存或抄写全部恢复码", saved, { saved = it })
        error?.let { Text(it, color = Red) }
        Action("保存好了，继续", enabled = saved, onClick = done)
    }
}
