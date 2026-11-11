#!/bin/sh
# Install the patched J5 key panel (View key = MQB 0x23 reaches the head unit). Run on the unit
# (i.MX6) over telnet:   sh install_keypanel_patch.sh /tmp/tsd.mibstd2.keypanel /tmp/startup_main
#
# Nothing on J5 is changed. The J5 startup script runs /net/imx6/tsd/bin/system/startup_main,
# when that file is executable, instead of starting main.root itself (a stock hook). Ours starts
# main.root with a copy of J5's main.conf whose key panel service runs the patched binary from
# /tsd/lib/aatokombi/j5 on the i.MX6.
#
# DANGER: if J5's main.root does not start, radio/audio/keys of the unit do not work. The i.MX6
# side (telnet) still starts: run uninstall_keypanel_patch.sh, or `touch
# /tsd/var/aatokombi/no-keypanel-patch` (stock main.conf, the hook stays), then reboot.
#
# The unit has no wc: sizes are checked with ls -l, line counts with grep -c.
set -e
BIN="$1"; SM="$2"
D=/tsd/lib/aatokombi/j5
SIZE=296253
size_ok() { ls -l "$1" | grep -q " $SIZE "; }

[ -s "$BIN" ] && [ -s "$SM" ] || { echo "usage: sh $0 /tmp/tsd.mibstd2.keypanel /tmp/startup_main"; exit 1; }
size_ok "$BIN" || { echo "patched keypanel has an unexpected size, nothing changed"; ls -l "$BIN"; exit 1; }
J5=
for n in /net/*; do
  if [ -f $n/tsd/bin/keypanel/tsd.mibstd2.keypanel ] && [ -f $n/tsd/etc/system/main.conf ]; then J5=$n; fi
done
[ -n "$J5" ] || { echo "J5 node not found under /net, nothing changed"; exit 1; }
echo "J5 node: $J5"
size_ok $J5/tsd/bin/keypanel/tsd.mibstd2.keypanel || { echo "J5 keypanel is not the expected firmware, nothing changed"; exit 1; }
N=$(grep -c '^   command /tsd/bin/keypanel/tsd.mibstd2.keypanel ' $J5/tsd/etc/system/main.conf || true)
[ "$N" = "1" ] || { echo "J5 main.conf: keypanel command found $N times, nothing changed"; exit 1; }
if [ -e /tsd/bin/system/startup_main ] && ! grep -q AAtoKombi /tsd/bin/system/startup_main; then
  echo "/tsd/bin/system/startup_main exists and is not ours, nothing changed"; exit 1
fi

. /tsd/etc/persistence/esd/scripts/util_mount.sh
mkdir -p $D /tsd/var/aatokombi
cp -f "$BIN" $D/tsd.mibstd2.keypanel
chmod 755 $D/tsd.mibstd2.keypanel
sed 's#^   command /tsd/bin/keypanel/tsd.mibstd2.keypanel #   command /net/imx6'"$D"'/tsd.mibstd2.keypanel #' \
  $J5/tsd/etc/system/main.conf > $D/main.conf
N=$(grep -c "^   command /net/imx6$D/tsd.mibstd2.keypanel " $D/main.conf || true)
if [ "$N" != "1" ] || ! size_ok $D/tsd.mibstd2.keypanel; then
  rm -f $D/main.conf $D/tsd.mibstd2.keypanel; . /tsd/etc/persistence/esd/scripts/util_mount_ro.sh
  echo "could not write the copies, nothing changed"; exit 1
fi
cp -f "$SM" /tsd/bin/system/startup_main
chmod 755 /tsd/bin/system/startup_main
rm -f /tsd/var/aatokombi/no-keypanel-patch
sync
. /tsd/etc/persistence/esd/scripts/util_mount_ro.sh
echo "installed: reboot the unit. J5 slog then shows 'AAtoKombi startup_main'; View arrives as a key."
