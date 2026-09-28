#!/usr/bin/env bash
# stop-bots.sh with --check filled in: lists the headless bot processes it would stop, stops nothing.
exec python3 -u "$(dirname "$0")/bots.py" stop --check
