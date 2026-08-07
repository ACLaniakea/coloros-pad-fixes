#!/usr/bin/env sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: $0 UNSIGNED_APK SIGNED_APK" >&2
    exit 2
fi

UNSIGNED_APK=$1
SIGNED_APK=$2
KEYSTORE=${PEN_HOOK_KEYSTORE:-/tmp/lenovo-pen-bridge-hook.jks}
ALIAS=${PEN_HOOK_ALIAS:-lenovo-pen-bridge}
STOREPASS=${PEN_HOOK_STOREPASS:-changeit}
KEYPASS=${PEN_HOOK_KEYPASS:-$STOREPASS}

[ -r "$UNSIGNED_APK" ] || {
    echo "missing unsigned APK: $UNSIGNED_APK" >&2
    exit 1
}
[ -r "$KEYSTORE" ] || {
    echo "missing keystore: $KEYSTORE" >&2
    exit 1
}

mkdir -p "${SIGNED_APK%/*}"
jarsigner \
    -keystore "$KEYSTORE" \
    -storepass "$STOREPASS" \
    -keypass "$KEYPASS" \
    -sigalg SHA256withRSA \
    -digestalg SHA-256 \
    -signedjar "$SIGNED_APK" \
    "$UNSIGNED_APK" "$ALIAS" >/dev/null

jarsigner -verify -certs "$SIGNED_APK" >/dev/null
sha256sum "$SIGNED_APK"
