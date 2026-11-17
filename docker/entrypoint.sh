#!/bin/sh
# Runs inside the toolchain container (see Dockerfile). The repo is bind-mounted at /work and the
# inputs are already in place (jar/lib/MIBHMI.jar, and shim/lib/libext.google.gal.receiver.so
# unless JAR_ONLY=1) - build.sh puts them there. Builds into jar/build/, shim/build/, native/build/,
# then runs the host tests: a failed test fails the build, so nothing is collected.
#   JAR_ONLY=1    only the jar (no libgal given to build.sh)
#   SKIP_TESTS=1  no tests (for a quick experiment; the result is untested)
set -e

echo "==> toolchain"
echo "    $(javac -version 2>&1 | head -1) / $(clang --version | head -1) / $(ld.lld --version | head -1) / $(python3 --version 2>&1)"

echo "==> building AAtoKombi.jar (HMI mod)"
( cd /work/jar && ./build.sh )
echo "      -> jar/build/AAtoKombi.jar"

SHIM_BUILT=0
if [ "${JAR_ONLY:-0}" = "1" ]; then
  echo "==> jar-only build: no libgal, no libkb_dm.so"
elif [ -f /work/shim/lib/libext.google.gal.receiver.so ]; then
  echo "==> building patched libext.google.gal.receiver.so (shim)"
  ( cd /work/shim && ./build.sh )   # default LIBGAL = shim/lib/libext.google.gal.receiver.so
  echo "      -> shim/build/libext.google.gal.receiver.so"
  SHIM_BUILT=1
else
  echo "==> no shim/lib/libext.google.gal.receiver.so — skipping shim (jar-only build)"
fi
if [ "${CLUSTER_SINK:-0}" = "1" ] && [ "${JAR_ONLY:-0}" != "1" ]; then
  echo "==> building native/build/libkb_dm.so (DisplayManager hook, AA cluster map)"
  ( cd /work/native && ./build.sh )
fi

if [ "${SKIP_TESTS:-0}" = "1" ]; then
  echo "==> SKIP_TESTS=1: the host tests were NOT run"
else
  FAILED=""
  run_test() {   # name, script
    echo "==> test: $1"
    if sh "$2"; then :; else FAILED="$FAILED $1"; fi
  }
  run_test "protocol decoders (tests/shim)" /work/tests/shim/run.sh
  run_test "cluster video ring (tests/ring)" /work/tests/ring/run.sh
  run_test "jar (tests/jar)"                 /work/tests/jar/run.sh
  run_test "shadow check (tests/shadows)"    /work/tests/shadows/run.sh
  if [ "$SHIM_BUILT" = "1" ]; then
    run_test "libgal layouts (tests/inject)" /work/tests/inject/run.sh
  fi
  if [ -n "$FAILED" ]; then
    echo "==> TESTS FAILED:$FAILED"
    echo "    Nothing is collected. (SKIP_TESTS=1 builds anyway - untested.)"
    exit 1
  fi
  echo "==> all tests passed"
fi
echo "==> done."
