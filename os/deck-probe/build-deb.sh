#!/bin/sh
# Builds deck-probe_<version>_all.deb for Netrunner OS: the Linux agent Deck Link ships in its
# deployment pack (desktop/src/main/resources/agent), its systemd timer, and the
# "Inscrire ce PC à l'inventaire" tool. Usage: build-deb.sh <output dir>
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
AGENT="$HERE/../../desktop/src/main/resources/agent"
OUT=${1:-.}
VERSION=$(cat "$HERE/VERSION")
ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
chmod 755 "$ROOT"

install -D -m 755 "$AGENT/deck-probe.py" "$ROOT/usr/lib/deck-probe/deck-probe.py"
install -D -m 644 "$AGENT/deck-probe.service" "$ROOT/usr/lib/systemd/system/deck-probe.service"
install -D -m 644 "$AGENT/deck-probe.timer" "$ROOT/usr/lib/systemd/system/deck-probe.timer"
install -D -m 755 "$HERE/inscrire-root" "$ROOT/usr/lib/deck-probe/inscrire-root"
install -D -m 755 "$HERE/deck-probe-inscrire" "$ROOT/usr/bin/deck-probe-inscrire"
install -D -m 644 "$HERE/deck-probe-inscrire.desktop" "$ROOT/usr/share/applications/deck-probe-inscrire.desktop"
install -D -m 644 "$HERE/org.netrunner.deck-probe.policy" "$ROOT/usr/share/polkit-1/actions/org.netrunner.deck-probe.policy"

mkdir -p "$ROOT/DEBIAN"
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: deck-probe
Version: $VERSION
Architecture: all
Maintainer: Netrunner <noreply@netrunner.invalid>
Depends: python3, iproute2, systemd, zenity, pkexec | policykit-1
Section: admin
Priority: optional
Description: Deck Probe, inventaire du PC pour Deck Link
 Envoie le nom, le modele, le numero de serie, l'OS, le processeur, la memoire,
 les disques, les cartes reseau et les paquets installes a Deck Link, au
 demarrage et chaque jour, une fois le PC inscrit avec le deck-probe.json du
 pack de deploiement.
CONTROL
cat > "$ROOT/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -e
if [ "$1" = configure ] && command -v systemctl >/dev/null; then
    systemctl enable deck-probe.timer >/dev/null 2>&1 || true
    [ -d /run/systemd/system ] && systemctl daemon-reload && systemctl start deck-probe.timer || true
fi
POSTINST
cat > "$ROOT/DEBIAN/prerm" <<'PRERM'
#!/bin/sh
set -e
if [ "$1" = remove ] && command -v systemctl >/dev/null; then
    systemctl disable --now deck-probe.timer >/dev/null 2>&1 || true
fi
PRERM
chmod 755 "$ROOT/DEBIAN/postinst" "$ROOT/DEBIAN/prerm"
mkdir -p "$OUT"
dpkg-deb --root-owner-group --build "$ROOT" "$OUT/deck-probe_${VERSION}_all.deb"
