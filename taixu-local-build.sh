#!/bin/sh
# ============================================================================
#  taixu-local-build —— 低内存环境下的太墟本地构建脚本
# ============================================================================
#
#  为什么需要这个脚本
#  ---------------------------------------------------------------------------
#  太墟的 gradle.properties 声明 -Xmx3072m + Metaspace 1024m，
#  但 Android 手机（PRoot 沙箱）总内存仅 11 GB，系统与太墟本体已占 ~7.5 GB，
#  留给 Gradle 的可用内存不足 4 GB —— 直接跑 ./gradlew 必然 OOM。
#
#  实测对比（一加 PJX110 / Android 16 / 11 GB 内存）
#  ---------------------------------------------------------------------------
#    默认参数（gradle.properties）    → OutOfMemoryError: GC overhead limit exceeded
#    本脚本参数                       → BUILD SUCCESSFUL，首次 12m14s，增量 2m58s
#
#  用法
#  ---------------------------------------------------------------------------
#    ./taixu-local-build.sh              # 构建 debug APK（默认）
#    ./taixu-local-build.sh release      # 构建 release APK（需签名配置）
#    ./taixu-local-build.sh module :feature:developer:compileDebugKotlin
#    ./taixu-local-build.sh clean        # 清理构建产物
#
#  关键参数说明
#  ---------------------------------------------------------------------------
#    -Xmx1536m            堆上限 1.5 GB（而非默认 3 GB）
#    -XX:+UseSerialGC     串行 GC —— 并行/并发 GC 会额外占用大量内存
#    --max-workers=1      单 worker，避免多个 Kotlin 编译任务同时抢内存
#    -Dkotlin.daemon.jvm.options  限制 Kotlin 编译器独立进程的堆
#
#  产物位置
#  ---------------------------------------------------------------------------
#    app/build/outputs/apk/debug/*.apk
#    app/build/outputs/apk/release/*.apk
#
# ============================================================================

set -u

# ── 环境准备 ────────────────────────────────────────────────────────────────
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR" || exit 1

# JDK：优先使用太墟内置的 JDK 25，其次系统 JAVA_HOME
if [ -x /root/.gradle/jdks/jdk-25/bin/java ]; then
    JAVA_HOME=/root/.gradle/jdks/jdk-25
elif [ -n "${JAVA_HOME:-}" ]; then
    :
else
    echo "错误：找不到 JDK。请设置 JAVA_HOME 或确保 /root/.gradle/jdks/jdk-25 存在。" >&2
    exit 3
fi
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

# Android SDK
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

# ── 内存参数（核心）─────────────────────────────────────────────────────────
GRADLE_MEM_ARGS="-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC"
KOTLIN_MEM_ARGS="-Xmx1024m"

# ── 参数解析 ────────────────────────────────────────────────────────────────
MODE="${1:-debug}"
case "$MODE" in
    debug)
        TASK=":app:assembleDebug"
        ;;
    release)
        TASK=":app:assembleRelease"
        ;;
    clean)
        TASK="clean"
        ;;
    module)
        shift
        TASK="${1:?用法: $0 module <gradle task>}"
        ;;
    *)
        # 允许直接传任意 Gradle 任务名
        TASK="$MODE"
        ;;
esac

# ── 构建前提示 ──────────────────────────────────────────────────────────────
AVAIL_MB=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo "?")
echo "┌─────────────────────────────────────────────────────────┐"
echo "│  taixu-local-build · 低内存构建模式                      │"
echo "├─────────────────────────────────────────────────────────┤"
printf "│  任务          : %-38s │\n" "$TASK"
printf "│  可用内存      : %-38s │\n" "${AVAIL_MB} MB"
printf "│  堆上限        : %-38s │\n" "1536 MB (SerialGC)"
echo "└─────────────────────────────────────────────────────────┘"
echo ""

if [ "$AVAIL_MB" != "?" ] && [ "$AVAIL_MB" -lt 2000 ]; then
    echo "⚠️  可用内存不足 2 GB，构建可能失败。建议："
    echo "    • 关闭其他应用后重试"
    echo "    • 或改用 CI 构建（GitHub Actions 有 7 GB 内存）"
    echo ""
fi

# ── 执行构建 ────────────────────────────────────────────────────────────────
# 注意：不要加 --configuration-cache。
# 实测（v0.18.0 / Gradle 9.7）该项目的 build-logic 复合构建存在 2 处配置缓存
# 不兼容问题，启用后构建直接 FAILED："Configuration cache problems found"。
# 去掉后增量构建稳定在 ~3 分钟。
START=$(date +%s)

./gradlew "$TASK" \
    --no-daemon \
    --max-workers=1 \
    --build-cache \
    -Dorg.gradle.jvmargs="$GRADLE_MEM_ARGS" \
    -Dkotlin.daemon.jvm.options="$KOTLIN_MEM_ARGS"
BUILD_RC=$?

END=$(date +%s)

# ── 结果汇报 ────────────────────────────────────────────────────────────────
echo ""
echo "───────────────────────────────────────────────────────────"
if [ "$BUILD_RC" -eq 0 ]; then
    echo "✅ 构建成功，耗时 $((END - START)) 秒"
    for d in app/build/outputs/apk/debug app/build/outputs/apk/release; do
        if [ -d "$d" ]; then
            for f in "$d"/*.apk; do
                [ -f "$f" ] && printf '   %s  (%s)\n' "$f" "$(du -h "$f" | cut -f1)"
            done
        fi
    done
else
    echo "❌ 构建失败（退出码 $BUILD_RC），耗时 $((END - START)) 秒"
    echo ""
    echo "排查建议："
    echo "  • 若出现 OutOfMemoryError —— 可用内存确实不足，请先释放内存或改用 CI"
    echo "  • 若出现编译错误 —— 查看上方 '^e:' 开头的 Kotlin 错误行"
fi
echo "───────────────────────────────────────────────────────────"

exit "$BUILD_RC"
