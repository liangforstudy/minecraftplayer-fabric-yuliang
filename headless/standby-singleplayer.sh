#!/usr/bin/env bash
# standby.sh with everything filled in: Bot1 boots to its title screen, then joins your singleplayer
# world (open to LAN: 127.0.0.1:25565), waiting for it to open. Logic in bots.py.
d="$(dirname "$0")"
python3 -u "$d/bots.py" standby bot1 && exec python3 -u "$d/bots.py" connect bot1 127.0.0.1:25565 --wait
