#!/bin/bash
# Builds the Linux packages (.deb + portable .tar.gz) into dist/linux. jpackage builds for the
# system it runs on, so this only works on Linux (dpkg-deb + fakeroot). Runs on hostinger, see
# tools/release-linux.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

[ "$(uname -s)" = Linux ] || { echo "Dieses Skript baut nur unter Linux." >&2; exit 1; }

VERSION="$(sed -n 's/^app = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
OUT_DIR="dist/linux"
APP_DIR="desktopApp/build/compose/binaries/main/app"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

echo "==> Tests, DEB und portables Paket ($VERSION)..."
./gradlew --console=plain :shared:desktopTest :desktopApp:packageDeb :desktopApp:createDistributable

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/*
cp desktopApp/build/compose/binaries/main/deb/*.deb "$OUT_DIR/"
tar -C "$APP_DIR" -czf "$OUT_DIR/Wassertiger-${VERSION}-linux-x86_64.tar.gz" Wassertiger
ls -la "$OUT_DIR"
