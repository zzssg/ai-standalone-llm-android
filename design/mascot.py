"""Generates the squirrel mascot as Android VectorDrawable path data.

The geometry is described once, as a list of primitives, and two backends
consume it: one emits VectorDrawable path data, the other rasterises with PIL so
a change can be looked at in a second instead of through a build and an emulator
round trip. Describing it twice is how the preview would start lying.

Everything is built from circles, arcs and one bezier outline, which is a
deliberate constraint: at 48dp a mascot made of clean geometry reads, and one
made of illustrative detail turns to mush.
"""
import math

# --- palette (kept in sync with Theme.kt) -----------------------------------
FUR = "#FFF07716"
FUR_DARK = "#FFC85410"
FUR_TAIL = "#FFD65E12"
CREAM = "#FFFFE9D6"
INK = "#FF2B1A10"
LENS = "#59F2FBFF"
LENS_RIM = "#FF0F7C96"
WHITE = "#FFFFFFFF"

VIEWPORT = 108.0


# --- primitives -------------------------------------------------------------

class Ellipse:
    def __init__(self, cx, cy, rx, ry=None):
        self.cx, self.cy, self.rx, self.ry = cx, cy, rx, ry if ry is not None else rx


class Poly:
    """A closed outline given as points, already flattened."""

    def __init__(self, points):
        self.points = points


class Ring:
    def __init__(self, cx, cy, r_outer, r_inner):
        self.cx, self.cy, self.r_outer, self.r_inner = cx, cy, r_outer, r_inner


def polar(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def bezier(p0, p1, p2, p3, n=24):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((
            u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
            u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1],
        ))
    return out


def capsule(x0, y0, x1, y1, r, n=16):
    """A round-ended bar, as an outline."""
    ang = math.degrees(math.atan2(y1 - y0, x1 - x0))
    pts = [polar(x1, y1, r, ang - 90 + 180 * i / n) for i in range(n + 1)]
    pts += [polar(x0, y0, r, ang + 90 + 180 * i / n) for i in range(n + 1)]
    return Poly(pts)


def tuft(tip, base_a, base_b):
    """An ear tuft: a soft triangle with a curved back."""
    return Poly(bezier(base_a, (base_a[0] - 1.5, base_a[1] - 4), tip, tip, 10)
                + bezier(tip, tip, (base_b[0] + 1.0, base_b[1] - 5), base_b, 10))


# --- the mascot -------------------------------------------------------------

def build(c):
    """Returns [(name, primitive, colour)] in painting order."""
    out = []

    # The tail is the whole point: it is what separates a squirrel from every
    # other small brown animal, so it is built as a plume of overlapping discs
    # that swell and taper along a curved spine rather than as a uniform band.
    # A band read as a handle in the first attempt.
    spine = bezier(
        (c["tail_x0"], c["tail_y0"]),
        (c["tail_x1"], c["tail_y1"]),
        (c["tail_x2"], c["tail_y2"]),
        (c["tail_x3"], c["tail_y3"]),
        n=c["tail_discs"] - 1,
    )
    for i, (x, y) in enumerate(spine):
        t = i / (len(spine) - 1)
        # Thin at the root and heavy at the tip, with the last two discs easing
        # off so the plume rounds instead of ending in a blunt circle.
        swell = t ** 0.7
        taper = 1.0 - max(0.0, (t - 0.88) / 0.12) ** 2 * 0.35
        r = (c["tail_r_min"] + (c["tail_r_max"] - c["tail_r_min"]) * swell) * taper
        out.append((f"tail{i}", Ellipse(x, y, r), FUR_TAIL))

    # Haunch, then body: two rounded shapes whose union is a sitting squirrel.
    out.append(("haunch", Ellipse(c["haunch_cx"], c["haunch_cy"],
                                  c["haunch_rx"], c["haunch_ry"]), FUR_DARK))
    out.append(("body", Ellipse(c["body_cx"], c["body_cy"],
                                c["body_rx"], c["body_ry"]), FUR))

    # Ears with tufts, behind the head so only their tops show.
    for side in (-1, 1):
        ex = c["head_cx"] + side * c["ear_dx"]
        ey = c["head_cy"] - c["ear_dy"]
        out.append((f"ear{side}", Ellipse(ex, ey, c["ear_rx"], c["ear_ry"]), FUR_DARK))
        out.append((f"tuft{side}", tuft(
            (ex + side * 1.5, ey - c["ear_ry"] - c["tuft_len"]),
            (ex - c["ear_rx"] * 0.8, ey - c["ear_ry"] * 0.4),
            (ex + c["ear_rx"] * 0.8, ey - c["ear_ry"] * 0.3),
        ), FUR_DARK))

    out.append(("head", Ellipse(c["head_cx"], c["head_cy"], c["head_r"]), FUR))
    out.append((f"innerear-1", Ellipse(c["head_cx"] - c["ear_dx"], c["head_cy"] - c["ear_dy"],
                                       c["ear_rx"] * 0.5, c["ear_ry"] * 0.5), CREAM))
    out.append((f"innerear1", Ellipse(c["head_cx"] + c["ear_dx"], c["head_cy"] - c["ear_dy"],
                                      c["ear_rx"] * 0.5, c["ear_ry"] * 0.5), CREAM))

    # The light shapes that stop it reading as a silhouette.
    out.append(("belly", Ellipse(c["belly_cx"], c["belly_cy"],
                                 c["belly_rx"], c["belly_ry"]), CREAM))
    out.append(("muzzle", Ellipse(c["muzzle_cx"], c["muzzle_cy"],
                                  c["muzzle_rx"], c["muzzle_ry"]), CREAM))

    for side in (-1, 1):
        out.append((f"eye{side}", Ellipse(c["eye_cx"] + side * c["eye_dx"],
                                          c["eye_cy"], c["eye_r"]), INK))
        out.append((f"glint{side}", Ellipse(c["eye_cx"] + side * c["eye_dx"] + c["eye_r"] * 0.38,
                                            c["eye_cy"] - c["eye_r"] * 0.42,
                                            c["eye_r"] * 0.36), WHITE))
    out.append(("nose", Ellipse(c["nose_cx"], c["nose_cy"],
                                c["nose_r"] * 1.25, c["nose_r"]), INK))

    # Arm and paw. Without them the glass reads as a separate object that
    # happens to overlap the animal, which is most of what was wrong with the
    # first two attempts.
    out.append(("arm", capsule(c["arm_x0"], c["arm_y0"], c["paw_cx"], c["paw_cy"],
                               c["arm_w"] / 2), FUR))

    # The magnifying glass. The lens is a pale tint rather than a saturated one:
    # a strong teal over orange fur composites to mud, and glass should lighten
    # what is behind it.
    gx, gy, gr = c["glass_cx"], c["glass_cy"], c["glass_r"]
    hx0, hy0 = polar(gx, gy, gr, c["handle_deg"])
    hx1, hy1 = polar(gx, gy, gr + c["handle_len"], c["handle_deg"])
    out.append(("handle", capsule(hx0, hy0, hx1, hy1, c["handle_w"] / 2), LENS_RIM))
    out.append(("lens", Ellipse(gx, gy, gr), LENS))
    # One short highlight in the upper left, where a light source would put it.
    out.append(("shine", capsule(*polar(gx, gy, gr * 0.62, 232),
                                 *polar(gx, gy, gr * 0.62, 285),
                                 gr * 0.11), "#B3FFFFFF"))
    out.append(("rim", Ring(gx, gy, gr + c["rim_w"], gr), LENS_RIM))

    # Last, so it closes over the rim: a paw behind the frame reads as a paw
    # near a floating magnifier rather than one holding it.
    out.append(("paw", Ellipse(c["paw_cx"], c["paw_cy"], c["paw_r"]), FUR_DARK))

    return out


# --- VectorDrawable backend -------------------------------------------------

def _n(v):
    return f"{v:.2f}"


def path_data(prim):
    if isinstance(prim, Ellipse):
        return (
            f"M{_n(prim.cx - prim.rx)},{_n(prim.cy)}"
            f"a{_n(prim.rx)},{_n(prim.ry)} 0 1,0 {_n(2 * prim.rx)},0"
            f"a{_n(prim.rx)},{_n(prim.ry)} 0 1,0 {_n(-2 * prim.rx)},0Z"
        )
    if isinstance(prim, Ring):
        ro, ri = prim.r_outer, prim.r_inner
        return (
            f"M{_n(prim.cx - ro)},{_n(prim.cy)}"
            f"a{_n(ro)},{_n(ro)} 0 1,0 {_n(2 * ro)},0"
            f"a{_n(ro)},{_n(ro)} 0 1,0 {_n(-2 * ro)},0Z"
            f"M{_n(prim.cx - ri)},{_n(prim.cy)}"
            f"a{_n(ri)},{_n(ri)} 0 1,1 {_n(2 * ri)},0"
            f"a{_n(ri)},{_n(ri)} 0 1,1 {_n(-2 * ri)},0Z"
        )
    if isinstance(prim, Poly):
        head = prim.points[0]
        rest = "".join(f"L{_n(x)},{_n(y)}" for x, y in prim.points[1:])
        return f"M{_n(head[0])},{_n(head[1])}{rest}Z"
    raise TypeError(prim)


#: What survives into the status-bar icon.
#
# A notification icon is masked to a flat silhouette at 24dp, where the full
# mascot collapses into a blob: fifteen overlapping tail discs and a translucent
# lens carry no information once everything is one colour. The head with its
# tufts and the magnifier's ring are the two shapes that still read at that
# size, and together they are still unmistakably this app.
NOTIFICATION_PARTS = ("head", "ear", "tuft", "rim", "handle")


def vector_drawable(cfg, size=108, monochrome=False, pad=2.0):
    """The mascot on a square canvas cropped to the drawing.

    The viewport is the drawing's own extent rather than the launcher's 108dp
    grid: left at 108 the art occupied about half its box, so every caller that
    asked for a 96dp mascot got a 60dp one floating in space, and had to guess a
    correction. Cropping here means a size in dp is the size on screen.
    """
    parts = []
    kept = [(n, p, c) for n, p, c in build(cfg)
            if not monochrome or n.startswith(NOTIFICATION_PARTS)]

    xs, ys = [], []
    for _, prim, _ in kept:
        if isinstance(prim, Ellipse):
            xs += [prim.cx - prim.rx, prim.cx + prim.rx]
            ys += [prim.cy - prim.ry, prim.cy + prim.ry]
        elif isinstance(prim, Ring):
            xs += [prim.cx - prim.r_outer, prim.cx + prim.r_outer]
            ys += [prim.cy - prim.r_outer, prim.cy + prim.r_outer]
        else:
            xs += [x for x, _ in prim.points]
            ys += [y for _, y in prim.points]
    x0, y0, x1, y1 = min(xs) - pad, min(ys) - pad, max(xs) + pad, max(ys) + pad
    side = max(x1 - x0, y1 - y0)
    dx = -x0 + (side - (x1 - x0)) / 2
    dy = -y0 + (side - (y1 - y0)) / 2

    for name, prim, colour in kept:
        fill_type = ' android:fillType="evenOdd"' if isinstance(prim, Ring) else ""
        parts.append(
            f"        <!-- {name} -->\n"
            f"        <path\n"
            f'            android:fillColor="{WHITE if monochrome else colour}"{fill_type}\n'
            f'            android:pathData="{path_data(prim)}" />'
        )

    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "    Generated by the mascot script in the design scratchpad: the shapes\n"
        "    are described as circles and arcs and emitted, rather than\n"
        "    hand-authored, so a proportion changes by editing a number and the\n"
        "    geometry stays exact. Edits here are lost on the next regeneration.\n"
        "-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{size}dp"\n'
        f'    android:height="{size}dp"\n'
        f'    android:viewportWidth="{_n(side)}"\n'
        f'    android:viewportHeight="{_n(side)}">\n\n'
        f'    <group android:translateX="{_n(dx)}" android:translateY="{_n(dy)}">\n\n'
        + "\n\n".join(parts)
        + "\n    </group>\n</vector>\n"
    )


def bounds(cfg):
    """The drawn extent, so the launcher transform is derived and not guessed."""
    xs, ys = [], []
    for _, prim, _ in build(cfg):
        if isinstance(prim, Ellipse):
            xs += [prim.cx - prim.rx, prim.cx + prim.rx]
            ys += [prim.cy - prim.ry, prim.cy + prim.ry]
        elif isinstance(prim, Ring):
            xs += [prim.cx - prim.r_outer, prim.cx + prim.r_outer]
            ys += [prim.cy - prim.r_outer, prim.cy + prim.r_outer]
        else:
            xs += [x for x, _ in prim.points]
            ys += [y for _, y in prim.points]
    return min(xs), min(ys), max(xs), max(ys)


def launcher_foreground(cfg, safe=66.0):
    """The same art, scaled and centred inside the launcher's safe zone.

    Every launcher mask crops differently, and only the central 66dp of the
    108dp canvas is guaranteed to survive. Rather than compromise the drawing to
    fit that, it is drawn full size and a group transform brings it in.
    """
    x0, y0, x1, y1 = bounds(cfg)
    scale = safe / max(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2

    paths = []
    for name, prim, colour in build(cfg):
        fill_type = ' android:fillType="evenOdd"' if isinstance(prim, Ring) else ""
        paths.append(
            "        <!-- " + name + " -->\n"
            "        <path\n"
            '            android:fillColor="' + colour + '"' + fill_type + "\n"
            '            android:pathData="' + path_data(prim) + '" />'
        )

    header = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "    Launcher foreground. Generated; see the mascot script in the design\n"
        "    scratchpad. The group transform fits the drawing to the 66dp safe\n"
        "    zone, so no launcher mask can clip the tail or the handle.\n"
        "-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n\n'
        "    <group\n"
        '        android:pivotX="' + _n(cx) + '"\n'
        '        android:pivotY="' + _n(cy) + '"\n'
        '        android:scaleX="' + _n(scale) + '"\n'
        '        android:scaleY="' + _n(scale) + '"\n'
        '        android:translateX="' + _n(54 - cx) + '"\n'
        '        android:translateY="' + _n(54 - cy) + '">\n\n'
    )
    return header + "\n\n".join(paths) + "\n    </group>\n</vector>\n"


# --- PIL preview backend ----------------------------------------------------

def _rgba(hex_argb):
    h = hex_argb.lstrip("#")
    a, r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4, 6))
    return (r, g, b, a)


def preview(cfg, path, scale=6, background=(255, 248, 242, 255), safe_zone=True):
    from PIL import Image, ImageDraw
    size = int(VIEWPORT * scale)
    img = Image.new("RGBA", (size, size), background)

    def stamp(points, colour):
        layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        ImageDraw.Draw(layer).polygon([(x * scale, y * scale) for x, y in points], fill=colour)
        img.alpha_composite(layer)

    for _, prim, colour in build(cfg):
        rgba = _rgba(colour)
        if isinstance(prim, Ellipse):
            stamp([(prim.cx + prim.rx * math.cos(math.radians(t)),
                    prim.cy + prim.ry * math.sin(math.radians(t))) for t in range(0, 361, 4)], rgba)
        elif isinstance(prim, Ring):
            outer = [polar(prim.cx, prim.cy, prim.r_outer, t) for t in range(0, 361, 4)]
            inner = [polar(prim.cx, prim.cy, prim.r_inner, t) for t in range(360, -1, -4)]
            stamp(outer + inner, rgba)
        else:
            stamp(prim.points, rgba)

    if safe_zone:
        lo, hi = 21 * scale, 87 * scale
        ImageDraw.Draw(img).ellipse([lo, lo, hi, hi], outline=(0, 0, 0, 45), width=2)
    img.save(path)
    return path


CONFIG = dict(
    # Tail: root tucked behind the haunch, sweeping left and up, the tip curling
    # in above the head. Kept high on purpose, which leaves the lower left clear
    # for the glass instead of the two fighting over the same corner.
    tail_x0=58.0, tail_y0=80.0,
    tail_x1=33.0, tail_y1=74.0,
    tail_x2=21.0, tail_y2=39.0,
    tail_x3=45.0, tail_y3=25.0,
    tail_discs=15, tail_r_min=4.5, tail_r_max=10.0,

    haunch_cx=64.0, haunch_cy=76.0, haunch_rx=15.0, haunch_ry=12.5,
    body_cx=67.0, body_cy=62.0, body_rx=14.0, body_ry=16.5,

    head_cx=70.0, head_cy=38.0, head_r=14.0,
    ear_dx=9.5, ear_dy=11.0, ear_rx=5.2, ear_ry=6.0, tuft_len=5.5,

    belly_cx=69.0, belly_cy=66.0, belly_rx=8.5, belly_ry=11.5,
    muzzle_cx=76.0, muzzle_cy=43.0, muzzle_rx=8.2, muzzle_ry=6.6,
    eye_cx=70.0, eye_cy=35.0, eye_dx=6.8, eye_r=3.4,
    nose_cx=81.5, nose_cy=41.5, nose_r=2.0,

    arm_x0=60.0, arm_y0=72.0, arm_w=8.0,
    paw_cx=45.0, paw_cy=69.5, paw_r=5.5,
    glass_cx=35.0, glass_cy=79.0, glass_r=11.0, rim_w=3.0,
    handle_deg=55.0, handle_len=8.0, handle_w=5.5,
)

if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "preview":
        print(preview(CONFIG, sys.argv[2]))
    elif len(sys.argv) > 1 and sys.argv[1] == "launcher":
        with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as fh:
            fh.write(launcher_foreground(CONFIG))
        print(f"wrote {sys.argv[2]}  bounds={bounds(CONFIG)}")
    else:
        out = sys.argv[1]
        mono = len(sys.argv) > 2 and sys.argv[2] == "mono"
        with open(out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(vector_drawable(CONFIG, monochrome=mono))
        print(f"wrote {out}")
