# -*- coding: utf-8 -*-
"""把生成的方形 logo 转成 Android 自适应图标资源（抠底 / 居中 / 多密度）"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

S = 432
MARK = 0.72          # 标记占画布比例（安全区内）
BG_TOP = (12, 24, 48)
BG_BOT = (5, 9, 18)


def load_mark(path):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = im.load()
    corners = [px[3, 3], px[w - 4, 3], px[3, h - 4], px[w - 4, h - 4]]
    bg = tuple(sum(c[i] for c in corners) // 4 for i in range(3))

    rgb = im.convert("RGB")
    data = rgb.load()
    alpha = Image.new("L", (w, h))
    ad = alpha.load()
    for y in range(h):
        for x in range(w):
            r, g, b = data[x, y]
            d = max(abs(r - bg[0]), abs(g - bg[1]), abs(b - bg[2]))
            if d < 9:
                a = 0
            elif d > 32:
                a = 255
            else:
                a = int((d - 9) / 23.0 * 255)
            ad[x, y] = a

    # 只保留最大连通块（主体），丢掉装饰星之类的小块
    seen = bytearray(w * h)
    comps = []
    for yy in range(h):
        for xx in range(w):
            i = yy * w + xx
            if seen[i] or ad[xx, yy] < 8:
                continue
            stack = [(xx, yy)]
            seen[i] = 1
            pts = []
            while stack:
                cx, cy = stack.pop()
                pts.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = cx + dx, cy + dy
                    if 0 <= nx < w and 0 <= ny < h:
                        j = ny * w + nx
                        if not seen[j] and ad[nx, ny] >= 8:
                            seen[j] = 1
                            stack.append((nx, ny))
            comps.append(pts)

    if comps:
        comps.sort(key=len, reverse=True)
        biggest = len(comps[0])
        for pts in comps[1:]:
            if len(pts) < biggest * 0.5:
                for (cx, cy) in pts:
                    ad[cx, cy] = 0

    fg = im.copy()
    fg.putalpha(alpha)
    box = alpha.getbbox()
    if box:
        fg = fg.crop(box)
    return fg, bg


def background(size):
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        d.line((0, y, size, y),
               fill=tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3)))
    # 中心柔光
    glow = Image.new("L", (size, size), 0)
    gd = ImageDraw.Draw(glow)
    r = size * 0.42
    for i in range(16, 0, -1):
        gd.ellipse((size / 2 - r * i / 16, size * 0.42 - r * i / 16,
                    size / 2 + r * i / 16, size * 0.42 + r * i / 16),
                   fill=int(20 * (1 - i / 16.0) + 8))
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.12))
    img = Image.composite(Image.new("RGB", (size, size), (26, 52, 96)), img, glow)
    import random
    rnd = random.Random(20261102)
    sd = ImageDraw.Draw(img)
    for _ in range(max(6, size // 60)):
        x = rnd.randrange(int(size * 0.08), int(size * 0.92))
        y = rnd.randrange(int(size * 0.06), int(size * 0.94))
        rr = rnd.choice([1, 1, 1.5, 2]) * size / 432.0
        a = rnd.randint(50, 110)
        sd.ellipse((x - rr, y - rr, x + rr, y + rr), fill=(120 + a, 150 + a, 190 + a))
    return img


def place(mark, size):
    target = int(size * MARK)
    k = target / float(max(mark.width, mark.height))
    nw, nh = max(1, int(mark.width * k)), max(1, int(mark.height * k))
    art = mark.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(art, ((size - nw) // 2, (size - nh) // 2))
    return canvas


def main():
    src = sys.argv[1]
    res = r"D:\大肥鱼的文件夹\apkbuild\project\res"
    out_preview = sys.argv[2] if len(sys.argv) > 2 else None
    dry = len(sys.argv) > 3 and sys.argv[3] == "dry"

    mark, bgcol = load_mark(src)
    print("source bg:", bgcol, "mark:", mark.size)

    if dry:
        dens = {}
    dens = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    for dpi, size in dens.items():
        d = os.path.join(res, "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        background(size).convert("RGB").save(os.path.join(d, "ic_launcher_bg.png"))
        place(mark, size).save(os.path.join(d, "ic_launcher_fg.png"))

    if dry:
        legacy = {}
    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, size in legacy.items():
        d = os.path.join(res, "mipmap-" + dpi)
        fg = place(mark, size)
        bg = background(size).convert("RGBA")
        bg.alpha_composite(fg)
        bg.convert("RGB").save(os.path.join(d, "ic_launcher.png"))

    if out_preview:
        flat = background(432).convert("RGBA")
        flat.alpha_composite(place(mark, 432))
        flat = flat.convert("RGB")
        prev = Image.new("RGB", (980, 470), (18, 20, 26))
        prev.paste(flat.resize((300, 300), Image.LANCZOS), (30, 30))
        mask = Image.new("L", (900, 900), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, 899, 899), fill=255)
        circ = flat.copy().convert("RGBA")
        circ.putalpha(mask.resize((432, 432), Image.LANCZOS))
        prev.paste(circ.resize((300, 300), Image.LANCZOS), (350, 40), circ.resize((300, 300), Image.LANCZOS))
        sq = Image.new("L", (900, 900), 0)
        ImageDraw.Draw(sq).rounded_rectangle((0, 0, 899, 899), radius=200, fill=255)
        rnd = flat.copy().convert("RGBA")
        rnd.putalpha(sq.resize((432, 432), Image.LANCZOS))
        prev.paste(rnd.resize((300, 300), Image.LANCZOS), (670, 40), rnd.resize((300, 300), Image.LANCZOS))
        for i, s in enumerate([96, 72, 48]):
            prev.paste(flat.resize((s, s), Image.LANCZOS), (40 + i * 100, 360))
        prev.save(out_preview)
        print("preview:", out_preview)
    print("done")


if __name__ == "__main__":
    main()
