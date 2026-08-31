# 04 — E-ink & Stylus Input Strategy

Status: **Draft v0.1** · Target: Boox Note Air 5c (verify all assumptions on the device in M1)

## 1. Hardware assumptions (to verify on device)

| Property | Assumption | Verify |
|---|---|---|
| Screen | 10.3" Kaleido 3, 2480×1860 px (300 ppi B/W, 150 ppi color) | settings / adb `wm size` |
| OS | Android 13+ (Boox OS layer on top) | device `getprop ro.build.version.release` |
| Stylus | Wacom EMR, ~4096 pressure levels, hover events, side button | write a probe app in M1 |
| Palm rejection | System-level in Boox notes app; **not** guaranteed for third-party apps | must implement hover-gating ourselves (04 §4) |
| Boox SDK (`com.onyx.android.sdk`) | Exists for EPD mode control; availability on this device unknown | probe in M1; ADR-002 fallback = standard APIs |
| Network | Wi-Fi only | — |

**M1 device probe checklist** (do before any polish):
1. `wm size`, density, Android version.
2. Do `ACTION_HOVER_MOVE` events arrive for the EMR pen? (palm rejection feasibility)
3. Does the pen side-button deliver `BTN_STYLUS`/`BTN_STYLUS2` in MotionEvents or key events?
4. Does standard `invalidate()` produce acceptable refresh while writing (A2-like), or do we need the Boox SDK / Regal hints?
5. Cold start timing; frontlight on/off contrast of parchment palette.

## 2. EPD refresh strategy

E-ink refreshes are the #1 UX risk. Rules that shape everything:

1. **While writing (strokes arrive):** draw into the view's canvas and invalidate dirty rects only. The device's fast mode (A2-like, via Boox "App Optimization" → the app can request it) shows ink nearly instantly with slight ghosting that disappears once the pen lifts.
2. **On pen-up + 400 ms quiet:** trigger one *Regal/GC16* clean pass over the changed region to eliminate ghosting (no full flash).
3. **Full-screen "clear" flashes:** allowed only for page turns, notebook open/close, and PDF import page changes. Never during writing or answer reveal.
4. **Animations (answer reveal, ink stirring):** never rely on alpha fades or smooth interpolation. Use **discrete steps** (e.g. 6–12 visible frames per reveal, each an EPD-friendly partial update) — see 08 §5 and `InkReveal` design.
5. **No animated page transitions** (no sliding). Page turns = instant swap + clear refresh.
6. **Frontlight/brightness:** we never touch it; we design for both lit and unlit reading (high contrast palette, 09 §3).

**Boox "App Optimization" guidance for the user (also in 10 §5):** set Nisaba to *Regal* (best text) or *Fast* mode if offered, disable "animations", and exempt from battery optimization.

## 3. Rendering pipeline (latency budget)

The stroke path must feel instant (N1: ≤ 16 ms to first ink):

```
MotionEvent (UI thread)
  → StrokeCapture: convert to page space, append to point pool
  → compute ink quad for [p_{n-2}, p_{n-1}, p_n] segment   (~2–5 µs)
  → invalidate(bounding rect of new segment)
  → StrokeRenderer draws buffered quads into Canvas (GPU)
  → Surface flips → EPD displays (fast mode)
```

- **No per-point allocations**: pooled `FloatArray` per stroke, grown geometrically; points flushed to the stroke object on pen-up.
- **Pressure→width curve**: `w = base + k·p^1.4` (nonlinear, feels like a real nib); base ≈ 1.5 px, max ≈ 6 px at page resolution. Ink color = current palette ink (09 §3).
- **Min/max segment length**: drop points closer than 0.5 px (avoids quads at rest); interpolate nothing (EMR is dense enough).
- Stroke is **vector**: never rasterize handwriting; the page bitmap is only the background.

## 4. Palm rejection (critical on Boox)

Boox notes apps reject palms at the system level; third-party apps must do it themselves. Our design:

1. **Hover-gating (primary):** EMR pens emit `ACTION_HOVER_ENTER/MOVE/EXIT` *before* touching. While `hoverActive == true`, any `ACTION_DOWN` that is not from the stylus tool type is treated as palm and ignored.
2. **Tool-type gating:** only `TOOL_TYPE_STYLUS` events draw. `TOOL_TYPE_FINGER` draws nothing, ever.
3. **Multi-touch**: two fingers = pan (v2 zoom); but while the pen is hovering or drawing, touch is fully ignored.
4. **Edge case — gloves / hover not delivered:** if probe shows no hover events, fall back to: ignore finger events that land while the pen was used within the last 1.5 s, plus a "palm-off" region at the bottom-right (the natural resting zone) ignored for fingers. Decide after M1 probe.
5. **Eraser via pen button:** side button toggles eraser *while held* (like the Boox default): `MotionEvent.isButtonPressed(BUTTON_STYLUS)` or key events — resolved in M1 probe. Eraser = stroke hit-test (v1), stroke erasure leaves no gaps (whole stroke disappears).

## 5. Capture details

| Event | Handling |
|---|---|
| `ACTION_DOWN` (stylus) | start stroke, record `t0` |
| `ACTION_MOVE` (stylus) | append `(x, y, pressure, t)`, update dirty rect, reset summon idle timer |
| `ACTION_UP` | finalize stroke → undo stack + autosave debounce; arm idle detection (F3.1) |
| `ACTION_CANCEL` | discard current stroke |
| `ACTION_HOVER_*` | set/clear hover gate |
| Side button | eraser while held (v1: whole-stroke erase) |

Coordinates: page space is fixed (blank: 2480×1860; PDF: page box at 300 dpi), view applies `PageTransform` (scale+translate). Strokes are stored in page space so they are zoom-independent.

## 6. Color policy

- B/W text dominates; color e-ink is 150 ppi and slower — use it sparingly.
- Ink palette (v1): **Ink Black** (#26221C), **Sepia** (#4A3A28), **Deep Blue** (#2C3E66). All ≥ 7:1 contrast on parchment.
- Backgrounds: parchment tones only (09 §3). No colored UI chrome except the single accent (quill button) in a deep ink red (#7A2E1D) — visible in B/W too (luminance ≠ background).
- Avoid thin colored lines (< 2 px) and small colored text.

## 7. Performance budgets (N5–N7)

| Metric | Budget |
|---|---|
| Touch → ink pixel | ≤ 16 ms |
| Point append + quad | ≤ 10 µs |
| Autosave serialize (500-stroke page) | ≤ 50 ms (IO thread) |
| Page render for LLM (2480×1860 → PNG) | ≤ 600 ms (IO thread) |
| Answer reveal total | 2–8 s, discrete steps, ≤ 12 partial refreshes |
| Memory: background cache | ≤ 3 pages × ~9 MB (RGB_565 @ 200 dpi A4) |
| Idle CPU (open notebook) | 0% — invalidate only on change; no polling (Handler timer only, and it's cheap) |

## 8. Device-testing checklist (M1+)

- [ ] Write 200 strokes continuously — any dropped points? ghosting after lift?
- [ ] Palm-rest on the left half while writing with right hand — no stray marks.
- [ ] Pen button eraser — consistent across apps/regions?
- [ ] Summon while writing (cancel-on-resume) — no visible glitch, no stuck indicator.
- [ ] Answer reveal with a complex drawing — ghosting acceptable? steps visible but smooth?
- [ ] 2-hour idle with notebook open — battery drain < 5%.
- [ ] Kill app during autosave — reopen, zero strokes lost (N4).
