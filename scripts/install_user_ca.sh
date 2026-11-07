#!/system/bin/sh

# install_user_ca.sh - also trust a CA as a user CA (Settings > Trusted credentials > User).
# Usage: install_user_ca.sh <cert_hash>   (expects /data/local/tmp/<cert_hash>.0; run as root)
#
# Android keeps user-installed CAs in /data/misc/user/<id>/cacerts-added/<hash>.0 and reads them
# from there, so as root installing one is just writing that file the way the system does. Unlike
# the system store change in inject_ca.sh, this lives in /data: every process sees it whatever its
# mount namespace, and it survives a reboot. Apps that opt into user CAs trust it.
# ZAPTUN_TMP_DIR and ZAPTUN_USER_CA_DIR only exist so the script can be tested off a device.
CERT_HASH="${1%.0}"
SOURCE="${ZAPTUN_TMP_DIR:-/data/local/tmp}/$CERT_HASH.0"
USER_DIR="${ZAPTUN_USER_CA_DIR:-/data/misc/user/0/cacerts-added}"

if [ "$(id -u)" != "0" ]; then
    echo "Error: must run as root (run 'adb root' first)."
    exit 1
fi

if [ -z "$CERT_HASH" ] || [ ! -f "$SOURCE" ]; then
    echo "Error: $SOURCE not found!"
    exit 1
fi

if cmp -s "$SOURCE" "$USER_DIR/$CERT_HASH.0" 2>/dev/null; then
    echo "Certificate $CERT_HASH.0 is already installed as a user CA, skipping."
    exit 0
fi

mkdir -p "$USER_DIR" || exit 1
cp "$SOURCE" "$USER_DIR/$CERT_HASH.0" || exit 1
chown system:system "$USER_DIR" "$USER_DIR/$CERT_HASH.0"
# World-readable, as Android's own installer leaves them: apps read this directory themselves.
chmod 755 "$USER_DIR"
chmod 644 "$USER_DIR/$CERT_HASH.0"
# Give the directory and file the SELinux labels Android's own installer would.
restorecon -R "$USER_DIR" 2>/dev/null

echo "Installed certificate $CERT_HASH.0 as a user CA."
