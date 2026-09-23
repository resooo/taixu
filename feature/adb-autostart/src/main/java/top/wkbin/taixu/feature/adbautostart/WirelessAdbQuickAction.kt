package top.wkbin.taixu.feature.adbautostart

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName

/**
 * 智枢顶部工具栏的「一键开启无线调试」快捷按钮。
 *
 * 由 feature:navigation 通过 ChatTopBar 的 topBarLeadingActions 插槽注入，
 * 因此不产生 feature 模块间的横向依赖。
 *
 * 交互设计：
 * - 单击 → 立即执行「开启无线调试 + 等待端口 + 连接」；
 * - 按钮颜色反映实时状态：已连接为强调色、已授权为次要色、未授权为警示色；
 * - 处理中显示省略号，避免用户重复点击。
 */
@Composable
fun WirelessAdbQuickAction(
    viewModel: AdbAutostartViewModel,
    modifier: Modifier = Modifier,
) {
    val connected by viewModel.adbConnected.collectAsStateWithLifecycle()
    val granted by viewModel.secureSettingsGranted.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val tint: Color = when {
        busy -> MaterialTheme.colorScheme.onSurfaceVariant
        connected -> MaterialTheme.colorScheme.primary
        granted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }

    IconButton(
        onClick = {
            // 未授权时先尝试「启用」（pm grant 点火），已授权则直接开启无线调试。
            if (!granted) viewModel.enableSecureSettings() else viewModel.autoStart()
        },
        enabled = !busy,
        modifier = modifier,
    ) {
        RuntimeIcon(
            RuntimeIconName.Network,
            Modifier.size(19.dp),
            tint = tint,
        )
    }
}
