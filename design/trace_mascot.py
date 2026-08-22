"""Traces a painted mascot into flat vector paths.

The source is a painterly illustration: soft gradients, fur texture, a blurred
background. Tracing that faithfully would produce thousands of paths, which a
VectorDrawable renders badly and which turns to mush at the 28dp the app uses
for the smallest instance of the mascot. So this does not try to be faithful.
It posterises to a handful of flat colours, throws away everything below a size
that would read on screen, and emits smooth outlines -- a poster of the painting
rather than a copy of it.

Backends: SVG (portable, what a designer would open) and Android VectorDrawable
(what the app actually loads), from the same traced geometry.

No third-party tracer is used because none is installed and the job is narrow:
region-grow the background away, k-means the rest, clean up, march the squares,
simplify, smooth.
"""
import math
import sys

sys.setrecursionlimit(20000)
from collections import deque

import numpy as np
from PIL import Image

# --- tunables ---------------------------------------------------------------

WORK = 640           #: working resolution; detail below this is not kept anyway
BG_TOLERANCE = 78    #: colour distance from the border median that still reads as background
COLOURS = 9          #: flat colours in the poster
SMOOTH_PASSES = 2    #: majority filters, to kill fur speckle before tracing
MIN_AREA_FRAC = 8e-4 #: drop islands smaller than this share of the subject
EPSILON = 0.7        #: Douglas-Peucker tolerance, in working pixels
VIEWPORT = 108.0     #: emitted coordinate space, matching the app's other art


# --- background -------------------------------------------------------------

def subject_mask(rgb):
    """True where the character is.

    Region-grows from the border rather than classifying by colour alone: the
    cream of the belly is closer to the olive background than the dark outline
    is, so any global colour test either eats the belly or keeps the background.
    Connectivity is what separates them, and the illustration's dark outline is
    what the growth stops against.
    """
    h, w, _ = rgb.shape
    border = np.concatenate([rgb[0], rgb[-1], rgb[:, 0], rgb[:, -1]])
    model = np.median(border, axis=0)
    near = np.linalg.norm(rgb - model, axis=2) < BG_TOLERANCE

    bg = np.zeros((h, w), bool)
    stack = [(0, x) for x in range(w) if near[0, x]]
    stack += [(h - 1, x) for x in range(w) if near[h - 1, x]]
    stack += [(y, 0) for y in range(h) if near[y, 0]]
    stack += [(y, w - 1) for y in range(h) if near[y, w - 1]]
    for y, x in stack:
        bg[y, x] = True
    stack = deque(stack)
    while stack:
        y, x = stack.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and near[ny, nx] and not bg[ny, nx]:
                bg[ny, nx] = True
                stack.append((ny, nx))

    subject = ~bg
    subject = fill_holes(subject)
    # Seal the channels the growth cut through low-contrast areas: where the ear
    # fades into the background the two are within tolerance of each other, and
    # a one-pixel-wide leak takes a wedge out of the ear. Closing puts it back
    # without moving the outline anywhere it was decided confidently.
    subject = erode(dilate(subject, 3), 3)
    # The blend at the silhouette edge is half background; keeping it would fringe
    # every outline with olive.
    return erode(subject, 2)


def fill_holes(mask):
    """Closes gaps the growth left inside the character."""
    h, w = mask.shape
    outside = np.zeros((h, w), bool)
    stack = deque()
    for y in range(h):
        for x in (0, w - 1):
            if not mask[y, x] and not outside[y, x]:
                outside[y, x] = True
                stack.append((y, x))
    for x in range(w):
        for y in (0, h - 1):
            if not mask[y, x] and not outside[y, x]:
                outside[y, x] = True
                stack.append((y, x))
    while stack:
        y, x = stack.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and not mask[ny, nx] and not outside[ny, nx]:
                outside[ny, nx] = True
                stack.append((ny, nx))
    return ~outside


def dilate(mask, times=1):
    out = mask.copy()
    for _ in range(times):
        d = out.copy()
        d[1:] |= out[:-1]
        d[:-1] |= out[1:]
        d[:, 1:] |= out[:, :-1]
        d[:, :-1] |= out[:, 1:]
        out = d
    return out


def erode(mask, times=1):
    out = mask.copy()
    for _ in range(times):
        e = out.copy()
        e[1:] &= out[:-1]
        e[:-1] &= out[1:]
        e[:, 1:] &= out[:, :-1]
        e[:, :-1] &= out[:, 1:]
        out = e
    return out


# --- posterise --------------------------------------------------------------

def kmeans(samples, k, iterations=24, seed=7):
    """Plain Lloyd's algorithm. sklearn is not installed and this is 15 lines."""
    rng = np.random.default_rng(seed)
    centres = samples[rng.choice(len(samples), k, replace=False)].astype(np.float64)
    for _ in range(iterations):
        d = ((samples[:, None, :] - centres[None, :, :]) ** 2).sum(axis=2)
        labels = d.argmin(axis=1)
        for i in range(k):
            hit = samples[labels == i]
            if len(hit):
                centres[i] = hit.mean(axis=0)
    return centres, labels


def posterise(rgb, mask, k):
    """Assigns every subject pixel one of k flat colours."""
    pixels = rgb[mask].astype(np.float64)
    sample = pixels[np.random.default_rng(3).choice(len(pixels), min(24000, len(pixels)), replace=False)]
    centres, _ = kmeans(sample, k)

    d = ((rgb.reshape(-1, 1, 3).astype(np.float64) - centres[None, :, :]) ** 2).sum(axis=2)
    labels = d.argmin(axis=1).reshape(rgb.shape[:2])
    labels[~mask] = -1
    return labels, centres


def majority(labels, mask, passes):
    """Fur is texture, not shape. This is what stops it becoming ten thousand islands."""
    out = labels.copy()
    for _ in range(passes):
        padded = np.pad(out, 1, constant_values=-1)
        stack = np.stack([
            padded[dy:dy + out.shape[0], dx:dx + out.shape[1]]
            for dy in range(3) for dx in range(3)
        ])
        best = out.copy()
        counts = np.zeros(out.shape, np.int8)
        for value in range(int(labels.max()) + 1):
            c = (stack == value).sum(axis=0).astype(np.int8)
            better = c > counts
            counts = np.where(better, c, counts)
            best = np.where(better, value, best)
        out = np.where(mask, best, -1)
    return out


def components(mask):
    """Connected components, 4-connected, as a label image and their sizes."""
    h, w = mask.shape
    labels = np.full((h, w), -1, np.int32)
    sizes = []
    for sy in range(h):
        for sx in range(w):
            if not mask[sy, sx] or labels[sy, sx] >= 0:
                continue
            idx = len(sizes)
            labels[sy, sx] = idx
            stack = deque([(sy, sx)])
            size = 0
            while stack:
                y, x = stack.popleft()
                size += 1
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and labels[ny, nx] < 0:
                        labels[ny, nx] = idx
                        stack.append((ny, nx))
            sizes.append(size)
    return labels, sizes


def drop_islands(labels, mask, min_area):
    """Removes specks, handing their pixels to the colour that surrounds them."""
    out = labels.copy()
    for value in range(int(labels.max()) + 1):
        comp, sizes = components(labels == value)
        for idx, size in enumerate(sizes):
            if size >= min_area:
                continue
            out[comp == idx] = -2  # marked for reassignment
    # Grow the survivors into the gaps.
    while (out == -2).any():
        holes = out == -2
        filled = out.copy()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            shifted = np.roll(out, (dy, dx), (0, 1))
            take = holes & (shifted >= 0)
            filled = np.where(take, shifted, filled)
        if (filled == -2).sum() == holes.sum():
            filled[filled == -2] = 0  # nothing adjacent; give up on these
        out = filled
    out[~mask] = -1
    return out


# --- trace ------------------------------------------------------------------

def contours(mask):
    """Every closed boundary of a binary mask, as pixel-space polygons.

    Marching squares over the dual grid. Outer boundaries and holes come out of
    the same pass, undistinguished, which is exactly what is wanted: emitted
    into one path with even-odd fill, nesting makes holes by itself and nothing
    has to work out which contour contains which.
    """
    h, w = mask.shape
    grid = np.zeros((h + 2, w + 2), bool)
    grid[1:-1, 1:-1] = mask

    # Edges of the boundary graph: for each cell corner, which way the boundary runs.
    segments = {}
    for y in range(h + 1):
        for x in range(w + 1):
            tl = grid[y, x]
            tr = grid[y, x + 1]
            bl = grid[y + 1, x]
            br = grid[y + 1, x + 1]
            code = (tl << 3) | (tr << 2) | (br << 1) | bl
            if code in (0, 15):
                continue
            top, right, bottom, left = (x + 0.5, y), (x + 1, y + 0.5), (x + 0.5, y + 1), (x, y + 0.5)
            # Every segment is directed so the filled side is on its right, in
            # screen coordinates where y grows downward. Get one entry backwards
            # and the walk crosses from one strand to another: the outline of a
            # 188,000-pixel silhouette came out as 225 fragments, the largest
            # enclosing 2,284 pixels, which looked like a tracing failure
            # everywhere except in the one table that caused it.
            pairs = {
                1: [(left, bottom)], 2: [(bottom, right)], 3: [(left, right)],
                4: [(right, top)], 5: [(left, top), (right, bottom)], 6: [(bottom, top)],
                7: [(left, top)], 8: [(top, left)], 9: [(top, bottom)],
                10: [(top, right), (bottom, left)], 11: [(top, right)],
                12: [(right, left)], 13: [(right, bottom)], 14: [(bottom, left)],
            }[code]
            for a, b in pairs:
                segments.setdefault(a, []).append(b)

    polys = []
    while segments:
        start = next(iter(segments))
        poly = [start]
        cur = start
        while True:
            nxt_list = segments.get(cur)
            if not nxt_list:
                break
            nxt = nxt_list.pop()
            if not nxt_list:
                segments.pop(cur, None)
            if nxt == start:
                break
            poly.append(nxt)
            cur = nxt
        if len(poly) >= 4:
            polys.append([(px - 1, py - 1) for px, py in poly])
    return polys


def _rdp(pts, epsilon):
    """Douglas-Peucker on an open polyline."""
    if len(pts) < 3:
        return pts
    (x0, y0), (x1, y1) = pts[0], pts[-1]
    dx, dy = x1 - x0, y1 - y0
    norm = math.hypot(dx, dy)
    worst, index = 0.0, 0
    for i in range(1, len(pts) - 1):
        x, y = pts[i]
        if norm < 1e-9:
            d = math.hypot(x - x0, y - y0)
        else:
            d = abs(dy * x - dx * y + x1 * y0 - y1 * x0) / norm
        if d > worst:
            worst, index = d, i
    if worst <= epsilon:
        return [pts[0], pts[-1]]
    return _rdp(pts[:index + 1], epsilon)[:-1] + _rdp(pts[index:], epsilon)


def simplify(points, epsilon):
    """Douglas-Peucker on a closed ring.

    Split at the far point first. Run naively on a ring and the baseline from
    the first point to the last is degenerate -- they are the same point -- so
    every vertex measures zero distance from it and the whole outline collapses
    to a single point. Two open halves have real baselines.
    """
    if len(points) < 5:
        return points

    x0, y0 = points[0]
    far = max(range(len(points)), key=lambda i: (points[i][0] - x0) ** 2 + (points[i][1] - y0) ** 2)
    if far < 2 or far > len(points) - 2:
        far = len(points) // 2

    first = _rdp(points[:far + 1], epsilon)
    second = _rdp(points[far:] + [points[0]], epsilon)
    return first[:-1] + second[:-1]


# Straight segments, not curves.
#
# The first attempt rounded every vertex with a quadratic through the edge
# midpoints. On a simplified outline that pulls each region in towards its own
# centre -- a four-point square comes out very nearly a circle -- so neighbouring
# colours separated and the background showed through the seams. The result read
# as a line drawing rather than a poster. Straight segments at a tight tolerance
# deviate by less than a pixel and touch their neighbours exactly.


# --- emit -------------------------------------------------------------------

def path_of(polys, scale, offset):
    """One path string covering every contour of a colour."""
    def place(p):
        return (p[0] * scale + offset[0], p[1] * scale + offset[1])

    out = []
    for poly in polys:
        pts = simplify(poly, EPSILON)
        if len(pts) < 3:
            continue
        sx, sy = place(pts[0])
        d = [f"M{sx:.1f},{sy:.1f}"]
        for point in pts[1:]:
            px, py = place(point)
            d.append(f"L{px:.1f},{py:.1f}")
        d.append("Z")
        out.append("".join(d))
    return "".join(out)


def trace(path, colours=COLOURS):
    im = Image.open(path).convert("RGB")
    side = max(im.size)
    im = im.resize((WORK, WORK), Image.LANCZOS)
    rgb = np.asarray(im).astype(np.int16)

    mask = subject_mask(rgb)
    labels, centres = posterise(rgb, mask, colours)
    labels = majority(labels, mask, SMOOTH_PASSES)
    labels = drop_islands(labels, mask, int(mask.sum() * MIN_AREA_FRAC))

    ys, xs = np.nonzero(mask)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    span = max(x1 - x0, y1 - y0)
    scale = VIEWPORT / span
    offset = (-x0 * scale + (VIEWPORT - (x1 - x0) * scale) / 2,
              -y0 * scale + (VIEWPORT - (y1 - y0) * scale) / 2)

    # Painted darkest first: the outline sits under everything it outlines.
    order = sorted(range(colours), key=lambda i: centres[i].sum())
    darkest = centres[order[0]]
    layers = [(
        f"#{int(darkest[0]):02X}{int(darkest[1]):02X}{int(darkest[2]):02X}",
        path_of(contours(mask), scale, offset),
    )]
    for i in order:
        polys = contours(labels == i)
        if not polys:
            continue
        d = path_of(polys, scale, offset)
        if d:
            r, g, b = (int(round(v)) for v in centres[i])
            layers.append((f"#{r:02X}{g:02X}{b:02X}", d))
    return layers, labels, centres, mask


def to_svg(layers):
    body = "\n".join(
        f'  <path fill="{colour}" fill-rule="evenodd" d="{d}"/>' for colour, d in layers
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {VIEWPORT:.0f} {VIEWPORT:.0f}" '
        f'width="{VIEWPORT:.0f}" height="{VIEWPORT:.0f}">\n{body}\n</svg>\n'
    )


def to_vector_drawable(layers, size=108):
    body = "\n\n".join(
        f'    <path\n'
        f'        android:fillColor="#FF{colour[1:]}"\n'
        f'        android:fillType="evenOdd"\n'
        f'        android:pathData="{d}" />'
        for colour, d in layers
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "    Traced from the painted reference by design/trace_mascot.py.\n"
        "    Posterised on purpose: the painting's fur and gradients cannot survive\n"
        "    at the sizes this is drawn, and tracing them faithfully would cost\n"
        "    thousands of paths. Regenerate rather than editing by hand.\n"
        "-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{size}dp"\n'
        f'    android:height="{size}dp"\n'
        f'    android:viewportWidth="{VIEWPORT:.0f}"\n'
        f'    android:viewportHeight="{VIEWPORT:.0f}">\n\n'
        + body
        + "\n</vector>\n"
    )


def cutout(src, path, size=640, quality=88):
    """The original painting with the background removed, as WebP.

    The honest destination for this artwork. Posterising it into a
    VectorDrawable produces paths of seven to thirteen thousand characters,
    which the project's own lint rejects on performance grounds; simplifying
    until the longest path fits the recommended limit costs the magnifying
    glass, and the character stops being recognisable. Every step of that
    simplification is a loss, and none of it buys anything a raster does not
    already give at this size: the largest place the mascot appears is 148dp,
    which is 592 pixels at the highest density Android ships.

    So the vector output stays for anywhere that needs one, and the app gets
    the painting itself -- which also looks considerably better than a
    nine-colour reduction of it.
    """
    im = Image.open(src).convert("RGB")
    work = im.resize((WORK, WORK), Image.LANCZOS)
    mask = subject_mask(np.asarray(work).astype(np.int16))

    # Only the character: the growth leaves detached wisps of tail and a few
    # specks along the frame edge, and on a transparent cutout those read as
    # dirt rather than as fur.
    comp, sizes = components(mask)
    mask = comp == int(np.argmax(sizes))

    # Grow back the two pixels the trace eroded, then feather, so the edge is
    # neither fringed with background nor cut with a hard staircase.
    alpha = Image.fromarray((dilate(mask, 2) * 255).astype(np.uint8))
    alpha = alpha.resize(im.size, Image.LANCZOS)

    ys, xs = np.nonzero(mask)
    scale = im.size[0] / WORK
    box = (int(xs.min() * scale), int(ys.min() * scale),
           int(xs.max() * scale) + 1, int(ys.max() * scale) + 1)
    side = max(box[2] - box[0], box[3] - box[1])
    cx, cy = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
    half = side // 2 + 4

    out = Image.new("RGBA", (side + 8, side + 8), (0, 0, 0, 0))
    rgba = im.convert("RGBA")
    rgba.putalpha(alpha)
    out.paste(rgba.crop((cx - half, cy - half, cx + half, cy + half)), (0, 0))
    out = out.resize((size, size), Image.LANCZOS)
    out.save(path, "WEBP", quality=quality, method=6)
    return path


def preview(layers, path, scale=6, background=(255, 248, 242, 255)):
    """Re-renders the emitted geometry, so what is checked is what will ship."""
    from PIL import ImageDraw
    size = int(VIEWPORT * scale)
    img = Image.new("RGBA", (size, size), background)
    for colour, d in layers:
        rgb = tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))
        layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        for sub in d.split("M")[1:]:
            pts = _flatten("M" + sub, scale)
            if len(pts) > 2:
                draw.polygon(pts, fill=rgb + (255,))
        img.alpha_composite(layer)
    img.save(path)
    return path


def _flatten(d, scale):
    """Walks a path string back into points, for the preview only."""
    pts = []
    for token in d.replace("Z", "").replace("M", " ").replace("L", " ").split():
        x, y = (float(v) for v in token.split(","))
        pts.append((x * scale, y * scale))
    return pts


def main(argv):
    """trace_mascot.py <source.png> [--svg f] [--vd f] [--preview f]
                       [--colours N] [--epsilon E] [--min-area F]

    The three tuning flags exist so the same source can produce both the hero
    artwork and a lighter version for the sizes where the detail is invisible
    anyway, without maintaining two drawings.
    """
    global COLOURS, EPSILON, MIN_AREA_FRAC
    src = argv[1]
    if "--colours" in argv:
        COLOURS = int(argv[argv.index("--colours") + 1])
    if "--epsilon" in argv:
        EPSILON = float(argv[argv.index("--epsilon") + 1])
    if "--min-area" in argv:
        MIN_AREA_FRAC = float(argv[argv.index("--min-area") + 1])

    layers, labels, centres, mask = trace(src, COLOURS)

    contour_count = sum(d.count("M") for _, d in layers)
    points = sum(d.count("L") for _, d in layers) + contour_count
    print(f"{len(layers)} layers, {contour_count} contours, {points} points")
    for colour, d in layers:
        print(f"  {colour}  {d.count('M'):3d} contours  {len(d) / 1024:5.1f} KB")

    for flag, writer in (("--svg", to_svg), ("--vd", to_vector_drawable)):
        if flag in argv:
            path = argv[argv.index(flag) + 1]
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(writer(layers))
            print(f"wrote {path}")
    if "--webp" in argv:
        print(f"wrote {cutout(src, argv[argv.index('--webp') + 1])}")
    if "--preview" in argv:
        print(preview(layers, argv[argv.index("--preview") + 1]))


if __name__ == "__main__":
    main(sys.argv)
