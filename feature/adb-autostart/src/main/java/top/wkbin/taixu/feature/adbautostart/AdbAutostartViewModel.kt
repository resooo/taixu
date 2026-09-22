package top.wkbin.taixu.feature.adbautostart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager

/**
 * 无线调试自动化的 UI 状态持有者。
 *
 * 独立于 `DeveloperViewModel`，避免上游改动该 ViewModel 时产生冲突。
 */
class AdbAutostartViewModel(
    private val controller: WirelessAdbController,
    private val preferences: AdbAutostartPreferences,
    private val autoStarter: WirelessAdbAutoStarter,
    private val adbManager: EmbeddedAdbManager,
) : ViewModel() {

    /** 是否已持有 WRITE_SECURE_SETTINGS。 */
    private val _secureSettingsGranted = MutableStateFlow(false)
    val secureSettingsGranted: StateFlow<Boolean> = _secureSettingsGranted.asStateFlow()

    /** 系统「无线调试」开关状态。 */
    private val _wirelessAdbEnabled = MutableStateFlow(false)
    val wirelessAdbEnabled: StateFlow<Boolean> = _wirelessAdbEnabled.asStateFlow()

    /** 状态文案，供卡片展示。 */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** 操作进行中标记。 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 开机自动恢复开关。 */
    val restoreOnBoot: StateFlow<Boolean> = preferences.restoreOnBoot
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** ADB 连接状态（复用 runtime 的既有状态流）。 */
    val adbState: StateFlow<EmbeddedAdbManager.ConnectionState> = adbManager.state
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            EmbeddedAdbManager.ConnectionState.Disconnected,
        )

    /** 是否已连接到无线 ADB。 */
    val adbConnected: StateFlow<Boolean> = adbState
        .map { it is EmbeddedAdbManager.ConnectionState.Connected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // 挂载按需自动开启钩子：任何 ADB 操作未连接时会自动开启无线调试。
        autoStarter.attachAutoEnableHook()
        refresh()
    }

    /** 只读刷新授权与开关状态。 */
    fun refresh() {
        _secureSettingsGranted.value = runCatching {
            controller.hasSecureSettingsPermission()
        }.getOrDefault(false)

        viewModelScope.launch {
            runCatching { controller.readState() }
                .onSuccess { result ->
                    _wirelessAdbEnabled.value = result.wirelessEnabled
                    _status.value = result.message
                }
                .onFailure { _status.value = it.message ?: "无法读取无线调试状态" }
        }
    }

    /** 一次性点火：经已连接的 ADB 通道授予自身 WRITE_SECURE_SETTINGS。 */
    fun enableSecureSettings() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "正在通过 ADB 通道授权系统设置写入权限…"

            val message = runCatching {
                controller.grantSecureSettings { command ->
                    val outcome = adbManager.executeShell(command)
                    outcome.exitCode == 0
                }
            }.fold(
                onSuccess = { result ->
                    _secureSettingsGranted.value = result.granted
                    if (result.granted) {
                        // 首次成功授权即视为完成过配对，为后续开机恢复建立前提。
                        preferences.setPairedOnce(true)
                        refresh()
                    }
                    result.message
                },
                onFailure = { it.message ?: "授权失败：未知错误" },
            )

            _status.value = message
            _busy.value = false
        }
    }

    /** 自动开始无线调试：开启开关 → 10 秒内等端口 → 连接。 */
    fun autoStart() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "正在自动开启无线调试并探测端口…"

            val message = autoStarter.ensureReady().fold(
                onSuccess = {
                    _wirelessAdbEnabled.value = true
                    preferences.setPairedOnce(true)
                    "无线调试已自动开启并完成连接。"
                },
                onFailure = { it.message?.takeIf { m -> m.isNotBlank() } ?: "自动开始无线调试失败" },
            )

            _status.value = message
            _busy.value = false
            refresh()
        }
    }

    /** 切换「开机自动恢复」。 */
    fun setRestoreOnBoot(enabled: Boolean) {
        viewModelScope.launch { preferences.setRestoreOnBoot(enabled) }
    }
}
