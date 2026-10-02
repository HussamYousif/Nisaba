#!/usr/bin/env python3
"""Exports Hershey single-line fonts to the app's JSON hand format (baseline y=0, x-height=1, y up).

  pip install Hershey-Fonts   # MIT-packaged, Hershey data is public domain (see docs/licenses.md)
  python tools/scripts/export_hershey.py

Output: scribe/src/main/resources/hands/{casual,formal}.json
Format: {"name","xHeight":1,"ascender","descender","glyphs":{"a":{"adv":0.9,"strokes":[{"pts":[x,y,...],"delayed":false}]}}}
"""
import json, os
from HersheyFonts import HersheyFonts

HANDS = {"casual": "futural", "formal": "cursive"}   # print-like (most legible) and cursive (prettier, harder to read)
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "scribe", "src", "main", "resources", "hands")
CHARS = [chr(c) for c in range(32, 127)]
TARGET_CAP = 1.8  # capitals should stand ~1.8 x-heights tall


def priv(g, name):
    return getattr(g, "_HersheyGlyph__" + name)


# Hershey's `greeks` stores the Greek alphabet IN ORDER under a..x (and A..X): a=alpha, b=beta, c=gamma, ... x=omega.
GREEK_ORDER = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda", "mu", "nu", "xi",
               "omicron", "pi", "rho", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega"]
GREEK_LOWER = {chr(ord("a") + k): n for k, n in enumerate(GREEK_ORDER)}
GREEK_UPPER = {chr(ord("A") + k): n.capitalize() for k, n in enumerate(GREEK_ORDER)
               if n in ("gamma", "delta", "theta", "lambda", "xi", "pi", "sigma", "upsilon", "phi", "psi", "omega")}


def greek_glyphs(xh_font_units, base):
    """Greek letters from Hershey `greeks`, normalised with the SAME x-height and baseline as the print hand."""
    f = HersheyFonts(); f.load_default_font("greeks")
    out = {}
    for latin, name in list(GREEK_LOWER.items()) + list(GREEK_UPPER.items()):
        g = list(f.glyphs_for_text(latin))[0]
        left, right = priv(g, "left_side"), priv(g, "right_side")
        strokes = []
        for s in g.strokes:
            pts = []
            for x, y in s:
                pts += [round((x - left) / xh_font_units, 4), round((priv(g, "baseline") - y) / xh_font_units, 4)]
            strokes.append({"pts": pts, "delayed": False})
        out[name] = {"adv": round((right - left) / xh_font_units, 4), "strokes": strokes}
    return out


def futural_metrics():
    f = HersheyFonts(); f.load_default_font("futural")
    gx = list(f.glyphs_for_text("x"))[0]
    base = priv(gx, "baseline")
    return base - min(y for st in gx.strokes for _, y in st), base


GREEK = None


def export(name, font):
    global GREEK
    if GREEK is None: GREEK = greek_glyphs(*futural_metrics())
    f = HersheyFonts(); f.load_default_font(font)
    gx = list(f.glyphs_for_text("x"))[0]
    base = priv(gx, "baseline")
    xh = base - min(y for s in gx.strokes for _, y in s)            # x-height in font units
    glyphs = {}
    top, bottom = 0.0, 0.0
    for ch in CHARS:
        g = list(f.glyphs_for_text(ch))[0]
        left, right = priv(g, "left_side"), priv(g, "right_side")
        strokes = []
        for s in g.strokes:
            pts = []
            for x, y in s:
                pts += [round((x - left) / xh, 4), round((base - y) / xh, 4)]
            ys = pts[1::2]
            above = min(ys) > 0.02 and len(pts) <= 12                  # dot above the x-height line (i, j, !, ?, ;, ")
            bar = ch in "tf" and len(pts) <= 6 and (max(ys) - min(ys)) < 0.2  # crossbars
            strokes.append({"pts": pts, "delayed": bool(above or bar)})
            top, bottom = max(top, max(ys)), min(bottom, min(ys))
        strokes.sort(key=lambda s: s["delayed"])                        # pen-lift extras go last, original order kept
        glyphs[ch] = {"adv": round((right - left) / xh, 4), "strokes": strokes}
    gh = list(f.glyphs_for_text("H"))[0]
    cap = (base - min(y for st in gh.strokes for _, y in st)) / xh          # capital height in x-heights
    cap_scale = round(min(1.0, TARGET_CAP / cap), 3)                       # tame tall cursive capitals
    math = GREEK
    hand = {"name": name, "xHeight": 1.0, "ascender": round(top, 3), "descender": round(bottom, 3), "capHeight": round(cap, 3), "capScale": cap_scale, "glyphs": glyphs}
    if math: hand["math"] = math
    path = os.path.join(OUT, name + ".json")
    with open(path, "w") as fh:
        json.dump(hand, fh, separators=(",", ":"))
    print(f"{name}: font={font} xh={xh} glyphs={len(glyphs)} bytes={os.path.getsize(path)} asc={top:.2f} desc={bottom:.2f}")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for n, fn in HANDS.items():
        export(n, fn)
