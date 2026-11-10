#!/usr/bin/env python3
"""
Patch the MIB2 STD2 J5 key panel service (tsd.mibstd2.keypanel) so that steering-wheel keys the
stock key table drops reach the head unit.

The service translates the MQB multifunction-wheel key code ("Tastencode", from the IOC) into a
DSI key code through a table of 29 entries of 8 bytes {u8 MQB code, u8 flag, 2 x 0, u32 DSI code}
at file offset 0x374b0 (mapMfwMqbEvent, 0x10c2d8). A code that is not in the table is dropped
with "MFW_MQB: mapMfwMqbEvent(): code not found in mapping table, original code: N" in the J5
slog. View on the MQB wheel is MQB 0x23 (35) and is missing (slog 2026-11-10).

The table has no free entry; the count (29) is compiled in. Entry 28 maps MQB 0xF1 to DSI 60,
the same DSI code as entry 27 (0xF0). This tool rewrites entry 28 to {0x23, 0, 0, 0, DSI}.
Default DSI 59 (stock: MQB 0x22, not present on the MQB wheel); the jar must use the same code
(Config.KEYPANEL_VIEW_CODE).

Only the unit owner's own file is patched; the result is a firmware-derived binary and must not
be published (dist-*/ is gitignored).

Usage: keypanel_patch.py <stock tsd.mibstd2.keypanel> <output> [--view-dsi 59] [--mqb 0x23]
"""
import sys

STOCK_SIZE = 296253
TABLE = 0x374b0
ENTRIES = 29
SLOT = 28
SLOT_STOCK = bytes([0xF1, 0, 0, 0, 60, 0, 0, 0])


def main(argv):
    args = [a for a in argv[1:]]
    view_dsi, mqb = 59, 0x23
    if '--view-dsi' in args:
        i = args.index('--view-dsi'); view_dsi = int(args[i + 1], 0); del args[i:i + 2]
    if '--mqb' in args:
        i = args.index('--mqb'); mqb = int(args[i + 1], 0); del args[i:i + 2]
    if len(args) != 2:
        print(__doc__); return 2
    src, dst = args
    d = bytearray(open(src, 'rb').read())
    if len(d) != STOCK_SIZE:
        print('unexpected size %d (stock %d): not this firmware, nothing written' % (len(d), STOCK_SIZE)); return 1
    if bytes(d[TABLE:TABLE + 8]) != bytes([0, 0, 0, 0, 34, 0, 0, 0]):
        print('key table not found at 0x%x, nothing written' % TABLE); return 1
    codes = [d[TABLE + 8 * k] for k in range(ENTRIES)]
    if mqb in codes:
        print('MQB 0x%02x is already in the table, nothing to do' % mqb); return 1
    slot = TABLE + 8 * SLOT
    if bytes(d[slot:slot + 8]) != SLOT_STOCK:
        print('entry %d is not the stock 0xF1 -> 60, nothing written' % SLOT); return 1
    if not 34 <= view_dsi <= 60:
        print('DSI code %d outside the stock range 34..60' % view_dsi); return 1
    d[slot:slot + 8] = bytes([mqb, 0, 0, 0, view_dsi, 0, 0, 0])
    open(dst, 'wb').write(d)
    print('%s: MQB 0x%02x -> DSI %d (entry %d, offset 0x%x)' % (dst, mqb, view_dsi, SLOT, slot))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
