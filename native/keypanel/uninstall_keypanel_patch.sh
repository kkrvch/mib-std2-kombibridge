#!/bin/sh
# Remove the J5 key panel patch: J5 starts its own main.root again. Reboot afterwards.
. /tsd/etc/persistence/esd/scripts/util_mount.sh
if [ -f /tsd/bin/system/startup_main ] && grep -q AAtoKombi /tsd/bin/system/startup_main; then
  rm -f /tsd/bin/system/startup_main
  echo "startup_main removed"
fi
rm -rf /tsd/lib/aatokombi/j5
sync
. /tsd/etc/persistence/esd/scripts/util_mount_ro.sh
echo "done: reboot the unit"
