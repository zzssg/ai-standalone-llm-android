"""Colours the line-art squirrel with the painting's palette.

Two source assets, neither of which is usable on its own. The line art is
potrace output -- the black strokes of a drawing, with no fills, and an outline
so sketchy that flood-filling it leaks straight out of the figure into the page.
The painting has the colours but is a different drawing: a different pose, a
different crop, a tail the line art does not have, so nothing can be sampled
across from one to the other by position.

So the colours come from the painting as a palette, and the areas to put them in
come from the line art:

  1. Rasterise the strokes.
  2. Close them into a silhouette with a heavy dilate-fill-erode. Sketchy fur
     ticks never form a closed outline; this is what makes a figure out of them.
  3. Find regions of the gaps between strokes, using the silhouette edge as a
     wall too -- that is what separates the chest from the head, since the
     stroke between them runs edge to edge rather than closing on itself.
  4. Assign each region a colour by looking it up from a seed point.
  5. Trace the regions back to vector paths, and put the original strokes on top
     unchanged, so the line work stays exactly as drawn.

Regions are keyed by seed point rather than by index: the indices come out of a
raster scan and would silently renumber if the resolution changed, which would
quietly recolour the animal rather than fail.
"""
import importlib.util
import re
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw

_spec = importlib.util.spec_from_file_location("tracer", "design/trace_mascot.py")
tracer = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tracer)

WORK = 700           #: raster resolution the regions are found at
SEAL = 16            #: dilation that closes the sketchy outline into a silhouette
TUCK = 3             #: how far a fill runs under the strokes, so no seam shows
MIN_REGION = 120     #: below this a region is stroke noise, not an area
EPSILON = 3.5        #: fill simplification, in working pixels
INK_EPSILON = 3.5    #: line-work simplification, same units
PATH_BUDGET = 780     #: characters per path; lint's VectorPath limit is 800
INK_WEIGHT = 0        #: extra thickness for the line work, in working pixels
VIEWPORT = 1200.0    #: the template's own coordinate space, kept


# --- the palette, measured from the painting -------------------------------
#
# k-means over the opaque pixels of ic_squirrel.webp, which is why these are the
# painting's actual colours rather than a guess at them.
FUR = "#BE611F"
FUR_DEEP = "#904511"
CREAM = "#E9C494"
BRASS = "#C99C65"
WOOD = "#653512"
INK = "#291408"
GLASS = "#D9D6BE"     #: the olive the painting shows through the lens, lightened
HIGHLIGHT = "#F0E6D4"

#: Seed points in the working raster, and the colour the region they land in gets.
#: Each is the pixel farthest from its region's edge, so a seed can never drift
#: onto a stroke -- the first attempt put the rim's seed where the circle was
#: guessed to be and it landed in the lens instead.
#: The area is asserted so a seed that drifts onto the wrong side of a stroke
#: fails the build instead of producing a differently coloured squirrel.
SEEDS = [
    ("head and arms", (355, 249), FUR, 83493),
    ("chest and right arm", (562, 627), FUR, 32259),
    ("lens", (536, 419), GLASS, 26746),
    ("rim", (507, 223), BRASS, 8197),
    ("magnified iris", (469, 347), FUR_DEEP, 3205),
    ("handle shaft", (334, 612), WOOD, 1971),
    ("magnified pupil", (517, 332), INK, 1907),
    ("handle grip", (397, 509), WOOD, 1804),
    ("handle cap", (299, 632), BRASS, 923),
    ("eye iris", (284, 321), FUR_DEEP, 733),
    ("collar ferrule", (408, 481), BRASS, 658),
    ("neck ferrule", (430, 459), BRASS, 525),
    ("eye pupil", (300, 342), INK, 420),
    ("stem", (429, 446), BRASS, 186),
    ("nose", (388, 360), FUR_DEEP, 163),
    ("eye highlight", (497, 319), HIGHLIGHT, 147),
    ("ear tip", (312, 118), FUR, 142),
]


# --- raster -----------------------------------------------------------------

def load_strokes(svg):
    """The template's paths, in its own 1200-unit space, with the group flattened."""
    text = open(svg, encoding="utf-8").read()
    group = re.search(
        r'<g[^>]*transform="translate\(([^,]+),([^)]+)\)\s*scale\(([^,]+),([^)]+)\)"', text)
    tx, ty, sx, sy = (float(g) for g in group.groups())

    # Grouped by path element, not flattened. A stroke is drawn hollow by
    # nesting its inner contour inside its outer one under even-odd; emit those
    # two as separate paths and the inner one fills solid instead of being a
    # hole. That put a black disc over the magnifier's lens.
    strokes = []
    for d in re.findall(r'<path[^>]*\sd="([^"]+)"', text):
        subs = [[(px * sx + tx, py * sy + ty) for px, py in sub] for sub in _parse(d)]
        if subs:
            strokes.append(subs)
    return strokes


def _parse(d):
    """Absolute M plus relative m/l/c and z: everything potrace emits."""
    tokens = re.findall(r"[MmLlCcZz]|-?\d*\.?\d+(?:[eE][-+]?\d+)?", d)
    subpaths, current = [], []
    x = y = 0.0
    start = (0.0, 0.0)
    i = 0
    cmd = None

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    while i < len(tokens):
        if re.match(r"[A-Za-z]", tokens[i]):
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                if len(current) > 2:
                    subpaths.append(current)
                current = []
                x, y = start
                continue
        if cmd in "Mm":
            dx, dy = num(), num()
            x, y = (dx, dy) if cmd == "M" else (x + dx, y + dy)
            if len(current) > 2:
                subpaths.append(current)
            current = [(x, y)]
            start = (x, y)
            cmd = "L" if cmd == "M" else "l"
        elif cmd in "Ll":
            dx, dy = num(), num()
            x, y = (dx, dy) if cmd == "L" else (x + dx, y + dy)
            current.append((x, y))
        elif cmd in "Cc":
            v = [num() for _ in range(6)]
            if cmd == "c":
                x1, y1, x2, y2, ex, ey = (x + v[0], y + v[1], x + v[2], y + v[3], x + v[4], y + v[5])
            else:
                x1, y1, x2, y2, ex, ey = v
            for step in range(1, 13):
                t = step / 12
                u = 1 - t
                current.append((
                    u ** 3 * x + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * ex,
                    u ** 3 * y + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * ey,
                ))
            x, y = ex, ey
        else:
            i += 1
    if len(current) > 2:
        subpaths.append(current)
    return subpaths


def _contains(outer, inner):
    """Is inner's first vertex inside outer? Ray casting, good enough here:
    potrace contours never straddle each other, they nest or stay apart."""
    x, y = inner[0]
    hit = False
    n = len(outer)
    for i in range(n):
        x0, y0 = outer[i]
        x1, y1 = outer[(i + 1) % n]
        if (y0 > y) != (y1 > y):
            if x < x0 + (y - y0) / (y1 - y0) * (x1 - x0):
                hit = not hit
    return hit


def group_nested(subpaths):
    """Splits a list of contours into the smallest groups that keep holes.

    Subpaths only have to share a path element when one sits inside another --
    that is what even-odd turns into a hole. Everything else can be its own
    path, which is what keeps any single path short enough for lint's limit
    without losing a hollow stroke to a solid blob.
    """
    boxes = []
    for sub in subpaths:
        xs = [p[0] for p in sub]
        ys = [p[1] for p in sub]
        boxes.append((min(xs), min(ys), max(xs), max(ys), (max(xs) - min(xs)) * (max(ys) - min(ys))))

    order = sorted(range(len(subpaths)), key=lambda i: -boxes[i][4])
    parent = [None] * len(subpaths)
    for pos, i in enumerate(order):
        for j in order[:pos]:
            bi, bj = boxes[i], boxes[j]
            if bj[0] <= bi[0] and bj[1] <= bi[1] and bj[2] >= bi[2] and bj[3] >= bi[3]:
                if _contains(subpaths[j], subpaths[i]):
                    parent[i] = j
    groups = {}
    for i in range(len(subpaths)):
        root = i
        while parent[root] is not None:
            root = parent[root]
        groups.setdefault(root, []).append(subpaths[i])
    return list(groups.values())


def rasterise(strokes, px):
    """Even-odd, because potrace nests a stroke's inner contour to make it hollow."""
    scale = px / VIEWPORT
    acc = np.zeros((px, px), np.uint8)
    for group in strokes:
        for sub in group:
            layer = Image.new("1", (px, px), 0)
            ImageDraw.Draw(layer).polygon([(x * scale, y * scale) for x, y in sub], fill=1)
            acc ^= np.asarray(layer, np.uint8)
    return acc > 0


def smooth_mask(mask, passes=3, radius=2):
    """Majority vote in a window, to take the staircase off a derived edge.

    The silhouette comes out of a dilate-fill-erode with a plus-shaped element,
    so its edge carries steps at the scale of the sealing radius. Wherever a
    real stroke covers that edge it does not matter; where the drawing leaves an
    outline open, the step is the only boundary the eye gets, and it reads as a
    tearing artefact rather than as a drawing.
    """
    out = mask.copy()
    size = radius * 2 + 1
    for _ in range(passes):
        padded = np.pad(out, radius, constant_values=False).astype(np.int16)
        total = np.zeros(out.shape, np.int16)
        for dy in range(size):
            for dx in range(size):
                total += padded[dy:dy + out.shape[0], dx:dx + out.shape[1]]
        out = total > (size * size) // 2
    return out


def label_regions(wall, min_area):
    h, w = wall.shape
    labels = np.full((h, w), -1, np.int32)
    areas = []
    for sy in range(h):
        for sx in range(w):
            if wall[sy, sx] or labels[sy, sx] >= 0:
                continue
            idx = len(areas)
            labels[sy, sx] = idx
            stack = deque([(sy, sx)])
            size = 0
            while stack:
                y, x = stack.popleft()
                size += 1
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w and not wall[ny, nx] and labels[ny, nx] < 0:
                        labels[ny, nx] = idx
                        stack.append((ny, nx))
            areas.append(size)
    return labels, areas


# --- build ------------------------------------------------------------------

def build(svg, px=WORK, tolerate=0.25):
    strokes = load_strokes(svg)
    ink = rasterise(strokes, px)
    silhouette = tracer.erode(tracer.fill_holes(tracer.dilate(ink, SEAL)), SEAL)
    silhouette = smooth_mask(silhouette)
    labels, areas = label_regions(ink | ~silhouette, MIN_REGION)

    scale = px / WORK
    report = []
    for name, (sx, sy), colour, expect in SEEDS:
        idx = labels[int(sy * scale), int(sx * scale)]
        if idx < 0:
            raise SystemExit(f"seed for {name!r} landed on a stroke, not in a region")
        area = areas[idx]
        drift = abs(area - expect * scale * scale) / max(expect * scale * scale, 1)
        if drift > tolerate:
            raise SystemExit(
                f"seed for {name!r} found a region of {area} where {int(expect * scale * scale)} "
                f"was expected -- it has moved to a different area of the drawing")
        report.append((name, colour, area))

    # One path per region, not per contour and not per colour. A region shaped
    # like a ring -- the magnifier's rim is exactly that -- needs its inner
    # contour inside the same path to read as a hole; emitted separately it
    # fills solid and covers the lens. Keeping regions apart rather than merging
    # a colour into one path keeps each path short.
    layers = []
    ink_paths = _emit_region(tracer.dilate(ink, INK_WEIGHT) if INK_WEIGHT else ink,
                             VIEWPORT / px, epsilon=INK_EPSILON)
    for name, (sx, sy), colour, _ in SEEDS:
        idx = labels[int(sy * scale), int(sx * scale)]
        mask = labels == idx
        # Run the fill under the strokes: the region stops at the ink, and a fill
        # that stops there too leaves a pale seam along every line.
        mask = smooth_mask(tracer.dilate(mask, TUCK), passes=2)
        for d in _emit_region(mask, VIEWPORT / px):
            layers.append((colour, [d]))
    return layers, ink_paths, report


def _emit_region(mask, scale, budget=PATH_BUDGET, max_bands=24, epsilon=None):
    """A region as paths, none longer than [budget] characters.

    A big fill is one enormous contour, and simplifying it far enough to fit the
    limit turns the whole silhouette polygonal -- the ears go first. So instead
    the mask is cut into horizontal bands and each is traced on its own. Bands
    share their cut line exactly and overlap by a pixel, so they tile back into
    the same shape with no seam, and each path is a fraction of the length.
    """
    for bands in range(1, max_bands + 1):
        height = mask.shape[0]
        step = height / bands
        out = []
        for b in range(bands):
            lo = int(b * step)
            hi = height if b == bands - 1 else int((b + 1) * step) + 1
            slab = np.zeros_like(mask)
            slab[lo:hi] = mask[lo:hi]
            if not slab.any():
                continue
            out += _emit(tracer.contours(slab), scale, epsilon)
        if not out or max(len(d) for d in out) <= budget:
            return out
    return out


def _emit(polys, scale, epsilon=None):
    """A region's contours, grouped only where one sits inside another."""
    simplified = []
    for poly in polys:
        pts = tracer.simplify(poly, EPSILON if epsilon is None else epsilon)
        if len(pts) >= 3:
            simplified.append(pts)
    out = []
    for group in group_nested(simplified):
        chunk = []
        for pts in group:
            chunk.append(f"M{pts[0][0] * scale:.1f},{pts[0][1] * scale:.1f}")
            chunk += [f"L{x * scale:.1f},{y * scale:.1f}" for x, y in pts[1:]]
            chunk.append("Z")
        out.append("".join(chunk))
    return out


# --- emit -------------------------------------------------------------------

def to_vector_drawable(layers, inks, size=108, viewport=VIEWPORT, crop=None):
    body = []
    for colour, paths in layers:
        for d in paths:
            body.append(
                f'    <path\n'
                f'        android:fillColor="#FF{colour[1:]}"\n'
                f'        android:fillType="evenOdd"\n'
                f'        android:pathData="{d}" />'
            )
    for d in inks:
        body.append(
            f'    <path\n'
            f'        android:fillColor="#FF{INK[1:]}"\n'
            f'        android:fillType="evenOdd"\n'
            f'        android:pathData="{d}" />'
        )
    group_open = group_close = ""
    if crop:
        x0, y0, side = crop
        s = viewport / side
        group_open = (f'    <group android:scaleX="{s:.4f}" android:scaleY="{s:.4f}"\n'
                      f'        android:translateX="{-x0 * s:.2f}" '
                      f'android:translateY="{-y0 * s:.2f}">\n')
        group_close = "    </group>\n"
        body = ["    " + line for line in body]

    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "    Generated by design/colour_lineart.py from the line-art template and\n"
        "    the painting's palette. Regenerate rather than editing by hand.\n"
        "\n"
        "    Every path stays under the 800 characters lint asks for, by cutting\n"
        "    the large areas into horizontal bands rather than by simplifying them\n"
        "    until they fit. Bands share their cut line exactly, so they tile back\n"
        "    into one shape, and the ears stay round instead of going polygonal.\n"
        "-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{size}dp"\n'
        f'    android:height="{size}dp"\n'
        f'    android:viewportWidth="{viewport:.0f}"\n'
        f'    android:viewportHeight="{viewport:.0f}">\n\n'
        + group_open
        + "\n\n".join(body)
        + "\n" + group_close
        + "</vector>\n"
    )


def to_svg(layers, inks, viewport=VIEWPORT):
    body = []
    for colour, paths in layers:
        for d in paths:
            body.append(f'  <path fill="{colour}" fill-rule="evenodd" d="{d}"/>')
    for d in inks:
        body.append(f'  <path fill="{INK}" fill-rule="evenodd" d="{d}"/>')
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {viewport:.0f} {viewport:.0f}" '
        f'width="{viewport:.0f}" height="{viewport:.0f}">\n'
        + "\n".join(body)
        + "\n</svg>\n"
    )


def preview(layers, inks, path, px=900, paper=(255, 248, 242), crop=None):
    """Renders the emitted paths, so what is checked is what will ship."""
    scale = px / VIEWPORT
    ox = oy = 0.0
    if crop:
        cx, cy, side = crop
        scale = px / side
        ox, oy = -cx, -cy
    img = Image.new("RGB", (px, px), paper)
    for colour, paths in layers + [(INK, inks)]:
        rgb = tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))
        # Even-odd inside each path, union between paths: exactly what Android
        # does with fillType="evenOdd" on separate <path> elements. Accumulating
        # across a whole colour instead would make two overlapping strokes cancel
        # into a hole here and not on the device.
        acc = np.zeros((px, px), np.uint8)
        for d in paths:
            one = np.zeros((px, px), np.uint8)
            for sub in d.split("M")[1:]:
                pts = [((float(a) + ox) * scale, (float(b) + oy) * scale)
                       for a, b in (p.split(",") for p in
                                    ("M" + sub).replace("Z", "").replace("M", " ")
                                    .replace("L", " ").split())]
                if len(pts) < 3:
                    continue
                layer = Image.new("1", (px, px), 0)
                ImageDraw.Draw(layer).polygon(pts, fill=1)
                one ^= np.asarray(layer, np.uint8)
            acc |= one
        arr = np.asarray(img).copy()
        arr[acc > 0] = rgb
        img = Image.fromarray(arr)
    img.save(path)
    return path


#: The part of the drawing a launcher icon can actually show, in template units.
#:
#: A launcher draws this at 48dp on the home screen. The whole figure at that
#: size is a smudge with two ears: the hands, the handle and the body are all
#: below the size at which anything reads. The head and the magnifier are the
#: two shapes that survive, and they are also the two that say what the app is.
#: Derived, not guessed: the first hand-written box put the glass off the
#: bottom-right corner. See launcher_crop().
LAUNCHER_CROP = None

#: Strokes shorter than this, in template units, are fur texture. Kept at full
#: size, dropped for the launcher, where they collapse into grey fuzz.
LAUNCHER_MIN_STROKE = 26.0


def drop_small(paths, min_extent):
    """Removes strokes too short to read once the drawing is icon-sized."""
    kept = []
    for d in paths:
        pts = [(float(a), float(b)) for a, b in
               (p.split(",") for p in
                d.replace("Z", "").replace("M", " ").replace("L", " ").split())]
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        if max(max(xs) - min(xs), max(ys) - min(ys)) >= min_extent:
            kept.append(d)
    return kept


def launcher_crop(svg, px=WORK, margin=0.06):
    """A square around the head and the magnifier, in template units.

    Measured from the regions themselves. The head's own region runs down both
    arms, so it cannot be used directly: the box is the ears at the top, the
    eyes at the left, and the rim at the right and bottom.
    """
    strokes = load_strokes(svg)
    ink = rasterise(strokes, px)
    silhouette = smooth_mask(tracer.erode(tracer.fill_holes(tracer.dilate(ink, SEAL)), SEAL))
    labels, areas = label_regions(ink | ~silhouette, MIN_REGION)

    wanted = ("rim", "lens", "eye iris", "eye pupil", "magnified iris", "ear tip")
    xs, ys = [], []
    for name, (sx, sy), _, _ in SEEDS:
        if name not in wanted:
            continue
        idx = labels[sy, sx]
        ry, rx = np.nonzero(labels == idx)
        xs += [rx.min(), rx.max()]
        ys += [ry.min(), ry.max()]

    # The ear tips sit above every region, so the top comes from the ink itself.
    iy, ix = np.nonzero(ink)
    ys.append(iy.min())

    scale = VIEWPORT / px
    x0, x1 = min(xs) * scale, max(xs) * scale
    y0, y1 = min(ys) * scale, max(ys) * scale
    side = max(x1 - x0, y1 - y0) * (1 + 2 * margin)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    return (cx - side / 2, cy - side / 2, side)


def launcher_foreground(layers, inks, crop_box, safe=0.62):
    """The crop, fitted inside the launcher's safe zone.

    Only the central 66 of the 108dp canvas survives every launcher mask, so the
    drawing is scaled into that rather than trimmed to fit it.
    """
    x0, y0, side = crop_box
    # Nothing is filtered by position. Cropping paths by overlap dropped the
    # handle's fill while keeping its outline, and the outline of a hollow shape
    # fills solid: the icon came out with a black bar through it. The viewport
    # does the cropping; this only decides what is too small to draw.
    trimmed = [(colour, ps) for colour, ps in layers]
    thinned = drop_small(inks, LAUNCHER_MIN_STROKE)

    # Fit the crop box into the safe zone: enlarge the box so that mapping it to
    # the full viewport leaves the drawing occupying the central `safe` of it.
    grown = side / safe
    margin = (grown - side) / 2
    return trimmed, thinned, (x0 - margin, y0 - margin, grown)


if __name__ == "__main__":
    svg = sys.argv[1]
    layers, inks, report = build(svg)

    if "--launcher" in sys.argv:
        box = launcher_crop(svg)
        print(f"crop box x={box[0]:.0f} y={box[1]:.0f} side={box[2]:.0f}")
        layers, inks, crop = launcher_foreground(layers, inks, box)
        path = sys.argv[sys.argv.index("--launcher") + 1]
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(to_vector_drawable(layers, inks, crop=crop))
        print(f"wrote {path}  ({sum(len(p) for _, p in layers)} fills, {len(inks)} strokes)")
        if "--preview" in sys.argv:
            print(preview(layers, inks, sys.argv[sys.argv.index("--preview") + 1], crop=crop))
        raise SystemExit
    longest = max([len(d) for _, ps in layers for d in ps] + [len(d) for d in inks])
    print(f"{len(layers)} colours, {sum(len(p) for _, p in layers)} fill paths, "
          f"{len(inks)} stroke paths, longest {longest} chars")
    for name, colour, area in report:
        print(f"  {colour}  {name:<22} {area:>7} px")

    for flag, writer in (("--svg", to_svg), ("--vd", to_vector_drawable)):
        if flag in sys.argv:
            path = sys.argv[sys.argv.index(flag) + 1]
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(writer(layers, inks))
            print(f"wrote {path}")
    if "--preview" in sys.argv:
        print(preview(layers, inks, sys.argv[sys.argv.index("--preview") + 1]))
