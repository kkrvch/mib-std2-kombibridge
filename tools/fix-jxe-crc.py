#!/usr/bin/env python
# fix-jxe-crc.py - make a MIBHMI.jxe readable by jxe2jar when rom.classes was modified on the unit.
#
# A .jxe is a zip whose single big entry, rom.classes, is STORED (not compressed). Tools that patch
# the HMI in place rewrite bytes inside rom.classes and leave the zip's CRC-32 alone; the unit never
# checks it, but jxe2jar reads the entry through Python's zipfile, which does - "Bad CRC-32 for file
# 'rom.classes'" - and the build dies before it has started.
#
# We only need the jar as a compile classpath, so a stale checksum is no reason to refuse: recompute
# the CRC over the bytes that are actually there and write it into both places a zip keeps it (local
# header, central directory). Run on the build's private COPY of the .jxe, never on the user's file.
#
# Runs inside the jxe2jar image, which has Python 2 only - keep it 2/3 compatible.
#
#   fix-jxe-crc.py <MIBHMI.jxe> [--strict]
# exit 0: CRC was right, or was repaired (says which);  2: not a jxe we understand;
#      3: --strict and the CRC is wrong (file left untouched).
from __future__ import print_function
import struct, sys, zlib

ENTRY = b'rom.classes'

def main():
    args = [a for a in sys.argv[1:] if a != '--strict']
    strict = '--strict' in sys.argv[1:]
    if len(args) != 1:
        print('usage: fix-jxe-crc.py <MIBHMI.jxe> [--strict]'); return 2
    path = args[0]
    raw = bytearray(open(path, 'rb').read())

    # Central directory first: it is what readers trust, and it tells us where the entry lives.
    cd = -1; pos = raw.find(b'PK\x01\x02')
    while pos != -1:
        n = struct.unpack_from('<H', raw, pos + 28)[0]
        if bytes(raw[pos + 46:pos + 46 + n]) == ENTRY: cd = pos; break
        pos = raw.find(b'PK\x01\x02', pos + 4)
    if cd < 0:
        print('fix-jxe-crc: no rom.classes entry - not a MIBHMI.jxe?'); return 2
    method, = struct.unpack_from('<H', raw, cd + 10)
    stored_crc, csize, usize = struct.unpack_from('<III', raw, cd + 16)
    lh, = struct.unpack_from('<I', raw, cd + 42)
    if method != 0 or csize != usize or bytes(raw[lh:lh + 4]) != b'PK\x03\x04':
        print('fix-jxe-crc: rom.classes is not a plain stored entry - leaving the file alone'); return 2
    n, m = struct.unpack_from('<HH', raw, lh + 26)
    data = lh + 30 + n + m
    if data + usize > len(raw):
        # A short file is a broken dump, not a patched one. Nothing honest to repair.
        print('fix-jxe-crc: rom.classes runs past the end of the file - the dump is TRUNCATED'); return 2

    actual = zlib.crc32(bytes(raw[data:data + usize])) & 0xffffffff
    if actual == stored_crc:
        print('fix-jxe-crc: rom.classes CRC ok (%08x)' % actual); return 0

    print('WARNING: rom.classes does not match its factory checksum (zip says %08x, bytes give %08x).'
          % (stored_crc, actual))
    print('         The HMI on this unit was modified in place (another mod / activation patch), or')
    print('         the dump got damaged on its way off the unit - the two look the same from here.')
    if strict:
        print('         STRICT_JXE is set - refusing to build from it.'); return 3
    struct.pack_into('<I', raw, cd + 16, actual)
    struct.pack_into('<I', raw, lh + 14, actual)
    open(path, 'wb').write(raw)
    print('         Building anyway: the checksum was corrected in the build\'s private copy only.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
