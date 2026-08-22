"""The mascot as a flat mark: a squirrel whose eye is the magnifier's lens.

Third attempt at this animal, and the first that is designed rather than
derived. The painting was too fine to survive 28dp, and tracing the line art
produced a hundred and thirty paths of fur texture that read as fuzz at any size
the app actually draws it. Flat design is what the brief calls for -- simple
shapes, no gradients, no shadows -- and simple shapes have to be built, not
extracted from a picture.

The idea is the one thing worth keeping from both earlier attempts: the lens is
the eye. One circle does the work of two, which is the whole claim of the
product in a single shape -- something small that looks closely.

Everything is described as parameters and emitted, so a proportion changes by
editing a number, and the PIL preview renders the emitted geometry rather than
re-deriving it, so what is checked is what ships.
"""
import math
import sys

VIEWPORT = 100.0

# --- palette ---------------------------------------------------------------
#
# Two colours and an ink. Flat design gets its clarity from restraint; the
# earlier mark used eight and turned muddy the moment it was scaled down.
FUR = "#F2711C"
FUR_DEEP = "#D65B10"
INK = "#2E1B10"
PAPER = "#FFFFFF"


# --- primitives -------------------------------------------------------------

class Disc:
    def __init__(self, cx, cy, r, ry=None):
        self.cx, self.cy, self.rx, self.ry = cx, cy, r, ry if ry is not None else r


class Poly:
    def __init__(self, points):
        self.points = points


class Ring:
    def __init__(self, cx, cy, r_outer, r_inner):
        self.cx, self.cy, self.r_outer, self.r_inner = cx, cy, r_outer, r_inner


def polar(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def arc(cx, cy, r, a0, a1, n=48):
    return [polar(cx, cy, r, a0 + (a1 - a0) * i / n) for i in range(n + 1)]


def bezier(p0, p1, p2, p3, n):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                    u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
    return out


def ribbon(spine, w_root, w_tip, n=22):
    """A tapered stroke along a curve: the tail.

    Two earlier shapes failed here. A band of constant radius around the head
    can only ever be a collar, and a band offset from it leaves a gap and reads
    as a bracket. A tail is a stroke that follows its own line and swells as it
    goes, so that is what this builds: offset the spine by a half width that
    grows from root to tip, and round the tip off.

    Sampled coarsely on purpose: at the size this is drawn, twenty-two segments
    along the spine are already sub-pixel, and forty pushed the path past the
    length lint allows.
    """
    pts = bezier(*spine, n=n)
    left, right = [], []
    for i, (x, y) in enumerate(pts):
        t = i / (len(pts) - 1)
        # Swell early, then hold: a tail is widest along its length, not only
        # at the very end.
        half = (w_root + (w_tip - w_root) * math.sin(t * math.pi / 2) ** 0.8) / 2
        j = min(i + 1, len(pts) - 1)
        k = max(i - 1, 0)
        dx, dy = pts[j][0] - pts[k][0], pts[j][1] - pts[k][1]
        length = math.hypot(dx, dy) or 1e-6
        nx, ny = -dy / length * half, dx / length * half
        left.append((x + nx, y + ny))
        right.append((x - nx, y - ny))

    # Round the tip rather than cutting it square.
    tipx, tipy = pts[-1]
    ang = math.degrees(math.atan2(pts[-1][1] - pts[-2][1], pts[-1][0] - pts[-2][0]))
    cap = [polar(tipx, tipy, w_tip / 2, ang - 90 + 180 * i / 8) for i in range(9)]
    return Poly(left + cap + right[::-1])


def capsule(x0, y0, x1, y1, r, n=8):
    ang = math.degrees(math.atan2(y1 - y0, x1 - x0))
    pts = [polar(x1, y1, r, ang - 90 + 180 * i / n) for i in range(n + 1)]
    pts += [polar(x0, y0, r, ang + 90 + 180 * i / n) for i in range(n + 1)]
    return Poly(pts)


def ear(tip, base_left, base_right, blunt=2.2):
    """A triangle with its apex chamfered off.

    Two earlier attempts at a soft tip went wrong the same way: a curve through
    the apex bows the sides inward and gives a flame, and an arc around the apex
    takes the reflex side and gives a knob on a stalk. Cutting the point off is
    both simpler and what actually reads as a rounded ear at icon size.
    """
    ax, ay = tip

    def pull(frm):
        dx, dy = frm[0] - ax, frm[1] - ay
        length = math.hypot(dx, dy) or 1e-6
        return (ax + dx / length * blunt, ay + dy / length * blunt)

    return Poly([base_left, pull(base_left), pull(base_right), base_right])


# --- the mark ---------------------------------------------------------------

#: What survives into the status-bar icon.
#:
#: A notification icon is flattened to one colour at 24dp, and a silhouette of
#: the whole mark is a blob: the lens stops being a hole and the eye stops being
#: an eye. The magnifier with its pupil is the part that still says what this is
#: once colour is gone.
NOTIFICATION_PARTS = ("rim", "handle", "eye")


def build(c):
    """Shapes in painting order. Nine of them, which is the point."""
    out = []
    fur, deep, ink = FUR, FUR_DEEP, INK

    # Tail first, behind everything.
    out.append(("tail", ribbon(c["tail_spine"], c["tail_root"], c["tail_tip"]), deep))

    # Ears before the head, so only their tips show above it.
    for side in (-1, 1):
        cx = c["head_cx"] + side * c["ear_dx"]
        out.append((f"ear{side}", ear(
            (cx + side * c["ear_tilt"], c["head_cy"] - c["ear_h"]),
            (cx - c["ear_w"] / 2, c["head_cy"] - c["ear_base"]),
            (cx + c["ear_w"] / 2, c["head_cy"] - c["ear_base"]),
        ), fur))

    out.append(("head", Disc(c["head_cx"], c["head_cy"], c["head_r"]), fur))

    # The magnifier. Its lens is left empty so whatever is behind shows through,
    # which is what lets one circle be both a lens and an eye socket.
    gx, gy, gr = c["glass_cx"], c["glass_cy"], c["glass_r"]
    hx, hy = polar(gx, gy, gr + c["rim_w"] / 2, c["handle_deg"])
    tx, ty = polar(gx, gy, gr + c["rim_w"] / 2 + c["handle_len"], c["handle_deg"])
    out.append(("handle", capsule(hx, hy, tx, ty, c["handle_w"] / 2), ink))
    out.append(("lens", Disc(gx, gy, gr), PAPER))
    out.append(("rim", Ring(gx, gy, gr + c["rim_w"], gr), ink))

    # The eye, magnified: bigger than it would be outside the glass. That
    # oversize is the joke, so it must not be trimmed to look anatomical.
    out.append(("eye", Disc(gx + c["eye_dx"], gy + c["eye_dy"], c["eye_r"]), ink))
    out.append(("glint", Disc(gx + c["eye_dx"] + c["eye_r"] * 0.38,
                              gy + c["eye_dy"] - c["eye_r"] * 0.4,
                              c["eye_r"] * 0.33), PAPER))

    # Nose: the one detail that makes it an animal rather than a shape.
    out.append(("nose", Disc(c["nose_cx"], c["nose_cy"], c["nose_r"] * 1.15,
                             c["nose_r"]), ink))
    return out


CONFIG = dict(
    # The tail's spine: out of the body low on the left, swinging wide and
    # rising, then curling back in above the head.
    tail_spine=((56.0, 74.0), (26.0, 74.0), (22.0, 32.0), (50.0, 22.0)),
    tail_root=8.0, tail_tip=18.0,

    head_cx=60.0, head_cy=58.0, head_r=24.0,
    ear_dx=11.5, ear_h=32.0, ear_base=10.0, ear_w=17.0, ear_tilt=1.5,
    glass_cx=67.0, glass_cy=54.0, glass_r=11.5, rim_w=3.0,
    handle_deg=125.0, handle_len=9.0, handle_w=4.5,
    eye_dx=0.0, eye_dy=0.5, eye_r=5.0,
    nose_cx=45.0, nose_cy=66.0, nose_r=2.4,
)


# --- emit -------------------------------------------------------------------

def _n(v):
    return f"{v:.1f}"


def path_data(prim):
    if isinstance(prim, Disc):
        return (f"M{_n(prim.cx - prim.rx)},{_n(prim.cy)}"
                f"a{_n(prim.rx)},{_n(prim.ry)} 0 1,0 {_n(2 * prim.rx)},0"
                f"a{_n(prim.rx)},{_n(prim.ry)} 0 1,0 {_n(-2 * prim.rx)},0Z")
    if isinstance(prim, Ring):
        ro, ri = prim.r_outer, prim.r_inner
        return (f"M{_n(prim.cx - ro)},{_n(prim.cy)}"
                f"a{_n(ro)},{_n(ro)} 0 1,0 {_n(2 * ro)},0"
                f"a{_n(ro)},{_n(ro)} 0 1,0 {_n(-2 * ro)},0Z"
                f"M{_n(prim.cx - ri)},{_n(prim.cy)}"
                f"a{_n(ri)},{_n(ri)} 0 1,1 {_n(2 * ri)},0"
                f"a{_n(ri)},{_n(ri)} 0 1,1 {_n(-2 * ri)},0Z")
    head = prim.points[0]
    return (f"M{_n(head[0])},{_n(head[1])}"
            + "".join(f"L{_n(x)},{_n(y)}" for x, y in prim.points[1:]) + "Z")


def to_vector_drawable(cfg, size=108, monochrome=False, safe=None):
    shapes = build(cfg)
    if monochrome:
        shapes = [(n, p, PAPER) for n, p, c in shapes if n.startswith(NOTIFICATION_PARTS)]
    body = []
    for name, prim, colour in shapes:
        fill = ' android:fillType="evenOdd"' if isinstance(prim, Ring) else ""
        body.append(f"        <!-- {name} -->\n"
                    f"        <path\n"
                    f'            android:fillColor="#FF{colour[1:]}"{fill}\n'
                    f'            android:pathData="{path_data(prim)}" />')

    xs, ys = _bounds(shapes)
    if safe:
        # Fit the drawing into the launcher's safe zone: only the central 66 of
        # the 108dp canvas survives every mask.
        span = max(xs[1] - xs[0], ys[1] - ys[0])
        scale = VIEWPORT * safe / span
        cx, cy = (xs[0] + xs[1]) / 2, (ys[0] + ys[1]) / 2
    else:
        span = max(xs[1] - xs[0], ys[1] - ys[0])
        scale = VIEWPORT * 0.94 / span
        cx, cy = (xs[0] + xs[1]) / 2, (ys[0] + ys[1]) / 2

    group = (f'    <group\n'
             f'        android:pivotX="{_n(cx)}"\n'
             f'        android:pivotY="{_n(cy)}"\n'
             f'        android:scaleX="{_n(scale)}"\n'
             f'        android:scaleY="{_n(scale)}"\n'
             f'        android:translateX="{_n(VIEWPORT / 2 - cx)}"\n'
             f'        android:translateY="{_n(VIEWPORT / 2 - cy)}">\n\n')

    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            "<!--\n"
            "    Generated by design/mascot_minimal.py. Seven flat shapes, no\n"
            "    gradients and no strokes, so it holds together from 28dp to the\n"
            "    launcher. Regenerate rather than editing by hand.\n"
            "-->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp"\n'
            f'    android:height="{size}dp"\n'
            f'    android:viewportWidth="{VIEWPORT:.0f}"\n'
            f'    android:viewportHeight="{VIEWPORT:.0f}">\n\n'
            + group + "\n\n".join(body) + "\n    </group>\n</vector>\n")


def to_svg(cfg):
    shapes = build(cfg)
    body = "\n".join(
        f'  <path fill="{colour}" fill-rule="evenodd" d="{path_data(prim)}"/>'
        for _, prim, colour in shapes)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {VIEWPORT:.0f} '
            f'{VIEWPORT:.0f}" width="{VIEWPORT:.0f}" height="{VIEWPORT:.0f}">\n'
            f"{body}\n</svg>\n")


def _bounds(shapes):
    xs, ys = [], []
    for _, prim, _ in shapes:
        if isinstance(prim, Disc):
            xs += [prim.cx - prim.rx, prim.cx + prim.rx]
            ys += [prim.cy - prim.ry, prim.cy + prim.ry]
        elif isinstance(prim, Ring):
            xs += [prim.cx - prim.r_outer, prim.cx + prim.r_outer]
            ys += [prim.cy - prim.r_outer, prim.cy + prim.r_outer]
        else:
            xs += [p[0] for p in prim.points]
            ys += [p[1] for p in prim.points]
    return (min(xs), max(xs)), (min(ys), max(ys))


def preview(cfg, path, px=600, paper=(255, 253, 251, 255), sizes=(28, 48, 96)):
    """The mark at full size, plus the sizes the app actually draws it at."""
    from PIL import Image, ImageDraw
    shapes = build(cfg)
    scale = px / VIEWPORT

    big = Image.new("RGBA", (px, px), paper)
    for _, prim, colour in shapes:
        rgb = tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5)) + (255,)
        layer = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        if isinstance(prim, Disc):
            draw.ellipse([(prim.cx - prim.rx) * scale, (prim.cy - prim.ry) * scale,
                          (prim.cx + prim.rx) * scale, (prim.cy + prim.ry) * scale], fill=rgb)
        elif isinstance(prim, Ring):
            draw.ellipse([(prim.cx - prim.r_outer) * scale, (prim.cy - prim.r_outer) * scale,
                          (prim.cx + prim.r_outer) * scale, (prim.cy + prim.r_outer) * scale],
                         fill=rgb)
            draw.ellipse([(prim.cx - prim.r_inner) * scale, (prim.cy - prim.r_inner) * scale,
                          (prim.cx + prim.r_inner) * scale, (prim.cy + prim.r_inner) * scale],
                         fill=(0, 0, 0, 0))
        else:
            draw.polygon([(x * scale, y * scale) for x, y in prim.points], fill=rgb)
        big.alpha_composite(layer)

    strip = px // 3
    sheet = Image.new("RGBA", (px + strip, px), paper)
    sheet.alpha_composite(big, (0, 0))
    y = 0
    for dp in sizes:
        shot = big.resize((dp * 3, dp * 3), Image.LANCZOS)
        sheet.alpha_composite(shot, (px + (strip - dp * 3) // 2, y + 12))
        y += dp * 3 + 24
    sheet.convert("RGB").save(path)
    return path


if __name__ == "__main__":
    argv = sys.argv
    if "--preview" in argv:
        print(preview(CONFIG, argv[argv.index("--preview") + 1]))
    if "--svg" in argv:
        p = argv[argv.index("--svg") + 1]
        open(p, "w", encoding="utf-8", newline="\n").write(to_svg(CONFIG))
        print(f"wrote {p}")
    for flag, kwargs in (("--vd", {}),
                         ("--launcher", {"safe": 0.66}),
                         ("--mono", {"monochrome": True})):
        if flag in argv:
            p = argv[argv.index(flag) + 1]
            open(p, "w", encoding="utf-8", newline="\n").write(
                to_vector_drawable(CONFIG, **kwargs))
            longest = max(len(d) for d in __import__("re").findall(
                r'android:pathData="([^"]+)"', open(p).read()))
            print(f"wrote {p}  longest path {longest} chars")
