#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- Environment setup (macOS) ---
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "${JAVA_HOME}/bin/java" ]]; then
    for candidate in \
        "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home" \
        "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home" \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi

AVD_NAME="${AVD_NAME:-corsa_aa_avd}"
SIM_MODE="${SIM_MODE:-drive}"
SIM_PORT="${SIM_PORT:-35000}"
SKIP_BUILD=0
NO_DHU=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --avd)        AVD_NAME="$2"; shift 2 ;;
        --mode)       SIM_MODE="$2"; shift 2 ;;
        --port)       SIM_PORT="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        --no-dhu)     NO_DHU=1; shift ;;
        -h|--help)
            echo "Usage: $0 [--avd NAME] [--mode drive|dc-charge|isotp-multiframe|chaos] [--port 35000] [--skip-build] [--no-dhu]"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

echo "==> Corsa-e Android Auto Dev Environment"
echo "    REPO_ROOT:    $REPO_ROOT"
echo "    ANDROID_HOME: $ANDROID_HOME"
echo "    JAVA_HOME:    ${JAVA_HOME:-<unset>}"
echo "    AVD_NAME:     $AVD_NAME"
echo "    SIM_MODE:     $SIM_MODE (port $SIM_PORT)"

# --- 1. Start AVD if no device is connected ---
CONNECTED_DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [[ -z "$CONNECTED_DEVICES" ]]; then
    if ! emulator -list-avds | grep -qx "$AVD_NAME"; then
        FIRST_AVD="$(emulator -list-avds | head -n 1 || true)"
        if [[ -n "$FIRST_AVD" ]]; then
            echo "==> AVD '$AVD_NAME' not found, using '$FIRST_AVD'"
            AVD_NAME="$FIRST_AVD"
        else
            echo "ERROR: No Android AVD found. Create one first (e.g. 'corsa_aa_avd')." >&2
            exit 1
        fi
    fi

    echo "==> Starting AVD '$AVD_NAME'..."
    nohup emulator -avd "$AVD_NAME" -gpu host -no-audio >/tmp/corsa_emulator.log 2>&1 &
    EMU_PID=$!
    echo "    Emulator PID: $EMU_PID (log: /tmp/corsa_emulator.log)"

    echo "==> Waiting for adb device..."
    adb wait-for-device

    echo "==> Waiting for Android boot completion..."
    until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
        sleep 2
    done
    # Give PackageManager a brief moment to settle after first boot
    sleep 3
    echo "    Device booted."
else
    echo "==> Using already connected device: $CONNECTED_DEVICES"
fi

# --- 2. Build & sideload APK with Play Store installer spoofing ---
cd "$REPO_ROOT"
if [[ "$SKIP_BUILD" -eq 0 ]]; then
    echo "==> Building debug APK..."
    ./gradlew assembleDebug
fi

APK_PATH="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
echo "==> Installing APK with Play Store installer spoofing..."
adb install -r -i "com.android.vending" "$APK_PATH"

# --- 3. Forward DHU port ---
echo "==> Forwarding TCP port 5277 for Android Auto DHU..."
adb forward tcp:5277 tcp:5277

# --- 4. Start Python ELM327 TCP Simulator ---
if lsof -iTCP:"$SIM_PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "==> ELM327 simulator already listening on port $SIM_PORT."
else
    echo "==> Starting ELM327 simulator (mode=$SIM_MODE, port=$SIM_PORT)..."
    nohup python3 "$REPO_ROOT/tools/corsa_elm_sim.py" \
        --mode "$SIM_MODE" \
        --port "$SIM_PORT" \
        >/tmp/corsa_elm_sim.log 2>&1 &
    SIM_PID=$!
    echo "$SIM_PID" >/tmp/corsa_elm_sim.pid
    echo "    Simulator PID: $SIM_PID (log: /tmp/corsa_elm_sim.log)"
fi

# --- 5. Launch Desktop Head Unit (DHU) ---
if [[ "$NO_DHU" -eq 1 ]]; then
    echo "==> Skipping DHU launch (--no-dhu)."
    exit 0
fi

DHU_BIN="$ANDROID_HOME/extras/google/auto/desktop-head-unit"
if [[ ! -x "$DHU_BIN" ]]; then
    echo "ERROR: DHU binary not found at $DHU_BIN" >&2
    exit 1
fi

echo "==> Launching Android Auto DHU (-c tools/headunit_corsa.ini)..."
echo "    Tip: Make sure 'Head Unit Server' is started in Android Auto Developer Settings on the AVD."
exec "$DHU_BIN" -c "$REPO_ROOT/tools/headunit_corsa.ini"
