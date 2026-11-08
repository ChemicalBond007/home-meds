package com.homemeds.app.ui

import android.content.Intent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import com.homemeds.app.BuildConfig
import com.homemeds.app.R

internal const val PROJECT_URL = "https://github.com/ChemicalBond007/home-meds"

@Composable internal fun AboutPanel(error: (String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val license = remember(resources) {
        resources.openRawResource(R.raw.license).bufferedReader().use { it.readText() }
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { error("无法打开链接，请安装浏览器后重试") }
    }
    RowGroup {
        SettingRow("赛博药箱 HomeMeds", "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", trailing = {})
        RowDivider()
        SettingRow("GitHub 项目主页", PROJECT_URL, onClick = { open(PROJECT_URL) })
    }
    Panel("开源许可证 · MIT License") {
        SelectionContainer { Text(license) }
    }
}
