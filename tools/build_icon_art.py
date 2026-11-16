# -*- coding: utf-8 -*-
"""把整张方形插画变成自适应图标（背景层整图，前景透明）"""
import os
import sys
from PIL import Image, ImageChops, ImageDraw

S = 432
FILL = 0.88          # 图像在画布中的占比，留出遮罩裁切余量


def main():
    src = sys.argv[1]
    res = r"D:\大肥鱼的文件夹\apkbuild\project\res"
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    dry = len(sys.argv) > 3 and sys.argv[3] == "dry"

    im = Image.open(src).convert("RGB")
    w, h = im.size
    px = im.load()
    corners = [px[2, 2], px[w - 3, 2], px[2, h - 3], px[w - 3, h - 3]]
    bg = tuple(sum(c[i] for c in corners) // 4 for i in range(3))
    # 裁到内容外接框（含青色圆环），保证构图居中
    diff = ImageChops.difference(im, Image.new("RGB", im.size, bg)).convert("L")
    box = diff.point(lambda v: 255 if v > 18 else 0).getbbox()
    if box:
        im = im.crop(box)
    print("corner bg:", bg, "content:", im.size)

    def tile(size):
        canvas = Image.new("RGB", (size, size), bg)
        side = int(size * FILL)
        art = im.resize((side, side), Image.LANCZOS)
        canvas.paste(art, ((size - side) // 2, (size - side) // 2))
        return canvas

    dens = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

    if not dry:
        for dpi, size in dens.items():
            d = os.path.join(res, "mipmap-" + dpi)
            os.makedirs(d, exist_ok=True)
            tile(size).save(os.path.join(d, "ic_launcher_bg.png"))
            fgp = os.path.join(d, "ic_launcher_fg.png")
            if os.path.exists(fgp):
                os.remove(fgp)
        for dpi, size in legacy.items():
            d = os.path.join(res, "mipmap-" + dpi)
            tile(size).save(os.path.join(d, "ic_launcher.png"))

    if preview:
        base = tile(432)
        prev = Image.new("RGB", (980, 470), (18, 20, 26))
        prev.paste(base.resize((300, 300), Image.LANCZOS), (30, 30))
        circ = Image.new("L", (900, 900), 0)
        ImageDraw.Draw(circ).ellipse((0, 0, 899, 899), fill=255)
        sq = Image.new("L", (900, 900), 0)
        ImageDraw.Draw(sq).rounded_rectangle((0, 0, 899, 899), radius=200, fill=255)
        for i, m in enumerate([circ, sq]):
            layer = base.convert("RGBA")
            layer.putalpha(m.resize((432, 432), Image.LANCZOS))
            prev.paste(layer.resize((300, 300), Image.LANCZOS), (350 + i * 320, 40),
                       layer.resize((300, 300), Image.LANCZOS))
        for i, s in enumerate([96, 72, 48]):
            prev.paste(base.resize((s, s), Image.LANCZOS), (40 + i * 100, 360))
        prev.save(preview)
        print("preview:", preview)
    print("done")


if __name__ == "__main__":
    main()
