#!/usr/bin/env bash
# The everyday start, everything filled in: your game straight into "New World", no bots.
# Another world: ./start-singleplayer-server.sh "<world name>". Logic in bots.py.
exec python3 -u "$(dirname "$0")/bots.py" prism "New World"
