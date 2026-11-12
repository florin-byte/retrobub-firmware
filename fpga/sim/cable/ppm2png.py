#!/usr/bin/env python3
"""GB-150: convert the simulator's 160x144 P6 screenshots to PNG (standard library only), 2x."""
import struct, sys, zlib

def convert(path):
    data = open(path, 'rb').read()
    assert data[:2] == b'P6'
    parts = data.split(b'\n', 3)
    w, h = map(int, parts[1].split())
    pix = parts[3]
    rows = []
    for y in range(h):
        row = pix[y * w * 3:(y + 1) * w * 3]
        wide = b''.join(row[i:i + 3] * 2 for i in range(0, len(row), 3))
        rows += [b'\0' + wide] * 2
    def chunk(tag, body):
        return struct.pack('>I', len(body)) + tag + body + struct.pack('>I', zlib.crc32(tag + body))
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w * 2, h * 2, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b''))
    open(path[:-4] + '.png', 'wb').write(png)

for p in sys.argv[1:]:
    convert(p)
