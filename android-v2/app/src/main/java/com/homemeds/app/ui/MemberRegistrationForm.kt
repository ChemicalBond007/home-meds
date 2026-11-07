package com.homemeds.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.homemeds.app.memberRegistrationBody
import org.json.JSONObject

@Composable internal fun MemberRegistrationForm(
    busy: Boolean,
    register: (JSONObject) -> Unit,
    scanner: @Composable (() -> Unit, (String) -> Unit) -> Unit = { dismiss, scanned ->
        ScannerDialog(dismiss, mode = ScanMode.GROUP_JOIN, scanned = scanned)
    },
) {
    var joinCode by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var elderly by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("1. 先输入家庭分组码")
    Field("家庭分组码", joinCode, { joinCode = it; error = null }, enabled = !busy)
    Action("扫描分组码二维码", secondary = true, enabled = !busy) { scanning = true }
    Text("请向管理员索取分组码，或扫描管理台中的二维码。扫码只填写分组码，不会自动注册。", color = Muted)
    Text("2. 填写成员资料")
    Field("用户名", username, { username = it; error = null }, enabled = !busy && joinCode.isNotBlank())
    Check("开启老人模式（大字、大按钮）", elderly, { elderly = it }, enabled = !busy)
    error?.let { Text(it, color = Red) }
    Action("注册并加入家庭", enabled = !busy && joinCode.isNotBlank() && joinCode.trim().length <= 100 && username.isNotBlank()) {
        try { register(memberRegistrationBody(username, joinCode, elderly)) }
        catch (e: Exception) { error = e.message ?: "请检查注册资料" }
    }
    if (scanning) scanner({ scanning = false }) { value ->
        if (!busy) { joinCode = value; error = null }
        scanning = false
    }
}
