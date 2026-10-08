"""Вырезка стикера-лиса с ровного холодно-серого фона (генерации вне Gemini, 2026-10-08).

Заливка от краёв идёт по серому фону и тени (низкая насыщенность, не белое) и упирается в белую
кромку стикера — кромка остаётся, как у остальных артов. Эрозия альфы на 1 px снимает серую
бахрому сглаживания, лёгкий блюр — лесенку. Результат: обрезка по bbox, WebP с альфой.
Запуск: python3 cut-grey-sticker.py <src.jpg> <dst.webp> (нужны Pillow и numpy).
"""
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageFilter

WHITE_MIN = 245  # кромка стикера: все каналы не ниже — сюда заливка не заходит
GREY_SPREAD = 22  # фон и тень: разброс каналов меньше — серое, не цветное


def flood_background(rgb):
    h, w, _ = rgb.shape
    is_bg = (rgb.min(axis=2) < WHITE_MIN) & ((rgb.max(axis=2) - rgb.min(axis=2)) < GREY_SPREAD)
    seen = np.zeros((h, w), bool)
    queue = deque((y, x) for y in range(h) for x in (0, w - 1) if is_bg[y, x])
    queue.extend((y, x) for x in range(w) for y in (0, h - 1) if is_bg[y, x])
    for y, x in queue:
        seen[y, x] = True
    while queue:
        y, x = queue.popleft()
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if 0 <= ny < h and 0 <= nx < w and is_bg[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True
                queue.append((ny, nx))
    return seen


def main(src, dst):
    image = Image.open(src).convert("RGB")
    rgb = np.array(image).astype(int)
    alpha = np.where(flood_background(rgb), 0, 255).astype(np.uint8)
    mask = Image.fromarray(alpha).filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.7))
    out = Image.fromarray(np.dstack([rgb.astype(np.uint8), np.array(mask)]))
    out = out.crop(out.getbbox())
    out.save(dst, "WEBP", quality=92, method=6)
    print(dst, out.size)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
