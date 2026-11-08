package com.homemeds.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.text.style.TextOverflow
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
private val Line = Color(0xFFDFE5D9)
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
    val text = TextStyle(fontSize = size, lineHeight = if (elderly) 33.sp else 24.sp)
    return Typography(bodyLarge = text, bodyMedium = text,
        bodySmall = text.copy(fontSize = if (elderly) 22.sp else 14.sp, lineHeight = if (elderly) 33.sp else 20.sp),
        labelLarge = text.copy(fontWeight = FontWeight.SemiBold), labelMedium = text, labelSmall = text,
        titleLarge = text.copy(fontSize = if (elderly) 28.sp else 22.sp, lineHeight = if (elderly) 36.sp else 30.sp, fontWeight = FontWeight.Bold),
        titleMedium = text.copy(fontSize = if (elderly) 25.sp else 19.sp, lineHeight = if (elderly) 33.sp else 26.sp, fontWeight = FontWeight.Bold))
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
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Red else if (secondary) Color(0xFFEDF2E9) else Green,
            contentColor = if (secondary) Ink else Color.White)) { Text(text) }
}
@Composable fun Field(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier,
    number: Boolean = false, secret: Boolean = false, multiline: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, change, modifier.fillMaxWidth().heightIn(min = touchHeight),
        enabled = enabled, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        shape = RoundedCornerShape(10.dp), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else if (secret) KeyboardType.Password else KeyboardType.Text))
}
@Composable fun Panel(title: String? = null, warning: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val border = BorderStroke(1.dp, if (warning) Amber else Line)
    val color = if (warning) Color(0xFFFFFAF0) else Color.White
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null || trailing != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                trailing?.invoke(this)
            }
            content()
        }
    }
    if (onClick != null) Surface(onClick, modifier.fillMaxWidth(), shape = shape, color = color, border = border) { body() }
    else Surface(modifier.fillMaxWidth(), shape = shape, color = color, border = border) { body() }
}
/** Scrollable screen body. Tab screens omit [title] because the top bar already names them. */
@Composable fun Page(title: String? = null, disclaimer: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
        content()
        if (disclaimer) Text("仅记录家庭用药，用量以医嘱和说明书为准。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable fun Check(label: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = touchHeight).clickable(enabled) { change(!checked) },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, change, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f))
    }
}
@Composable fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = touchHeight).clickable(enabled) { change(!checked) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, change, enabled = enabled)
    }
}
@Composable fun Picker(label: String, value: String?, options: List<Pair<String, String>>, change: (String) -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = touchHeight), enabled = enabled,
                shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text(options.find { it.first == value }?.second ?: "请选择", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 400.dp)) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; change(id) },
                        modifier = Modifier.heightIn(min = touchHeight))
                }
            }
        }
    }
}
/** A compact dropdown chip showing the selected option. */
@Composable fun ChipPicker(value: String?, options: List<Pair<String, String>>, change: (String) -> Unit,
    enabled: Boolean = true, description: String? = null, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(onClick = { expanded = true }, enabled = enabled,
            label = { Text(options.find { it.first == value }?.second ?: "请选择", maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)) },
            trailingIcon = { Icon(Icons.Default.ExpandMore, description) },
            modifier = Modifier.heightIn(min = touchHeight))
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 400.dp)) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; change(id) },
                    modifier = Modifier.heightIn(min = touchHeight))
            }
        }
    }
}
@Composable fun Tabs(labels: List<String>, selected: Int, select: (Int) -> Unit) {
    if (labels.size in 2..4) {
        val on = tabPalette(true)
        val off = tabPalette(false)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(selected = i == selected, onClick = { select(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                    modifier = Modifier.heightIn(min = touchHeight),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = on.background, activeContentColor = on.content,
                        activeBorderColor = on.border, inactiveContainerColor = off.background, inactiveContentColor = off.content,
                        inactiveBorderColor = off.border)) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        return
    }
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

/** Buttons sit side by side and wrap only when they do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ActionRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically, content = content)
}

data class MenuAction(val label: String, val enabled: Boolean = true, val danger: Boolean = false, val onClick: () -> Unit)

/** "⋮" overflow menu for less-used actions; item labels stay identical to the former buttons. */
@Composable fun MoreMenu(actions: List<MenuAction>, enabled: Boolean = true, description: String = "更多操作") {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.size(touchHeight), enabled = enabled) { Icon(Icons.Default.MoreVert, description) }
        DropdownMenu(open, { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(text = { Text(a.label, color = if (a.danger && a.enabled) Red else Color.Unspecified) },
                    onClick = { open = false; a.onClick() }, enabled = a.enabled, modifier = Modifier.heightIn(min = touchHeight))
            }
        }
    }
}

@Composable fun StatusChip(text: String, color: Color = Green) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = .12f), border = BorderStroke(1.dp, color.copy(alpha = .5f))) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 2.dp), color = if (color == Amber) Ink else color,
            style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, subtitleLines: Int = 2,
    trailing: @Composable RowScope.() -> Unit = { if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted) }) {
    Row(Modifier.fillMaxWidth().heightIn(min = touchHeight + 8.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
    }
}

/** White rounded group for list rows separated by thin dividers. */
@Composable fun RowGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, Line)) {
        Column(content = content)
    }
}
@Composable fun RowDivider() { HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Line) }

@Composable fun SectionHeader(title: String, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = touchHeight), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        action?.invoke(this)
    }
}

@Composable fun SubPage(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(back, Modifier.size(touchHeight)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable fun Collapsible(title: String, initiallyOpen: Boolean = false, open: Boolean? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(initiallyOpen) }
    val shown = open == true || expanded
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = touchHeight).clickable { expanded = !shown },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = Green, fontWeight = FontWeight.SemiBold)
            Icon(if (shown) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (shown) "收起$title" else "展开$title", tint = Green)
        }
        if (shown) content()
    }
}

@Composable fun Modal(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).widthIn(max = 680.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(dismiss, Modifier.size(touchHeight)) { Icon(Icons.Default.Close, "关闭") }
                }
                Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}
@Composable fun Confirm(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    Modal(title, dismiss) {
        Text(text)
        ActionRow { Action("取消", secondary = true, onClick = dismiss); Action("明确确认", danger = true, onClick = confirm) }
    }
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
        ActionRow {
            Action("取消", secondary = true, onClick = dismiss)
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
}
private class UserInput(message: String) : Exception(message)
