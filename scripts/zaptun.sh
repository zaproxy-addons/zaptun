#!/usr/bin/env bash
# zaptun.sh - set up an emulator/rooted device for ZAP and drive the zaptun VPN over adb.
#
#   zaptun.sh install [app-debug.apk]     install zaptun and pre-approve the VPN (no consent dialog)
#   zaptun.sh cert [port]                 fetch ZAP's root CA and inject it into the system store
#   zaptun.sh start [port] [apps]         cert + start forwarding all apps (or only the listed packages)
#   zaptun.sh stop [port]                 stop the VPN and remove the adb reverse rule
#
# port is ZAP's listening port on this machine (default 9090; a fresh ZAP install listens on
# 8080, so pass 8080 then, or whatever Tools > Options > Network > Local Servers/Proxies says).
# The device reaches it at
# 127.0.0.1:<port>, tunnelled to this machine with adb reverse. That's far faster than the
# emulator's 10.0.2.2 alias, which adds about a second to every new connection. Set
# ZAP_DEVICE_HOST (e.g. 10.0.2.2, or this machine's LAN address) to use that address instead,
# without adb reverse.
# Set ZAP_CERT to a PEM file to use it instead of downloading the CA from ZAP.
# Set ANDROID_SERIAL (e.g. emulator-5554) to pick the device, like any adb call.
# start makes zaptun Android's always-on VPN with lockdown ("Block connections without VPN"), so
# nothing leaves the device around ZAP while zaptun reconnects or restarts; stop undoes it. Set
# ZAPTUN_ALWAYS_ON=0 to leave Android's always-on VPN setting alone.
# adb is looked for like the ZAP add-on does: in $ANDROID_HOME, $ANDROID_SDK_ROOT, Android
# Studio's default SDK folders, then PATH.
set -euo pipefail

PKG="dev.davidjosearaujo.zaptun"
ACTIVITY="$PKG/.MainActivity"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_PORT=9090

die() { echo "zaptun: $*" >&2; exit 1; }

# Android Studio doesn't put platform-tools on PATH (on macOS the SDK is in ~/Library/Android/sdk),
# so look in the SDK first. That's also the adb the emulator's tools use: two different adb
# versions keep restarting each other's server.
find_adb() {
    local sdk
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then
            echo "$sdk/platform-tools/adb"
            return
        fi
    done
    command -v adb
}
ADB=$(find_adb) || ADB=""
adb() {
    [ -n "$ADB" ] || die "adb not found. Install the Android SDK platform-tools, or set ANDROID_HOME to the SDK folder."
    "$ADB" "$@"
}

# Downloads the root CA the way a browser would: the welcome page at http://zap/ (served through
# the proxy) links to it with a one-time nonce, so no API key is needed.
fetch_ca() {
    local port="$1" out="$2" link
    link=$(curl -sf -x "http://127.0.0.1:$port" http://zap/ | grep -o 'rootCaCert/?apinonce=[0-9a-f]*' | head -1) ||
        die "ZAP isn't answering on 127.0.0.1:$port. Is it running, on that port? Pass ZAP's port, e.g. '$(basename "$0") start 8080' (ZAP's default)."
    [ -n "$link" ] || die "couldn't find the CA download link on ZAP's welcome page"
    curl -sf -x "http://127.0.0.1:$port" "http://zap/OTHER/network/other/$link" -o "$out" ||
        die "downloading ZAP's root CA failed"
    grep -q 'BEGIN CERTIFICATE' "$out" || die "ZAP returned something that isn't a PEM certificate"
}

is_root() { [ "$(adb shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; }

# Gets a root adb shell if the device allows one (adb root). Returns false if it doesn't.
try_root() {
    adb wait-for-device
    is_root && return
    adb root >/dev/null 2>&1 || true
    local i
    for i in $(seq 1 15); do
        sleep 1
        is_root && return
    done
    return 1
}

adb_root() {
    try_root || die "no root adb shell (Google Play images don't allow it, use a Google APIs image)"
}

vpn_running() { adb shell dumpsys activity services "$PKG" | grep ZapVpnService >/dev/null; }

# Turns Android's always-on VPN, with lockdown, on or off for zaptun ($1: on or off). Only system
# code may change it, so the app's AlwaysOnVpn tool runs as root from zaptun's own APK. Not fatal.
always_on_enabled() { [ "${ZAPTUN_ALWAYS_ON:-1}" != 0 ]; }

always_on() {
    always_on_enabled || return 0
    adb shell "CLASSPATH=\$(pm path $PKG | head -n 1 | cut -d: -f2) app_process /system/bin $PKG.AlwaysOnVpn $1" ||
        echo "zaptun: couldn't turn Android's always-on VPN $1" >&2
}

# Fetches ZAP's CA, injects it into the device (system store, and user store too) and sets
# CA_SHA256 to its fingerprint.
inject_cert() {
    local port="$1" tmp hash
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' RETURN
    if [ -n "${ZAP_CERT:-}" ]; then
        cp "$ZAP_CERT" "$tmp/ca.pem"
    else
        fetch_ca "$port" "$tmp/ca.pem"
    fi
    hash=$(openssl x509 -inform PEM -subject_hash_old -in "$tmp/ca.pem" -noout)
    cp "$tmp/ca.pem" "$tmp/$hash.0"
    # The app uses the fingerprint to show whether exactly this CA is trusted.
    CA_SHA256=$(openssl x509 -inform PEM -in "$tmp/ca.pem" -noout -fingerprint -sha256 |
        cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f')

    adb_root
    adb push "$tmp/$hash.0" "$SCRIPT_DIR/inject_ca.sh" "$SCRIPT_DIR/install_user_ca.sh" /data/local/tmp/ >/dev/null
    adb shell chmod 755 /data/local/tmp/inject_ca.sh /data/local/tmp/install_user_ca.sh
    adb shell /data/local/tmp/inject_ca.sh "$hash"
    # Also a user CA: it lives in /data, so processes whose mount namespace
    # missed the system store change still trust it. A bonus, so not fatal.
    adb shell /data/local/tmp/install_user_ca.sh "$hash" ||
        echo "zaptun: couldn't also install the CA as a user CA" >&2
}

# Lets the app switch off Android's global proxy while it is connected (and put it back), which
# apps that honour it would otherwise use instead of the VPN, and show its notification. Not fatal.
grant_permissions() {
    adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS ||
        echo "zaptun: couldn't let the app manage Android's global proxy" >&2
    # Android before 13 has no such permission to grant, and the app works without it.
    adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
}

case "${1:-}" in
    install)
        APK="${2:-$SCRIPT_DIR/../android-app/app/build/outputs/apk/debug/app-debug.apk}"
        adb install -r "$APK"
        # Pre-approve the VPN so starting it from adb never stops at the consent dialog.
        adb shell appops set "$PKG" ACTIVATE_VPN allow
        grant_permissions
        ;;
    cert)
        inject_cert "${2:-$DEFAULT_PORT}"
        ;;
    start)
        PORT="${2:-$DEFAULT_PORT}"
        inject_cert "$PORT"
        grant_permissions
        if [ -n "${ZAP_DEVICE_HOST:-}" ]; then
            HOST="$ZAP_DEVICE_HOST"
        else
            # The device's 127.0.0.1:<port> becomes this machine's, where ZAP listens.
            adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null || die "adb reverse tcp:$PORT failed"
            HOST=127.0.0.1
        fi
        adb shell am start -n "$ACTIVITY" \
            --es host "$HOST" --ei port "$PORT" \
            --es apps "'${3:-}'" --es ca_sha256 "$CA_SHA256" --ez start true >/dev/null
        # Lockdown only once the VPN is up, or the device would have no network meanwhile.
        for i in $(seq 1 20); do
            vpn_running && break
            sleep 0.5
        done
        vpn_running || die "the VPN didn't come up. Open the zaptun app on the device to see why."
        always_on on
        echo "zaptun: forwarding ${3:-all apps} to $HOST:$PORT"
        ;;
    stop)
        # First: with lockdown still on, the device would have no network once the VPN is off.
        if always_on_enabled; then
            if try_root; then always_on off; else echo "zaptun: no root adb shell, so always-on VPN is left as it is" >&2; fi
        fi
        adb shell am start -n "$ACTIVITY" --ez stop true >/dev/null
        # The rule start set up; with ZAP_DEVICE_HOST, start set none.
        if [ -z "${ZAP_DEVICE_HOST:-}" ]; then
            adb reverse --remove "tcp:${2:-$DEFAULT_PORT}" >/dev/null 2>&1 || true
        fi
        echo "zaptun: stopped"
        ;;
    *)
        # The usage text is the comment block at the top of this file.
        sed -n '2,/^set /{/^#/s/^# \{0,1\}//p;}' "$0"
        exit 1
        ;;
esac
