#!/bin/sh
# Runs inside the toolchain container (see Dockerfile). The repo is bind-mounted at /work and the
# two inputs are already in place (jar/lib/MIBHMI.jar, shim/lib/libext.google.gal.receiver.so) —
# build.sh puts them there. Builds both artifacts into jar/build/ and shim/build/.
set -e

echo "==> building AAtoKombi.jar (HMI mod)"
( cd /work/jar && ./build.sh )
echo "      -> jar/build/AAtoKombi.jar"

# The shim is only rebuilt if a target libgal is present. jar-only changes (e.g. the layout probe)
# don't need it — keep your already-deployed patched libgal.
if [ -f /work/shim/lib/libext.google.gal.receiver.so ]; then
  echo "==> building patched libext.google.gal.receiver.so (shim)"
  ( cd /work/shim && ./build.sh )   # default LIBGAL = shim/lib/libext.google.gal.receiver.so
  echo "      -> shim/build/libext.google.gal.receiver.so"
else
  echo "==> no shim/lib/libext.google.gal.receiver.so — skipping shim (jar-only build)"
fi
echo "==> done."
