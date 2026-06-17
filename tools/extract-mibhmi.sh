#!/usr/bin/env bash
# extract-mibhmi.sh — prepare a target firmware's MIBHMI.jar for an AAtoKombi build, and verify
# that the classes this mod SHADOWS/depends on are structurally compatible with that firmware.
#
# Where MIBHMI.jar comes from:
#   The toolbox script `dump_hmi.sh` dumps /tsd/hmi/tsd.mibstd2.hmi.ifs to the SD card (dump/hmi/).
#   MIBHMI.jar lives INSIDE that IFS image — extract it with `dumpifs` (see work/dumpifs/), then
#   feed the resulting MIBHMI.jar to this script.
#
# What it does:
#   1. installs the jar as the build classpath at jar/lib/MIBHMI.jar
#   2. extracts the exact stock classes we shadow / rely on into jar/reference/classes/
#   3. runs javap structural checks: do those stock classes still expose the members our shadows
#      assume? -> prints OK / WARN so you know the shadows fit THIS firmware before building.
#   4. (optional) if $CFR points at cfr.jar, decompiles them to jar/reference/decompiled/ for a
#      manual diff against our shadow sources in jar/src/.
#
# Usage:
#   tools/extract-mibhmi.sh /path/to/MIBHMI.jar
#   CFR=/path/to/cfr.jar tools/extract-mibhmi.sh /path/to/MIBHMI.jar
set -euo pipefail

abspath() { ( cd "$(dirname "$1")" && printf '%s/%s\n' "$(pwd)" "$(basename "$1")" ); }

REPO="$(cd "$(dirname "$0")/.." && pwd)"

JAR_IN="${1:-}"
if [ -z "$JAR_IN" ] || [ ! -f "$JAR_IN" ]; then
  echo "usage: tools/extract-mibhmi.sh /path/to/MIBHMI.jar"
  exit 1
fi
JAR="$(abspath "$JAR_IN")"
CFR="${CFR:-}"
[ -n "$CFR" ] && [ -f "$CFR" ] && CFR="$(abspath "$CFR")"

command -v jar   >/dev/null || { echo "ERROR: 'jar' not found (need a JDK on PATH)."; exit 1; }
command -v javap >/dev/null || { echo "ERROR: 'javap' not found (need a JDK on PATH)."; exit 1; }

cd "$REPO"
REF=jar/reference
CLASSES_DIR="$REF/classes"
DECOMP_DIR="$REF/decompiled"

# Stock classes we need out of MIBHMI.jar (the ones our jar/src shadows, stubs, or depends on).
NEED_CLASSES="
de/vw/mib/bap/mqbab2/audiosd/functions/CurrentStationInfo
de/vw/mib/asl/internal/androidauto/target/AndroidAutoTarget
de/vw/mib/bap/mqbab2/generated/audiosd/serializer/CurrentStationInfo_Status
org/dsi/ifc/androidauto2/Constants
"

# Structural contract our shadows rely on: "<class> : <member1> <member2> ...".
# A WARN here means the target firmware drifted and the corresponding shadow may need updating.
declare -a CHECKS=(
  "de/vw/mib/bap/mqbab2/generated/audiosd/serializer/CurrentStationInfo_Status : primaryInformation secondaryInformation tertiaryInformation quaternaryInformation pi_Type si_Type ti_Type qi_Type channel_Id"
  "de/vw/mib/bap/mqbab2/audiosd/functions/CurrentStationInfo : getFunctionId setStationInfoForMirrorLink init"
  "de/vw/mib/asl/internal/androidauto/target/AndroidAutoTarget : initHandler getDefaultTargetId dsiAndroidAuto2UpdateNowPlayingData dsiAndroidAuto2UpdateNavigationNextTurnEvent dsiAndroidAuto2UpdateNavigationNextTurnDistance"
  "org/dsi/ifc/androidauto2/Constants : NAVFOCUS_PROJECTED NAVFOCUS_NATIVE"
)

echo "==> [1/4] install build classpath -> jar/lib/MIBHMI.jar"
mkdir -p jar/lib
cp "$JAR" jar/lib/MIBHMI.jar

echo "==> [2/4] extract reference classes -> $CLASSES_DIR"
rm -rf "$CLASSES_DIR"; mkdir -p "$CLASSES_DIR"
missing=0
for c in $NEED_CLASSES; do
  if jar tf "$JAR" 2>/dev/null | grep -qx "$c.class"; then
    ( cd "$CLASSES_DIR" && jar xf "$JAR" "$c.class" )
    echo "    + $c"
  else
    echo "    ! MISSING in jar: $c   (firmware layout differs — shadows may not apply)"
    missing=1
  fi
done

echo "==> [3/4] structural compatibility checks (javap)"
warns=0
for entry in "${CHECKS[@]}"; do
  cls="${entry%% : *}"; members="${entry##* : }"
  if [ ! -f "$CLASSES_DIR/$cls.class" ]; then
    echo "    WARN  $cls : class not extracted (see above)"; warns=$((warns+1)); continue
  fi
  dump="$(javap -p -cp "$CLASSES_DIR" "${cls//\//.}" 2>/dev/null || true)"
  short="${cls##*/}"
  for m in $members; do
    if printf '%s' "$dump" | grep -q "$m"; then
      echo "    OK    $short.$m"
    else
      echo "    WARN  $short.$m  -> not found (shadow may need updating for this firmware)"
      warns=$((warns+1))
    fi
  done
done

echo "==> [4/4] decompile reference classes"
if [ -z "$CFR" ] && [ -f cfr.jar ]; then CFR="$(abspath cfr.jar)"; fi
if [ -n "$CFR" ] && [ -f "$CFR" ]; then
  rm -rf "$DECOMP_DIR"; mkdir -p "$DECOMP_DIR"
  for c in $NEED_CLASSES; do
    [ -f "$CLASSES_DIR/$c.class" ] || continue
    out="$DECOMP_DIR/$c.java"; mkdir -p "$(dirname "$out")"
    java -jar "$CFR" "$CLASSES_DIR/$c.class" >"$out" 2>/dev/null && echo "    decompiled $c"
  done
  echo "    -> compare with our shadows, e.g.:"
  echo "       diff $DECOMP_DIR/de/vw/mib/bap/mqbab2/audiosd/functions/CurrentStationInfo.java \\"
  echo "            jar/src/de/vw/mib/bap/mqbab2/audiosd/functions/CurrentStationInfo.java"
else
  echo "    (skipped: set CFR=/path/to/cfr.jar to also decompile the stock classes for diffing)"
fi

echo
if [ "$missing" = 0 ] && [ "$warns" = 0 ]; then
  echo "RESULT: OK — jar/lib/MIBHMI.jar installed and the shadowed classes match. Ready to build."
else
  echo "RESULT: review the WARN/MISSING lines above — some shadows may need updating for this firmware"
  echo "        (decompile with CFR and diff against jar/src/ before relying on the build)."
fi
