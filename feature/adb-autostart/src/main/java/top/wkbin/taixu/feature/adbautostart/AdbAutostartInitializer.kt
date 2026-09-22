package top.wkbin.taixu.feature.adbautostart

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager

/**
 * 无线调试自动化的零侵入初始化入口。
 *
 * Android 会在 Application.onCreate 之后、首个 Activity 之前自动调用已声明的
 * ContentProvider.onCreate()。利用该机制，本模块可在**不改动宿主 Application 类**的
 * 前提下完成两件事：
 *
 * 1. **挂载 autoEnableHook** —— 此前仅在用户进入设置页时才挂载，导致 AI 首次
 *    调用 ADB 时钩子尚未生效，「按需自动开启」不工作。改为随进程启动即挂载。
 * 2. **启动预热**（可选）—— 若用户已开启「开机自动恢复」且已完成点火授权，
 *    则在后台静默确保无线 ADB 连接就绪，使后续 AI 调用无需等待开启过程。
 *
 * 该 Provider 不承载任何数据访问职责，仅作为初始化钩子使用。
 */
class AdbAutostartInitializer : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context ?: return false

        // 用 applicationScope 而非 GlobalScope：随进程存续，且不阻塞主线程。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val autoStarter: WirelessAdbAutoStarter = org.koin.core.context.GlobalContext.get().koin.get()
                val adbManager: EmbeddedAdbManager = org.koin.core.context.GlobalContext.get().koin.get()
                val preferences = AdbAutostartPreferences(appContext)

                // 1) 挂载钩子：任何 ADB 调用在未连接时会自动尝试开启无线调试。
                autoStarter.attachAutoEnableHook()
                Log.i(TAG, "autoEnableHook 已随进程启动挂载")

                // 2) 启动预热：仅在用户显式开启「开机自动恢复」且已完成授权时执行，
                //    避免对未使用该功能的用户产生额外行为。
                val snapshot = preferences.snapshot()
                if (snapshot.restoreOnBoot && snapshot.pairedOnce) {
                    val controller: WirelessAdbController = org.koin.core.context.GlobalContext.get().koin.get()
                    if (controller.hasSecureSettingsPermission()) {
                        if (adbManager.state.value is EmbeddedAdbManager.ConnectionState.Connected) {
                            Log.i(TAG, "无线 ADB 已连接，跳过启动预热")
                        } else {
                            Log.i(TAG, "开始启动预热：确保无线 ADB 就绪")
                            val result = autoStarter.ensureReady()
                            Log.i(TAG, "启动预热结果: ${result.fold({ "成功" }, { it.message ?: "失败" })}")
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "无线调试自动化初始化失败", it) }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "AdbAutostartInit"
    }
}
