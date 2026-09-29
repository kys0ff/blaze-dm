#!/usr/bin/env python3
"""Chrome fidelity scans over PNG screenshots (stray hairlines + mixed surfaces).

The Kotlin twin of this check runs inside `:gui:uiScreenshots` and gates the build on the
harness's own renders. This script exists so the *same* rules can be pointed at screenshots
taken from a running window -- the only way to see artifacts that only a real window produces
(the title-bar seam leaking the AWT panel color, resize gaps, native decorations).

Usage:
    scripts/ui_fidelity_check.py [path ...]

With no arguments it scans build/ui-screenshots/*.png. A path may be a PNG or a directory.
Exit code 0 = clean, 1 = at least one issue (printed with the row, colors and delta).
"""

from __future__ import annotations

import glob
import os
import sys
from collections import Counter

from PIL import Image

# --- thresholds (kept in sync with PngFidelityScan.kt) ------------------------------------------
ROW_COVERAGE = 0.90          # share of a row that must be one color for it to "span the window"
MAX_HAIRLINE = 2             # a full-width band this tall is a line, not a region
LINE_LUMA_DELTA = 24         # how far a line must sit from the surface it cuts
BAND_TOLERANCE = 10          # per-channel drift still counted as "the same band"
MIN_BAND_HEIGHT = 12         # rows needed before a band is checked for surface mixing
SURFACE_COVERAGE = 0.04      # share of a band a second color must own
SURFACE_LUMA_DELTA = 6       # how different that second color must be
MIN_FLAT_RUN = 0.30          # how wide a flat stretch must be to count as a surface

# Intentional hairlines / in-content colors, by exact hex. Add here only with a reason: a
# whitelisted color can never fail again.
LINE_WHITELIST = {
    "#393b40": "island rim / IDE hairline (dark)",
    "#2b2d31": "table separator (dark)",
    "#35373b": "table separator (dark, blended)",
    "#d0d3d9": "island rim / IDE hairline (light)",
    "#e1e4e8": "table separator (light)",
    "#c9ccd6": "island rim (light)",
}
SURFACE_WHITELIST = set(LINE_WHITELIST) | {
    "#2d5177", "#365273", "#4b6e92",  # selection: active / inactive / hover
    "#373328", "#f4ede0",              # progress fill over island (dark / light)
}


def luma(hex_color: str) -> float:
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    return 0.299 * r + 0.587 * g + 0.114 * b


def row_pixels(image: Image.Image) -> list[list[str]]:
    """The image as hex strings per row, read off the raw RGB buffer (no deprecated PIL API)."""
    data = image.tobytes()
    stride = image.width * 3
    return [
        [
            "#%02x%02x%02x" % (data[i], data[i + 1], data[i + 2])
            for i in range(y * stride, y * stride + stride, 3)
        ]
        for y in range(image.height)
    ]


def row_dominant(pixels: list[str]) -> tuple[str, float]:
    color, count = Counter(pixels).most_common(1)[0]
    return color, count / len(pixels)


def dominant_luma(color: str) -> float:
    return luma(color)


def same_band(a: str, b: str) -> bool:
    ca = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    cb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return all(abs(x - y) <= BAND_TOLERANCE for x, y in zip(ca, cb))


def scan(path: str) -> list[str]:
    name = os.path.basename(path)
    image = Image.open(path).convert("RGB")
    width, height = image.size
    rows = row_pixels(image)
    issues: list[str] = []

    # ---- stray hairlines: a 1-2px full-width band between two rows of the same other band -------
    dom = [row_dominant(row) for row in rows]
    y = 0
    while y < height:
        color, coverage = dom[y]
        if coverage < ROW_COVERAGE:
            y += 1
            continue
        end = y
        while end + 1 < height and dom[end + 1][0] == color and dom[end + 1][1] >= ROW_COVERAGE:
            end += 1
        above = dom[y - 1][0] if y > 0 else None
        below = dom[end + 1][0] if end + 1 < height else None
        thin = end - y + 1 <= MAX_HAIRLINE
        if thin and above and below and same_band(above, below):
            delta = abs(dominant_luma(color) - dominant_luma(above))
            if delta >= LINE_LUMA_DELTA and color not in LINE_WHITELIST:
                issues.append(
                    f"{name} [stray-line] y={y} full-width {end - y + 1}px line {color} "
                    f"over/under {above} (delta luma {delta:.0f})"
                )
        y = end + 1

    # ---- mixed surfaces: a second flat, wide color inside one surface band ----------------------
    y = 0
    while y < height:
        color, coverage = dom[y]
        if coverage < 0.5:
            y += 1
            continue
        end = y
        while end + 1 < height and dom[end + 1][0] == color and dom[end + 1][1] >= 0.5:
            end += 1
        if end - y + 1 >= MIN_BAND_HEIGHT:
            counts: Counter = Counter()
            runs: dict[str, int] = {}
            total = 0
            for row in rows[y:end + 1]:
                run_color, run_length = None, 0
                for pixel in row:
                    counts[pixel] += 1
                    total += 1
                    if pixel == run_color:
                        run_length += 1
                    else:
                        run_color, run_length = pixel, 1
                    runs[pixel] = max(runs.get(pixel, 0), run_length)
            for candidate, seen in counts.most_common():
                if candidate == color or candidate in SURFACE_WHITELIST:
                    continue
                if seen / total < SURFACE_COVERAGE:
                    continue
                if runs[candidate] / width < MIN_FLAT_RUN:
                    continue
                if abs(dominant_luma(candidate) - dominant_luma(color)) < SURFACE_LUMA_DELTA:
                    continue
                issues.append(
                    f"{name} [mixed-surface] band y={y}..{end} is {color} but {candidate} "
                    f"owns {100 * seen / total:.0f}% of it in a {100 * runs[candidate] / width:.0f}% "
                    f"wide flat stretch"
                )
                break
        y = end + 1

    return issues


def main(argv: list[str]) -> int:
    targets = argv or ["build/ui-screenshots"]
    files: list[str] = []
    for target in targets:
        if os.path.isdir(target):
            files.extend(sorted(glob.glob(os.path.join(target, "*.png"))))
        elif os.path.isfile(target):
            files.append(target)
        else:
            print(f"!! no such path: {target}", file=sys.stderr)
            return 2
    if not files:
        print("!! nothing to scan", file=sys.stderr)
        return 2

    all_issues: list[str] = []
    for f in files:
        all_issues.extend(scan(f))

    print(f"ui_fidelity_check: scanned {len(files)} png(s)")
    for issue in all_issues:
        print("  -", issue)
    if all_issues:
        print(f"FAIL: {len(all_issues)} issue(s)")
        return 1
    print("OK: no stray hairlines, no mixed surfaces")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
