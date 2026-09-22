#!/usr/bin/env bash
# Thin wrapper - all logic is in bots.py (same on macOS, Linux and Windows).
exec python3 -u "$(dirname "$0")/bots.py" setup "$@"
