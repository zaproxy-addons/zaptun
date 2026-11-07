#!/system/bin/sh

# inject_ca.sh - System CA Injection for Android 14+ (API 34+)
# Overlays the system CA store, and the Conscrypt APEX's copy of it in every mount namespace,
# with one that includes ZAP's CA.
# Usage: inject_ca.sh <cert_hash>   (expects /data/local/tmp/<cert_hash>.0)
CERT_HASH="${1%.0}"
TMP_DIR="/data/local/tmp"
CERT_FILE="$CERT_HASH.0"
TARGET_CERT="$TMP_DIR/$CERT_FILE"

if [ "$(id -u)" != "0" ]; then
    echo "Error: must run as root (run 'adb root' first)."
    exit 1
fi

if [ -z "$CERT_HASH" ] || [ ! -f "$TARGET_CERT" ]; then
    echo "Error: $TARGET_CERT not found!"
    exit 1
fi

SYSTEM_CERTS="/system/etc/security/cacerts"
CONSCRYPT_CERTS="/apex/com.android.conscrypt/cacerts"
OVERRIDE_DIR="$TMP_DIR/cacerts_override"

# Skip if already injected (e.g. state restored from a snapshot)
if grep -q " $SYSTEM_CERTS tmpfs " /proc/mounts && [ -f "$SYSTEM_CERTS/$CERT_FILE" ]; then
    echo "Certificate $CERT_FILE already injected, skipping."
    exit 0
fi

# On Android 14+ the Conscrypt APEX holds the authoritative CA store
if [ -d "$CONSCRYPT_CERTS" ]; then
    SOURCE_CERTS="$CONSCRYPT_CERTS"
else
    SOURCE_CERTS="$SYSTEM_CERTS"
fi

# Prepare temporary copy of existing + ZAP certs
rm -rf "$OVERRIDE_DIR"
mkdir -p -m 700 "$OVERRIDE_DIR"
cp "$SOURCE_CERTS"/* "$OVERRIDE_DIR/" || exit 1
cp "$TARGET_CERT" "$OVERRIDE_DIR/" || exit 1

# Mount tmpfs over system certs folder and fill it
mount -t tmpfs tmpfs "$SYSTEM_CERTS" || exit 1
mv "$OVERRIDE_DIR"/* "$SYSTEM_CERTS/"
rm -rf "$OVERRIDE_DIR"
# Set permissions and SELinux context
chown root:root "$SYSTEM_CERTS"/*
chmod 644 "$SYSTEM_CERTS"/*
chcon u:object_r:system_file:s0 "$SYSTEM_CERTS"/*

if [ ! -d "$CONSCRYPT_CERTS" ]; then
    echo "Successfully injected certificate $CERT_FILE into $SYSTEM_CERTS (pre-APEX Android)."
    exit 0
fi

# Bind-mount into the Conscrypt APEX dir of every mount namespace (incl. this
# one and zygote's, so newly started apps inherit it). Many processes share a
# namespace, so mount once per namespace to avoid stacking bind mounts.
SEEN_NS=""
NS_COUNT=0
for NS in /proc/[0-9]*/ns/mnt; do
    NS_ID=$(readlink "$NS" 2>/dev/null) || continue
    case " $SEEN_NS " in
        *" $NS_ID "*) continue ;;
    esac
    SEEN_NS="$SEEN_NS $NS_ID"
    if nsenter --mount="$NS" -- mount --bind "$SYSTEM_CERTS" "$CONSCRYPT_CERTS" 2>/dev/null; then
        NS_COUNT=$((NS_COUNT + 1))
    fi
done

echo "Successfully injected certificate $CERT_FILE into $NS_COUNT Conscrypt APEX mount namespaces."
