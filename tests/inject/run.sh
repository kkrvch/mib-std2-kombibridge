#!/bin/sh
# Host test of shim/layout.py + inject.py (python3 + pyelftools, as the build itself needs).
# Uses the stock libgal build.sh staged at shim/lib/ (or the path given); skipped without one.
set -e
cd "$(dirname "$0")"
exec python3 -B test_layout.py "$@"
