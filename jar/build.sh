#!/bin/sh
# Builds AAtoKombi.jar — the HMI-side mod that reads the navigation data the shim writes to
# /dev/shmem/aa_nav and renders it on the instrument cluster (via the media now-playing widget).
#
# Requirements:
#   * JDK 8 javac  — it must support "-source 1.3 -target 1.3" (newer JDKs dropped 1.3).
#                    Point JAVAC/JAR at a JDK 8 if your default java is newer.
#   * python3      — for tools/check_shadows.py (standard library only); without it the shadows
#                    are not checked against MIBHMI.jar (a warning says so).
#   * MIBHMI.jar   — the HMI framework jar. It is NOT shipped
#                    here. Put it at jar/lib/MIBHMI.jar, or set
#                    MIBHMI=/path/to/MIBHMI.jar. It is used only as the compile classpath.
#
# Usage:   ./build.sh
#          MIBHMI=/path/to/MIBHMI.jar JAVAC=/path/to/jdk8/bin/javac ./build.sh
#
set -e
cd "$(dirname "$0")"

MIBHMI="${MIBHMI:-lib/MIBHMI.jar}"
JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

if [ ! -f "$MIBHMI" ]; then
  echo "ERROR: compile classpath not found: $MIBHMI"
  echo "       Extract MIBHMI.jar and place it at jar/lib/MIBHMI.jar"
  echo "       (or run: MIBHMI=/path/to/MIBHMI.jar ./build.sh)"
  exit 1
fi

# The shadows replace stock classes of ONE firmware. Are the stock classes of THIS MIBHMI.jar the
# ones they were made from (jar/shadows.lock)? And, after compiling, does every shadow still offer
# what its stock class offers to the rest of the HMI? See tools/check_shadows.py.
# ALLOW_SHADOW_MISMATCH=1 turns a failed check into a warning (to experiment on another firmware).
PYTHON="${PYTHON:-python3}"
shadow_check() {
  if ! command -v "$PYTHON" >/dev/null 2>&1; then
    echo "WARNING: $PYTHON not found - the shadows were NOT checked against $MIBHMI"
    return 0
  fi
  if "$PYTHON" -B ../tools/check_shadows.py "$@"; then return 0; fi
  if [ "${ALLOW_SHADOW_MISMATCH:-0}" = "1" ]; then
    echo "WARNING: ALLOW_SHADOW_MISMATCH=1 - continuing although the shadow check failed. UNVERIFIED jar."
    return 0
  fi
  echo "       (ALLOW_SHADOW_MISMATCH=1 builds anyway, for experiments only.)"
  exit 1
}
echo "[1/5] shadows: the stock classes of this MIBHMI.jar against jar/shadows.lock"
shadow_check stock "$MIBHMI" src shadows.lock

echo "[2/5] compiling (javac -source/target 1.3, classpath = $MIBHMI)"
rm -rf build/classes
mkdir -p build/classes
find src -name '*.java' > build/sources.txt
"$JAVAC" -source 1.3 -target 1.3 -encoding UTF-8 -nowarn -cp "$MIBHMI" -d build/classes @build/sources.txt

echo "[3/5] shadows: every compiled shadow against its stock class"
shadow_check api "$MIBHMI" src build/classes

echo "[4/5] dropping compile-only stub (org/dsi/ifc/androidauto2/Constants)"
# Constants is a stub used ONLY so the sources compile; the real values are ROM-inlined on the
# device, so we must NOT ship our stub or it would shadow them.
rm -f build/classes/org/dsi/ifc/androidauto2/Constants.class

echo "[5/5] packaging build/AAtoKombi.jar"
"$JAR" cf build/AAtoKombi.jar -C build/classes .

echo "OK -> $(cd build && pwd)/AAtoKombi.jar"
