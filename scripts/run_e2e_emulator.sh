#!/usr/bin/env bash
# =============================================================================
# YouYou Android E2E 测试 —— 默认走模拟器路径
#
# 功能：
#   1. 自动检测已运行的 Android 模拟器
#   2. 无模拟器时自动启动默认 AVD（Pixel_10_Pro）
#   3. 等待模拟器完全启动（boot_completed）
#   4. 解锁模拟器屏幕
#   5. 构建并运行 androidTest（全部 E2E 测试）
#   6. 输出测试结果摘要
#
# 用法：
#   ./scripts/run_e2e_emulator.sh              # 运行全部测试
#   ./scripts/run_e2e_emulator.sh --tests com.example.youyou_album.server.ServerConnectionE2ETest
#   ./scripts/run_e2e_emulator.sh --no-start   # 不自动启动模拟器（要求已有设备）
#   ./scripts/run_e2e_emulator.sh --avd <name> # 指定 AVD 名称
# =============================================================================

set -euo pipefail

# ─── 配置 ───────────────────────────────────────────────────────────────────
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$ANDROID_SDK/platform-tools/adb"
EMULATOR="$ANDROID_SDK/emulator/emulator"
DEFAULT_AVD="Pixel_10_Pro"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/apps/android"

# ─── 参数解析 ───────────────────────────────────────────────────────────────
AVD_NAME="$DEFAULT_AVD"
AUTO_START=true
TEST_FILTER=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --avd)
            AVD_NAME="$2"
            shift 2
            ;;
        --no-start)
            AUTO_START=false
            shift
            ;;
        --tests)
            TEST_FILTER="$2"
            shift 2
            ;;
        -h|--help)
            grep '^#' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "未知参数: $1" >&2
            exit 1
            ;;
    esac
done

# ─── 工具函数 ───────────────────────────────────────────────────────────────
log()  { echo -e "\033[1;34m[E2E]\033[0m $*"; }
warn() { echo -e "\033[1;33m[WARN]\033[0m $*" >&2; }
err()  { echo -e "\033[1;31m[ERROR]\033[0m $*" >&2; }

# 检查命令是否存在
require_cmd() {
    if ! command -v "$1" &>/dev/null && [[ ! -x "$1" ]]; then
        err "未找到命令: $1"
        exit 1
    fi
}

# 获取第一个运行中的模拟器序列号
# 注意：BSD grep（macOS 自带）不支持 \s，必须用 [[:space:]]，否则永远匹配不到已启动的模拟器。
get_running_emulator() {
    "$ADB" devices 2>/dev/null | grep -E 'emulator-[0-9]+[[:space:]]+device' | head -1 | awk '{print $1}' || true
}

# 等待模拟器启动完成
wait_for_boot() {
    local serial="$1"
    local timeout=180
    local elapsed=0

    log "等待模拟器启动完成（最长 ${timeout}s）..."
    while [[ $elapsed -lt $timeout ]]; do
        local boot_status
        boot_status="$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || echo '')"
        if [[ "$boot_status" == "1" ]]; then
            log "模拟器启动完成（耗时 ${elapsed}s）"
            return 0
        fi
        sleep 3
        elapsed=$((elapsed + 3))
    done
    err "模拟器启动超时"
    return 1
}

# 唤醒并解锁模拟器屏幕
#
# 息屏会让 Compose 用例假失败（`No compose hierarchies found in the app`），
# 因为 Activity 起不到前台。所以先关掉自动息屏再唤醒。
unlock_screen() {
    local serial="$1"
    log "唤醒并解锁模拟器屏幕..."
    "$ADB" -s "$serial" shell settings put system screen_off_timeout 2147483647 2>/dev/null || true
    "$ADB" -s "$serial" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
    "$ADB" -s "$serial" shell input keyevent 82 2>/dev/null || true  # MENU 唤醒
    "$ADB" -s "$serial" shell input keyevent 4 2>/dev/null || true   # BACK 关闭锁屏
    sleep 1
}

# ─── 主流程 ─────────────────────────────────────────────────────────────────

log "===== YouYou Android E2E 测试（模拟器路径） ====="
log "项目根目录: $PROJECT_ROOT"
log "Android SDK: $ANDROID_SDK"

# 检查依赖
require_cmd "$ADB"
require_cmd "$EMULATOR"

# 1. 检测运行中的模拟器
RUNNING_EMULATOR="$(get_running_emulator)"

if [[ -n "$RUNNING_EMULATOR" ]]; then
    log "检测到已运行的模拟器: $RUNNING_EMULATOR"
    TARGET_SERIAL="$RUNNING_EMULATOR"
else
    if [[ "$AUTO_START" != "true" ]]; then
        err "未检测到运行中的模拟器，且指定了 --no-start"
        exit 1
    fi

    # 检查 AVD 是否存在
    if ! "$EMULATOR" -list-avds 2>/dev/null | grep -q "^${AVD_NAME}$"; then
        err "AVD '$AVD_NAME' 不存在。可用 AVD："
        "$EMULATOR" -list-avds
        exit 1
    fi

    log "启动模拟器: $AVD_NAME"
    "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-save -no-boot-anim -gpu auto &
    EMULATOR_PID=$!

    # 等待模拟器出现在 adb devices 中
    log "等待模拟器设备上线..."
    for i in $(seq 1 60); do
        RUNNING_EMULATOR="$(get_running_emulator)"
        if [[ -n "$RUNNING_EMULATOR" ]]; then
            break
        fi
        sleep 2
    done

    if [[ -z "$RUNNING_EMULATOR" ]]; then
        err "模拟器未能在 120s 内上线"
        kill "$EMULATOR_PID" 2>/dev/null || true
        exit 1
    fi

    TARGET_SERIAL="$RUNNING_EMULATOR"
    log "模拟器已上线: $TARGET_SERIAL (PID: $EMULATOR_PID)"
fi

# 2. 等待启动完成并解锁
wait_for_boot "$TARGET_SERIAL"
unlock_screen "$TARGET_SERIAL"

# 3. 显示设备信息
log "设备信息:"
"$ADB" -s "$TARGET_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' | sed 's/^/  型号: /'
DEVICE_ANDROID_VERSION="$("$ADB" -s "$TARGET_SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
DEVICE_API_LEVEL="$("$ADB" -s "$TARGET_SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
log "  Android 版本: ${DEVICE_ANDROID_VERSION} (API ${DEVICE_API_LEVEL})"

if [[ "${DEVICE_API_LEVEL:-0}" -lt 31 ]]; then
    err "当前客户端最低支持 Android 12（API 31），拒绝在 API ${DEVICE_API_LEVEL:-未知} 上执行验收"
    exit 1
fi

# 4. 构建并运行测试
log "开始构建并运行 E2E 测试..."
cd "$ANDROID_DIR"

GRADLE_ARGS=("connectedDebugAndroidTest")
if [[ -n "$TEST_FILTER" ]]; then
    GRADLE_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=$TEST_FILTER")
fi

log "执行: ./gradlew ${GRADLE_ARGS[*]}（ANDROID_SERIAL=${TARGET_SERIAL}）"

# 打时间戳：结果目录里会残留上一次（甚至别的设备）的 XML，
# 统计时必须只算本次运行新写出的文件，否则会打印出与退出码矛盾的「失败: 0」。
RUN_STAMP="$(mktemp -t youyou-e2e-stamp)"
trap 'rm -f "$RUN_STAMP"' EXIT

TEST_EXIT_CODE=1
set +e
# 必须用 ANDROID_SERIAL 钉住设备。不钉的话 Gradle 会**在所有已连接设备上各跑一遍**——
# 包括用户插着的真机，而真机上跑全套会去清真实 prefs、写真实库。
ANDROID_SERIAL="$TARGET_SERIAL" ./gradlew "${GRADLE_ARGS[@]}"
TEST_EXIT_CODE=$?
set -e

# 5. 输出结果摘要
echo ""
log "===== 测试结果摘要 ====="

REPORT_DIR="$ANDROID_DIR/app/build/reports/androidTests/connected"
if [[ -d "$REPORT_DIR" ]]; then
    log "测试报告目录: $REPORT_DIR"
    # 查找 index.html
    REPORT_HTML=$(find "$REPORT_DIR" -name "index.html" -maxdepth 2 | head -1 || true)
    if [[ -n "$REPORT_HTML" ]]; then
        log "测试报告: file://$REPORT_HTML"
    fi
fi

RESULT_DIR="$ANDROID_DIR/app/build/outputs/androidTest-results/connected"
if [[ -d "$RESULT_DIR" ]]; then
    log "测试结果 XML: $RESULT_DIR"
    # 只统计本次运行新写出的 XML（-newer "$RUN_STAMP"）。
    FRESH_XMLS=$(find "$RESULT_DIR" -name "TEST-*.xml" -newer "$RUN_STAMP" 2>/dev/null || true)
    if [[ -n "$FRESH_XMLS" ]]; then
        TOTAL=0; FAILURES=0; ERRORS=0; SKIPPED=0
        while IFS= read -r xml; do
            [[ -z "$xml" ]] && continue
            # 取该文件 <testsuite> 上的计数
            line=$(grep -o 'tests="[0-9]*"[^>]*' "$xml" | head -1 || true)
            n() { printf '%s' "$line" | grep -o "$1=\"[0-9]*\"" | head -1 | grep -o '[0-9]*' || echo 0; }
            TOTAL=$((TOTAL + $(n tests)))
            SKIPPED=$((SKIPPED + $(n skipped)))
            FAILURES=$((FAILURES + $(n failures)))
            ERRORS=$((ERRORS + $(n errors)))
        done <<< "$FRESH_XMLS"
        log "本次运行：总计 ${TOTAL} | 通过 $((TOTAL - SKIPPED - FAILURES - ERRORS)) | 跳过 ${SKIPPED} | 失败 ${FAILURES} | 错误 ${ERRORS}"
    else
        warn "未找到本次运行的测试结果 XML（构建可能在测试前就失败了）"
    fi
fi

# 退出码是唯一判据；上面的计数只是摘要，不允许与之矛盾。
if [[ ${TEST_EXIT_CODE:-1} -eq 0 ]]; then
    log "全部 E2E 测试通过！"
else
    err "E2E 测试失败（退出码: ${TEST_EXIT_CODE:-1}）"
    err "请查看上方日志或测试报告了解详情"
fi

exit ${TEST_EXIT_CODE:-1}
