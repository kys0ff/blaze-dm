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
  3. Trace the ambient title-bar glow (see `gradient_report`): the IDE glow is *project-colored*,
     so comparing absolute pixels between a teal IDE and an orange Blaze is meaningless. What has to
     match is the SHAPE — the tint over the frame color at the same relative position — so every
     gradient landmark is printed as a delta over the sampled base frame color, plus a hue-free
     luminance delta for the apples-to-apples comparison.

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

# The brief's gradient sample positions, as fractions of the window width (measured at 5/60/110/
# 450/600/750 px on the 1440px-wide reference; the rest pin down the peak and the zero crossing).
GRADIENT_X_FRACTIONS = [
    ("x=5", 5 / 1440), ("x=20", 20 / 1440), ("x=60", 60 / 1440), ("x=110 peak", 110 / 1440),
    ("x=150", 150 / 1440), ("x=200", 200 / 1440), ("x=300", 300 / 1440), ("x=450", 450 / 1440),
    ("x=600", 600 / 1440), ("x=700", 700 / 1440), ("x=750 zero", 750 / 1440),
]
# How far below the title band the glow is tracked down the left stripe/gap (the brief says it is
# back to base by y~250-300, measured from the top of the frame).
GRADIENT_Y_BELOW_BAND = [1, 20, 60, 120, 180, 240, 300, 360]

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


# ── Ambient gradient ────────────────────────────────────────────────────────────────

def base_frame_color(img):
    """The glow-free frame color, sampled at the bottom of the window: the status bar sits on the
    frame far below the glow's reach, so the base is whatever the app actually renders rather than
    an assumed constant (and a wrong base would silently shift every gradient landmark)."""
    w, h = img.size
    return med(img, int(w * 0.60), h - 8, int(w * 0.80), h - 4)


def title_band_height(img, limit=120):
    """Height of the title band: the first row that turns into an island, scanned at 0.9W where
    neither image has window-control glyphs. The glow keeps that column bright, so the cutoff is
    the island surface (lum < 35) and not the tint."""
    px = img.load()
    w, h = img.size
    x = int(w * 0.9)
    for y in range(2, min(h, limit)):
        if lum(px[x, y]) < 35.0:
            return y
    return 43


def median_box(img, x, y, half=2, xlo=None, xhi=None, ylo=None, yhi=None):
    """Per-channel median over a small box. Median, not mean, on purpose: the reference paints its
    project icon and window title straight over the glow, and a mean would read those light glyphs
    as if they were tint."""
    px = img.load()
    w, h = img.size
    xs = range(max(0, x - half) if xlo is None else xlo,
               min(w, x + half + 1) if xhi is None else xhi)
    ys = range(max(1, y - half) if ylo is None else ylo,
               min(h, y + half + 1) if yhi is None else yhi)
    out = []
    for ch in range(3):
        vals = sorted(px[xp, yp][ch] for xp in xs for yp in ys)
        out.append(vals[len(vals) // 2] if vals else 0)
    return tuple(out)


def band_tint(img, band, x, base):
    """Glow tint (delta over [base]) at column [x] inside the title band, sampled as the median of
    the whole band column so the horizontal profile is measured, not the glyph on top of it."""
    return delta(median_box(img, x, band // 2, xlo=x - 2, xhi=x + 3, ylo=2, yhi=band - 1), base)


def vert_tint(img, x, y, base):
    """Glow tint (delta over [base]) at the stripe|island seam, a few pixels around (x, y)."""
    return delta(median_box(img, x, y, half=2), base)


def gap_column_x(img, band, y):
    """x of the frame-colored seam just left of the first island at row [y]. Below the band the
    stripe is filled with buttons, so this gap is the one column that is frame color at the same
    structural place in both images — which is what makes the vertical samples comparable."""
    px = img.load()
    w, h = img.size
    y = min(h - 3, max(band + 1, y))
    for x in range(1, min(w, 140)):
        run = 0
        while x + run < w and lum(px[x + run, y]) < 35.0:
            run += 1
        if run >= 6:  # first sustained island run = the island's left edge
            return max(1, x - 2)
    return 20


def gradient_report(app, ref):
    """Trace the IDE's ambient glow in both images: tint over the base frame color at the same
    relative positions, horizontally across the title band and vertically down the left seam.
    Hue is deliberately not compared (Blaze tints with the flame orange, the reference with a
    project teal); the magnitude column is the hue-free Δluminance, and that is what has to match."""
    base_a, base_r = base_frame_color(app), base_frame_color(ref)
    band_a, band_r = title_band_height(app), title_band_height(ref)
    print(f"gradient base frame: app {base_a} (target {TARGETS['frame']})  ref {base_r}")
    print(f"gradient title band: app {band_a}px  ref {band_r}px")
    print()

    header = f"{'landmark':14} {'app tint':>14} {'ref tint':>14} {'app dLum':>9} {'ref dLum':>9} {'|diff|':>7}"
    print("horizontal, inside the title band (x as a fraction of the window width)")
    print(header)
    print("-" * len(header))
    worst = 0
    for name, fx in GRADIENT_X_FRACTIONS:
        ta = band_tint(app, band_a, int(fx * app.width), base_a)
        tr = band_tint(ref, band_r, int(fx * ref.width), base_r)
        la, lr = lum(ta), lum(tr)
        worst = max(worst, abs(la - lr))
        print(f"{name:14} {str(ta):>14} {str(tr):>14} {la:>9.1f} {lr:>9.1f} {abs(la - lr):>7.1f}")

    seam_a = gap_column_x(app, band_a, band_a + 8)
    seam_r = gap_column_x(ref, band_r, band_r + 8)
    print()
    print(f"vertical, down the stripe/island seam (app x={seam_a}, ref x={seam_r}), y from the top")
    print(header)
    print("-" * len(header))
    for dy in GRADIENT_Y_BELOW_BAND:
        ta = vert_tint(app, seam_a, band_a + dy, base_a)
        tr = vert_tint(ref, seam_r, band_r + dy, base_r)
        la, lr = lum(ta), lum(tr)
        worst = max(worst, abs(la - lr))
        print(f"{f'y=band+{dy}':14} {str(ta):>14} {str(tr):>14} {la:>9.1f} {lr:>9.1f} {abs(la - lr):>7.1f}")
    print()
    print(f"gradient: worst hue-free magnitude diff across landmarks = {worst:.1f} luminance units "
          "(in form, a few units is a pass; >8 means the falloff shape drifted)")
    return worst


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
    # The shell shot starts with a synthetic title band (the real window paints the glow behind a
    # transparent Islands bar), so every app coordinate below is measured from `band`, not from 0.
    band = title_band_height(app)
    # The frame landmark moves to the glow-free bottom strip: the title band is now tinted on
    # purpose, so sampling it for the flat frame color would report the glow as a frame error.
    app_frame = base_frame_color(app)
    app_island = med(app, 120, ah - 200, 700, ah - 140)   # table body, above the status bar
    app_stripe = med(app, 31, band + 8, 34, band + 10)  # button background, clear of the centred glyph

    ref_frame = base_frame_color(ref)
    ref_island = med(ref, int(rw * 0.75), int(rh * 0.55), int(rw * 0.85), int(rh * 0.70))
    ref_stripe = med(ref, 8, 560, 12, 566)  # selected stripe button bg (found at y~558-587)

    rows = [
        ("frame (title/status/gaps)", app_frame, ref_frame, TARGETS["frame"]),
        ("island (editor/tool body)", app_island, ref_island, TARGETS["island"]),
        ("selected stripe button", app_stripe, ref_stripe, TARGETS["stripe_selected"]),
    ]

    # ── Geometry (real detectors) ───────────────────────────────────────────────────
    # App: downloads island top-left corner is near (stripe ~40 + gap ~3), just under the band.
    app_corner_box = (30, band, 130, band + 90)
    app_radius, app_corner, app_t = measure_corner_radius(app, app_corner_box)
    # Sampled from the bottom up so the row is inside the table whatever the band height is.
    gap_y = ah - 70
    _, gap_cx = find_gap(app, y=gap_y, x_start=45, x_end=aw - 5)
    app_gap = find_gap(app, y=gap_y, x_start=45, x_end=aw - 5)[0] if gap_cx else 0
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
    print()

    # ── Ambient gradient profile ────────────────────────────────────────────────────
    gradient_report(app, ref)

    # ── Composite comparison crops (app | ref, 4x nearest-neighbour) ────────────────
    if gap_cx:
        app_gap_box = (max(0, gap_cx - 12), ah - 160, min(aw, gap_cx + 12), ah - 30)
    else:
        app_gap_box = (aw // 2 - 12, ah - 160, aw // 2 + 12, ah - 30)
    ref_gap_box = (1123, 150, 1147, 250)

    made = []
    made.append(("compare-corner", composite(app, ref, app_corner_box, ref_corner_box,
                                             os.path.join(OUT_DIR, "compare-corner.png"))))
    made.append(("compare-stripe", composite(app, ref, (2, band + 2, 42, band + 46), (2, 550, 42, 594),
                                             os.path.join(OUT_DIR, "compare-stripe.png"))))
    made.append(("compare-gap", composite(app, ref, app_gap_box, ref_gap_box,
                                          os.path.join(OUT_DIR, "compare-gap.png"))))
    # The glow's corner: the band's left half plus the stripe/seam below it, where the whole
    # horizontal peak and the start of the vertical decay are visible at once.
    made.append(("compare-gradient", composite(app, ref, (0, 0, 240, 160), (0, 0, 240, 160),
                                                os.path.join(OUT_DIR, "compare-gradient.png"))))
    print()
    for name, size in made:
        print(f"wrote {OUT_DIR}/{name}.png  {size}")


if __name__ == "__main__":
    main()
