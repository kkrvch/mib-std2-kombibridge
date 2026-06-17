#!/usr/bin/env python3
# unpack-ifs.py — extract files from a QNX6 mkifs image (e.g. tsd.mibstd2.hmi.ifs).
#
# Why this exists: the toolbox's "Dump files" copies MIBHMI.jxe directly off a running unit. But if
# you only have the whole HMI image (dump_hmi.sh's fallback → tsd.mibstd2.hmi.ifs), you need to
# unpack it — and the stock tool for that, QNX `dumpifs`, ships only with the QNX SDP. This is a
# self-contained replacement: pure Python, no QNX SDP, no liblzo2, no pip packages.
#
# It handles the common MIB case: a startup-header image whose imagefs is LZO-compressed in 64 KiB
# blocks (each block a `[uint16 big-endian length][lzo1x stream]`). Uncompressed images work too.
#
# Usage:
#   tools/unpack-ifs.py <image.ifs>                       # extract MIBHMI.jxe next to the image
#   tools/unpack-ifs.py <image.ifs> --list                # list the directory, don't extract
#   tools/unpack-ifs.py <image.ifs> --name FOO --out DIR  # extract one entry (basename match) to DIR
#   tools/unpack-ifs.py <image.ifs> --all  --out DIR      # extract everything
import argparse
import os
import struct
import sys


# --- pure-python lzo1x decompressor (port of the canonical minilzo lzo1x_decompress) ------------
def lzo1x_decompress(src):
    """Decompress one lzo1x stream (terminated by the end-of-stream marker). Returns bytes."""
    out = bytearray()
    ip = 0
    n = len(src)

    def lit(count):  # copy `count` literals from input to output
        nonlocal ip
        out.extend(src[ip:ip + count])
        ip += count

    def copy_match(m_pos, count):  # overlapping byte-wise copy of `count` bytes
        for _ in range(count):
            out.append(out[m_pos])
            m_pos += 1

    t = 0
    state = "top"            # top | first_literal_run | match | match_done
    if src[ip] > 17:
        t = src[ip] - 17
        ip += 1
        lit(t)
        state = "first_literal_run"

    while True:
        if state == "top":
            t = src[ip]; ip += 1
            if t < 16:
                if t == 0:
                    while src[ip] == 0:
                        t += 255; ip += 1
                    t += 15 + src[ip]; ip += 1
                lit(t + 3)
                state = "first_literal_run"
            else:
                state = "match"

        if state == "first_literal_run":
            t = src[ip]; ip += 1
            if t < 16:
                m_pos = len(out) - (1 + 0x0800) - (t >> 2) - (src[ip] << 2); ip += 1
                copy_match(m_pos, 3)
                state = "match_done"
            else:
                state = "match"

        if state == "match":
            if t >= 64:
                m_pos = len(out) - 1 - ((t >> 2) & 7) - (src[ip] << 3); ip += 1
                t = (t >> 5) - 1
            elif t >= 32:
                t &= 31
                if t == 0:
                    while src[ip] == 0:
                        t += 255; ip += 1
                    t += 31 + src[ip]; ip += 1
                m_pos = len(out) - 1 - (src[ip] >> 2) - (src[ip + 1] << 6); ip += 2
            elif t >= 16:
                m_pos = len(out) - ((t & 8) << 11)
                t &= 7
                if t == 0:
                    while src[ip] == 0:
                        t += 255; ip += 1
                    t += 7 + src[ip]; ip += 1
                m_pos -= (src[ip] >> 2) + (src[ip + 1] << 6); ip += 2
                if m_pos == len(out):
                    return bytes(out)            # end-of-stream marker
                m_pos -= 0x4000
            else:  # t < 16
                m_pos = len(out) - 1 - (t >> 2) - (src[ip] << 2); ip += 1
                copy_match(m_pos, 2)
                state = "match_done"
            if state == "match":
                copy_match(m_pos, t + 2)
                state = "match_done"

        if state == "match_done":
            t = src[ip - 2] & 3
            if t == 0:
                state = "top"
                continue
            lit(t)
            t = src[ip]; ip += 1
            state = "match"


# --- QNX mkifs image -----------------------------------------------------------------------------
STARTUP_SIGNATURE = 0x00FF7EEB
IMAGE_SIGNATURE = b"imagefs"
BLOCK = 0x10000  # imagefs LZO block decompresses to 64 KiB


def decompress_imagefs(raw):
    """Return the decompressed imagefs bytes (starting at the 'imagefs' signature)."""
    sig = struct.unpack_from("<I", raw, 0)[0]
    if sig != STARTUP_SIGNATURE:
        # No startup header — assume the file already starts with a plain imagefs.
        off = raw.find(IMAGE_SIGNATURE)
        if off < 0:
            sys.exit("error: not a QNX startup image and no 'imagefs' signature found")
        return raw[off:]

    flags1 = raw[6]
    comp = (flags1 & 0x1C) >> 2  # 0 none, 1 zlib, 2 lzo, 3 ucl
    startup_size = struct.unpack_from("<I", raw, 0x20)[0]
    imagefs_size = struct.unpack_from("<I", raw, 0x2C)[0]

    if comp == 0:
        off = raw.find(IMAGE_SIGNATURE, startup_size)
        return raw[off:]
    if comp != 2:
        sys.exit(f"error: imagefs compression type {comp} not supported (only none/lzo)")

    out = bytearray()
    p = startup_size
    while len(out) < imagefs_size and p + 2 <= len(raw):
        ln = struct.unpack_from(">H", raw, p)[0]
        if ln == 0:
            break
        p += 2
        out += lzo1x_decompress(raw[p:p + ln])
        p += ln
    if not out.startswith(IMAGE_SIGNATURE):
        sys.exit("error: decompressed data is not an imagefs (wrong format or corrupt image)")
    return bytes(out)


def walk_dir(fs):
    """Yield (path, data_offset, size) for every regular file in the imagefs."""
    image_size, hdr_dir_size, dir_offset = struct.unpack_from("<III", fs, 8)
    p = dir_offset
    while p < hdr_dir_size:
        size = struct.unpack_from("<H", fs, p)[0]
        if size == 0:
            break
        mode = struct.unpack_from("<I", fs, p + 8)[0]
        if (mode & 0xF000) == 0x8000:  # regular file
            foff, fsize = struct.unpack_from("<II", fs, p + 24)
            name = fs[p + 32:p + size].split(b"\0", 1)[0].decode("latin1")
            yield name, foff, fsize
        p += size


def main():
    ap = argparse.ArgumentParser(description="Extract files from a QNX mkifs image.")
    ap.add_argument("image", help="the .ifs image (e.g. tsd.mibstd2.hmi.ifs)")
    ap.add_argument("--list", action="store_true", help="list files, do not extract")
    ap.add_argument("--all", action="store_true", help="extract every file")
    ap.add_argument("--name", default="MIBHMI.jxe", help="basename to extract (default: MIBHMI.jxe)")
    ap.add_argument("--out", help="output directory (default: alongside the image)")
    args = ap.parse_args()

    raw = open(args.image, "rb").read()
    fs = decompress_imagefs(raw)
    files = list(walk_dir(fs))

    if args.list:
        for name, foff, fsize in files:
            print(f"{fsize:>10}  {name}")
        print(f"\n{len(files)} files")
        return

    outdir = args.out or os.path.dirname(os.path.abspath(args.image))
    os.makedirs(outdir, exist_ok=True)

    def write(name, foff, fsize):
        dest = os.path.join(outdir, os.path.basename(name))
        with open(dest, "wb") as fh:
            fh.write(fs[foff:foff + fsize])
        print(f"extracted {name} ({fsize} bytes) -> {dest}")

    if args.all:
        for name, foff, fsize in files:
            write(name, foff, fsize)
        return

    hits = [f for f in files if os.path.basename(f[0]) == args.name]
    if not hits:
        sys.exit(f"error: {args.name} not found. Try --list to see the contents.")
    for name, foff, fsize in hits:
        write(name, foff, fsize)


if __name__ == "__main__":
    main()
