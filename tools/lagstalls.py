"""Freeze a local Paper server now and then, like 9b9t's stalls: python lagstalls.py <server dir> [options]

--every 20-40   seconds between stalls (random in the range)
--stall 2-6     seconds each stall lasts
--catchup 0     ticks run flat out after a stall (/tick sprint), like 9b9t's jump after one; 0 = none

Ctrl+C (or killing it) unfreezes the server on the way out. Uses rcon.py next to it.
"""
import argparse
import random
import socket
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import rcon  # noqa: E402


def span(s):
    lo, _, hi = s.partition("-")
    return float(lo), float(hi or lo)


class Console:
    def __init__(self, server_dir):
        p = rcon.props(server_dir)
        self.s = socket.create_connection(("127.0.0.1", int(p.get("rcon.port", "25575"))), timeout=10)
        self.s.sendall(rcon.packet(1, 3, p["rcon.password"]))
        if rcon.read(self.s)[0] == -1:
            sys.exit("RCON login refused")
        self.n = 1

    def run(self, cmd):
        self.n += 1
        self.s.sendall(rcon.packet(self.n, 2, cmd))
        return rcon.read(self.s)[2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("server_dir")
    ap.add_argument("--every", default="20-40")
    ap.add_argument("--stall", default="2-6")
    ap.add_argument("--catchup", type=int, default=0)
    a = ap.parse_args()
    every, stall = span(a.every), span(a.stall)
    c = Console(a.server_dir)
    print(f"stalls every {a.every} s, {a.stall} s each, catch-up {a.catchup} ticks", flush=True)
    try:
        while True:
            time.sleep(random.uniform(*every))
            length = random.uniform(*stall)
            c.run("tick freeze")
            print(f"{time.strftime('%H:%M:%S')} stall {length:.1f} s", flush=True)
            time.sleep(length)
            c.run("tick unfreeze")
            if a.catchup > 0:
                c.run(f"tick sprint {a.catchup}")
    except KeyboardInterrupt:
        pass
    finally:
        c.run("tick unfreeze")
        print("unfrozen", flush=True)


if __name__ == "__main__":
    main()
