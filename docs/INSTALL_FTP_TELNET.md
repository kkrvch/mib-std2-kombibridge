# AAtoKombi install via FTP + Telnet

Build the two artifacts from files pulled off the unit, then deploy them over FTP + Telnet only —
no toolbox SD-card round-trip, no local JDK/clang (the build runs in Docker).

**Prerequisites:** `curl`, Docker running, and a unit reachable on the network. Replace `{UNIT_IP}`
throughout (e.g. `10.13.5.118`), pick up one from the network section in toolbox. Telnet/FTP creds 
are `root` / `root`.

> **Applies only to units reachable over the network:** either a unit with built-in Wi-Fi, or one
> without it but with a USB→Ethernet adapter connected. If the unit has no network path, use the
> toolbox SD-card flow instead.

---

## 0. Enable access (toolbox)

Run **"Activate permanent telnet and console access"** and **"Activate telnet and ftp access until
the next reboot"** from the MIB2Toolbox network menu. This must be done **first** — every step below
needs FTP (download/upload) or Telnet up.

---

## 1. Download stock GAL + HMI executable, build both artifacts

```sh
# Stock GAL (the file we patch).
curl -sS -u root:root --disable-epsv \
     ftp://{UNIT_IP}/tsd/lib/sal/gal/libext.google.gal.receiver.so \
     -o libext.google.gal.receiver.so

# HMI executable (compile classpath for the jar).
curl -sS -u root:root --disable-epsv \
     ftp://{UNIT_IP}/tsd/hmi/ifs/MIBHMI.jxe \
     -o MIBHMI.jxe
```

**Before building, review `jar/src/de/aatokombi/Config.java`** and enable the features you want /
disable the ones you don't (`SHOW_NAV`, `SHOW_MEDIA`, `SHOW_MEDIA_PROGRESS`, `SHOW_COVER_ART`,
`SUPPRESS_NAV_ACTIVE_PLACEHOLDER`, `LOG_LEVEL`, …). The flags are compile-time constants, so a
disabled feature leaves no bytecode behind — you must rebuild the jar after changing them.

```sh
# Build both artifacts (all in Docker).
./build.sh MIBHMI.jxe libext.google.gal.receiver.so
# -> dist/AAtoKombi.jar
# -> dist/libext.google.gal.receiver.so
```

Notes:
- If you already installed the mod once, the GAL path above now holds the *patched* GAL — pull the
  stock backup instead: `.../libext.google.gal.receiver.so.aatokombi.bak`.
- If `jar/lib/MIBHMI.jar` is already cached for this firmware (`build.sh` caches it keyed on the
  `.jxe` hash), you can skip the `MIBHMI.jxe` download and only pull the GAL.
- If `/tsd/hmi/ifs/MIBHMI.jxe` isn't served over FTP, dump the HMI image via the toolbox
  (`dump_hmi.sh` → `tsd.mibstd2.hmi.ifs`) and extract it with `tools/unpack-ifs.py`.

---

## 2. FTP: upload both files to /tmp

```sh
curl -sS -u root:root --disable-epsv -T AAtoKombi.jar \
     ftp://{UNIT_IP}/tmp/AAtoKombi.jar
curl -sS -u root:root --disable-epsv -T libext.google.gal.receiver.so \
     ftp://{UNIT_IP}/tmp/libext.google.gal.receiver.so
```

---

## 3. Install + backup

`telnet {UNIT_IP}`, login: `root`, password: `root`

```sh
# remount system partition read/write
. /tsd/etc/persistence/esd/scripts/util_mount.sh

# jar
cp -f /tmp/AAtoKombi.jar /tsd/hmi/HMI/jar/AAtoKombi.jar
chmod a+rwx /tsd/hmi/HMI/jar/AAtoKombi.jar

# GAL: stock backup (once — guarded) + replacement
GAL=/tsd/lib/sal/gal/libext.google.gal.receiver.so
[ -f "$GAL.aatokombi.bak" ] || cp "$GAL" "$GAL.aatokombi.bak"
cp -f /tmp/libext.google.gal.receiver.so "$GAL" && chmod a+rx "$GAL"
ls -la "$GAL" "$GAL.aatokombi.bak"

# runHMI.sh: backup (once) + bootclasspath row (idempotent)
HMI_SH=/tsd/hmi/runHMI.sh
[ -f "$HMI_SH.bak" ] || cp "$HMI_SH" "$HMI_SH.bak"
if ! grep -q 'Xbootclasspath/p:$MIBJAR/AAtoKombi.jar' "$HMI_SH"; then
    sed -i 's/\(^BOOTCLASSPATH=.*$\)/\1\nBOOTCLASSPATH="$BOOTCLASSPATH -Xbootclasspath\/p:$MIBJAR\/AAtoKombi.jar"/' "$HMI_SH"
    echo "runHMI.sh patched"
else echo "already patched"; fi

sync
. /tsd/etc/persistence/esd/scripts/util_mount_ro.sh
```

Reboot the unit.

---

## 4. Check after reboot

```sh
grep AAtoKombi /tsd/hmi/runHMI.sh    # bootclasspath row is in place

# connect the phone via Android Auto — the patched GAL writes these while connected
cat /dev/shmem/aa_nav      # turn-by-turn line, during route guidance
cat /dev/shmem/aa_media    # now-playing line (song / artist / album / …)
```

What actually renders on the cluster depends on the `Config.java` flags you built with.

---

## 5. Rollback (if needed)

`telnet {UNIT_IP}`, login: `root`, password: `root`

```sh
. /tsd/etc/persistence/esd/scripts/util_mount.sh

# restore stock GAL
GAL=/tsd/lib/sal/gal/libext.google.gal.receiver.so
cp -f "$GAL.aatokombi.bak" "$GAL" && chmod a+rx "$GAL"

# restore runHMI.sh (removes the bootclasspath row) + drop the jar
cp -f /tsd/hmi/runHMI.sh.bak /tsd/hmi/runHMI.sh
rm -f /tsd/hmi/HMI/jar/AAtoKombi.jar

grep AAtoKombi /tsd/hmi/runHMI.sh || echo "bootclasspath removed"

sync
. /tsd/etc/persistence/esd/scripts/util_mount_ro.sh
```

Reboot the unit.

> The key step is restoring `runHMI.sh` (removing the `-Xbootclasspath/p` row) — without it the jar
> is never loaded even if it stays on disk. Restoring the stock GAL also stops the `/dev/shmem`
> writes. If `runHMI.sh.bak` is missing, remove the row by hand:
> `sed -i '/Xbootclasspath\/p:$MIBJAR\/AAtoKombi.jar/d' /tsd/hmi/runHMI.sh`
