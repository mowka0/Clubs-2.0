"""Real-ESRGAN (RRDBNet) без basicsr: веса RealESRGAN_x4plus_anime_6B, RGB и альфа отдельно, тайлинг."""
import sys, os, math
import numpy as np, torch, torch.nn as nn, torch.nn.functional as F
from PIL import Image

class RDB(nn.Module):
    def __init__(s, nf=64, gc=32):
        super().__init__()
        s.conv1 = nn.Conv2d(nf, gc, 3, 1, 1); s.conv2 = nn.Conv2d(nf+gc, gc, 3, 1, 1); s.conv3 = nn.Conv2d(nf+2*gc, gc, 3, 1, 1)
        s.conv4 = nn.Conv2d(nf+3*gc, gc, 3, 1, 1); s.conv5 = nn.Conv2d(nf+4*gc, nf, 3, 1, 1); s.lrelu = nn.LeakyReLU(0.2, True)
    def forward(s, x):
        x1 = s.lrelu(s.conv1(x)); x2 = s.lrelu(s.conv2(torch.cat((x, x1), 1))); x3 = s.lrelu(s.conv3(torch.cat((x, x1, x2), 1)))
        x4 = s.lrelu(s.conv4(torch.cat((x, x1, x2, x3), 1))); x5 = s.conv5(torch.cat((x, x1, x2, x3, x4), 1)); return x5 * 0.2 + x
class RRDB(nn.Module):
    def __init__(s, nf, gc):
        super().__init__(); s.rdb1 = RDB(nf, gc); s.rdb2 = RDB(nf, gc); s.rdb3 = RDB(nf, gc)
    def forward(s, x): return s.rdb3(s.rdb2(s.rdb1(x))) * 0.2 + x
class RRDBNet(nn.Module):
    def __init__(s, nf=64, nb=6, gc=32):
        super().__init__()
        s.conv_first = nn.Conv2d(3, nf, 3, 1, 1); s.body = nn.Sequential(*[RRDB(nf, gc) for _ in range(nb)]); s.conv_body = nn.Conv2d(nf, nf, 3, 1, 1)
        s.conv_up1 = nn.Conv2d(nf, nf, 3, 1, 1); s.conv_up2 = nn.Conv2d(nf, nf, 3, 1, 1); s.conv_hr = nn.Conv2d(nf, nf, 3, 1, 1); s.conv_last = nn.Conv2d(nf, 3, 3, 1, 1); s.lrelu = nn.LeakyReLU(0.2, True)
    def forward(s, x):
        f = s.conv_first(x); f = s.conv_body(s.body(f)) + f
        f = s.lrelu(s.conv_up1(F.interpolate(f, scale_factor=2, mode='nearest'))); f = s.lrelu(s.conv_up2(F.interpolate(f, scale_factor=2, mode='nearest')))
        return s.conv_last(s.lrelu(s.conv_hr(f)))

dev = torch.device('mps' if torch.backends.mps.is_available() else 'cpu')
net = RRDBNet().to(dev).eval()
sd = torch.load('RealESRGAN_x4plus_anime_6B.pth', map_location='cpu', weights_only=True); net.load_state_dict(sd.get('params_ema', sd.get('params')), strict=True)

@torch.no_grad()
def up4(rgb: np.ndarray, tile=256, pad=16) -> np.ndarray:
    """rgb float32 HxWx3 [0,1] -> 4H x 4W x 3, тайлами с перекрытием."""
    h, w, _ = rgb.shape; out = np.zeros((h*4, w*4, 3), np.float32)
    x = torch.from_numpy(rgb).permute(2, 0, 1).unsqueeze(0).to(dev)
    for y0 in range(0, h, tile):
        for x0 in range(0, w, tile):
            y1, x1 = min(y0+tile, h), min(x0+tile, w)
            py0, px0, py1, px1 = max(0, y0-pad), max(0, x0-pad), min(h, y1+pad), min(w, x1+pad)
            o = net(x[:, :, py0:py1, px0:px1]).clamp(0, 1)[0].permute(1, 2, 0).cpu().numpy()
            out[y0*4:y1*4, x0*4:x1*4] = o[(y0-py0)*4:(y1-py0)*4, (x0-px0)*4:(x1-px0)*4]
    return out

def upscale(src, dst, target_h):
    im = Image.open(src).convert('RGBA'); a = np.array(im).astype(np.float32) / 255
    rgb, alpha = a[..., :3], a[..., 3:]
    # прозрачные области: цвет под нулевой альфой мусорный — заливаем ближайшим непрозрачным цветом грубо (средним), чтобы край не темнел
    fill = (rgb * alpha).sum((0, 1)) / max(alpha.sum(), 1); rgb = rgb * alpha + fill * (1 - alpha)
    rgb4 = up4(rgb); a4 = up4(np.repeat(alpha, 3, axis=2))[..., :1]
    out = Image.fromarray((np.concatenate([rgb4, a4], 2) * 255).round().astype(np.uint8), 'RGBA')
    if out.height > target_h: out = out.resize((round(out.width * target_h / out.height), target_h), Image.LANCZOS)
    out.save(dst, optimize=True); return out.size

if __name__ == '__main__':
    src, dst, th = sys.argv[1], sys.argv[2], int(sys.argv[3]); print(src, '->', dst, upscale(src, dst, th), 'dev', dev)
