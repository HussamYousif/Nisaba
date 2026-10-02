#!/usr/bin/env python3
"""Generates the Nisaba mark: Android vector drawables (launcher foreground + monochrome) and the 512 px Play icon.

The mark is Nisaba's star: eight cuneiform wedges around a small hub; the south wedge is a longer reed-pen nib in lapis.
  .tools/venv/bin/python tools/scripts/make_brand.py
"""
import math, os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
INK, LAPIS, PAPER = "#111111", "#27407A", "#F6F2EA"
N = 8


def wedge(cx, cy, angle_deg, length, base, inner):
    a = math.radians(angle_deg); dx, dy = math.cos(a), math.sin(a); nx, ny = -dy, dx
    tip = (cx + dx * length, cy + dy * length)
    b1 = (cx + dx * inner + nx * base, cy + dy * inner + ny * base)
    b2 = (cx + dx * inner - nx * base, cy + dy * inner - ny * base)
    return [tip, b1, b2]


def wedges(cx, cy, r):
    """Returns [(polygon, is_nib)] — the south wedge (angle 90°) is the long nib."""
    out = []
    for i in range(N):
        ang = 90 + i * 360 / N                       # start at the south wedge
        nib = i == 0
        out.append((wedge(cx, cy, ang, r * (1.42 if nib else 1.0), r * (0.2 if nib else 0.19), r * 0.2), nib))
    return out


def path_data(poly):
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in poly) + " Z"


def vector(name, color_for, viewport=108.0, cx=54.0, cy=46.0, r=24.0, hub=True):
    parts = []
    for poly, nib in wedges(cx, cy, r):
        parts.append(f'    <path android:fillColor="{color_for(nib)}" android:pathData="{path_data(poly)}"/>')
    if hub:
        parts.append(f'    <path android:fillColor="{color_for(False)}" android:pathData="M{cx-2.6:.2f},{cy:.2f} a2.6,2.6 0 1,0 5.2,0 a2.6,2.6 0 1,0 -5.2,0 Z"/>')
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{viewport:.0f}dp" '
           f'android:height="{viewport:.0f}dp" android:viewportWidth="{viewport:.0f}" android:viewportHeight="{viewport:.0f}">\n'
           + "\n".join(parts) + "\n</vector>\n")
    open(os.path.join(ROOT, "app/src/main/res/drawable", name + ".xml"), "w").write(xml)


def play_icon(size=512):
    scale = 4
    img = Image.new("RGB", (size * scale, size * scale), PAPER)
    d = ImageDraw.Draw(img)
    cx, cy, r = size * scale / 2, size * scale * 0.44, size * scale * 0.29
    for poly, nib in wedges(cx, cy, r):
        d.polygon(poly, fill=LAPIS if nib else INK)
    hr = r * 0.11
    d.ellipse((cx - hr, cy - hr, cx + hr, cy + hr), fill=INK)
    img = img.resize((size, size), Image.LANCZOS)
    os.makedirs(os.path.join(ROOT, "docs/play"), exist_ok=True)
    img.save(os.path.join(ROOT, "docs/play/icon-512.png"))


if __name__ == "__main__":
    vector("ic_launcher_foreground", lambda nib: LAPIS if nib else INK)
    vector("ic_launcher_monochrome", lambda nib: "#000000")
    vector("ic_mark", lambda nib: LAPIS if nib else INK, viewport=108.0, cx=54.0, cy=46.0, r=24.0)
    play_icon()
    print("brand assets written")
