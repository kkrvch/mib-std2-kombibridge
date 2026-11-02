#!/bin/sh
# Take libkb_dm.so out of DisplayManager's LD_PRELOAD and remove it (run on the unit over telnet).
# Removes only our entry from cpu.conf; the stock copy saved by the installer stays in
# /tsd/var/aatokombi/cpu.conf.stock. Reboot afterwards.
CPU=/tsd/etc/system/cpu.conf
. /tsd/etc/persistence/esd/scripts/util_mount.sh
sed 's#:/tsd/lib/aatokombi/libkb_dm.so##g' $CPU > $CPU.new
if [ -s $CPU.new ] && ! grep -q 'libkb_dm.so' $CPU.new; then
  mv -f $CPU.new $CPU
  rm -f /tsd/lib/aatokombi/libkb_dm.so
  echo "removed"
else
  rm -f $CPU.new
  echo "cpu.conf not changed; restore by hand: cp /tsd/var/aatokombi/cpu.conf.stock $CPU"
fi
sed -n '/^service iMX6.DispMg/,/^service /p' $CPU | grep LD_PRELOAD
sync
. /tsd/etc/persistence/esd/scripts/util_mount_ro.sh
echo "reboot the unit"
