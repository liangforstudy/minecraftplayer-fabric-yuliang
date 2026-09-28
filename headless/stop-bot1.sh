#!/usr/bin/env bash
# Stop just Bot1 - other bots and your Prism game are left alone. Logic in bots.py.
exec python3 -u "$(dirname "$0")/bots.py" stop-bot bot1
