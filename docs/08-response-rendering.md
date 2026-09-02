# 08 — Response Rendering (the ink that answers)

Status: **Draft v0.1** · This is where "magic" becomes engineering. ADR-006: structured blocks.

## 1. Layout model

Each summon appends one **AnswerGroup** to the page, rendered as **ink on the canvas itself** — in page space, directly below the user's last content on the same scrollable page (F4.2). There is no separate UI section: the scribe's reply appears exactly where the user is looking, drawn by `AnswerInk` (text lines, drawing paths, ruled tables) and revealed step by step (`InkSurfaceView.setAnswers`). The page's display height grows to fit the answer.

```
┌──────────────────────────────────────┐
│  …user's handwritten strokes…        │
├──────────────────────────────────────┤
│  ✦  (flourish divider, drawn in)     │
│  [block 1: text]                     │
│  [block 2: drawing]                  │
│  [block 3: table]                    │
│  ✧  (end flourish)                  │  ← F4.6 regenerate/delete controls are M5 backlog (not in v1)
├──────────────────────────────────────┤
│  …more user writing below…           │
└──────────────────────────────────────┘
```

- Blocks flow like a document; the whole page scrolls (vertical). The user's next writing starts below the last block.
- Everything is **vector** and re-rendered from the saved data on reopen (F4.7) — the response is part of the notebook, not a screenshot.

## 2. Block types (contract in 07 §6)

| Type | Renderer | Notes |
|---|---|---|
| `text` | `AnswerInk` + `hand.Scribe` (Stroke items) | Text → synthesized pen strokes (§3) |
| `drawing` | `SvgInkRenderer` | SVG subset → ink paths, draw-on animation |
| `table` | `AnswerInk` (Rule items) | Ruled ledger, calligraphic headers |

## 3. Typography — the scribe's hand (no fonts for prose)

The answer text is not typeset: it is **written**. The `hand` domain
(`com.nisaba.hand.Scribe`, pure geometry, JVM-tested) synthesizes pen strokes
from plain text — the same kind of ink the user writes with. Glyphs are
authored as *pen knots* (the points a hand passes through), spline-fitted
into smooth strokes; every letter ends at the baseline so consecutive letters
share the point (the connected wave — the pen bounces at the baseline and
never lifts inside a word).

Writing model:

- **Alphabet** (`Glyphs`): lowercase a–z, capitals, digits, punctuation, in
  one shared hand. Proportions (hump pitch ≈ 0.5×x-height, ring widths,
  ascender 1.6×x-height) are tuned against the bundled cursive fonts, which
  are kept only for ornaments and table cells.
- **Determinism**: everything derives from the group id; a saved answer
  re-inks itself identically on reopen (pinned by tests).
- **Human variation** (seeded, per instance): per-letter scale/slant/rotation
  and vertical bob, per-word x-height and spacing, whole-line baseline
  wander and slope drift. `Options.variation = 0` is the perfect-schoolbook
  render used while authoring glyphs.
- **Pen feel**: stroke width is modulated along the path — thick downstrokes,
  thin upstrokes, ramps where the pen lands and lifts — and short strokes
  (i-dots, commas, full stops) are heavy stabs, not sweeps.
- **Pen-lift marks**: i/j dots and t-crossings are drawn after their word;
  capitals, digits and punctuation lift the pen (the next letter starts past
  the glyph's rightmost ink).
- **Typography mapping**: `##` headings are scribed larger; `-` bullets get a
  hand-drawn dash with hanging indent; `**bold**`/`*italics*` markers are
  stripped (the hand has one weight); typographic quotes/dashes/ellipses map
  to their plain forms; characters outside the alphabet become a small
  visible scratch rather than being dropped.

Legibility is verified the same way the scribe is read back on-device: the
app's own vision model must transcribe the synthesized pangram (and answer
pages) word for word — a hard gate that ran during glyph authoring.

Sizes are page-space px: body x-height 44, headings 56; sepia ink
`#3A2E25` on parchment `#F3EAD8`. Left-aligned, ragged right; a line fits
the block width or a lone overlong word is squeezed to fit. Table cells and
the ✦/✧ flourishes remain font-rendered (dense small text must stay
readable; ornaments are single glyphs).

## 4. SVG subset spec
## 4. SVG subset spec (the drawing block)

Normalized canvas: `viewBox="0 0 1000 1000"` (aspect-agnostic; renderer letterboxes into the block width, height ≤ 420 dp).

| Element | Allowed attrs |
|---|---|
| `<path>` | `d` (M/L/C/Q/Z only), `stroke`, `stroke-width` (4–10), `stroke-linecap="round"`, `stroke-linejoin="round"`, `fill` (#3A2E25 or `none`), `opacity` (1.0/0.35), `transform` (translate/scale) |
| `<polyline>` `<line>` | same stroke attrs |
| `<rect>` `<circle>` `<ellipse>` | stroke attrs + `fill` |
| anything else | **rejected** (dropped with warning) |

- Parsed with `PathParser`/custom tokenizer → `android.graphics.Path`; `<text>` etc. rejected per system prompt (the model is told: no text in drawings).
- Safety limits enforced at parse: ≤ 40 elements, ≤ 2500 chars, no nested transforms; oversized → "«the drawing smudged»" placeholder (07 §6).
- Hatching (`opacity 0.35` fills) supported for shading — dithers acceptably on e-ink.

## 5. Ink-reveal animation (draw-on effect)

The magical "a quill is drawing this" effect, e-ink-safe (N9):

- **Text**: every word is one continuous pen stroke, revealed front-to-back by arc length — the pen visibly writes word by word (dots and crossings follow their word); each stroke takes ~2–14 discrete steps depending on length.
- **Drawings**: `PathMeasure.getSegment()` ink-draw: path revealed front-to-back in 8–14 discrete steps; each step = one partial-refresh `invalidate` (A2), final step triggers one Regal clean pass. Total 2–5 s for a full drawing.
- **Tables**: ruled lines stroke in left→right (each line ~2 steps), then cells' text appears row by row.
- Engine: `InkReveal` — a single Choreographer loop, discrete *steps* (never per-frame 60 fps), no alpha fades (e-ink can't do them), no motion blur, no scaling animations.
- Every reveal is **skippable**: tap → complete instantly (all steps collapse to final state + clean refresh).

## 6. Table rendering

- Columns sized by content (max cell width 220 dp; truncate with "…" at 40 chars per contract).
- Ink rules: header row = Great Vibes, thin double rule under header; horizontal rules every 4 rows (ledger style); vertical rules only between columns.
- Alternating rows: no shading in v1 (e-ink dithering cost); row banding v2 with 0.35 opacity hatch.
- Wide tables: allow horizontal scroll within the block (min column 90 dp).

## 7. Controls & state (F4.6)

- ✒️ **"Write it again"** → regenerates the whole AnswerGroup (new summon with same page snapshot + transcript minus this group); on success the group is replaced atomically (F5.3).
- ⌫ **Smudge away** → animated ink smudge (discrete gray-streak steps) then removal.
- Long-press text block → "Copy text".
- While a reveal is running, controls disabled; while a request is pending, the previous group shows a "rewriting…" state.

## 8. Future: RTL & Arabic (OQ)

If Arabic answers are ever wanted (user selected English for v1): add an Arabic cursive font (e.g. *Aref Ruqaa* or *Amiri*), RTL `StaticLayout` direction per block, and an `rtl: true` flag in the block contract; the SVG contract is direction-neutral. Explicitly **not** in v1 scope.
