#!/bin/sh
# Install libkb_dm.so into DisplayManager's LD_PRELOAD (run on the unit over telnet).
# Usage:  sh install_dm_hook.sh /tmp/libkb_dm.so
#
# What it changes: copies the library to /tsd/lib/aatokombi/libkb_dm.so and appends it to the
# "envar LD_PRELOAD" line of "service iMX6.DispMg" in /tsd/etc/system/cpu.conf. The stock
# cpu.conf is saved once to /tsd/var/aatokombi/cpu.conf.stock. Reboot afterwards.
#
# DANGER: a library named in LD_PRELOAD that cannot be loaded keeps DisplayManager from
# starting and the unit resets. The script therefore copies, compares, lets the library CHECK
# ITSELF on this unit (below) and only then edits cpu.conf. Keep uninstall_dm_hook.sh at hand. Do
# not combine with mst2-cluster-nav, which hooks the same DisplayManager functions.
#
# Self check: the copied library is loaded into a harmless process (`ls`) with KB_DM_CHECK set. It
# writes one line to /dev/shmem/aatokombi-dm.check - which exists only if the library could be
# loaded here - saying whether this unit's libOpenMAXAL and DisplayManager are the ones the hook
# knows (see kb_dm_check in native/src/dm_hook.c). No result, or a libOpenMAXAL that differs:
# nothing is changed. FORCE=1 sh install_dm_hook.sh ... installs anyway.
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

# From here the system partition is writable. ro() puts it back exactly once, and the trap runs it
# on EVERY way out - also when set -e ends the script in the middle (a failed cp, a full partition).
RW=1
ro() { if [ -n "$RW" ]; then RW=; . /tsd/etc/persistence/esd/scripts/util_mount_ro.sh; fi; }
trap ro 0
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
  rm -f $LIB.new; ro
  echo "copy mismatch, nothing changed"; exit 1
fi
# --- self check on this unit, before anything refers to the library --------------------------
CHK=/dev/shmem/aatokombi-dm.check
DMBIN=
for x in $(sed -n '/^service iMX6.DispMg/,/^service /{s/^ *command  *\([^ ]*\).*/\1/p;}' $CPU); do DMBIN=$x; break; done
rm -f $CHK
LD_PRELOAD=$LIB.new KB_DM_CHECK=1 KB_DM_BIN="$DMBIN" ls /dev/null >/dev/null 2>&1 || true
refuse() {
  if [ "$FORCE" = "1" ]; then echo "FORCE=1: installing anyway"; return 0; fi
  rm -f $LIB.new; ro
  echo "nothing changed. (FORCE=1 sh $0 ... installs anyway - only if you know why the check is wrong)"
  exit 1
}
if [ ! -s $CHK ]; then
  echo "self check: no result - the library could not be loaded on this unit."
  echo "As a preload of DisplayManager it would keep DisplayManager from starting."
  refuse
else
  echo "self check (DisplayManager: ${DMBIN:-not found in cpu.conf}):"
  sed -n p $CHK
  if grep -q 'omx=differs' $CHK; then
    echo "libOpenMAXAL on this unit is not the build the hook knows: the hook would replace nothing"
    echo "(no AA map on the cluster). This firmware is not supported by libkb_dm.so as it is."
    refuse
  fi
  grep -q 'omx=unknown' $CHK && echo "note: libOpenMAXAL could not be looked at from here; the hook checks it again inside DisplayManager (see /dev/shmem/aatokombi-dm.log after the reboot)"
  grep -q 'most=ok' $CHK || echo "note: MostSink call site not confirmed: the MOST ring is not waited for (pass-through mode only)"
  grep -q 'mmr=ok' $CHK || echo "note: mm-renderer client not confirmed: decode mode (the default) may not work; see the log after the reboot"
fi

mv -f $LIB.new $LIB
rm -f $VAR/video-off $VAR/dm-strikes $VAR/dm-busy

if sed -n '/^service iMX6.DispMg/,/^service /p' $CPU | grep -q 'libkb_dm.so'; then
  echo "cpu.conf already names libkb_dm.so"
else
  sed '/^service iMX6.DispMg/,/^service /s#^\(   envar LD_PRELOAD .*\)$#\1:'"$LIB"'#' $CPU > $CPU.new
  N=$(sed -n '/^service iMX6.DispMg/,/^service /p' $CPU.new | grep -c 'libkb_dm.so' || true)
  if [ "$N" != "1" ] || [ ! -s $CPU.new ]; then
    rm -f $CPU.new; ro
    echo "could not edit cpu.conf (found $N), nothing changed"; exit 1
  fi
  chmod 644 $CPU.new
  mv -f $CPU.new $CPU
  echo "cpu.conf: DisplayManager preloads $LIB"
fi
sed -n '/^service iMX6.DispMg/,/^service /p' $CPU | grep LD_PRELOAD
sync
ro
echo "done - reboot the unit. Off switch: an SD card with an empty file 'aatokombi-video-disable'."
echo "After the reboot /dev/shmem/aatokombi-dm.log must say 'libOpenMAXAL 0480 layout confirmed';"
echo "'build differs' there means the hook is inert on this firmware (stock cluster map only)."
