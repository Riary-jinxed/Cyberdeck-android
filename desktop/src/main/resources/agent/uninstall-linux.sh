#!/bin/sh
# Deck Probe: removes the agent and its config from this Linux PC, as root.
if [ "$(id -u)" -ne 0 ]; then
    echo "Deck Probe : lance ce script en root (sudo sh uninstall-linux.sh)."
    exit 1
fi
rm -rf /etc/deck-probe
if ! dpkg -S /usr/lib/deck-probe/deck-probe.py >/dev/null 2>&1; then
    systemctl disable --now deck-probe.timer 2>/dev/null
    rm -f /etc/systemd/system/deck-probe.service /etc/systemd/system/deck-probe.timer
    rm -rf /usr/lib/deck-probe
    systemctl daemon-reload
fi
echo "Deck Probe désinstallé."
