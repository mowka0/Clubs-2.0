"""Вырезка лиса из оригинала nano banana по пайплайну хэндоффа (empty-states-handoff § 5):
flood-fill белого от краёв (порог >216), кеинг холодно-серой тени в нижней зоне, эрозия альфы
MinFilter(5), блюр альфы 1.0; затем обрезка по bbox и масштаб до целевой высоты."""
import os, sys
import numpy as np
from PIL import Image, ImageFilter
from collections import deque

REPO = "/Users/ivanvarlamov/Desktop/Clubs 2.0"
DL = os.path.expanduser("~/Downloads")
PAIRS = {
  "fox-cafe":      f"{REPO}/fox/Gemini_Generated_Image_cttbcvcttbcvcttb.png",
  "fox-catalog":   f"{REPO}/fox/Gemini_Generated_Image_c5lzklc5lzklc5lz.png",
  "fox-chat":      f"{DL}/Gemini_Generated_Image_ljwbbhljwbbhljwb.png",
  "fox-error":     f"{REPO}/fox/Gemini_Generated_Image_splmrmsplmrmsplm.png",
  "fox-filter":    f"{DL}/Gemini_Generated_Image_cmtk57cmtk57cmtk.png",
  "fox-finances":  f"{REPO}/docs/design/empty-states/generated/fox-finances-src.png",
  "fox-interests": f"{REPO}/fox/Gemini_Generated_Image_ow6o82ow6o82ow6o.png",
  "fox-invite":    f"{REPO}/fox/Gemini_Generated_Image_i9z6i3i9z6i3i9z6.png",
  "fox-myclubs":   f"{REPO}/fox/Gemini_Generated_Image_oru3xyoru3xyoru3.png",
  "fox-stats":     f"{REPO}/fox/Gemini_Generated_Image_1ra07q1ra07q1ra0.png",
}
TARGET_H = 1800  # 2× текущих 900: на слайде интро ~400 css px × 3 dpr = 1200, запас на iPad

def flood_bg(arr_gray, thr=216):
    h, w = arr_gray.shape; bg = arr_gray > thr; seen = np.zeros_like(bg); dq = deque()
    for x in range(w):
        for y in (0, h-1):
            if bg[y,x] and not seen[y,x]: seen[y,x]=True; dq.append((y,x))
    for y in range(h):
        for x in (0, w-1):
            if bg[y,x] and not seen[y,x]: seen[y,x]=True; dq.append((y,x))
    while dq:
        y,x = dq.popleft()
        for ny,nx in ((y-1,x),(y+1,x),(y,x-1),(y,x+1)):
            if 0<=ny<h and 0<=nx<w and bg[ny,nx] and not seen[ny,nx]: seen[ny,nx]=True; dq.append((ny,nx))
    return seen

def cut(src):
    im = Image.open(src).convert("RGB"); rgb = np.array(im).astype(int); h, w, _ = rgb.shape
    gray = np.array(im.convert("L"))
    bg = flood_bg(gray)
    # тень: холодный серый, только нижняя треть, связанная с фоном не обязательна — ключ по цвету
    r, g, b = rgb[...,0], rgb[...,1], rgb[...,2]
    shadow = (r < 244) & (np.abs(r - b) < 14) & (np.abs(r - g) < 14) & (r > 150) & (np.arange(h)[:,None] > h*0.66)
    alpha = np.where(bg | shadow, 0, 255).astype(np.uint8)
    a = Image.fromarray(alpha, "L").filter(ImageFilter.MinFilter(5)).filter(ImageFilter.GaussianBlur(1.0))
    out = Image.fromarray(np.dstack([rgb.astype(np.uint8), np.array(a)]), "RGBA")
    bbox = out.getbbox(); out = out.crop(bbox)
    return out

os.makedirs("cut", exist_ok=True)
for name, src in PAIRS.items():
    im = cut(src)
    scale = min(1.0, TARGET_H / im.height)  # не апскейлим: маленькие остаются как есть
    if scale < 1.0: im = im.resize((round(im.width*scale), round(im.height*scale)), Image.LANCZOS)
    im.save(f"cut/{name}.png", optimize=True)
    q = im.quantize(colors=256, method=Image.Quantize.FASTOCTREE); q.save(f"cut/{name}-png8.png", optimize=True)
    im.save(f"cut/{name}.webp", quality=88, method=6)
    print(f"{name:14s} {im.width}x{im.height}  png {os.path.getsize(f'cut/{name}.png')//1024} KB  png8 {os.path.getsize(f'cut/{name}-png8.png')//1024} KB  webp {os.path.getsize(f'cut/{name}.webp')//1024} KB")
