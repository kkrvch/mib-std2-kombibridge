#!/bin/sh
# Host unit test for the AA 1.7 wire decoders (shim/src/navproto.h). Needs a host C++ compiler
# ($CXX, else clang++ or g++). Runs under ASan+UBSan when their runtime is installed, so an
# over-read in the hand-rolled protobuf reader is a hard failure; plain otherwise. No compiler at
# all: SKIPPED (exit 0).
set -e
cd "$(dirname "$0")"
if [ -z "${CXX:-}" ]; then
  for c in clang++ g++ c++; do if command -v $c >/dev/null 2>&1; then CXX=$c; break; fi; done
fi
if [ -z "${CXX:-}" ]; then echo "SKIPPED: no host C++ compiler"; exit 0; fi
OUT="${TMPDIR:-/tmp}/kb_test_navproto"

# On macOS the Command Line Tools clang++ does not always pick up libc++ on its own — point it at the
# SDK explicitly. On Linux (docker image) this block is simply skipped.
EXTRA=""
if SDK="$(xcrun --show-sdk-path 2>/dev/null)" && [ -n "$SDK" ]; then
  EXTRA="-isysroot $SDK"
  [ -d "$SDK/usr/include/c++/v1" ] && EXTRA="$EXTRA -cxx-isystem $SDK/usr/include/c++/v1"
fi

# shellcheck disable=SC2086
if ! $CXX $EXTRA -std=c++11 -O1 -g -Wall -Wextra -Wno-unused-function \
     -fsanitize=address,undefined -fno-omit-frame-pointer \
     -o "$OUT" test_navproto.cpp 2>/dev/null; then
  echo "(sanitizer runtime not available: building without it)"
  $CXX $EXTRA -std=c++11 -O1 -g -Wall -Wextra -Wno-unused-function -o "$OUT" test_navproto.cpp
fi
exec "$OUT"
