#!/bin/sh
# Install libkb_dm.so into DisplayManager's LD_PRELOAD (run on the unit over telnet).
# Usage:  sh install_dm_hook.sh /tmp/libkb_dm.so
#
# What it changes: copies the library to /tsd/lib/aatokombi/libkb_dm.so and appends it to the
# "envar LD_PRELOAD" line of "service iMX6.DispMg" in /tsd/etc/system/cpu.conf. The stock
# cpu.conf is saved once to /tsd/var/aatokombi/cpu.conf.stock. Reboot afterwards.
#
# DANGER: a library named in LD_PRELOAD that cannot be loaded keeps DisplayManager from
# starting and the unit resets. The script therefore copies, compares and only then edits
# cpu.conf. Keep uninstall_dm_hook.sh at hand. Do not combine with mst2-cluster-nav, which hooks
# the same DisplayManager functions.
set -e
SRC="$1"
LIBDIR=/tsd/lib/aatokombi
LIB=$LIBDIR/libkb_dm.so
CPU=/tsd/etc/system/cpu.conf
VAR=/tsd/var/aatokombi

[ -n "$SRC" ] && [ -s "$SRC" ] || { echo "usage: sh $0 /tmp/libkb_dm.so"; exit 1; }
grep -q '^service iMX6.DispMg' $CPU || { echo "no iMX6.DispMg service in $CPU: not this firmware"; exit 1; }
if grep -q 'mst2-cluster-nav' $CPU; then
  echo "mst2-cluster-nav is installed: it hooks the same DisplayManager functions. Uninstall it first."
  exit 1
fi

. /tsd/etc/persistence/esd/scripts/util_mount.sh
mkdir -p $LIBDIR $VAR
[ -f $VAR/cpu.conf.stock ] || cp $CPU $VAR/cpu.conf.stock

cp -f "$SRC" $LIB.new
chmod a+rx $LIB.new
# QNX has no cksum: compare with cmp when present, else by size
if command -v cmp >/dev/null 2>&1; then
  if cmp -s "$SRC" $LIB.new; then SAME=0; else SAME=1; fi
else
  if [ "$(wc -c < "$SRC")" = "$(wc -c < $LIB.new)" ]; then SAME=0; else SAME=1; fi
fi
if [ "$SAME" != "0" ]; then
  rm -f $LIB.new; . /tsd/etc/persistence/esd/scripts/util_mount_ro.sh
  echo "copy mismatch, nothing changed"; exit 1
fi
mv -f $LIB.new $LIB
rm -f $VAR/video-off $VAR/dm-strikes $VAR/dm-busy

if sed -n '/^service iMX6.DispMg/,/^service /p' $CPU | grep -q 'libkb_dm.so'; then
  echo "cpu.conf already names libkb_dm.so"
else
  sed '/^service iMX6.DispMg/,/^service /s#^\(   envar LD_PRELOAD .*\)$#\1:'"$LIB"'#' $CPU > $CPU.new
  N=$(sed -n '/^service iMX6.DispMg/,/^service /p' $CPU.new | grep -c 'libkb_dm.so' || true)
  if [ "$N" != "1" ] || [ ! -s $CPU.new ]; then
    rm -f $CPU.new; . /tsd/etc/persistence/esd/scripts/util_mount_ro.sh
    echo "could not edit cpu.conf (found $N), nothing changed"; exit 1
  fi
  chmod 644 $CPU.new
  mv -f $CPU.new $CPU
  echo "cpu.conf: DisplayManager preloads $LIB"
fi
sed -n '/^service iMX6.DispMg/,/^service /p' $CPU | grep LD_PRELOAD
sync
. /tsd/etc/persistence/esd/scripts/util_mount_ro.sh
echo "done - reboot the unit. Off switch: an SD card with an empty file 'aatokombi-video-disable'."
