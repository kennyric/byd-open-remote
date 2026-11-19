"""Generate scannable Companion APK QR PNG + copyright-free geometric EV icons for watch-gt4-store.

Usage:
  python scripts/generate_qr.py https://github.com/your-user/your-repo/releases/latest
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICONS_DIR = os.path.join(ROOT, "entry", "src", "main", "js", "default", "common", "icons")
MEDIA_DIR = os.path.join(ROOT, "entry", "src", "main", "resources", "base", "media")

EMERALD = "#10b981"
CYAN = "#38bdf8"
TEXT_PRIMARY = "#ffffff"
BG_DARK = "#0b1116"

QR_SPECS_M = {
    1: (21, 16, 10, []),
    2: (25, 28, 16, [6, 18]),
    3: (29, 44, 26, [6, 22]),
    4: (33, 64, 36, [6, 26]),  # Wait: V4-M uses 2 blocks in ISO 18004 (18 EC per block * 2)! Use V1-V3 single-block up to 42 chars cleanly.
}

GF_EXP = [0] * 512
GF_LOG = [0] * 256
_x = 1
for _i in range(255):
    GF_EXP[_i] = _x
    GF_LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    GF_EXP[_i] = GF_EXP[_i - 255]


def _gf_mul(a, b):
    return 0 if (a == 0 or b == 0) else GF_EXP[GF_LOG[a] + GF_LOG[b]]


def _rs_ec(data_bytes, ec_len):
    g = [1]
    for i in range(ec_len):
        root = GF_EXP[i]
        nxt = [0] * (len(g) + 1)
        for j, c in enumerate(g):
            nxt[j] ^= c
            nxt[j + 1] ^= _gf_mul(c, root)
        g = nxt
    buf = list(data_bytes) + [0] * ec_len
    for i in range(len(data_bytes)):
        coef = buf[i]
        if coef:
            for j in range(1, len(g)):
                buf[i + j] ^= _gf_mul(g[j], coef)
    return buf[len(data_bytes):]


def encode_qr_matrix(text):
    raw = text.encode("utf-8")
    ver = None
    for v in (1, 2, 3):
        if len(raw) + 2 <= QR_SPECS_M[v][1]:
            ver = v
            break
    if ver is None:
        raise ValueError(f"URL length ({len(raw)}) exceeds 42-byte V1-V3 single-block encoder limit; shorten URL or use a short release redirect.")

    size, data_cw, ec_cw, align = QR_SPECS_M[ver]
    bits = []

    def push_bits(val, n):
        for k in range(n - 1, -1, -1):
            bits.append((val >> k) & 1)

    push_bits(0b0100, 4)
    push_bits(len(raw), 8)
    for b in raw:
        push_bits(b, 8)
    push_bits(0, min(4, data_cw * 8 - len(bits)))
    while len(bits) % 8 != 0:
        bits.append(0)
    pad = [0xEC, 0x11]
    idx = 0
    while len(bits) < data_cw * 8:
        push_bits(pad[idx % 2], 8)
        idx += 1

    data_bytes = [int("".join(str(b) for b in bits[i:i + 8]), 2) for i in range(0, len(bits), 8)]
    ec_bytes = _rs_ec(data_bytes, ec_cw)
    stream = []
    for cw in data_bytes + ec_bytes:
        for k in range(7, -1, -1):
            stream.append((cw >> k) & 1)
    stream.extend([0] * (0 if ver == 1 else 7))

    mod = [[None] * size for _ in range(size)]
    is_func = [[False] * size for _ in range(size)]

    def set_func(r, c, val):
        if 0 <= r < size and 0 <= c < size:
            mod[r][c] = 1 if val else 0
            is_func[r][c] = True

    def draw_finder(r0, c0):
        for dr in range(-1, 8):
            for dc in range(-1, 8):
                r, c = r0 + dr, c0 + dc
                if not (0 <= r < size and 0 <= c < size):
                    continue
                if dr in (-1, 7) or dc in (-1, 7):
                    set_func(r, c, 0)
                elif dr in (0, 6) or dc in (0, 6) or (2 <= dr <= 4 and 2 <= dc <= 4):
                    set_func(r, c, 1)
                else:
                    set_func(r, c, 0)

    draw_finder(0, 0)
    draw_finder(0, size - 7)
    draw_finder(size - 7, 0)

    for i in range(8, size - 8):
        set_func(6, i, 1 if i % 2 == 0 else 0)
        set_func(i, 6, 1 if i % 2 == 0 else 0)

    for ar in align:
        for ac in align:
            if is_func[ar][ac]:
                continue
            for dr in range(-2, 3):
                for dc in range(-2, 3):
                    set_func(ar + dr, ac + dc, 1 if (abs(dr) == 2 or abs(dc) == 2 or (dr == 0 and dc == 0)) else 0)

    set_func(size - 8, 8, 1)
    for i in range(9):
        if i != 6:
            set_func(8, i, 0)
            set_func(i, 8, 0)
    for i in range(8):
        set_func(8, size - 1 - i, 0)
    for i in range(7):
        set_func(size - 1 - i, 8, 0)

    bidx = 0
    up = True
    col = size - 1
    while col > 0:
        if col == 6:
            col -= 1
        for r in (range(size - 1, -1, -1) if up else range(size)):
            for dc in (0, -1):
                c = col + dc
                if not is_func[r][c]:
                    mod[r][c] = stream[bidx] if bidx < len(stream) else 0
                    bidx += 1
        up = not up
        col -= 2

    def apply_format(grid, mask_id):
        rem = mask_id << 10
        gen = 0b10100110111
        for i in range(4, -1, -1):
            if (rem >> (i + 10)) & 1:
                rem ^= gen << i
        fmt = ((mask_id << 10) | rem) ^ 0b101010000010010
        ca = [(8, 0), (8, 1), (8, 2), (8, 3), (8, 4), (8, 5), (8, 7), (8, 8),
              (7, 8), (5, 8), (4, 8), (3, 8), (2, 8), (1, 8), (0, 8)]
        cb = [(size - 1 - i, 8) for i in range(7)] + [(8, size - 8 + i) for i in range(8)]
        for k in range(15):
            bit = (fmt >> (14 - k)) & 1
            grid[ca[k][0]][ca[k][1]] = bit
            grid[cb[k][0]][cb[k][1]] = bit

    def mask_cond(m, r, c):
        if m == 0: return (r + c) % 2 == 0
        if m == 1: return r % 2 == 0
        if m == 2: return c % 3 == 0
        if m == 3: return (r + c) % 3 == 0
        if m == 4: return ((r // 2) + (c // 3)) % 2 == 0
        if m == 5: return ((r * c) % 2 + (r * c) % 3) == 0
        if m == 6: return (((r * c) % 2 + (r * c) % 3) % 2) == 0
        return (((r + c) % 2 + (r * c) % 3) % 2) == 0

    def penalty(grid):
        score = 0
        for r in range(size):
            run = 1
            for c in range(1, size):
                if grid[r][c] == grid[r][c - 1]:
                    run += 1
                    if run == 5: score += 3
                    elif run > 5: score += 1
                else:
                    run = 1
        for c in range(size):
            run = 1
            for r in range(1, size):
                if grid[r][c] == grid[r - 1][c]:
                    run += 1
                    if run == 5: score += 3
                    elif run > 5: score += 1
                else:
                    run = 1
        return score

    best, best_sc = None, 10**9
    for m in range(8):
        cand = [row[:] for row in mod]
        for r in range(size):
            for c in range(size):
                if not is_func[r][c] and mask_cond(m, r, c):
                    cand[r][c] ^= 1
        apply_format(cand, m)
        sc = penalty(cand)
        if sc < best_sc:
            best_sc, best = sc, cand
    return best


def generate_qr_png(url, out_path, target_px=200):
    mat = encode_qr_matrix(url)
    n = len(mat)
    quiet = 2
    total = n + quiet * 2
    scale = max(1, target_px // total)
    side = total * scale
    img = Image.new("RGB", (side, side), "#ffffff")
    d = ImageDraw.Draw(img)
    for r in range(n):
        for c in range(n):
            if mat[r][c]:
                x0 = (c + quiet) * scale
                y0 = (r + quiet) * scale
                d.rectangle([x0, y0, x0 + scale - 1, y0 + scale - 1], fill="#0a0e14")
    img = img.resize((target_px, target_px), Image.Resampling.NEAREST)
    img.save(out_path, "PNG")


def draw_ev_emblem(draw, cx, cy, r, line_w=4):
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=EMERALD, width=line_w)
    pad = max(4, r // 4)
    draw.arc([cx - r + pad, cy - r + pad, cx + r - pad, cy + r - pad], start=35, end=315, fill=CYAN, width=line_w)
    bolt_h = r - pad - 2
    draw.polygon([
        (cx + max(2, r // 9), cy - bolt_h),
        (cx - max(3, r // 3), cy + 1),
        (cx + max(1, r // 12), cy + 1),
        (cx - max(2, r // 9), cy + bolt_h),
        (cx + max(3, r // 3), cy - 1),
        (cx - max(1, r // 12), cy - 1),
    ], fill=TEXT_PRIMARY)


def generate_brand_free_icons():
    # Transparent 48x48 header emblem
    em = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
    draw_ev_emblem(ImageDraw.Draw(em), 48, 48, 38, line_w=6)
    em.resize((48, 48), Image.Resampling.LANCZOS).save(os.path.join(ICONS_DIR, "ev_emblem.png"), "PNG")

    # Launcher icons (96x96 and 48x48)
    for fname, px in (("icon.png", 96), ("icon_small.png", 48)):
        canvas = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
        d = ImageDraw.Draw(canvas)
        d.ellipse([6, 6, 185, 185], fill=BG_DARK, outline="#223042", width=6)
        draw_ev_emblem(d, 96, 96, 64, line_w=10)
        canvas.resize((px, px), Image.Resampling.LANCZOS).save(os.path.join(MEDIA_DIR, fname), "PNG")


if __name__ == "__main__":
    os.makedirs(ICONS_DIR, exist_ok=True)
    os.makedirs(MEDIA_DIR, exist_ok=True)
    target_url = sys.argv[1] if len(sys.argv) > 1 else "https://github.com"
    generate_qr_png(target_url, os.path.join(ICONS_DIR, "qr_apk.png"), target_px=200)
    generate_brand_free_icons()
    print(f"Generated QR for {target_url} and copyright-free EV icons.")
