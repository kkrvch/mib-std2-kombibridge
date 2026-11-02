#!/usr/bin/env bash
# build.sh — one button: turn the TWO files the toolbox "Dump files" puts on the SD card into the
# TWO deployable artifacts. Everything runs in Docker — no local JDK 8 / clang / lld / Python /
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
#   MIBHMI.jxe --(tools/jxe2jar, in Docker)--> MIBHMI.jar --+
#                                                          +--> Docker toolchain --> 2 deployable files
#   stock libgal -----------------------------------------+    (javac+jar ; clang/lld/inject)
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="aatokombi-build"

JXE="${1:-}"; GAL="${2:-}"; OUT="${3:-dist}"
usage(){ echo "usage: ./build.sh <MIBHMI.jxe> <stock libext.google.gal.receiver.so> [outdir]"; exit 1; }
[ -n "$JXE" ] && [ -f "$JXE" ] || usage
[ -n "$GAL" ] && [ -f "$GAL" ] || usage
command -v docker >/dev/null || { echo "ERROR: docker is required"; exit 1; }

abspath(){ ( cd "$(dirname "$1")" && printf '%s/%s\n' "$(pwd)" "$(basename "$1")"; ); }
JXE="$(abspath "$JXE")"; GAL="$(abspath "$GAL")"
mkdir -p "$OUT"; OUT="$(abspath "$OUT")"

# 0) the toolchain image (cached after the first run) — it also runs the jxe2jar converter
echo "==> [0/4] building toolchain image '$IMAGE' (first run downloads the base image)"
docker build -t "$IMAGE" -f docker/Dockerfile .

# 1) MIBHMI.jxe -> MIBHMI.jar with the vendored converter (tools/jxe2jar, Python 3).
#    It replaced ghcr.io/adi961/jxe2jar, whose conversion corrupted int constants (e.g. the
#    NavigationMapAdapter event ids) and dropped InnerClasses/throws information.
#    Cached, keyed on the .jxe AND the converter sources, so a converter fix re-converts once.
#    Delete jar/lib/MIBHMI.jar to force a redo.
mkdir -p jar/lib shim/lib
JXE_ID="$(shasum -a256 "$JXE" | cut -d' ' -f1)"
CONV_ID="$(cat tools/jxe2jar/*.py | shasum -a256 | cut -d' ' -f1)"
STAMP="jar/lib/.MIBHMI.jxe.sha256"
if [ -f jar/lib/MIBHMI.jar ] && [ "$(cat "$STAMP" 2>/dev/null)" = "$JXE_ID $CONV_ID" ]; then
  echo "==> [1/4] jxe2jar: cached jar/lib/MIBHMI.jar matches this .jxe and converter — skipping"
else
  WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
  cp "$JXE" "$WORK/MIBHMI.jxe"
  echo "==> [1/4] jxe2jar: MIBHMI.jxe -> MIBHMI.jar  (tools/jxe2jar, several minutes, once per firmware)"
  docker run --rm -v "$PWD":/work -v "$WORK":/data --user "$(id -u):$(id -g)" -e HOME=/tmp -w /data \
    "$IMAGE" python3 -B /work/tools/jxe2jar/jxe2jar.py /data/MIBHMI.jxe /data/MIBHMI.jar
  [ -f "$WORK/MIBHMI.jar" ] || { echo "ERROR: jxe2jar produced no MIBHMI.jar"; exit 1; }
  cp -f "$WORK/MIBHMI.jar" jar/lib/MIBHMI.jar
  echo "$JXE_ID $CONV_ID" > "$STAMP"
  echo "    MIBHMI.jar: $(wc -c < jar/lib/MIBHMI.jar) bytes (cached for next time)"
fi

# 2) stage the stock libgal into the build's drop location (skip if arg 2 already IS that file)
stage(){ if [ "$1" -ef "$2" ]; then echo "    (already in place: $2)"; else cp -f "$1" "$2"; fi; }
stage "$GAL" shim/lib/libext.google.gal.receiver.so

# 3) build BOTH artifacts in the toolchain container (image cached after the first run)
echo "==> [2/4] running build in container"
docker run --rm -v "$PWD":/work -w /work --user "$(id -u):$(id -g)" \
  -e HOME=/tmp -e VIDEO_PROBE="${VIDEO_PROBE:-0}" -e VIDEO_PROBE_DEEP="${VIDEO_PROBE_DEEP:-0}" \
  -e VIDEO_PIPELINE="${VIDEO_PIPELINE:-0}" -e CLUSTER_SINK="${CLUSTER_SINK:-0}" \
  -e CLUSTER_DEFINES="${CLUSTER_DEFINES:-}" \
  "$IMAGE" /work/docker/entrypoint.sh

# 4) collect the two deployables
echo "==> [3/4] collect -> $OUT"
cp -f jar/build/AAtoKombi.jar                   "$OUT/AAtoKombi.jar"
cp -f shim/build/libext.google.gal.receiver.so  "$OUT/libext.google.gal.receiver.so"
if [ "${CLUSTER_SINK:-0}" = "1" ] && [ -f native/build/libkb_dm.so ]; then
  cp -f native/build/libkb_dm.so                "$OUT/libkb_dm.so"
  cp -f native/install/install_dm_hook.sh native/install/uninstall_dm_hook.sh "$OUT/"
  echo "  + libkb_dm.so (DisplayManager hook for the AA cluster map) and its install scripts"
fi

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
