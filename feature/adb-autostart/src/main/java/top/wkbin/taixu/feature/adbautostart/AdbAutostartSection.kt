package top.wkbin.taixu.feature.adbautostart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.ui.components.IconTile
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.SectionHeader
import top.wkbin.taixu.ui.components.StatusBadge

/**
 * 「自动无线调试」设置卡片。
 *
 * 自包含：由本模块提供 ViewModel 与全部 UI，宿主页面只需调用一次本函数，
 * 因此上游改动设置页布局时不会影响本功能的可用性。
 */
@Composable
fun AdbAutostartSection(
    viewModel: AdbAutostartViewModel,
    modifier: Modifier = Modifier,
) {
    val secureSettingsGranted by viewModel.secureSettingsGranted.collectAsStateWithLifecycle()
    val wirelessAdbEnabled by viewModel.wirelessAdbEnabled.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val restoreOnBoot by viewModel.restoreOnBoot.collectAsStateWithLifecycle()

    SectionHeader(
        "自动无线调试",
        "一次性授权后，太墟可自行开关无线调试，无需 Shizuku 或手动进入开发者选项",
        modifier,
    )
    RuntimeCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(
                icon = RuntimeIconName.Link,
                color = if (wirelessAdbEnabled) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("无线调试状态", style = MaterialTheme.typography.titleMedium)
                Text(
                    status ?: "尚未检测",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusBadge(
                text = when {
                    wirelessAdbEnabled -> "已开启"
                    secureSettingsGranted -> "已授权"
                    else -> "未授权"
                },
                color = when {
                    wirelessAdbEnabled -> MaterialTheme.colorScheme.secondary
                    secureSettingsGranted -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }

        if (!secureSettingsGranted) {
            Spacer(Modifier.height(12.dp))
            Text(
                "第 1 步：先在「安全配对与连接」完成配对并连接，然后点击下面的启用按钮。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = viewModel::enableSecureSettings,
                enabled = !busy && !secureSettingsGranted,
                modifier = Modifier.weight(1f),
            ) { Text(if (secureSettingsGranted) "已授权" else "启用") }

            Button(
                onClick = viewModel::autoStart,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (busy) "处理中…" else "自动开始无线调试") }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("开机自动恢复", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "重启后静默恢复无线调试，无需再次手动开启",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = restoreOnBoot,
                onCheckedChange = viewModel::setRestoreOnBoot,
                enabled = secureSettingsGranted,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "启用上述能力后，AI 执行命令、抓取日志、构建后安装 APK 等操作都会自动连通无线调试。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
