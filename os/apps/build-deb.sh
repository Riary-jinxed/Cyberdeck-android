#!/bin/sh
# Builds netrunner-apps_<version>_amd64.deb: Netrunner OS's own apps (os-apps/), one jar run
# by the system Java, with a launcher, a menu entry and an icon per app.
# Usage: [JCEF_DIR=<engine dir>] build-deb.sh <netrunner-apps jar> <output dir>
# JCEF_DIR is Web's Chromium engine, as `java -jar <jar> web --installer <dir>` fetches it.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
JAR=${1:?jar}
OUT=${2:-.}
VERSION=$(grep -o 'val appVersion = "[^"]*"' "$HERE/../../os-apps/build.gradle.kts" | cut -d'"' -f2)
APPS="photos video pdf texte tableur presentation web"
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

if [ -n "$JCEF_DIR" ]; then
    mkdir -p "$ROOT/usr/lib/netrunner"
    cp -a "$JCEF_DIR" "$ROOT/usr/lib/netrunner/jcef"
    chmod -R u+rwX,go+rX,go-w "$ROOT/usr/lib/netrunner/jcef"
    # Chromium's sandbox helper must run as root to sandbox the pages.
    chmod 4755 "$ROOT/usr/lib/netrunner/jcef/chrome-sandbox"
else
    echo "build-deb.sh : pas de JCEF_DIR, Web téléchargera son moteur au premier lancement" >&2
fi

mkdir -p "$ROOT/DEBIAN"
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: netrunner-apps
Version: $VERSION
Architecture: amd64
Maintainer: Netrunner <noreply@netrunner.invalid>
Depends: default-jre (>= 2:1.17), libglib2.0-bin, xdg-user-dirs, xdg-utils, libvlc5, vlc-plugin-base,
 libnss3, libgbm1, libxkbcommon0, libxcomposite1, libxdamage1, libxrandr2,
 libasound2t64 | libasound2, libatk-bridge2.0-0t64 | libatk-bridge2.0-0, libcups2t64 | libcups2
Section: graphics
Priority: optional
Description: Applis de Netrunner OS
 Les applis maison de Netrunner OS, reliees a As//ka par son pont :
 Photos (galerie, visionneuse, diaporama), Video (lecteur sur libVLC), PDF (lecteur),
 Texte (traitement de texte, Word et OpenDocument), Tableur (classeurs Excel,
 OpenDocument et CSV), Presentation (diaporamas PowerPoint et OpenDocument),
 Web (navigateur sur le moteur Chromium).
CONTROL
mkdir -p "$OUT"
dpkg-deb --root-owner-group --build "$ROOT" "$OUT/netrunner-apps_${VERSION}_amd64.deb"
