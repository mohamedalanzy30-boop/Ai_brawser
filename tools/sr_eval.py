"""Measure what the SR model really does for 360p..660p sources (run in CI: needs the model, ffmpeg, onnxruntime, lpips).
Ground truth: 1080p frames. Each source height: GT -> downscale -> H.264 (like a streaming site) -> decode -> upscale back to 1080p.
Methods: bicubic (what the screen does), 'fit360' (current app: squeeze into 640x360, model x4), 'native' (model at the source size),
'native_q' (same, A16W8 quantized like on the NPU). Reports PSNR / SSIM (higher = better) and LPIPS (lower = better, the perceptual one)."""
import sys, os, json, time, subprocess
import numpy as np
from PIL import Image
import onnxruntime as ort

model, ffmpeg, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
qmodels = dict(a.split('=') for a in sys.argv[4:])  # height=path of quantized fixed-size models
os.makedirs(outdir, exist_ok=True)
sess = ort.InferenceSession(model, providers=['CPUExecutionProvider'])
qs = {int(h): ort.InferenceSession(p, providers=['CPUExecutionProvider']) for h, p in qmodels.items()}

def run(s, img):  # img HxWx3 uint8 -> 4x uint8
    x = (img.astype(np.float32) / 255).transpose(2, 0, 1)[None]
    y = s.run(None, {s.get_inputs()[0].name: x})[0][0]
    return (np.clip(y.transpose(1, 2, 0), 0, 1) * 255 + 0.5).astype(np.uint8)

def rs(img, w, h, f=Image.BICUBIC): return np.asarray(Image.fromarray(img).resize((w, h), f))

def ff_frames(path, n):
    raw = subprocess.run([ffmpeg, '-loglevel', 'error', '-i', path, '-vf', "select='not(mod(n\\,40))',scale=1920:1080", '-fps_mode', 'vfr', '-frames:v', str(n), '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, 1080, 1920, 3)

def degrade(frames, h):  # downscale + h264 crf 26, like a 360p..660p stream
    w = int(round(h * 16 / 9 / 2)) * 2
    src = os.path.join(outdir, 'tmp.rgb'); open(src, 'wb').write(frames.tobytes())
    enc = os.path.join(outdir, f'tmp{h}.mp4')
    subprocess.run([ffmpeg, '-loglevel', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', '1920x1080', '-r', '24', '-i', src, '-vf', f'scale={w}:{h}:flags=lanczos', '-c:v', 'libx264', '-crf', '26', '-preset', 'medium', '-pix_fmt', 'yuv420p', enc], check=True)
    raw = subprocess.run([ffmpeg, '-loglevel', 'error', '-i', enc, '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, h, w, 3)

def y(img): return img.astype(np.float64) @ [0.299, 0.587, 0.114]
def psnr(a, b): m = np.mean((y(a) - y(b)) ** 2); return 10 * np.log10(255 ** 2 / max(m, 1e-9))
def ssim(a, b):
    from scipy.ndimage import gaussian_filter as g
    a, b = y(a), y(b); C1, C2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    ma, mb = g(a, 1.5), g(b, 1.5); sa, sb, sab = g(a * a, 1.5) - ma ** 2, g(b * b, 1.5) - mb ** 2, g(a * b, 1.5) - ma * mb
    return float(np.mean(((2 * ma * mb + C1) * (2 * sab + C2)) / ((ma ** 2 + mb ** 2 + C1) * (sa + sb + C2))))
import torch, lpips
lp = lpips.LPIPS(net='alex', verbose=False)
def lpd(a, b):
    t = lambda x: torch.from_numpy(x.astype(np.float32) / 127.5 - 1).permute(2, 0, 1)[None]
    with torch.no_grad(): return float(lp(t(a[::2, ::2]), t(b[::2, ::2])))  # half res keeps it quick

def fit360(img):  # what the app does now: aspect-fit into 640x360 (no bars needed for 16:9)
    return rs(img, 640, 360, Image.BILINEAR) if img.shape[0] != 360 else img

gt = np.concatenate([ff_frames(p, 3) for p in sys.stdin.read().split()])
print('ground-truth frames', len(gt))
res = {}
for h in (360, 480, 540, 660):
    lo = degrade(gt, h)
    acc = {}
    for i, (g, l) in enumerate(zip(gt, lo)):
        t0 = time.time(); outs = {'bicubic': rs(l, 1920, 1080)}
        t1 = time.time(); outs['fit360'] = rs(run(sess, fit360(l)), 1920, 1080, Image.LANCZOS); t2 = time.time()
        outs['native'] = rs(run(sess, l), 1920, 1080, Image.LANCZOS); t3 = time.time()
        if h in qs: outs['native_q'] = rs(run(qs[h], l), 1920, 1080, Image.LANCZOS)
        for k, o in outs.items():
            r = acc.setdefault(k, {'psnr': [], 'ssim': [], 'lpips': []})
            r['psnr'].append(psnr(o, g)); r['ssim'].append(ssim(o, g)); r['lpips'].append(lpd(o, g))
        acc.setdefault('_cpu_s', {'fit360': [], 'native': []}); acc['_cpu_s']['fit360'].append(t2 - t1); acc['_cpu_s']['native'].append(t3 - t2)
        if i in (1, 4):  # crops for the eye: 640x360 region of the 1080p result
            cy, cx = 360, 640
            row = [g[cy:cy + 360, cx:cx + 640]] + [outs[k][cy:cy + 360, cx:cx + 640] for k in outs]
            Image.fromarray(np.concatenate(row, 1)).save(os.path.join(outdir, f'crop_{h}p_{i}_gt-' + '-'.join(outs) + '.png'))
    res[h] = {k: {m: round(float(np.mean(v)), 4) for m, v in d.items()} for k, d in acc.items()}
    print(h, json.dumps(res[h]), flush=True)
json.dump(res, open(os.path.join(outdir, 'results.json'), 'w'), indent=1)
