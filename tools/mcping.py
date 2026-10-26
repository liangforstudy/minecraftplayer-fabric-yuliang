"""Server-list ping (the multiplayer screen's status request), no login.

Asks a server for its status once per protocol number and prints what it reports: version name,
protocol, players and the MOTD. A server that always answers with the same protocol is that version;
one that echoes whatever we sent is a proxy (or ViaVersion) speaking for it.

    python tools/mcping.py 2b2t.com 774 776 777
    python tools/mcping.py 2b2t.com:25565 340

Protocols: 1.12.2 = 340, 1.21.11 = 774, 26.2 = 776, 26.3 = 777 (from the client jars' version.json).
"""
import json
import socket
import struct
import sys


def varint(n):
    out = b""
    n &= 0xFFFFFFFF
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def read_varint(sock):
    n = shift = 0
    while True:
        b = sock.recv(1)
        if not b:
            raise EOFError("connection closed")
        n |= (b[0] & 0x7F) << shift
        if not b[0] & 0x80:
            return n
        shift += 7


def packet(pid, body):
    data = varint(pid) + body
    return varint(len(data)) + data


def text(component):
    """A chat component (string or JSON) as plain text."""
    if isinstance(component, str):
        return component
    if isinstance(component, list):
        return "".join(text(c) for c in component)
    if isinstance(component, dict):
        return component.get("text", "") + "".join(text(c) for c in component.get("extra", []))
    return ""


def ping(host, port, protocol):
    with socket.create_connection((host, port), timeout=10) as s:
        h = host.encode()
        s.sendall(packet(0x00, varint(protocol) + varint(len(h)) + h + struct.pack(">H", port) + varint(1)))
        s.sendall(packet(0x00, b""))
        read_varint(s)                                  # packet length
        if read_varint(s) != 0x00:
            raise ValueError("not a status response")
        size = read_varint(s)
        raw = b""
        while len(raw) < size:
            chunk = s.recv(size - len(raw))
            if not chunk:
                raise EOFError("short response")
            raw += chunk
        return json.loads(raw.decode("utf-8"))


def srv(host):
    """The _minecraft._tcp SRV target, as the game looks it up first (2b2t.com's A record is a web IP)."""
    import re
    import subprocess
    try:
        out = subprocess.run(["nslookup", "-type=SRV", "_minecraft._tcp." + host],
                             capture_output=True, text=True, timeout=15).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    target = re.search(r"svr hostname\s*=\s*(\S+)", out)
    port = re.search(r"port\s*=\s*(\d+)", out)
    return (target.group(1).rstrip("."), int(port.group(1))) if target and port else None


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    host, _, port = sys.argv[1].partition(":")
    port = int(port or 25565)
    if not sys.argv[1].count(":"):
        found = srv(host)
        if found:
            print(f"SRV: {host} -> {found[0]}:{found[1]}")
            host, port = found
    for proto in (int(p) for p in sys.argv[2:]):
        try:
            st = ping(host, port, proto)
        except Exception as e:                         # one failed ping shouldn't hide the others
            print(f"sent {proto}: failed — {e}")
            continue
        v = st.get("version", {})
        p = st.get("players", {})
        motd = " ".join(text(st.get("description", "")).split())
        print(f"sent {proto}: version name {v.get('name')!r}, protocol {v.get('protocol')}, "
              f"players {p.get('online')}/{p.get('max')}")
        print(f"    motd: {motd[:160]}")
        extra = sorted(k for k in st if k not in ("version", "players", "description", "favicon"))
        if extra:
            print(f"    other fields: {', '.join(f'{k}={st[k]!r}' for k in extra)[:200]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
