#!/usr/bin/env python3
"""
UI delta check + composite comparison for the Islands chrome.

Two jobs:
  1. Sample the same landmarks (frame / island / selected stripe) in a rendered app screenshot and
     in the reference IDE screenshot (docs/reference/ide-islands-dark.png) and print RGB deltas
     against the measured targets from the brief.
  2. MEASURE the geometry — island gap width and island corner radius — with real detectors (no
     "by construction" hand-waving), and emit side-by-side 4x nearest-neighbour composite crops
     (app | reference) to build/ui-screenshots/compare-*.png so the radius/gap/stripe can be
     eyeballed and tuned.

Usage:  python3 scripts/ui_delta_check.py [app_png] [ref_png] [out_dir]
Defaults:
  app  = build/ui-screenshots/10-shell-dark.png
  ref  = docs/reference/ide-islands-dark.png
  out  = build/ui-screenshots
"""
import os
import sys
from math import sqrt

from PIL import Image

APP_PNG = sys.argv[1] if len(sys.argv) > 1 else "build/ui-screenshots/10-shell-dark.png"
REF_PNG = sys.argv[2] if len(sys.argv) > 2 else "docs/reference/ide-islands-dark.png"
OUT_DIR = sys.argv[3] if len(sys.argv) > 3 else "build/ui-screenshots"

# Measured targets from the brief (dark Islands).
TARGETS = {
    "frame": (0x26, 0x28, 0x2C),
    "island": (0x1E, 0x1F, 0x22),
    "stripe_selected": (0x3A, 0x3B, 0x3F),
}
ISLAND_GAP_TARGET = (2, 4)   # px
RADIUS_TARGET = (10, 12)     # px

# A rounded corner of radius R, measured along the 45-degree diagonal from the bounding-box corner,
# is crossed at t = R * (1 - 1/sqrt(2)); so R = t / (1 - 1/sqrt(2)).
DIAG_FACTOR = 1.0 / (1.0 - 1.0 / sqrt(2.0))  # ~ 3.4142


def load(path):
    return Image.open(path).convert("RGB")


def lum(c):
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def med(img, x0, y0, x1, y1, step=2):
    """Average of a rectangular sample (robust to a few text pixels)."""
    rs = gs = bs = n = 0
    for x in range(x0, x1, step):
        for y in range(y0, y1, step):
            r, g, b = img.getpixel((x, y))
            rs += r; gs += g; bs += b; n += 1
    n = max(n, 1)
    return (rs // n, gs // n, bs // n)


def delta(a, b):
    return tuple(a[i] - b[i] for i in range(3))


def island_mask(img, box, threshold=35.0):
    """Binary island/frame mask over a crop. In dark Islands the island surface (lum ~31) is darker
    than the frame (lum ~40), so an island pixel is one below an absolute luminance [threshold]. An
    absolute cutoff (not an auto midpoint) is used on purpose: crops that also contain text/content
    would otherwise drag an auto midpoint so far that frame pixels classify as island."""
    x0, y0, x1, y1 = box
    px = img.load()
    w, h = x1 - x0, y1 - y0
    mask = [[False] * w for _ in range(h)]
    for j in range(h):
        for i in range(w):
            mask[j][i] = lum(px[x0 + i, y0 + j]) < threshold
    return mask, (0, 0, threshold)


def measure_corner_radius(img, box):
    """Fit the island corner radius from the alpha/brightness profile along the diagonal.

    Finds the island's straight top edge (cy) and straight left edge (cx) inside the crop, then walks
    the 45-degree diagonal out from the bounding corner (cx, cy) until it enters the island; the
    diagonal distance t converts to radius via the quarter-arc relation t = R(1 - 1/sqrt2).
    Returns (radius_px, corner, t) or (None, None, None) if no island present.
    """
    x0, y0, x1, y1 = box
    mask, _ = island_mask(img, box)
    h = len(mask)
    w = len(mask[0]) if h else 0
    if w == 0 or h == 0:
        return None, None, None

    # left straight edge: first column whose island run reaches >=55% of crop height
    cx = None
    for i in range(w):
        col = sum(1 for j in range(h) if mask[j][i])
        if col >= 0.55 * h:
            cx = i
            break
    # top straight edge: first row whose island run reaches >=55% of crop width
    cy = None
    for j in range(h):
        row = sum(1 for i in range(w) if mask[j][i])
        if row >= 0.55 * w:
            cy = j
            break
    if cx is None or cy is None:
        return None, None, None

    # walk the diagonal from the bounding corner outward until we hit island
    t = 0
    while t < min(w - cx, h - cy) - 1:
        if mask[cy + t][cx + t]:
            break
        t += 1
    else:
        return None, (x0 + cx, y0 + cy), None
    radius = t * DIAG_FACTOR
    return round(radius, 1), (x0 + cx, y0 + cy), t


def find_gap(img, y, x_start, x_end):
    """Scan a row between two island regions and return (gap_width_px, gap_center_x): the widest
    bright (frame) run. Frame is lighter than island in dark Islands."""
    px = img.load()
    lums = [lum(px[x, y]) for x in range(x_start, x_end)]
    lo, hi = min(lums), max(lums)
    mid = (lo + hi) / 2.0
    best = 0
    best_center = None
    run = 0
    for i, l in enumerate(lums):
        if l > mid:  # frame
            run += 1
            if run > best:
                best = run
                best_center = x_start + i - run // 2
        else:
            run = 0
    return best, best_center


def nn_scale(img, factor=4):
    return img.resize((img.width * factor, img.height * factor), Image.NEAREST)


def crop(img, box):
    return img.crop(box)


def composite(app, ref, app_box, ref_box, out_path, sep=8):
    """Side-by-side app | reference crop, both nearest-neighbour scaled 4x, separated by a seam."""
    a = nn_scale(crop(app, app_box))
    r = nn_scale(crop(ref, ref_box))
    h = max(a.height, r.height)
    canvas = Image.new("RGB", (a.width + sep + r.width, h), (0, 0, 0))
    canvas.paste(a, (0, 0))
    canvas.paste(r, (a.width + sep, 0))
    canvas.save(out_path)
    return canvas.size


def main():
    app = load(APP_PNG)
    ref = load(REF_PNG)
    aw, ah = app.size
    rw, rh = ref.size
    os.makedirs(OUT_DIR, exist_ok=True)

    # ── Colour landmarks ───────────────────────────────────────────────────────────
    app_frame = med(app, 60, 1, 1080, 3)
    app_island = med(app, 120, 620, 700, 690)
    app_stripe = med(app, 31, 8, 34, 10)  # button background, clear of the centred glyph

    ref_frame = med(ref, rw // 2 - 40, 3, rw // 2 + 40, 8)
    ref_island = med(ref, int(rw * 0.75), int(rh * 0.55), int(rw * 0.85), int(rh * 0.70))
    ref_stripe = med(ref, 8, 560, 12, 566)  # selected stripe button bg (found at y~558-587)

    rows = [
        ("frame (title/status/gaps)", app_frame, ref_frame, TARGETS["frame"]),
        ("island (editor/tool body)", app_island, ref_island, TARGETS["island"]),
        ("selected stripe button", app_stripe, ref_stripe, TARGETS["stripe_selected"]),
    ]

    # ── Geometry (real detectors) ───────────────────────────────────────────────────
    # App: downloads island top-left corner is near (stripe ~40 + gap ~3).
    app_corner_box = (30, 0, 130, 90)
    app_radius, app_corner, app_t = measure_corner_radius(app, app_corner_box)
    _, gap_cx = find_gap(app, y=650, x_start=45, x_end=aw - 5)
    app_gap = find_gap(app, y=650, x_start=45, x_end=aw - 5)[0] if gap_cx else 0
    # Reference: tool-window island corner at x=41/y=42; editor|right-tool gap at x~1135.
    ref_corner_box = (30, 30, 130, 120)
    ref_radius, ref_corner, ref_t = measure_corner_radius(ref, ref_corner_box)
    ref_gap = find_gap(ref, y=200, x_start=1100, x_end=1200)[0]

    print(f"app = {APP_PNG} ({aw}x{ah})")
    print(f"ref = {REF_PNG} ({rw}x{rh})")
    print()
    print(f"{'landmark':28} {'app':>14} {'ref':>14} {'target':>14} {'d(app,tgt)':>14} {'d(app,ref)':>14}")
    print("-" * 102)
    for name, a, r, t in rows:
        print(f"{name:28} {str(a):>14} {str(r):>14} {str(t):>14} {str(delta(a, t)):>14} {str(delta(a, r)):>14}")
    print()
    print(f"{'metric':28} {'app':>10} {'ref':>10} {'target':>10}")
    print("-" * 62)
    print(f"{'island gap (px)':28} {app_gap:>10} {ref_gap:>10} {'2-4':>10}")
    print(f"{'island radius (px, diag fit)':28} {str(app_radius):>10} {str(ref_radius):>10} {'10-12':>10}")
    print(f"{'  (corner / diag t)':28} {str(app_corner)} t={app_t}   {str(ref_corner)} t={ref_t}")

    # ── Composite comparison crops (app | ref, 4x nearest-neighbour) ────────────────
    if gap_cx:
        app_gap_box = (max(0, gap_cx - 12), 560, min(aw, gap_cx + 12), 720)
    else:
        app_gap_box = (aw // 2 - 12, 560, aw // 2 + 12, 720)
    ref_gap_box = (1123, 150, 1147, 250)

    made = []
    made.append(("compare-corner", composite(app, ref, app_corner_box, ref_corner_box,
                                             os.path.join(OUT_DIR, "compare-corner.png"))))
    made.append(("compare-stripe", composite(app, ref, (2, 2, 42, 46), (2, 550, 42, 594),
                                             os.path.join(OUT_DIR, "compare-stripe.png"))))
    made.append(("compare-gap", composite(app, ref, app_gap_box, ref_gap_box,
                                          os.path.join(OUT_DIR, "compare-gap.png"))))
    print()
    for name, size in made:
        print(f"wrote {OUT_DIR}/{name}.png  {size}")


if __name__ == "__main__":
    main()
