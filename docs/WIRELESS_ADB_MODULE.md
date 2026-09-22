# 无线 ADB 自动化模块化重构说明

> 目标：让「无线调试自动开启」能力在上游（`wkbin/taixu`）持续更新时保持低冲突、易跟进。

## 一、背景与问题

最初该能力以**内联方式**直接修改上游既有文件，共涉及 7 个文件、约 450 行改动：

| 文件 | 改动量 |
|---|---|
| `PrivilegeManager.kt` | +156 行（新增 4 个方法 + 2 个数据类） |
| `EmbeddedAdbManager.kt` | +167 行（新增自动连接逻辑，并改造 executeShell/installApk 内部） |
| `DeveloperViewModel.kt` | +117 行（新增状态流与 3 个业务方法） |
| `AdbLogcatScreen.kt` | +70 行（UI 卡片） |
| `KoinModule.kt` ×2 | DI 装配改动 |
| `AndroidManifest.xml` | 权限声明 |

**问题**：上游每次更新这些文件，都会产生合并冲突，尤其 `EmbeddedAdbManager.executeShell` 这类高频改动函数。

## 二、重构方案

### 核心思路

把全部业务逻辑迁入**新增模块** `feature/adb-autostart`（上游不存在同名路径 → 永不冲突），
上游侧只保留**最小钩子**（可空字段 + 一行调用）。

### 模块结构

```
feature/adb-autostart/
├── build.gradle.kts
├── src/main/AndroidManifest.xml            ← 库 Manifest，权限/Receiver 由合并机制注入
└── src/main/java/top/wkbin/taixu/
    ├── di/feature/adbautostart/
    │   └── KoinModule.kt                   依赖注册（含 viewModel）
    └── feature/adbautostart/
        ├── WirelessAdbController.kt        自持开关写入 / pm grant 点火 / 状态读取
        ├── WirelessAdbAutoStarter.kt       10 秒窗口等待 mDNS 端口 + 钩子挂载
        ├── AdbBootReceiver.kt              开机自动恢复（BOOT_COMPLETED）
        ├── AdbAutostartPreferences.kt      独立 DataStore（与上游偏好隔离）
        ├── AdbAutostartViewModel.kt        UI 状态
        └── AdbAutostartSection.kt          自包含 Compose 卡片
```

### 上游侧的最小钩子（唯一对接点）

**`runtime/.../bridge/adb/EmbeddedAdbManager.kt`（+18 行）**

```kotlin
/**
 * 【扩展点】按需开启无线调试的注入钩子。
 * 默认 null（行为与上游完全一致）。由外部模块在启动时挂载实现。
 */
@Volatile
var autoEnableHook: (suspend () -> Boolean)? = null
```

在两处 ADB 入口各加一行：

```kotlin
suspend fun executeShell(...): ShellOutcome {
    runCatching { autoEnableHook?.invoke() }   // ← 新增 1 行
    ...上游原逻辑不动...
}

suspend fun installApk(apk: File): Result<String> {
    ...
    autoEnableHook?.invoke()                   // ← 新增 1 行
    ...
}
```

**未挂载钩子时，行为与上游 100% 一致**（可空调用，无副作用）。

### UI 接入：插槽注入（规避架构检查）

太墟有 `:architectureCheck` 任务，**禁止 feature 模块之间横向依赖**：

```
feature:developer must not depend on feature:adb-autostart   ← 会被拒绝
```

因此采用**插槽模式**：

```kotlin
// feature/developer —— 只声明插槽，不引用新模块
@Composable
fun AdbLogcatScreen(
    onBack: () -> Unit,
    autoAdbSection: (@Composable () -> Unit)? = null,   // ← 仅 +4 行
    viewModel: DeveloperViewModel = koinViewModel(),
) { ... autoAdbSection?.invoke() ... }
```

```kotlin
// feature:navigation —— 架构检查豁免层，负责组装
entry<AdbLogcatDestination> {
    AdbLogcatScreen(
        onBack = ::popBack,
        autoAdbSection = { AdbAutostartSection(viewModel = koinViewModel()) },
    )
}
```

## 三、重构后的冲突面

| 上游文件 | 净改动 | 风险 |
|---|---:|---|
| `AndroidManifest.xml` | **0** | 无（权限由模块 Manifest 合并） |
| `PrivilegeManager.kt` | **0** | 无 |
| `DeveloperViewModel.kt` | **0** | 无 |
| `runtime/KoinModule.kt` | **0** | 无 |
| `developer/KoinModule.kt` | **0** | 无 |
| `settings.gradle.kts` | +1 | 极低 |
| `navigation/build.gradle.kts` | +1 | 极低 |
| `navigation/KoinModule.kt` | +2 | 极低 |
| `AdbLogcatScreen.kt` | +4 | 低（插槽声明） |
| `TaiXuNavHost.kt` | +9 | 低（组装调用） |
| `EmbeddedAdbManager.kt` | **+18** | 低（钩子字段 + 2 行调用） |

**合计：11 处、约 35 行纯新增**（原为 7 文件、450 行含内部改造）。

## 四、新功能：开机自动恢复

### 流程

```
系统开机 → BOOT_COMPLETED 广播
   → AdbBootReceiver（模块自带 Manifest 声明）
   → 检查三个前提：
       ① 用户已开启「开机自动恢复」开关（默认关闭）
       ② 曾成功完成过配对（pairedOnce）
       ③ 已持有 WRITE_SECURE_SETTINGS
   → 静默开启无线调试 + 等待 mDNS 端口 + 自动连接
   → 用户解锁手机时，ADB 通常已就绪
```

### 设置页开关

卡片底部新增「开机自动恢复」开关（默认关闭，需先完成点火授权才可开启）。

## 五、上游跟进操作指南

上游发布新版本后：

```bash
cd /workspace/taixu-fork
git fetch upstream main
git rebase upstream/main      # 或 git merge upstream/main
```

**预期冲突**：仅可能出现在 `EmbeddedAdbManager.kt` 的钩子两行附近，
以及 `AdbLogcatScreen.kt` 的插槽参数、`TaiXuNavHost.kt` 的组装调用。

**冲突解决要点**：
1. 保留 `autoEnableHook` 字段与两处 `runCatching { autoEnableHook?.invoke() }`；
2. 保留 `AdbLogcatScreen` 的 `autoAdbSection` 参数与调用；
3. 保留 navigation 层传入 `AdbAutostartSection(...)` 的组装；
4. `feature/adb-autostart/` 目录本身不会产生冲突。

### 更优路径：向上游提 PR

该能力解决的正是上游的真实痛点（无线调试需手动开、Shizuku 易超时），
建议以当前模块化形态提交 PR，被合并后即可零维护。

## 六、验证记录

| 项 | 结果 |
|---|---|
| 架构检查 `:architectureCheck` | ✅ 通过（插槽方案规避 feature 横向依赖） |
| CI 构建 | ✅ https://github.com/resooo/taixu/actions/runs/35736301829 |
| APK SHA256 | `04f03c71574dda0ea65b8a994a4fe7f40ffea069cb310c73514535bfbc8fe275` |
| 新模块代码打包 | ✅ WirelessAdbController / AutoStarter / AdbBootReceiver 等全部命中 |
