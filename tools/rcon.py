"""Send console commands to a local Paper server over RCON: python rcon.py <server dir> <command> [<command> ...]

Reads rcon.port and rcon.password from <server dir>/server.properties and never prints the password.
Each command's reply is printed on its own line, prefixed with "> command".
"""
import socket
import struct
import sys
from pathlib import Path


def props(server_dir):
    out = {}
    for line in (Path(server_dir) / "server.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def packet(req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def read(sock):
    size = struct.unpack("<i", recv_exact(sock, 4))[0]
    data = recv_exact(sock, size)
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, kind, data[8:-2].decode("utf-8", "replace")


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("RCON connection closed")
        buf += chunk
    return buf


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    p = props(sys.argv[1])
    with socket.create_connection(("127.0.0.1", int(p.get("rcon.port", "25575"))), timeout=10) as s:
        s.sendall(packet(1, 3, p["rcon.password"]))
        if read(s)[0] == -1:
            sys.exit("RCON login refused")
        for i, cmd in enumerate(sys.argv[2:], start=2):
            s.sendall(packet(i, 2, cmd))
            print("> " + cmd)
            print(read(s)[2])


if __name__ == "__main__":
    main()
