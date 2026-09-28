#!/usr/bin/env bash
# Your game only, straight into a world - no bots. Logic in bots.py.
exec python3 -u "$(dirname "$0")/bots.py" prism "$@"
