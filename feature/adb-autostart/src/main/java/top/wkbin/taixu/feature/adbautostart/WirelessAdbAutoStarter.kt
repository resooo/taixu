package top.wkbin.taixu.feature.adbautostart

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager

/**
 * 无线调试自动开启的编排层。
 *
 * 把「开启系统开关」与「等待端口并连接」两件事串起来，供三个场景复用：
 * 1. 用户点击「自动开始无线调试」按钮；
 * 2. 开机广播 [AdbBootReceiver]；
 * 3. 检测到需要 ADB 时（由 [EmbeddedAdbManager.autoEnableHook] 触发）。
 */
class WirelessAdbAutoStarter(
    private val controller: WirelessAdbController,
    private val adbManager: EmbeddedAdbManager,
) {

    /** 上次尝试自动开启的时间戳，用于频控（避免连续失败时反复徒劳重试）。 */
    @Volatile
    private var lastAutoEnableAt: Long = 0L

    /**
     * 确保无线 ADB 可用：开启开关 → 10 秒内等待 mDNS 端口 → 自动连接。
     *
     * @return 成功或失败（失败时携带可直接展示给用户的文案）。
     */
    suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        // 已连接时直接返回。
        if (adbManager.state.value is EmbeddedAdbManager.ConnectionState.Connected) {
            return@withContext Result.success(Unit)
        }

        adbManager.startDiscovery()

        // 端口可能已存在：先直接试一次，命中通常 < 1 秒。
        val immediate = runCatching { adbManager.connect() }.getOrNull()
        if (immediate?.isSuccess == true) return@withContext Result.success(Unit)

        // 端口缺失时开启系统开关（自持写 Settings.Global）。
        if (controller.hasSecureSettingsPermission()) {
            val enabled = runCatching { controller.enableWirelessAdb().wirelessEnabled }.getOrDefault(false)
            Log.i(TAG, "ensureReady: 开关写入结果=$enabled")
        }

        // 在 10 秒窗口内等待系统广播 mDNS 端口，出现即连接。
        val endpoint = withTimeoutOrNull(AUTO_START_TIMEOUT_MS) {
            while (true) {
                adbManager.discovery.value.connectEndpoints.firstOrNull()?.let {
                    return@withTimeoutOrNull it
                }
                delay(AUTO_START_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        if (endpoint == null) {
            val message = "10 秒内未发现无线调试端口。请确认「无线调试」已开启；刚开启时系统广播端口可能稍有延迟。"
            return@withContext Result.failure(IllegalStateException(message))
        }

        // connect() 会读取已发现端点；此处端口已确认存在，重试一次即可完成连接。
        val retry = runCatching { adbManager.connect(endpoint.port) }.getOrNull()
        if (retry?.isSuccess == true) {
            Result.success(Unit)
        } else {
            val reason = retry?.exceptionOrNull()?.message
            Result.failure(
                IllegalStateException(reason?.takeIf { it.isNotBlank() } ?: "端口已发现但连接失败，请重试。"),
            )
        }
    }

    /**
     * 把本模块的自动开启能力接到 ADB 管理器上。
     *
     * 挂载后，任何 ADB 操作（executeShell / captureLogcat / installApk）在发现未连接时，
     * 都会自动尝试开启无线调试，用户无需手动点击。
     * 该钩子是 runtime 侧唯一的对接点（一个可空字段），上游更新时冲突面最小。
     */
    fun attachAutoEnableHook() {
        adbManager.autoEnableHook = {
            // 频控：60 秒内最多真正尝试一次，避免连续调用时反复无效重试。
            val now = System.currentTimeMillis()
            if (now - lastAutoEnableAt < AUTO_ENABLE_COOLDOWN_MS) {
                false
            } else {
                lastAutoEnableAt = now
                controller.hasSecureSettingsPermission() &&
                    controller.enableWirelessAdb().wirelessEnabled
            }
        }
        Log.i(TAG, "autoEnableHook 已挂载")
    }

    companion object {
        private const val TAG = "WirelessAdbAutoStarter"

        /** 「自动开始无线调试」总窗口：从触发开关到端口可用不超过 10 秒。 */
        private const val AUTO_START_TIMEOUT_MS = 10_000L

        /** 等待端口期间的轮询间隔。 */
        private const val AUTO_START_POLL_MS = 250L

        /** 自动开启的频控窗口：60 秒内最多触发一次。 */
        private const val AUTO_ENABLE_COOLDOWN_MS = 60_000L
    }
}
