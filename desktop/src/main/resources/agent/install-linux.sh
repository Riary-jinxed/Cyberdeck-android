#!/bin/sh
# Deck Probe: installs the inventory agent on this Linux PC (systemd, Python 3), as root:
#   sudo sh install-linux.sh
# Safe to run again: it updates the files and the config. On Netrunner OS the agent is already
# there, only the config is copied.
set -e
SRC=$(cd "$(dirname "$0")" && pwd)
if [ "$(id -u)" -ne 0 ]; then
    echo "Deck Probe : lance ce script en root (sudo sh install-linux.sh)."
    exit 1
fi
command -v python3 >/dev/null || { echo "Deck Probe : python3 est nécessaire."; exit 1; }

# Left to the package when Netrunner OS's deck-probe is installed.
if ! dpkg -S /usr/lib/deck-probe/deck-probe.py >/dev/null 2>&1; then
    install -D -m 755 "$SRC/deck-probe.py" /usr/lib/deck-probe/deck-probe.py
    install -D -m 644 "$SRC/deck-probe.service" /etc/systemd/system/deck-probe.service
    install -D -m 644 "$SRC/deck-probe.timer" /etc/systemd/system/deck-probe.timer
fi
# The fleet key stays readable by root only.
install -d -m 700 /etc/deck-probe
install -m 600 "$SRC/deck-probe.json" /etc/deck-probe/deck-probe.json

systemctl daemon-reload
systemctl enable --now deck-probe.timer
echo "Deck Probe installé : inventaire au démarrage et chaque jour à 12:00."
python3 /usr/lib/deck-probe/deck-probe.py
