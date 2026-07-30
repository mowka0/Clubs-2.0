#!/usr/bin/env python3
"""Вырезка фона у арта-стикера — версия с сохранением белой рамки.

Почему не одна заливка. У стикера структура «фон(255) → серая тень(213–247) → БЕЛАЯ рамка(255)
→ персонаж». Одна заливка с порогом «светлее 216» считает фоном и тень, и рамку, поэтому
достаточно одной протечки в кольце тени — и рамка съедается целиком (так и вышло на
fox-finances-src). Поэтому две ступени:

  1) заливка от краёв ТОЛЬКО по чистому белому (min ≥ 250) — снимает фон, упирается в тень;
  2) расширение уже снятой области в НЕЙТРАЛЬНО-СЕРОЕ (min ≥ 190, |r−g|,|g−b| ≤ 10) —
     снимает кольцо тени и упирается в белую рамку, которая не серая.

Дальше как раньше: эрозия альфы против ореола, мягкий край, снос мелких islands-артефактов,
кроп по содержимому, PNG8.
"""
import sys
from collections import deque
from PIL import Image, ImageFilter

SRC, DST = sys.argv[1], sys.argv[2]
PURE_WHITE = 250            # ступень 1: что считаем чистым фоном
GRAY_MIN, GRAY_TOL = 190, 10  # ступень 2: светлое и почти нейтральное = запечённая тень
SPECK_MAX_PX = 400          # островки мельче — мусор генерации, сносим

im = Image.open(SRC).convert('RGBA')
w, h = im.size
px = im.load()
opaque = bytearray([1]) * 0 or bytearray(w * h)
for i in range(w * h):
    opaque[i] = 1


def rgb(x, y):
    r, g, b, _ = px[x, y]
    return r, g, b


def is_pure_white(x, y):
    return min(rgb(x, y)) >= PURE_WHITE


def is_gray(x, y):
    """Запечённая тень: светлая и почти нейтральная, но НЕ чисто белая.

    Верхняя граница обязательна: без неё белая рамка стикера (255,255,255) тоже проходит
    проверку на нейтральность, и вторая ступень съедает её вслед за тенью.
    """
    r, g, b = rgb(x, y)
    m = min(r, g, b)
    return GRAY_MIN <= m < PURE_WHITE and abs(r - g) <= GRAY_TOL and abs(g - b) <= GRAY_TOL


def flood(seed_test, expand_test):
    """Заливка от краёв: сначала сеем по seed_test, растём по expand_test."""
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if opaque[y * w + x] and seed_test(x, y):
                opaque[y * w + x] = 0
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if opaque[y * w + x] and seed_test(x, y):
                opaque[y * w + x] = 0
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and opaque[ny * w + nx] and expand_test(nx, ny):
                opaque[ny * w + nx] = 0
                q.append((nx, ny))


# Ступень 1 — фон.
flood(is_pure_white, is_pure_white)
# Ступень 2 — тень: растём из уже снятого, но только по нейтрально-серому.
q = deque((x, y) for y in range(h) for x in range(w) if not opaque[y * w + x])
while q:
    x, y = q.popleft()
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < w and 0 <= ny < h and opaque[ny * w + nx] and is_gray(nx, ny):
            opaque[ny * w + nx] = 0
            q.append((nx, ny))

# Мелкие островки (пылинки генерации, оторванные от силуэта) — в мусор.
seen = bytearray(w * h)
specks = 0
for start in range(w * h):
    if not opaque[start] or seen[start]:
        continue
    comp, q = [], deque([start])
    seen[start] = 1
    while q:
        i = q.popleft()
        comp.append(i)
        x, y = i % w, i // w
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            j = ny * w + nx
            if 0 <= nx < w and 0 <= ny < h and opaque[j] and not seen[j]:
                seen[j] = 1
                q.append(j)
    if len(comp) <= SPECK_MAX_PX:
        specks += 1
        for i in comp:
            opaque[i] = 0

mask = Image.new('L', (w, h))
mask.putdata([255 if v else 0 for v in opaque])
mask = mask.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.8))
im.putalpha(mask)

im = im.crop(im.getbbox())
im.convert('RGBA').quantize(colors=256, method=Image.Quantize.FASTOCTREE).save(DST, optimize=True)
print(f'{w}x{h} → {im.size[0]}x{im.size[1]}, снято мелких островков: {specks}')
