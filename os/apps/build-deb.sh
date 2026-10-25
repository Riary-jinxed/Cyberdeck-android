#!/bin/sh
# Builds netrunner-apps_<version>_amd64.deb: Netrunner OS's own apps (os-apps/), one jar run
# by the system Java, with a launcher, a menu entry and an icon per app.
# Usage: build-deb.sh <netrunner-apps jar> <output dir>
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
JAR=${1:?jar}
OUT=${2:-.}
VERSION=$(grep -o 'val appVersion = "[^"]*"' "$HERE/../../os-apps/build.gradle.kts" | cut -d'"' -f2)
APPS="photos video"
ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
chmod 755 "$ROOT"

install -D -m 644 "$JAR" "$ROOT/usr/share/netrunner/netrunner-apps.jar"
for app in $APPS; do
    mkdir -p "$ROOT/usr/bin"
    cat > "$ROOT/usr/bin/netrunner-$app" <<LAUNCHER
#!/bin/sh
exec java -Xmx1g -jar /usr/share/netrunner/netrunner-apps.jar $app "\$@"
LAUNCHER
    chmod 755 "$ROOT/usr/bin/netrunner-$app"
    install -D -m 644 "$HERE/netrunner-$app.desktop" "$ROOT/usr/share/applications/netrunner-$app.desktop"
    install -D -m 644 "$HERE/netrunner-$app.svg" "$ROOT/usr/share/icons/hicolor/scalable/apps/netrunner-$app.svg"
done

mkdir -p "$ROOT/DEBIAN"
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: netrunner-apps
Version: $VERSION
Architecture: amd64
Maintainer: Netrunner <noreply@netrunner.invalid>
Depends: default-jre (>= 2:1.17), libglib2.0-bin, xdg-user-dirs, libvlc5, vlc-plugin-base
Section: graphics
Priority: optional
Description: Applis de Netrunner OS
 Les applis maison de Netrunner OS, reliees a As//ka par son pont :
 Photos (galerie, visionneuse, diaporama), Video (lecteur sur libVLC).
CONTROL
mkdir -p "$OUT"
dpkg-deb --root-owner-group --build "$ROOT" "$OUT/netrunner-apps_${VERSION}_amd64.deb"
