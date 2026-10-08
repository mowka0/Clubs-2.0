import sys
import numpy as np
from PIL import Image
from scipy import ndimage

src, dst = sys.argv[1], sys.argv[2]
im = np.array(Image.open(src).convert('RGBA')).astype(np.float32)
alpha = im[..., 3]
# Фон = прозрачное, связанное с краем картинки; всё прозрачное внутри фигуры — дыры.
transparent = alpha < 128
labels, n = ndimage.label(transparent)
border = np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))
background = np.isin(labels, border[border > 0])
holes = transparent & ~background
# Полупрозрачная кайма вокруг дыр тоже внутренняя: расширяем маску на 2 px, но не в фон.
grow = ndimage.binary_dilation(holes, iterations=2) & ~background & (alpha < 250)
mask = holes | grow
a = (alpha[mask] / 255.0)[:, None]
im[mask, :3] = im[mask, :3] * a + 255.0 * (1 - a)  # белое, как было до снятия кромки
im[mask, 3] = 255
Image.fromarray(im.round().astype(np.uint8), 'RGBA').save(dst)
print('holes px', int(holes.sum()), 'mask px', int(mask.sum()), 'components', n)
