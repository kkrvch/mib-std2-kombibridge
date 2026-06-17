#!/bin/sh
# Builds AAtoKombi.jar — the HMI-side mod that reads the navigation data the shim writes to
# /dev/shmem/aa_nav and renders it on the instrument cluster (via the media now-playing widget).
#
# Requirements:
#   * JDK 8 javac  — it must support "-source 1.3 -target 1.3" (newer JDKs dropped 1.3).
#                    Point JAVAC/JAR at a JDK 8 if your default java is newer.
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

echo "[1/3] compiling (javac -source/target 1.3, classpath = $MIBHMI)"
rm -rf build/classes
mkdir -p build/classes
find src -name '*.java' > build/sources.txt
"$JAVAC" -source 1.3 -target 1.3 -encoding UTF-8 -nowarn -cp "$MIBHMI" -d build/classes @build/sources.txt

echo "[2/3] dropping compile-only stub (org/dsi/ifc/androidauto2/Constants)"
# Constants is a stub used ONLY so the sources compile; the real values are ROM-inlined on the
# device, so we must NOT ship our stub or it would shadow them.
rm -f build/classes/org/dsi/ifc/androidauto2/Constants.class

echo "[3/3] packaging build/AAtoKombi.jar"
"$JAR" cf build/AAtoKombi.jar -C build/classes .

echo "OK -> $(cd build && pwd)/AAtoKombi.jar"
