#!/bin/sh
# Host test of tools/check_shadows.py (python3 + javac). See test_check_shadows.py.
set -e
cd "$(dirname "$0")"
exec python3 -B test_check_shadows.py "$@"
