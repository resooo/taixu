package top.wkbin.taixu.ui.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.OnboardingPreferences
import top.wkbin.taixu.core.datastore.RegistryPreferences
import top.wkbin.taixu.core.datastore.RuntimePreferences
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.core.model.InstalledRuntime
import top.wkbin.taixu.core.tools.RuntimeManager
import top.wkbin.taixu.core.tools.SignedRegistryRequest
import top.wkbin.taixu.core.tools.ToolManager
import top.wkbin.taixu.core.tools.ToolRegistry
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.RuntimeHealth
import top.wkbin.taixu.runtime.RootfsUpdateInfo
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager
import top.wkbin.taixu.runtime.privilege.PrivilegeManager
import top.wkbin.taixu.runtime.privilege.ShellExecResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DeveloperViewModel(
    private val linuxRuntime: LinuxRuntime,
    private val runtimeManager: RuntimeManager,
    private val runtimePreferences: RuntimePreferences,
    private val agentPreferences: AgentPreferences,
    private val registryPreferences: RegistryPreferences,
    private val onboardingPreferences: top.wkbin.taixu.core.datastore.OnboardingPreferences,
    private val toolRegistry: ToolRegistry,
    private val toolManager: ToolManager,
    private val logger: AppLogger,
    private val embeddedAdbManager: EmbeddedAdbManager,
    private val privilegeManager: PrivilegeManager,
) : ViewModel() {

    init {
        embeddedAdbManager.startDiscovery(EmbeddedAdbManager.TAG_DEVELOPER_UI)
    }

    override fun onCleared() {
        super.onCleared()
        embeddedAdbManager.stopDiscovery(EmbeddedAdbManager.TAG_DEVELOPER_UI)
    }

    val runtimeState: StateFlow<RuntimeState> = linuxRuntime.state

    private val _commandInput = MutableStateFlow("cat /etc/os-release")
    val commandInput: StateFlow<String> = _commandInput.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _health = MutableStateFlow<RuntimeHealth?>(null)
    val health: StateFlow<RuntimeHealth?> = _health.asStateFlow()

    private val _commandResult = MutableStateFlow<CommandResult?>(null)
    val commandResult: StateFlow<CommandResult?> = _commandResult.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 当前 message 是否为失败结果（类型化标记，避免 UI 用字符串匹配判断样式）。 */
    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    private fun setMessage(text: String, isError: Boolean = false) {
        _message.value = text
        _messageIsError.value = isError
    }
    private val _unusedRuntimes = MutableStateFlow<List<InstalledRuntime>>(emptyList())
    val unusedRuntimes: StateFlow<List<InstalledRuntime>> = _unusedRuntimes.asStateFlow()
    private val _processes = MutableStateFlow<List<ManagedProcess>>(emptyList())
    val processes: StateFlow<List<ManagedProcess>> = _processes.asStateFlow()
    private val _rootfsVersion = MutableStateFlow<String?>(null)
    val rootfsVersion: StateFlow<String?> = _rootfsVersion.asStateFlow()
    private val _rootfsUpdate = MutableStateFlow<RootfsUpdateInfo?>(null)
    val rootfsUpdate: StateFlow<RootfsUpdateInfo?> = _rootfsUpdate.asStateFlow()
    private var initializationJob: Job? = null

    val adbState: StateFlow<EmbeddedAdbManager.ConnectionState> = embeddedAdbManager.state
    val adbDiscovery: StateFlow<EmbeddedAdbManager.DiscoveryState> = embeddedAdbManager.discovery
    private val _adbBusy = MutableStateFlow(false)
    val adbBusy: StateFlow<Boolean> = _adbBusy.asStateFlow()
    private val _adbMessage = MutableStateFlow<String?>(null)
    val adbMessage: StateFlow<String?> = _adbMessage.asStateFlow()

    /** 是否已持有 WRITE_SECURE_SETTINGS（点火完成后为 true）。 */
    private val _secureSettingsGranted = MutableStateFlow(false)
    val secureSettingsGranted: StateFlow<Boolean> = _secureSettingsGranted.asStateFlow()

    /** 系统无线调试开关状态。 */
    private val _wirelessAdbEnabled = MutableStateFlow(false)
    val wirelessAdbEnabled: StateFlow<Boolean> = _wirelessAdbEnabled.asStateFlow()

    /** 无线调试相关状态文案，供 UI 展示。 */
    private val _wirelessAdbStatus = MutableStateFlow<String?>(null)
    val wirelessAdbStatus: StateFlow<String?> = _wirelessAdbStatus.asStateFlow()

    private val _logcatOutput = MutableStateFlow("")
    val logcatOutput: StateFlow<String> = _logcatOutput.asStateFlow()

    val adbNotificationEnabled: StateFlow<Boolean> = runtimePreferences.adbNotificationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAdbNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runtimePreferences.setAdbNotificationEnabled(enabled)
        }
    }

    fun pairWirelessAdb(code: String, pairingPort: Int? = null) {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = if (pairingPort != null) "正在使用指定配对端口 $pairingPort 进行安全配对…" else "正在使用自动发现的配对端口进行安全配对…"
            val result = embeddedAdbManager.pair(pairingPort, code.trim())
            _adbMessage.value = result.fold(
                onSuccess = { "配对并连接成功；密钥已安全保存，后续将自动发现端口并重连。" },
                onFailure = { it.message ?: "无线 ADB 配对失败" },
            )
            _adbBusy.value = false
        }
    }

    fun connectWirelessAdb(explicitPort: Int? = null) {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = if (explicitPort != null) "正在连接指定端口 $explicitPort…" else "正在连接自动发现的无线调试端口…"
            val result = embeddedAdbManager.connect(explicitPort)
            _adbMessage.value = result.fold(
                onSuccess = { "无线 ADB 已连接。" },
                onFailure = { it.message ?: "无线 ADB 连接失败" },
            )
            _adbBusy.value = false
        }
    }

    fun restartAdbDiscovery() {
        embeddedAdbManager.restartDiscovery()
        _adbMessage.value = "已重新启动 mDNS 端口探测。"
    }

    // ── 无线调试自持（一次性 pm grant 点火） ────────────────────────────────

    /** 只读刷新授权与开关状态，用于页面初始化与徽标展示。 */
    fun refreshWirelessAdbState() {
        _secureSettingsGranted.value = privilegeManager.hasSecureSettingsPermission()
        viewModelScope.launch {
            runCatching { privilegeManager.readWirelessAdbState() }
                .onSuccess { result ->
                    _wirelessAdbEnabled.value = result.wirelessEnabled
                    _wirelessAdbStatus.value = result.message
                }
                .onFailure { _wirelessAdbStatus.value = it.message ?: "无法读取无线调试状态" }
        }
    }

    /**
     * 一次性点火：经当前已连接的无线 ADB 通道执行 pm grant，
     * 把 WRITE_SECURE_SETTINGS 授予太墟自身，此后即可自行开关无线调试。
     */
    fun enableSecureSettings() {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = "正在通过 ADB 通道授权系统设置写入权限…"

            val result = privilegeManager.grantSecureSettingsViaAdb { command ->
                val outcome = embeddedAdbManager.executeShell(command)
                ShellExecResult(
                    success = outcome.exitCode == 0,
                    exitCode = outcome.exitCode ?: -1,
                    stdout = outcome.output,
                    stderr = "",
                )
            }

            _secureSettingsGranted.value = result.granted
            _adbMessage.value = result.message
            if (result.granted) refreshWirelessAdbState()
            _adbBusy.value = false
        }
    }

    /**
     * 自动开始无线调试：开启开关 → 10 秒内等待 mDNS 发现端口 → 自动连接。
     * 已点火时完全依靠自身权限，不再需要 Shizuku。
     */
    fun autoStartWirelessAdb() {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = "正在自动开启无线调试并探测端口…"

            val canSelfHeld = privilegeManager.hasSecureSettingsPermission()
            val result = embeddedAdbManager.ensureWirelessAdbReady(
                enableSwitch = if (canSelfHeld) {
                    { privilegeManager.enableWirelessAdbSelfHeld().wirelessEnabled }
                } else {
                    null
                },
            )

            _adbMessage.value = result.fold(
                onSuccess = {
                    _wirelessAdbEnabled.value = true
                    _wirelessAdbStatus.value = "无线调试已自动开启并完成连接。"
                    "无线调试已自动开启并完成连接。"
                },
                onFailure = { error -> error.message ?: "自动开始无线调试失败" },
            )
            _adbBusy.value = false
            refreshWirelessAdbState()
        }
    }

    fun captureLogcat(packageName: String, tag: String, priority: Char, keyword: String, lines: Int) {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = "正在抓取日志…"
            runCatching {
                embeddedAdbManager.captureLogcat(
                    EmbeddedAdbManager.LogcatRequest(packageName.trim(), tag.trim(), priority, keyword, lines),
                )
            }.onSuccess { result ->
                _logcatOutput.value = result.output
                _adbMessage.value = if (result.success) "日志抓取完成。" else result.output
            }.onFailure { error ->
                _adbMessage.value = error.message ?: "日志抓取失败"
            }
            _adbBusy.value = false
        }
    }

    fun clearDeviceLogcat() {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            val result = embeddedAdbManager.clearLogcat()
            if (result.success) _logcatOutput.value = ""
            _adbMessage.value = if (result.success) "设备 Logcat 缓冲区已清空。" else result.output
            _adbBusy.value = false
        }
    }

    val registryManifestUrl: StateFlow<String> = registryPreferences.manifestUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val registrySignatureUrl: StateFlow<String> = registryPreferences.signatureUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val registryPublicKey: StateFlow<String> = registryPreferences.publicKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val _registryStatus = MutableStateFlow<String?>(null)
    val registryStatus: StateFlow<String?> = _registryStatus.asStateFlow()

    /** 工具清单更新是否失败（类型化标记）。 */
    private val _registryStatusIsError = MutableStateFlow(false)
    val registryStatusIsError: StateFlow<Boolean> = _registryStatusIsError.asStateFlow()

    private fun setRegistryStatus(text: String, isError: Boolean = false) {
        _registryStatus.value = text
        _registryStatusIsError.value = isError
    }

    val agentLoggingEnabled: StateFlow<Boolean> = agentPreferences.agentLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _agentLogSize = MutableStateFlow(0L)
    val agentLogSize: StateFlow<Long> = _agentLogSize.asStateFlow()
    private val _agentLogLocation = MutableStateFlow("")
    val agentLogLocation: StateFlow<String> = _agentLogLocation.asStateFlow()

    fun setAgentLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            agentPreferences.setAgentLoggingEnabled(enabled)
            refreshAgentLogSize()
        }
    }

    fun refreshAgentLogSize() {
        _agentLogSize.value = logger.getAgentLogSizeBytes()
        _agentLogLocation.value = logger.getAgentLogLocation()
    }

    fun readAgentLogs(): String = logger.readAgentLogs()

    fun clearAgentLogs() {
        logger.clearAgentLogs()
        refreshAgentLogSize()
        setMessage("智能体日志已清空。")
    }

    fun saveRegistryConfig(manifestUrl: String, signatureUrl: String, publicKey: String) {
        viewModelScope.launch {
            registryPreferences.setRegistryConfig(manifestUrl, signatureUrl, publicKey)
            setRegistryStatus("工具清单配置已保存。")
        }
    }

    fun updateRegistry() {
        viewModelScope.launch {
            setRegistryStatus("正在下载并验证工具清单…")
            val request = SignedRegistryRequest(
                manifestUrl = registryManifestUrl.value,
                signatureUrl = registrySignatureUrl.value,
                publicKeyBase64 = registryPublicKey.value,
            )
            val result = toolRegistry.updateSigned(request)
            if (result.isSuccess) {
                toolManager.syncRegistry()
                setRegistryStatus("工具清单已更新：${result.getOrNull()} 个工具。")
            } else {
                logger.e("Registry update failed: ${result.errorOrNull()?.message}")
                setRegistryStatus("工具清单更新失败，请检查清单地址、签名与公钥配置", isError = true)
            }
        }
    }

    init {
        refreshUnusedRuntimes()
        refreshProcesses()
        refreshRootfsVersion()
        refreshAgentLogSize()
        // 无线调试状态刷新：需在全部 StateFlow 声明之后执行（Kotlin 属性按书写顺序初始化）。
        refreshWirelessAdbState()
    }

    fun onCommandInputChanged(value: String) {
        _commandInput.value = value
    }

    fun initialize() {
        if (_busy.value) return
        _message.value = null
        initializationJob = viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = linuxRuntime.initialize()) {
                    is AppResult.Success -> setMessage("初始化完成。")
                    is AppResult.Failure -> {
                        logger.e("Runtime initialize failed: ${result.error.message}")
                        setMessage("初始化失败，请检查网络与存储空间后重试", isError = true)
                    }
                }
            } finally {
                _busy.value = false
                initializationJob = null
            }
        }
    }

    fun cancelInitialization() {
        initializationJob?.cancel()
    }

    fun updateRootfs() {
        if (_busy.value || linuxRuntime.state.value !is RuntimeState.Ready) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            _messageIsError.value = false
            runCatching { linuxRuntime.updateRootfs() }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        setMessage("RootFS 更新完成，用户数据已保留。")
                    } else {
                        logger.e("RootFS update failed: ${result.errorOrNull()?.message}")
                        setMessage("RootFS 更新失败，已自动恢复旧版本；请稍后重试", isError = true)
                    }
                    refreshRootfsVersion()
                }
                .onFailure { throwable ->
                    logger.e("RootFS update failed", throwable)
                    setMessage("RootFS 更新失败，请检查网络后重试", isError = true)
                }
            _busy.value = false
        }
    }

    fun checkRootfsUpdate() {
        if (_busy.value || linuxRuntime.state.value !is RuntimeState.Ready) return
        viewModelScope.launch {
            _busy.value = true
            setMessage("正在检查 RootFS 的 OCI manifest…")
            runCatching { linuxRuntime.checkRootfsUpdate() }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        val info = result.getOrNull()!!
                        _rootfsUpdate.value = info
                        if (info.hasUpdate) {
                            setMessage("检测到 RootFS 新版本，可以更新。")
                        } else {
                            setMessage("RootFS 已是最新版本。")
                        }
                    } else {
                        logger.e("RootFS update check failed: ${result.errorOrNull()?.message}")
                        setMessage("RootFS 更新检查失败，请检查网络后重试", isError = true)
                    }
                }
                .onFailure { throwable ->
                    logger.e("RootFS update check failed", throwable)
                    setMessage("RootFS 更新检查失败，请检查网络后重试", isError = true)
                }
            _busy.value = false
        }
    }

    fun runHealthCheck() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { linuxRuntime.healthCheck() }
                .onSuccess { _health.value = it }
                .onFailure {
                    logger.e("Health check failed", it)
                    setMessage("健康检查失败，请确认运行时已就绪后重试", isError = true)
                }
            _busy.value = false
        }
    }

    fun refreshUnusedRuntimes() {
        viewModelScope.launch {
            runCatching { runtimeManager.unusedRuntimes() }
                .onSuccess { _unusedRuntimes.value = it }
                .onFailure { logger.e("读取可清理 Runtime 失败", it) }
        }
    }

    fun cleanupRuntime(runtimeId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { runtimeManager.cleanup(runtimeId) }
                .onSuccess { result ->
                    if (result.isSuccess) setMessage("共享 Runtime 已清理。") else setMessage("清理失败，请稍后重试", isError = true)
                    refreshUnusedRuntimes()
                }
                .onFailure { logger.e("Runtime cleanup failed", it); setMessage("清理失败，请稍后重试", isError = true) }
            _busy.value = false
        }
    }

    fun refreshProcesses() {
        viewModelScope.launch {
            runCatching {
                linuxRuntime.cleanupDeadBackground()
                linuxRuntime.listBackground()
            }.onSuccess { _processes.value = it }
                .onFailure { logger.e("读取后台进程失败", it) }
        }
    }

    fun stopProcess(processId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            runCatching { linuxRuntime.stopBackground(processId) }
                .onSuccess { refreshProcesses() }
                .onFailure { logger.e("Stop process failed", it); setMessage("停止进程失败，请稍后重试", isError = true) }
        }
    }

    fun refreshRootfsVersion() {
        _rootfsVersion.value = linuxRuntime.rootfsVersion()
    }

    fun resetLinuxEnvironment() {
        if (_busy.value || linuxRuntime.state.value is RuntimeState.Initializing) return
        viewModelScope.launch {
            _busy.value = true
            val distroId = linuxRuntime.activeDistroId.value
            runCatching {
                val result = linuxRuntime.resetSandbox(distroId)
                check(result.isSuccess) { result.errorOrNull()?.message ?: "Linux 环境重置失败" }
                toolManager.resetDistroState(distroId)
                // A factory-style runtime reset intentionally restarts the
                // complete first-run flow: environment download, then model
                // selection/configuration.
                onboardingPreferences.setOnboardingCompleted(false)
                result
            }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        setMessage("Linux 环境已恢复初始状态，工作区工程未删除。")
                    } else {
                        logger.e("Linux reset failed: ${result.errorOrNull()?.message}")
                        setMessage("重置失败，请稍后重试", isError = true)
                    }
                }
                .onFailure { logger.e("Linux reset failed", it); setMessage("重置失败，请稍后重试", isError = true) }
            _busy.value = false
        }
    }

    fun runCommand() {
        if (_busy.value) return
        val command = _commandInput.value.trim()
        if (command.isEmpty()) {
            setMessage("命令不能为空。", isError = true)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching {
                linuxRuntime.execute(ShellCommand(commandLine = command))
            }.onSuccess {
                _commandResult.value = it
            }.onFailure {
                logger.e("Command execution failed", it)
                _commandResult.value = null
                setMessage("命令执行失败，请检查命令语法与运行时状态", isError = true)
            }
            _busy.value = false
        }
    }
}
