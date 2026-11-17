#!/usr/bin/env bash
# build.sh — one button: turn the TWO files the toolbox "Dump files" puts on the SD card into the
# TWO deployable artifacts. Everything runs in Docker — no local JDK 8 / clang / lld / Python /
# pyelftools needed, just Docker.
#
#   ./build.sh <MIBHMI.jxe> <stock libext.google.gal.receiver.so> [outdir=dist]
#   ./build.sh <MIBHMI.jxe> - [outdir]      jar only (keep the libgal already on the unit)
#
# The host tests run after the build (docker/entrypoint.sh); a failed test stops it before anything
# is collected. SKIP_TESTS=1 skips them.
#
# Whether the libgal gets Android Auto's map on the AID (and libkb_dm.so is built) is a switch in
# jar/src/de/aatokombi/Config.java: ClusterMap.ENABLED. CLUSTER_SINK=0/1 overrides it.
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
usage(){ echo "usage: ./build.sh <MIBHMI.jxe> <stock libext.google.gal.receiver.so | -> [outdir]"; exit 1; }
[ -n "$JXE" ] && [ -f "$JXE" ] || usage
JAR_ONLY=0
if [ -z "$GAL" ] || [ "$GAL" = "-" ]; then JAR_ONLY=1; else [ -f "$GAL" ] || usage; fi
command -v docker >/dev/null || { echo "ERROR: docker is required"; exit 1; }

abspath(){ ( cd "$(dirname "$1")" && printf '%s/%s\n' "$(pwd)" "$(basename "$1")"; ); }
JXE="$(abspath "$JXE")"; [ "$JAR_ONLY" = "1" ] || GAL="$(abspath "$GAL")"
# sha256sum on Linux, shasum on macOS
sha256(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
mkdir -p "$OUT"; OUT="$(abspath "$OUT")"

# 0) the toolchain image (cached after the first run) — it also runs the jxe2jar converter
echo "==> [1/4] building toolchain image '$IMAGE' (first run downloads the base image)"
docker build -t "$IMAGE" -f docker/Dockerfile .

# 1) MIBHMI.jxe -> MIBHMI.jar with the vendored converter (tools/jxe2jar, Python 3).
#    It replaced ghcr.io/adi961/jxe2jar, whose conversion corrupted int constants (e.g. the
#    NavigationMapAdapter event ids) and dropped InnerClasses/throws information.
#    Cached, keyed on the .jxe AND the converter sources, so a converter fix re-converts once.
#    Delete jar/lib/MIBHMI.jar to force a redo.
mkdir -p jar/lib shim/lib
JXE_ID="$(sha256 "$JXE" | cut -d' ' -f1)"
CONV_ID="$(cat tools/jxe2jar/*.py | sha256 | cut -d' ' -f1)"
STAMP="jar/lib/.MIBHMI.jxe.sha256"
if [ -f jar/lib/MIBHMI.jar ] && [ "$(cat "$STAMP" 2>/dev/null)" = "$JXE_ID $CONV_ID" ]; then
  echo "==> [2/4] jxe2jar: cached jar/lib/MIBHMI.jar matches this .jxe and converter — skipping"
else
  WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
  cp "$JXE" "$WORK/MIBHMI.jxe"
  echo "==> [2/4] jxe2jar: MIBHMI.jxe -> MIBHMI.jar  (tools/jxe2jar, several minutes, once per firmware)"
  docker run --rm -v "$PWD":/work -v "$WORK":/data --user "$(id -u):$(id -g)" -e HOME=/tmp -w /data \
    "$IMAGE" python3 -B /work/tools/jxe2jar/jxe2jar.py /data/MIBHMI.jxe /data/MIBHMI.jar
  [ -f "$WORK/MIBHMI.jar" ] || { echo "ERROR: jxe2jar produced no MIBHMI.jar"; exit 1; }
  cp -f "$WORK/MIBHMI.jar" jar/lib/MIBHMI.jar
  echo "$JXE_ID $CONV_ID" > "$STAMP"
  echo "    MIBHMI.jar: $(wc -c < jar/lib/MIBHMI.jar) bytes (cached for next time)"
fi

# 2) stage the stock libgal into the build's drop location (skip if arg 2 already IS that file)
stage(){ if [ "$1" -ef "$2" ]; then echo "    (already in place: $2)"; else cp -f "$1" "$2"; fi; }
[ "$JAR_ONLY" = "1" ] || stage "$GAL" shim/lib/libext.google.gal.receiver.so

# The AA map on the AID (CLUSTER_SINK libgal + libkb_dm.so): Config.ClusterMap.ENABLED, unless
# CLUSTER_SINK=0/1 is given explicitly.
. tools/config-get.sh
cluster_sink_choice
echo "  AA map on the AID ............... CLUSTER_SINK=$CLUSTER_SINK (from $CLUSTER_SINK_FROM)"

# 3) build BOTH artifacts in the toolchain container (image cached after the first run)
echo "==> [3/4] running build in container"
docker run --rm -v "$PWD":/work -w /work --user "$(id -u):$(id -g)" \
  -e HOME=/tmp -e VIDEO_PROBE="${VIDEO_PROBE:-0}" -e VIDEO_PROBE_DEEP="${VIDEO_PROBE_DEEP:-0}" \
  -e CLUSTER_SINK="$CLUSTER_SINK" -e JAR_ONLY="$JAR_ONLY" -e SKIP_TESTS="${SKIP_TESTS:-0}" \
  -e CLUSTER_DEFINES="${CLUSTER_DEFINES:-}" \
  -e ALLOW_SHADOW_MISMATCH="${ALLOW_SHADOW_MISMATCH:-0}" \
  "$IMAGE" /work/docker/entrypoint.sh

# 4) collect the two deployables
echo "==> [4/4] collect -> $OUT"
cp -f jar/build/AAtoKombi.jar                   "$OUT/AAtoKombi.jar"
if [ "$JAR_ONLY" = "1" ]; then
  if [ -f "$OUT/libext.google.gal.receiver.so" ]; then
    echo "  (the libext.google.gal.receiver.so already in $OUT is from an EARLIER build, not this one)"
  fi
else
  cp -f shim/build/libext.google.gal.receiver.so  "$OUT/libext.google.gal.receiver.so"
fi
# libkb_dm.so must come from the same build as the libgal (they share the cluster video memory and
# its version): one from an earlier CLUSTER_SINK build is taken out rather than left next to these.
if [ "${CLUSTER_SINK:-0}" = "1" ] && [ "$JAR_ONLY" != "1" ] && [ -f native/build/libkb_dm.so ]; then
  cp -f native/build/libkb_dm.so                "$OUT/libkb_dm.so"
  cp -f native/install/install_dm_hook.sh native/install/uninstall_dm_hook.sh "$OUT/"
  echo "  + libkb_dm.so (DisplayManager hook for the AA cluster map) and its install scripts"
else
  for f in libkb_dm.so install_dm_hook.sh uninstall_dm_hook.sh; do
    if [ -f "${OUT:?}/${f:?}" ]; then
      rm -f "${OUT:?}/${f:?}"
      echo "  - $f removed from $OUT (left from an earlier CLUSTER_SINK build)"
    fi
  done
fi

echo
echo "OK. Ready in $OUT :"
echo "  AAtoKombi.jar                  -> SD custom/java/AAtoKombi.jar"
[ "$JAR_ONLY" = "1" ] || echo "  libext.google.gal.receiver.so  -> SD custom/sal/libext.google.gal.receiver.so"
if [ -f "$OUT/libkb_dm.so" ] && [ "${CLUSTER_SINK:-0}" = "1" ]; then
  echo "  libkb_dm.so + install_dm_hook.sh -> FTP to /tmp, then over telnet (native/README.md)"
fi
echo "Then on the unit: Toolbox -> Customisation -> Navi -> Enable AAtoKombi -> reboot."

# --- what this build actually IS -------------------------------------------------------------
echo
echo "Build configuration:"
echo "  AA protocol advertised .......... 1.7 (NavigationState: manoeuvres, lane guidance,"
echo "                                    destination distance / travel time, rerouting notice)"
# Every compile-time constant that went into the jar, read back out of the source that produced it.
# The values are baked in and unreadable afterwards, so printing them here is the only record of what
# a given pair of artifacts actually does — and the cheapest way to catch a flag left flipped from
# the previous build. Names are ALL_CAPS by convention, prefixed with their group (a nested class
# declared at 4 spaces; the helper classes with the variants of a choice, nested deeper, are not listed); the value is taken verbatim, so a symbolic
# one (LOG_LEVEL = MIBLogger.INFO) prints as written rather than as a number nobody remembers.
CONSTANTS="$(awk '
  /^    public static final class [A-Za-z0-9_]+/ { for (i = 1; i <= NF; i++) if ($i == "class") { grp = $(i + 1); sub(/[^A-Za-z0-9_].*$/, "", grp) } next }
  /^        public static final [A-Za-z][A-Za-z0-9_]*[[:space:]]+[A-Z][A-Z0-9_]*[[:space:]]*=/ {
    line = $0; sub(/^        public static final [A-Za-z][A-Za-z0-9_]*[[:space:]]+/, "", line)
    name = line; sub(/[[:space:]]*=.*$/, "", name)
    val = line; sub(/^[^=]*=[[:space:]]*/, "", val); sub(/;.*$/, "", val)
    print (grp != "" ? grp "." : "") name " " val }' jar/src/de/aatokombi/Config.java 2>/dev/null)"
if [ -n "$CONSTANTS" ]; then
  echo "  Config.java:"
  printf '%s\n' "$CONSTANTS" \
    | while read -r name value; do printf "    %-44s %s\n" "$name" "$value"; done
  # A diagnostic build keeps logs and trip dumps in the head unit's RAM: fine for a test drive,
  # not what should stay on the unit or be given to anybody else.
  case "$CONSTANTS" in
    *"Advanced.LOG_LEVEL MIBLogger.INFO"*|*"Advanced.LOG_LEVEL MIBLogger.ERROR"*|*"Advanced.LOG_LEVEL MIBLogger.SILENT"*) ;;
    *) echo
       echo "  NOTE: Advanced.LOG_LEVEL is DEBUG/TRACE - a DIAGNOSTIC build (RAM log + trip dumps on)."
       echo "        Set it to MIBLogger.INFO for everyday use." ;;
  esac
else
  # Silence here would read as "no switches", which is never true — say it is the reader that broke.
  echo "  Config.java: could not read the constants (declaration style changed? see build.sh)"
fi
