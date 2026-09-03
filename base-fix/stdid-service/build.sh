#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
sdk="$root/workspace/min-sdk"
out="$root/releases/StdID-Compat-v3.2.0.apk"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
"$sdk/build-tools/android-14/aapt2" link -o "$tmp/base.apk" -I "$sdk/platforms/android-35/android.jar" --manifest "$root/base-fix/stdid-service/AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 35 --version-code 302000 --version-name 3.2.0
javac --release 17 -classpath "$sdk/platforms/android-35/android.jar" -d "$tmp/classes" "$root/base-fix/stdid-service/src/com/oplus/stdid/IdentifyService.java"
mkdir -p "$tmp/dex"
java -cp "$root/../tools/dex/r8.jar" com.android.tools.r8.D8 --lib "$sdk/platforms/android-35/android.jar" --min-api 29 --output "$tmp/dex" "$tmp/classes/com/oplus/stdid/IdentifyService.class" "$tmp/classes/com/oplus/stdid/IdentifyService\$StdIdBinder.class"
jar uf "$tmp/base.apk" -C "$tmp/dex" classes.dex
"$sdk/build-tools/android-14/zipalign" -f -p 4 "$tmp/base.apk" "$tmp/aligned.apk"
"$sdk/build-tools/android-14/apksigner" sign --ks /run/media/ACLaniakea/IXUNICS/pad/keys/aclaniakea.jks --ks-key-alias aclaniakea --ks-pass pass:changeit --key-pass pass:changeit --out "$out" "$tmp/aligned.apk"
echo "$out"
