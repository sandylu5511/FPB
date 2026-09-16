#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成一张大尺寸测试照片（PNG），用于验证 FPB 的"原图直存"。

不依赖 PIL：PNG 格式手写（IHDR/IDAT/IEND + zlib）。
像素熵刻意做成"中等"：横向每 8px 一组伪随机扰动 ——
压得动（几 MB）但重编码成 2048px JPEG 后会缩小一个数量级，
足以区分"原图入库"与"入库前压缩"。
"""
import struct
import zlib

W, H = 4000, 3000
OUT = r"D:\MixiaVault\tools\test-photo.png"


class Lcg:
    """确定性伪随机，跨平台结果一致。"""

    def __init__(self, seed):
        self.s = seed & 0x7FFFFFFF

    def next(self):
        self.s = (self.s * 48271) % 0x7FFFFFFF
        return self.s


def main():
    rng = Lcg(20260915)
    noise = [rng.next() % 37 for _ in range(W)]  # 每 2px 共用一个扰动值

    rows = []
    for y in range(H):
        t = y / (H - 1)
        r0 = int(40 + 180 * t)
        g0 = int(200 - 120 * t)
        b0 = int(90 + 120 * t)
        row = bytearray(W * 3)
        for x in range(W):
            if x % 2 == 0:
                d = noise[x // 2]
            row[x * 3] = (r0 + d) & 0xFF
            row[x * 3 + 1] = (g0 + d) & 0xFF
            row[x * 3 + 2] = (b0 + d) & 0xFF
        rows.append(b"\x00" + bytes(row))

    raw = b"".join(rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 6))
           + chunk(b"IEND", b""))

    with open(OUT, "wb") as f:
        f.write(png)
    print(f"{OUT}: {len(png)} bytes ({len(png)/1024/1024:.2f} MB), {W}x{H}")


if __name__ == "__main__":
    main()
