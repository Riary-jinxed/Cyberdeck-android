#!/bin/sh
# Builds the Netrunner OS User edition ISO with Debian live-build. Runs as root on Debian 13, or
# in a privileged debian:trixie container as the CI does:
#   ASKA_DEB=aska.deb os/user/build.sh <out dir> <package.deb>…
# The packages are installed in the image: deck-probe (os/deck-probe/build-deb.sh) and
# netrunner-apps (os/apps/build-deb.sh). ASKA_DEB (optional, from the askapc-v… release) is put
# on the image for « Installer As//ka ». WORK (default /var/tmp/netrunner-os-user) holds the build tree.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
VERSION=$(cat "$HERE/VERSION")
OUT=$(realpath -m "${1:?dossier de sortie}")
shift
ASKA=${ASKA_DEB:+$(realpath "$ASKA_DEB")}
WORK=${WORK:-/var/tmp/netrunner-os-user}

command -v lb >/dev/null || { apt-get update; apt-get install -y live-build; }

rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"
cp -r "$HERE/config" "$WORK/"
mkdir -p "$WORK/config/packages.chroot"
for deb in "$@"; do cp "$deb" "$WORK/config/packages.chroot/"; done
cd "$WORK"
if [ -n "$ASKA" ]; then
    mkdir -p config/includes.chroot/opt/netrunner/extras
    cp "$ASKA" config/includes.chroot/opt/netrunner/extras/aska.deb
fi

lb config \
    --mode debian \
    --distribution trixie \
    --architectures amd64 \
    --archive-areas "main contrib non-free non-free-firmware" \
    --mirror-bootstrap http://deb.debian.org/debian/ \
    --mirror-binary http://deb.debian.org/debian/ \
    --mirror-binary-security http://security.debian.org/debian-security/ \
    --binary-images iso-hybrid \
    --debian-installer none \
    --memtest none \
    --firmware-chroot true \
    --iso-application "Netrunner OS User" \
    --iso-publisher "Netrunner" \
    --iso-volume "NETRUNNER_USER" \
    --image-name "netrunner-os-user" \
    --bootappend-live "boot=live components quiet splash locales=fr_FR.UTF-8 keyboard-layouts=fr timezone=Europe/Paris username=netrunner user-fullname=Netrunner hostname=netrunner"

{ lb build 2>&1; echo $? > "$WORK/lb.status"; } | tee "$OUT/build.log"
[ "$(cat "$WORK/lb.status")" = 0 ] || { echo "lb build a échoué"; exit 1; }
# Everything the User edition promises must be in the image.
for f in usr/bin/libreoffice usr/bin/firefox-esr usr/bin/netrunner-video usr/bin/netrunner-photos usr/bin/kdenlive usr/bin/netrunner-pdf usr/bin/netrunner-texte \
    usr/bin/calamares usr/bin/startxfce4 usr/lib/deck-probe/deck-probe.py usr/bin/deck-probe-inscrire; do
    [ -e "chroot/$f" ] || { echo "manque dans l'image : /$f"; exit 1; }
done
if [ -n "$ASKA" ]; then [ -f chroot/opt/netrunner/extras/aska.deb ] || { echo "manque As//ka"; exit 1; }; fi
ISO=
for f in netrunner-os-user*.iso; do ISO=$f; done
[ -f "$ISO" ] || { echo "pas d'ISO"; exit 1; }
mv "$ISO" "$OUT/netrunner-os-user-$VERSION-amd64.iso"
cd "$OUT"
sha256sum "netrunner-os-user-$VERSION-amd64.iso" > "netrunner-os-user-$VERSION-amd64.iso.sha256"
ls -lh "$OUT"
