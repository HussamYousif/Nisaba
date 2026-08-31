# 08 — Response Rendering (the ink that answers)

Status: **Draft v0.1** · This is where "magic" becomes engineering. ADR-006: structured blocks.

## 1. Layout model

Each summon appends one **AnswerGroup** to the page, rendered in a `AnswerBlockView` region **below the user's writing** on the same scrollable page (F4.2):

```
┌──────────────────────────────────────┐
│  …user's handwritten strokes…        │
├──────────────────────────────────────┤
│  ✦  (flourish divider, drawn in)     │
│  [block 1: text]                     │
│  [block 2: drawing]                  │
│  [block 3: table]                    │
│  ✧  (end flourish)  ✒️ ↺ ⌫          │  ← regenerate / delete controls (F4.6)
├──────────────────────────────────────┤
│  …more user writing below…           │
└──────────────────────────────────────┘
```

- Blocks flow like a document; the whole page scrolls (vertical). The user's next writing starts below the last block.
- Everything is **vector** and re-rendered from the saved data on reopen (F4.7) — the response is part of the notebook, not a screenshot.

## 2. Block types (contract in 07 §6)

| Type | Renderer | Notes |
|---|---|---|
| `text` | `MarkdownInk` | Markdown subset → cursive spans |
| `drawing` | `SvgInkRenderer` | SVG subset → ink paths, draw-on animation |
| `table` | `TableInkRenderer` | Ruled ledger, calligraphic headers |

## 3. Typography — cursive but legible (N11)

User decision: English answers → Latin cursive fonts. Bundled (OFL, offline):

| Role | Font | Sizes (sp) | Why |
|---|---|---|---|
| Body text | **Dancing Script** (Medium 500) | 20 (min 18) | Flowing script, unusually legible; variable weight; reads well at arm's length |
| Headings (`##`) | **Great Vibes** | 26 (min 24) | Decorative calligraphy for the few headings; short lines only |
| Table headers | **Great Vibes** | 16 (min 14) | Ornamental but short strings |
| Table cells | **Kalam** (Regular) | 15 (min 14) | Handwritten look with near-print legibility; dense text must stay readable |
| Small labels / UI ink | **Kalam** | 12–14 | "The ink stirs…", controls |

Legibility rules:
- Body line-height 1.6, letterspacing +0.02em (script fonts need air).
- Never render script below 14 sp; never set body *italic* + script (fake italics forbidden — we only use true font weights).
- Color: sepia ink `#3A2E25` on parchment `#F3EAD8` (≥ 7:1 contrast, N11).
- Slight per-line baseline jitter (±0.5 px) for handwriting feel — never per-glyph rotation on body text (kills legibility).
- Justification: left-aligned, ragged right (script fonts justify badly).
- Paragraph spacing: 1.2em. `**bold**` → Dancing Script **Bold 700**; `*italics*` → Dancing Script 500 (no fake slant); headers → Great Vibes.

Markdown subset (per system prompt): `##`, `**bold**`, `*italics*`, `-` bullets, `1.` numbered, blank-line paragraphs. Rendered via `StaticLayout` + custom spans. **No** links/code/tables-in-markdown (tables are their own block).

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

- **Text**: render as static spans but reveal by *ink opacity ramp* in discrete steps — 6 steps × 2 frames per line (whole lines appear in order; not per-glyph — per-glyph typing is illegible and wasteful on e-ink).
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
