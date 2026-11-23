#!/bin/sh
# Build native/build/libkb_dm.so (QNX 6.5 armle-v7, soft-float) with clang + ld.lld; no QNX SDK:
# the unit's libc.so.3 is replaced at link time by a stub that only carries the symbol names.
# Entry point kb_dm_init via DT_INIT (the QNX 6.5 run-time linker predates .init_array).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
CLANG="${CLANG:-clang}"
LLD="${LLD:-ld.lld}"
OUT="$HERE/build"
CFLAGS="--target=armv7a-none-eabi -marm -mfloat-abi=soft -fPIC -Os -ffreestanding -nostdlib
 -fno-stack-protector -fno-builtin -fvisibility=hidden -std=c99 -Wall -Wextra -Werror
 -I$HERE/include -I$HERE/../shim/src"
mkdir -p "$OUT"
"$CLANG" $CFLAGS -fvisibility=default -c "$HERE/stubs/libc_stub.c" -o "$OUT/libc_stub.o"
"$LLD" -shared --hash-style=sysv -soname libc.so.3 -o "$OUT/libc.so.3" "$OUT/libc_stub.o"
"$CLANG" $CFLAGS -c "$HERE/src/dm_hook.c" -o "$OUT/dm_hook.o"
"$LLD" -shared -z defs -z max-page-size=0x1000 --hash-style=sysv --gc-sections \
  -soname libkb_dm.so -init kb_dm_init -o "$OUT/libkb_dm.so" "$OUT/dm_hook.o" "$OUT/libc.so.3"
echo "$OUT/libkb_dm.so"
