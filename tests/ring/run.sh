#!/bin/sh
# Host test of the cluster video ring (shim/src/cluster_video_shm.h). Needs a host C compiler
# ($CC, else cc / clang / gcc) with pthreads; tries AddressSanitizer + UBSan first, plain if their runtime is
# missing. No compiler at all: SKIPPED (exit 0).
set -e
cd "$(dirname "$0")"
if [ -z "${CC:-}" ]; then
  for c in cc clang gcc; do if command -v $c >/dev/null 2>&1; then CC=$c; break; fi; done
fi
if [ -z "${CC:-}" ]; then echo "SKIPPED: no host C compiler"; exit 0; fi
OUT="${TMPDIR:-/tmp}/kb_test_ring"
if ! "$CC" -std=gnu99 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined -pthread -o "$OUT" test_ring.c 2>/dev/null; then
  echo "(sanitizer runtime not available: building without it)"
  "$CC" -std=gnu99 -O1 -g -Wall -Wextra -Werror -pthread -o "$OUT" test_ring.c
fi
exec "$OUT"
