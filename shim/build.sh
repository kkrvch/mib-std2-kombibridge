#!/bin/sh
# Builds the patched libext.google.gal.receiver.so (the "shim").
#
# What it does: compiles a tiny freestanding ARM listener (navshim_fs.cpp), links it into a
# self-contained ELF blob, then injects that blob into the firmware's libgal and patches the
# tail of GalReceiver::init so the GAL receiver registers Google's NavigationStatusEndpoint and
# MediaPlaybackStatusEndpoint (which the stock SAL never registers). Captured turn-by-turn is
# written to /dev/shmem/aa_nav and the now-playing track to /dev/shmem/aa_media.
#
# Requirements (no QNX SDK needed):
#   * clang            (system clang on macOS is fine)
#   * ld.lld           (brew install lld) — set LLD=/path/to/ld.lld if not on PATH
#   * python3 + pyelftools   (pip3 install pyelftools)
#   * your firmware's libext.google.gal.receiver.so  (NOT shipped; extract from your unit via toolbox)
#
# Per-firmware addresses are AUTO-RESOLVED:
#   inject.py reads the target libgal's own .dynsym / relocations / .plt and the BL site in
#   GalReceiver::init, and bakes the right absolute addresses into the blob's .galrefs slots.
#   So the SAME shim sources work against any STD2 libgal build — no manual address editing.
#   (If the BL auto-locator ever fails on an exotic build, pass --bl-offset 0xNNNN to inject.py.)
#
# Usage:   ./build.sh /path/to/libext.google.gal.receiver.so
#          LIBGAL=/path/to/libgal.so ./build.sh
#
set -e
cd "$(dirname "$0")"

LIBGAL="${1:-${LIBGAL:-lib/libext.google.gal.receiver.so}}"
CLANG="${CLANG:-clang}"
LLD="${LLD:-ld.lld}"

FLAGS="-target armv7-none-eabi -marm -mfloat-abi=soft -ffreestanding -nostdlib \
 -fno-exceptions -fno-rtti -fno-builtin -fno-stack-protector -Os -fPIC \
 -fno-jump-tables -ffunction-sections -fdata-sections"

if [ ! -f "$LIBGAL" ]; then
  echo "ERROR: target libgal not found: $LIBGAL"
  echo "       Extract libext.google.gal.receiver.so from your firmware (/tsd/lib/sal/gal/) and pass its path."
  exit 1
fi

mkdir -p build
echo "[1/3] compile  src/navshim_fs.cpp -> build/navshim.o"
$CLANG $FLAGS -c src/navshim_fs.cpp -o build/navshim.o

echo "[2/3] link     -> build/navshim.so (self-contained ET_DYN, R_ARM_RELATIVE only)"
# max-page-size=0x1000 keeps the blob compact (without it lld puts each segment on a 64 KB page).
$LLD -shared -Bsymbolic -z max-page-size=0x1000 --gc-sections -o build/navshim.so build/navshim.o

echo "[3/3] inject   into a copy of $(basename "$LIBGAL")"
python3 inject.py "$LIBGAL" build/navshim.so -o build/libext.google.gal.receiver.so --write

echo "OK -> $(cd build && pwd)/libext.google.gal.receiver.so"
