#!/usr/bin/env bash
# build.sh — one button: turn the TWO files the toolbox "Dump files" puts on the SD card into the
# TWO deployable artifacts. Everything runs in Docker — no local JDK 8 / clang / lld / jxe2jar /
# pyelftools needed, just Docker.
#
#   ./build.sh <MIBHMI.jxe> <stock libext.google.gal.receiver.so> [outdir=dist]
#
# Inputs (from the unit, dumped via the toolbox "Dump AAKombi files" menu item):
#   MIBHMI.jxe                          the HMI executable (dumped from /tsd/hmi/ifs/)
#   libext.google.gal.receiver.so       the STOCK GAL library (dumped from /tsd/lib/sal/gal/)
#
# Outputs (copy straight onto the SD):
#   <outdir>/AAtoKombi.jar                  -> custom/java/AAtoKombi.jar
#   <outdir>/libext.google.gal.receiver.so  -> custom/sal/libext.google.gal.receiver.so  (patched)
#
# Pipeline:
#   MIBHMI.jxe --(ghcr.io/adi961/jxe2jar)--> MIBHMI.jar --+
#                                                          +--> Docker toolchain --> 2 deployable files
#   stock libgal -----------------------------------------+    (javac+jar ; clang/lld/inject)
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="aatokombi-build"
JXE2JAR_IMAGE="${JXE2JAR_IMAGE:-ghcr.io/adi961/jxe2jar:latest}"

JXE="${1:-}"; GAL="${2:-}"; OUT="${3:-dist}"
usage(){ echo "usage: ./build.sh <MIBHMI.jxe> <stock libext.google.gal.receiver.so> [outdir]"; exit 1; }
[ -n "$JXE" ] && [ -f "$JXE" ] || usage
[ -n "$GAL" ] && [ -f "$GAL" ] || usage
command -v docker >/dev/null || { echo "ERROR: docker is required"; exit 1; }

abspath(){ ( cd "$(dirname "$1")" && printf '%s/%s\n' "$(pwd)" "$(basename "$1")"; ); }
JXE="$(abspath "$JXE")"; GAL="$(abspath "$GAL")"
mkdir -p "$OUT"; OUT="$(abspath "$OUT")"

# 1) MIBHMI.jxe -> MIBHMI.jar  (adi961's jxe2jar image; amd64 toolchain, emulated on arm64 — slow).
#    The conversion is deterministic per firmware, so we CACHE it keyed on the .jxe's hash and skip
#    it on rebuilds where only the mod sources changed. Delete jar/lib/MIBHMI.jar to force a redo.
mkdir -p jar/lib shim/lib
JXE_ID="$(shasum -a256 "$JXE" | cut -d' ' -f1)"
STAMP="jar/lib/.MIBHMI.jxe.sha256"
if [ -f jar/lib/MIBHMI.jar ] && [ "$(cat "$STAMP" 2>/dev/null)" = "$JXE_ID" ]; then
  echo "==> [1/4] jxe2jar: cached jar/lib/MIBHMI.jar matches this .jxe — skipping conversion"
else
  WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
  cp "$JXE" "$WORK/MIBHMI.jxe"
  echo "==> [1/4] jxe2jar: MIBHMI.jxe -> MIBHMI.jar  ($JXE2JAR_IMAGE)  [emulated, one-time per firmware]"
  docker run --rm --platform linux/amd64 -v "$WORK":/data "$JXE2JAR_IMAGE" \
    bash -c "python2 JXE2JAR.py /data/MIBHMI.jxe /data/MIBHMI.jar && chown $(id -u):$(id -g) /data/MIBHMI.jar"
  [ -f "$WORK/MIBHMI.jar" ] || { echo "ERROR: jxe2jar produced no MIBHMI.jar"; exit 1; }
  cp -f "$WORK/MIBHMI.jar" jar/lib/MIBHMI.jar
  echo "$JXE_ID" > "$STAMP"
  echo "    MIBHMI.jar: $(wc -c < jar/lib/MIBHMI.jar) bytes (cached for next time)"
fi

# 2) stage the stock libgal into the build's drop location (skip if arg 2 already IS that file)
stage(){ if [ "$1" -ef "$2" ]; then echo "    (already in place: $2)"; else cp -f "$1" "$2"; fi; }
stage "$GAL" shim/lib/libext.google.gal.receiver.so

# 3) build BOTH artifacts in the toolchain container (image cached after the first run)
echo "==> [2/4] building toolchain image '$IMAGE' (first run downloads the base image)"
docker build -t "$IMAGE" -f docker/Dockerfile .
echo "==> [3/4] running build in container"
docker run --rm -v "$PWD":/work -w /work --user "$(id -u):$(id -g)" -e HOME=/tmp \
  "$IMAGE" /work/docker/entrypoint.sh

# 4) collect the two deployables
echo "==> [4/4] collect -> $OUT"
cp -f jar/build/AAtoKombi.jar                   "$OUT/AAtoKombi.jar"
cp -f shim/build/libext.google.gal.receiver.so  "$OUT/libext.google.gal.receiver.so"

echo
echo "OK. Two files ready in $OUT :"
echo "  AAtoKombi.jar                  -> SD custom/java/AAtoKombi.jar"
echo "  libext.google.gal.receiver.so  -> SD custom/sal/libext.google.gal.receiver.so"
echo "Then on the unit: Toolbox -> Customisation -> Navi -> Enable AAtoKombi -> reboot."

# --- what this build actually IS -------------------------------------------------------------
echo
echo "Build configuration:"
echo "  AA protocol advertised .......... 1.7 (NavigationState: manoeuvres, lane guidance,"
echo "                                    destination distance / travel time, rerouting notice)"
# Every compile-time constant that went into the jar, read back out of the source that produced it.
# The values are baked in and unreadable afterwards, so printing them here is the only record of what
# a given pair of artifacts actually does — and the cheapest way to catch a flag left flipped from
# the previous build. Names are ALL_CAPS by convention; the value is taken verbatim, so a symbolic
# one (LOG_LEVEL = MIBLogger.INFO) prints as written rather than as a number nobody remembers.
CONSTANTS="$(sed -n 's/^[[:space:]]*public static final [A-Za-z][A-Za-z0-9_]*[[:space:]]\{1,\}\([A-Z][A-Z0-9_]*\)[[:space:]]*=[[:space:]]*\([^;]*\);.*/\1 \2/p' \
                 jar/src/de/aatokombi/Config.java 2>/dev/null)"
if [ -n "$CONSTANTS" ]; then
  echo "  Config.java:"
  printf '%s\n' "$CONSTANTS" \
    | while read -r name value; do printf "    %-32s %s\n" "$name" "$value"; done
else
  # Silence here would read as "no switches", which is never true — say it is the reader that broke.
  echo "  Config.java: could not read the constants (declaration style changed? see build.sh)"
fi
