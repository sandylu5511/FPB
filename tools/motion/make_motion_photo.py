#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""合成一张「实况照片」（Google Motion Photo / 三星动态照片）用于验收。

## 为什么需要它

真机上的实况照片没法随手造：它是一张 JPEG 尾部接了 MP4，并且**必须**靠 XMP 元数据
声明"我是实况照片"，系统 MediaProvider 才会认。手工拼一份，才能把
"导入后影片段还在不在"这件事变成可复算的数字。

## 结构

```
SOI
APP0 JFIF
APP1 XMP   ← 声明 GCamera:MotionPhoto / MicroVideo 与 Container:Directory
...其余 JPEG 段...
SOS + 扫描数据
EOI
MP4        ← 直接接在文件末尾，偏移量由 XMP 里的 Length 声明
```

两种声明都写上，因为不同厂商各认一套：
- 老格式：`GCamera:MicroVideo=1` + `GCamera:MicroVideoOffset=<影片字节数>`
- 新格式（Android 13+ AOSP 看这个）：`Container:Directory` 里列出
  `Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length=<图片字节数>`
  与 `Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length=<影片字节数>`

`Item:Length` 里那个"图片字节数"包含 XMP 自身的长度，而 XMP 的长度又取决于数字的位数，
所以用不动点迭代把它算准 —— 差一个字节，系统就认为这不是合法的实况照片。
"""
import io
import sys

BASE = r"D:\MixiaVault\tools\motion\base.jpg"
CLIP = r"D:\MixiaVault\tools\motion\clip.mp4"
OUT = r"D:\MixiaVault\tools\motion\motion-photo.jpg"

XMP_HEADER = b"http://ns.adobe.com/xap/1.0/\x00"

TEMPLATE = """<?xpacket begin="\ufeff" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    GCamera:MotionPhoto="1"
    GCamera:MotionPhotoVersion="1"
    GCamera:MotionPhotoPresentationTimestampUs="1500000"
    GCamera:MicroVideo="1"
    GCamera:MicroVideoVersion="1"
    GCamera:MicroVideoOffset="{video_len}"
    GCamera:MicroVideoPresentationTimestampUs="1500000">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="{image_len}" Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="{video_len}" Item:Padding="0"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""


def build_xmp_segment(image_len: int, video_len: int) -> bytes:
    payload = XMP_HEADER + TEMPLATE.format(
        image_len=image_len, video_len=video_len
    ).encode("utf-8")
    # APP1 段：FFE1 + 2 字节长度（含长度字段自身）+ 负载
    return b"\xff\xe1" + (len(payload) + 2).to_bytes(2, "big") + payload


def insert_after_soi(jpeg: bytes, segment: bytes) -> bytes:
    """把 APP1 插到 SOI 之后、APP0 之前 —— 这是 XMP 的常规位置。"""
    if jpeg[:2] != b"\xff\xd8":
        raise SystemExit("底图不是 JPEG")
    return jpeg[:2] + segment + jpeg[2:]


def main():
    jpeg = io.open(BASE, "rb").read()
    clip = io.open(CLIP, "rb").read()

    # 不动点迭代：段长取决于图片总长，图片总长又取决于段长。
    image_len = len(jpeg)
    for _ in range(8):
        seg = build_xmp_segment(image_len, len(clip))
        candidate = len(jpeg) + len(seg)
        if candidate == image_len:
            break
        image_len = candidate
    else:
        raise SystemExit("XMP 长度没有收敛")

    seg = build_xmp_segment(image_len, len(clip))
    out = insert_after_soi(jpeg, seg)
    if len(out) != image_len:
        raise SystemExit(f"自检失败：算出 {image_len}，实际 {len(out)}")
    out = out + clip

    io.open(OUT, "wb").write(out)
    print(f"底图           {len(jpeg):>8,} B")
    print(f"XMP 段         {len(seg):>8,} B")
    print(f"图片部分合计   {image_len:>8,} B  ← 应等于 XMP 里 Primary 的 Length")
    print(f"影片部分       {len(clip):>8,} B  ← 应等于 XMP 里 MotionPhoto 的 Length")
    print(f"实况照片总计   {len(out):>8,} B")
    print(f"输出           {OUT}")


if __name__ == "__main__":
    sys.exit(main())
