#!/usr/bin/env python3
"""Deck Probe for Linux: sends this PC's inventory (name, model, serial number, OS, CPU, RAM,
disks, network cards, packages, updates) to Deck Link, in the same report as deck-probe.ps1.

Python 3.7+, standard library only. Config: /etc/deck-probe/deck-probe.json, the one Deck Link
writes in its deployment pack (servers, port, fleet key). Run as root: the serial number is only
readable by root. --print writes the report instead of sending it.
"""
import argparse
import hashlib
import hmac
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request

DEFAULT_CONFIG = "/etc/deck-probe/deck-probe.json"
DISK_TYPES = {"ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "zfs", "vfat", "exfat", "ntfs", "ntfs3", "fuseblk"}
PLACEHOLDERS = {"to be filled by o.e.m.", "default string", "system product name", "not specified", "none"}
GB = 1024 ** 3


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().strip()
    except OSError:
        return ""


def run(*command, timeout=180):
    """Standard output of a command, or None when it fails, is missing or takes too long."""
    try:
        p = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        return None
    return p.stdout.decode("utf-8", "replace") if p.returncode == 0 else None


def dmi(name):
    return read("/sys/class/dmi/id/" + name)


def os_release():
    values = {}
    for line in read("/etc/os-release").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            values[k] = v.strip().strip('"')
    return values


def logged_user():
    """The user of the open desktop session (the agent itself runs as root)."""
    for line in (run("who") or "").splitlines():
        name = line.split()[0] if line.split() else ""
        if name and name != "root":
            return name
    return os.environ.get("SUDO_USER") or ""


def disks():
    """Mounted file systems on real disks, by mount point, with their free space."""
    out, seen = [], set()
    for line in read("/proc/mounts").splitlines():
        f = line.split(" ")
        if len(f) < 3 or f[2] not in DISK_TYPES or not f[0].startswith("/dev/") or f[0] in seen:
            continue
        seen.add(f[0])
        mount = f[1].replace("\\040", " ")
        try:
            st = os.statvfs(mount)
        except OSError:
            continue
        size = st.f_blocks * st.f_frsize
        if size >= GB:
            out.append({"name": mount, "sizeGB": round(size / GB), "freeGB": st.f_bavail * st.f_frsize // GB})
    return sorted(out, key=lambda d: d["name"])


def network():
    """Cards that are up, with a MAC address and an IPv4 address."""
    try:
        links = json.loads(run("ip", "-j", "addr", "show") or "[]")
    except ValueError:
        links = []
    out = []
    for link in links:
        name = link.get("ifname", "")
        mac = link.get("address", "")
        if not name or link.get("link_type") != "ether" or len(mac) != 17 or "UP" not in link.get("flags", []):
            continue
        if os.path.exists("/sys/devices/virtual/net/" + name):
            continue
        ipv4 = ", ".join(a["local"] for a in link.get("addr_info", []) if a.get("family") == "inet" and a.get("local"))
        if not ipv4:
            continue
        kind = "Wi-Fi" if os.path.exists("/sys/class/net/%s/wireless" % name) else "Ethernet"
        out.append({"name": "%s (%s)" % (name, kind), "mac": mac.upper(), "ipv4": ipv4})
    return out


def software():
    """Installed packages: dpkg (Debian, Ubuntu…) or rpm (Fedora, openSUSE…)."""
    out = run("dpkg-query", "-W", "-f", "${Package}\t${Version}\t${Maintainer}\n")
    if out is None:
        out = run("rpm", "-qa", "--qf", "%{NAME}\t%{VERSION}-%{RELEASE}\t%{VENDOR}\n") or ""
    packages = []
    for line in out.splitlines():
        f = line.split("\t")
        if len(f) >= 2 and f[0].strip():
            packages.append({"name": f[0], "version": f[1], "publisher": (f[2] if len(f) > 2 else "").split(" <")[0]})
    return sorted(packages, key=lambda p: p["name"])


def updates():
    """Last package change, updates waiting (from apt's last refresh), pending restart."""
    last = 0
    for path in ("/var/log/apt/history.log", "/var/lib/dpkg/status"):
        try:
            last = int(os.path.getmtime(path) * 1000)
            break
        except OSError:
            pass
    pending = -1
    sim = run("apt-get", "-s", "-o", "Debug::NoLocking=1", "upgrade", timeout=120)
    if sim is not None:
        pending = sum(1 for line in sim.splitlines() if line.startswith("Inst "))
    return {"lastInstalled": last, "pending": pending, "rebootRequired": os.path.exists("/var/run/reboot-required")}


def report():
    rel = os_release()
    kb = next((int("".join(c for c in l if c.isdigit()) or 0) for l in read("/proc/meminfo").splitlines() if l.startswith("MemTotal:")), 0)
    cpu = next((l.split(":", 1)[1].strip() for l in read("/proc/cpuinfo").splitlines() if l.startswith("model name")), "")
    model = " ".join(v for v in (dmi("product_name"), dmi("product_version")) if v and v.lower() not in PLACEHOLDERS)
    return {
        "probe": 1,
        "hostname": socket.gethostname().split(".")[0] or read("/etc/hostname") or "PC",
        "domain": (run("hostname", "-d") or "").strip(),
        "user": logged_user(),
        "manufacturer": dmi("sys_vendor"),
        "model": model,
        "serial": dmi("product_serial"),
        "os": rel.get("PRETTY_NAME") or (os.uname().sysname + " " + os.uname().release),
        "osBuild": os.uname().release,
        "cpu": cpu,
        "ramGB": round(kb / 1024 / 1024),
        "disks": disks(),
        "network": network(),
        "software": software(),
        "updates": updates(),
    }


def sign(key_hex, stamp, body):
    """HMAC-SHA256 of "<time>\\n<body>" with the fleet key (dev.netrunner.sync.AgentAuth)."""
    return hmac.new(bytes.fromhex(key_hex), str(stamp).encode() + b"\n" + body, hashlib.sha256).hexdigest()


def send(config_path, body):
    with open(config_path, encoding="utf-8-sig") as f:
        cfg = json.load(f)
    stamp = int(time.time() * 1000)
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "X-DeckProbe-Time": str(stamp),
        "X-DeckProbe-Signature": sign(cfg["key"], stamp, body),
    }
    last = None
    for server in cfg.get("servers", []):
        url = "http://%s:%s/deck-probe/report" % (server, cfg.get("port", 47322))
        try:
            urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers, method="POST"), timeout=15).close()
            print("Deck Probe : inventaire envoyé à %s" % server)
            return 0
        except Exception as e:  # noqa: BLE001 (any failure: try the next address)
            last = e
    print("Deck Probe : Deck Link injoignable (%s)" % last)
    return 1


def main():
    ap = argparse.ArgumentParser(description="Deck Probe : inventaire de ce PC pour Deck Link")
    ap.add_argument("--config", default=DEFAULT_CONFIG)
    ap.add_argument("--print", action="store_true", help="affiche le rapport au lieu de l'envoyer")
    args = ap.parse_args()
    body = json.dumps(report(), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if args.print:
        sys.stdout.buffer.write(body + b"\n")
        return 0
    if not os.path.exists(args.config):
        print("Deck Probe : pas de config (%s), ce PC n'est pas encore inscrit à l'inventaire" % args.config)
        return 0
    return send(args.config, body)


if __name__ == "__main__":
    sys.exit(main())
