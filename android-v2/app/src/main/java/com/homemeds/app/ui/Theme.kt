package com.homemeds.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val Green = Color(0xFF164E44)
val Cream = Color(0xFFF4F5EF)
val Ink = Color(0xFF203E35)
val Red = Color(0xFF9B2828)
val Amber = Color(0xFFD9B44A)
val Muted = Color(0xFF54655B)
val LocalElderly = staticCompositionLocalOf { false }
val touchHeight @Composable get() = if (LocalElderly.current) 60.dp else 48.dp

internal data class TabPalette(val background: Color, val content: Color, val border: Color)
internal fun tabPalette(selected: Boolean) = TabPalette(
    background = if (selected) Color(0xFFDCEBD4) else Color.White,
    content = Ink,
    border = if (selected) Green else Color(0xFFCBD6C6),
)

internal fun appTypography(elderly: Boolean): Typography {
    val size = if (elderly) 22.sp else 17.sp
    // Let each component provide its content color, including white text on primary buttons.
    val text = TextStyle(fontSize = size, lineHeight = if (elderly) 33.sp else 27.sp)
    return Typography(bodyLarge = text, bodyMedium = text, bodySmall = text.copy(fontSize = if (elderly) 22.sp else 14.sp),
        labelLarge = text.copy(fontWeight = FontWeight.SemiBold), labelMedium = text, labelSmall = text,
        titleLarge = text.copy(fontSize = if (elderly) 28.sp else 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = text.copy(fontSize = if (elderly) 25.sp else 20.sp, fontWeight = FontWeight.Bold))
}

@Composable fun HomeMedsTheme(elderly: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalElderly provides elderly) {
        MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White, background = Cream,
            onBackground = Ink, surface = Color.White, onSurface = Ink, error = Red, secondaryContainer = Color(0xFFEDF2E9),
            outline = Color(0xFFAAB9A6)), typography = appTypography(elderly), content = content)
    }
}

@Composable fun Action(text: String, enabled: Boolean = true, secondary: Boolean = false, danger: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick, modifier.heightIn(min = touchHeight), enabled = enabled, shape = RoundedCornerShape(12.dp),
        border = if (secondary) BorderStroke(1.dp, Color(0xFFCBD6C6)) else null,
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Red else if (secondary) Color(0xFFEDF2E9) else Green,
            contentColor = if (secondary) Ink else Color.White)) { Text(text) }
}
@Composable fun Field(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier,
    number: Boolean = false, secret: Boolean = false, multiline: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, change, modifier.fillMaxWidth().heightIn(min = touchHeight),
        enabled = enabled, label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 3 else 1,
        shape = RoundedCornerShape(10.dp), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else if (secret) KeyboardType.Password else KeyboardType.Text))
}
@Composable fun Panel(title: String? = null, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = if (warning) Color(0xFFFFFAF0) else Color.White,
        border = BorderStroke(1.dp, if (warning) Amber else Color(0xFFDFE5D9)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}
@Composable fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
        Text("仅记录家庭用药，用量以医嘱和说明书为准。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable fun Check(label: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = touchHeight).clickable(enabled) { change(!checked) },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, change, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f))
    }
}
@Composable fun Picker(label: String, value: String?, options: List<Pair<String, String>>, change: (String) -> Unit,
    enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Muted)
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = touchHeight), enabled = enabled,
                shape = RoundedCornerShape(10.dp)) { Text(options.find { it.first == value }?.second ?: "请选择") }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 400.dp)) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; change(id) },
                        modifier = Modifier.heightIn(min = touchHeight))
                }
            }
        }
    }
}
@Composable fun Tabs(labels: List<String>, selected: Int, select: (Int) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels.size) { i ->
            val active = i == selected
            val palette = tabPalette(active)
            Button(onClick = { select(i) },
                modifier = Modifier.heightIn(min = touchHeight).semantics {
                    this.selected = active
                    role = Role.Tab
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(if (active) 2.dp else 1.dp, palette.border),
                colors = ButtonDefaults.buttonColors(containerColor = palette.background, contentColor = palette.content)) {
                Text(labels[i], fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
@Composable fun Modal(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).widthIn(max = 680.dp).fillMaxHeight(.92f), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Action("关闭", secondary = true, onClick = dismiss)
                }
                content()
            }
        }
    }
}
@Composable fun Confirm(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    Modal(title, dismiss) { Text(text); Action("明确确认", danger = true, onClick = confirm) }
}
data class FormField(val key: String, val label: String, val type: String = "text", val options: List<Pair<String, String>> = emptyList())

@Composable fun EditForm(title: String, fields: List<FormField>, initial: org.json.JSONObject, busy: Boolean,
    dismiss: () -> Unit, extra: @Composable (MutableMap<String, String>) -> Unit = {}, save: (org.json.JSONObject) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>().apply {
        fields.forEach { put(it.key, if (initial.isNull(it.key)) "" else initial.opt(it.key)?.toString().orEmpty()) }
    } }
    var error by remember { mutableStateOf<String?>(null) }
    Modal(title, dismiss) {
        extra(values)
        fields.forEach { f ->
            when (f.type) {
                "expiry" -> ExpiryField(values[f.key].orEmpty(), { values[f.key] = it }, enabled = !busy)
                "bool" -> Check(f.label, values[f.key] in setOf("true", "1"), { values[f.key] = it.toString() }, enabled = !busy)
                "choice" -> Picker(f.label, values[f.key], f.options, { values[f.key] = it }, enabled = !busy)
                else -> Field(f.label, values[f.key].orEmpty(), { values[f.key] = it }, number = f.type in setOf("number", "int"),
                    secret = f.type == "secret", multiline = f.type == "multiline", enabled = !busy)
            }
        }
        error?.let { Text(it, color = Red) }
        Action("核对并保存", enabled = !busy) {
            try {
                val body = org.json.JSONObject(initial.toString())
                fields.forEach { f ->
                    val v = values[f.key].orEmpty().trim()
                    body.put(f.key, when (f.type) {
                        "bool" -> v in setOf("true", "1")
                        "number" -> v.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: throw UserInput("请填写有效的${f.label}")
                        "int" -> v.toIntOrNull() ?: throw UserInput("请填写整数${f.label}")
                        "nullable" -> v.ifBlank { null } ?: org.json.JSONObject.NULL
                        "expiry" -> com.homemeds.app.medicineExpiry(v)?.date?.toString() ?: org.json.JSONObject.NULL
                        else -> v
                    })
                }
                save(body)
            } catch (e: Exception) { error = e.message ?: "请检查填写内容" }
        }
    }
}
private class UserInput(message: String) : Exception(message)
