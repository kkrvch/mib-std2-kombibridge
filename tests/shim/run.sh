#!/bin/sh
# Host unit test for the AA 1.7 wire decoders (shim/src/navproto.h). Needs only a host clang++.
# Runs under ASan+UBSan so an over-read in the hand-rolled protobuf reader is a hard failure.
set -e
cd "$(dirname "$0")"
CXX="${CXX:-clang++}"

# On macOS the Command Line Tools clang++ does not always pick up libc++ on its own — point it at the
# SDK explicitly. On Linux (docker image) this block is simply skipped.
EXTRA=""
if SDK="$(xcrun --show-sdk-path 2>/dev/null)" && [ -n "$SDK" ]; then
  EXTRA="-isysroot $SDK"
  [ -d "$SDK/usr/include/c++/v1" ] && EXTRA="$EXTRA -cxx-isystem $SDK/usr/include/c++/v1"
fi

# shellcheck disable=SC2086
$CXX $EXTRA -std=c++11 -O1 -g -Wall -Wextra -Wno-unused-function \
     -fsanitize=address,undefined -fno-omit-frame-pointer \
     -o /tmp/test_navproto test_navproto.cpp
exec /tmp/test_navproto
