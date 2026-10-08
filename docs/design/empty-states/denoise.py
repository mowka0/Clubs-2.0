import sys
import numpy as np, cv2
from PIL import Image
src, dst = sys.argv[1], sys.argv[2]
rgba = np.array(Image.open(src).convert('RGBA'))
rgb = rgba[..., :3]
# Медиана гасит одиночные пятнышки палитры, двусторонний фильтр сглаживает заливки, не размывая контур.
out = cv2.medianBlur(rgb, 5)
out = cv2.bilateralFilter(out, d=9, sigmaColor=40, sigmaSpace=7)
alpha = cv2.medianBlur(rgba[..., 3], 3)
Image.fromarray(np.dstack([out, alpha]), 'RGBA').save(dst)
