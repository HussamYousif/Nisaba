# 02 — Requirements

Status: **Draft v0.1** · Scope: v1 (first usable APK on the Boox Note Air 5c)

IDs are stable. Items marked **[v2]** are explicitly deferred, listed here so the design doesn't paint us into a corner.

## 1. Scenarios (user stories)

| # | Story |
|---|---|
| S1 | As a user with a new tablet, I open Nisaba and see a shelf of my notebooks (or an empty shelf), so I can pick where to write. |
| S2 | As a user, I tap **"A Blank Parchment"** and instantly get a fresh blank page to write on. |
| S3 | As a user, I tap **"Open a Tome"**, pick a PDF, and get a notebook whose pages are the PDF pages (with a progress indicator while importing). |
| S4 | As a user, I write with the stylus: strokes appear instantly with pressure-sensitive thickness, and my palm resting on the screen does nothing. |
| S5 | As a user, I make a mistake: I erase with the pen's side button (or an eraser tool), and I can undo/redo strokes. |
| S6 | As a user, I finish writing a question and pause; after ~3 seconds the notebook begins "reading" the page; if I resume writing it cancels, and if I do nothing the answer appears below my writing. |
| S7 | As a user, I can always tap the floating **quill button** to summon the answer immediately instead of waiting. |
| S8 | As a user, the answer arrives as **cursive text, ruled tables, or hand-drawn pictures** — matching what I asked for — and draws itself in gradually. |
| S9 | As a user, I can ask follow-ups by writing below the previous answer; the notebook remembers the conversation. |
| S10 | As a user, I can regenerate an answer (✒️ "Write it again") or delete it (smudge it away), and the conversation stays consistent. |
| S11 | As a user, I close the app mid-thought; when I reopen the notebook, everything (strokes + answers) is exactly where I left it. |
| S12 | As a user, I export a page as a PNG image to share or archive. **[v2: export whole notebook to PDF]** |
| S13 | As a user, I enter my DeepSeek API key once in Settings and forget about it. |
| S14 | As a user on Wi-Fi, if the summon fails (offline, timeout, bad key), I get a friendly, magical error and can retry — my writing is never lost. |

## 2. Functional requirements

### F1 — Notebooks
- **F1.1** Create a blank notebook (title auto-generated, e.g. *"Notebook 3"*; renameable). 
- **F1.2** Create a notebook from a PDF (see F6).
- **F1.3** List notebooks on the shelf with a rendered thumbnail of the most recent page.
- **F1.4** Delete a notebook (with a two-step confirm — long-press → "Smudge away").
- **F1.5** Rename a notebook.
- **F1.6** Multi-page notebooks: swipe or tap page-edge to turn pages; new blank page button (⇢). **[v2: insert blank pages between PDF pages]**

### F2 — Stylus writing
- **F2.1** Capture stylus strokes: polyline points with `(x, y, pressure, timestamp)`; ink thickness maps pressure → width curve (see 04 §5).
- **F2.2** Fingers never draw. Stylus hover suppresses finger/touch drawing (palm rejection, see 04 §4).
- **F2.3** Eraser: pen side-button toggle + toolbar eraser tool; erases whole strokes in v1 (pixel-level erasing in v2).
- **F2.4** Undo/redo stack (stroke-level), including erases.
- **F2.5** Ink colors: v1 offers *Ink Black*, *Sepia*, and *Deep Blue* (limited palette — see 04 §6).
- **F2.6** Stroke data must survive app death at any point (autosave debounce + atomic writes, see 05 §4).
- **F2.7** Zoom: fixed 100% fit-width in v1; pinch-zoom with re-render in v2. (10.3" at 300ppi makes 100% usable; revisit after device testing.)

### F3 — Summoning the answer
- **F3.1** Idle detection: no new stroke points for **3.0 s** (tunable in Settings) *and* at least one new stroke since the last summon → auto-send the page.
- **F3.2** Cancel-on-resume: if the user produces new stroke points while a request is pending, cancel the request (network call aborted) and re-arm idle detection. A partially-started answer is discarded cleanly.
- **F3.3** Manual summon: floating **quill button**; also re-summons the *latest question* (e.g. after an error) and re-sends the current page with full context.
- **F3.4** Only send when there is something new to answer; never re-summon identical content automatically.
- **F3.5** While a request is in flight: show a subtle "the ink stirs…" indicator; input remains fully usable (user may doodle); a small ✕ cancels manually.
- **F3.6** Only the **current page** is sent as image (plus conversation transcript as text — see 07 §5). **[v2: include last N pages]**

### F4 — Answer blocks (the response)
- **F4.1** Response = ordered list of blocks: `text` (markdown subset), `drawing` (SVG subset), `table`. Contract in 07 §6, rendering in 08.
- **F4.2** Blocks render below the user's writing on the same page, separated by a thin flourish divider; the whole exchange is persisted as part of the notebook.
- **F4.3** Text renders in the cursive body font, legible at arm's length (≥ ~18sp effective), headings in the calligraphic font (see 08 §3).
- **F4.4** Drawings animate in as "ink being drawn" (PathMeasure, discrete steps — see 08 §5). Drawings are constrained to a safe SVG subset (no gradients, no text, bounded size).
- **F4.5** Tables render as ruled lines with calligraphic headers, auto-sized columns (see 08 §6).
- **F4.6** Per-answer controls: regenerate (✒️), delete (smudge), and "copy text" on long-press. **[v2: edit]**
- **F4.7** Answers are stored in the notebook transcript; reopening the page re-renders them exactly (vector, not screenshots).

### F5 — Conversation memory
- **F5.1** The notebook keeps a transcript of exchanges (user turn = page + optional note; assistant turn = blocks).
- **F5.2** On summon, the last ≤ 10 exchanges are included as text context (the *current* page is always sent as the image).
- **F5.3** Deleting an answer removes it and its contribution to context; regenerating replaces it atomically.

### F6 — PDF import
- **F6.1** Import via Android Storage Access Framework (SAF) `ACTION_OPEN_DOCUMENT`; `application/pdf`.
- **F6.2** Import progress UI (page count, "the tome is being copied…"); cancellable.
- **F6.3** Each PDF page becomes a notebook page with the PDF page rendered as background; user strokes overlay it.
- **F6.4** Backgrounds render at ≤ 200 dpi, RGB_565, with an LRU cache (memory strategy in 06 §4).
- **F6.5** Imported PDF file is copied into app storage (not streamed from source) so it survives source deletion.
- **F6.6** Encrypted PDFs: prompt for password (PdfRenderer supports it) **[v2 if needed]**.
- **F6.7** Rendered background quality must keep text readable at 100% zoom; zoom-dependent re-render in v2.

### F7 — Settings
- **F7.1** DeepSeek **API key** (masked, stored in EncryptedSharedPreferences — see 03 §7).
- **F7.2** Base URL (default `https://api.deepseek.com`), **model name** (default `deepseek-v4-flash-vision-exp`).
- **F7.3** Response language (default English), temperature (default 0.7), max tokens (default 4096).
- **F7.4** Idle-pause duration (default 3 s), auto-summon on/off, sounds on/off.
- **F7.5** Theme tone (parchment shades), ink color default.
- **F7.6** "Test the connection" button that sends a tiny request and reports a friendly result.

### F8 — Export **[v2: PDF export; v1 = PNG]**
- **F8.1** Export current page → PNG at page resolution (strokes + background).
- **F8.2** Share sheet after export.

## 3. Non-functional requirements

| ID | Requirement | Target |
|---|---|---|
| N1 | **Stroke latency** — first ink pixel on screen | ≤ 16 ms after touch-down; no perceived lag |
| N2 | **Summon detection** — idle detection fires | 3.0 s ± 200 ms, no CPU wake-ups while writing |
| N3 | **Answer latency** — visible answer start after summon | ≤ 2 s overhead for image prep + request; generation time is model-bound (10–60 s typical) |
| N4 | **Crash safety** — strokes/answers lost on kill | 0 lost strokes; autosave ≤ 800 ms after last point; atomic JSON writes |
| N5 | **Battery** — idle (notebook open, not writing) | no wakelocks; no polling; draws only on invalidation |
| N6 | **Memory** | ≤ 256 MB app heap; PDF page bitmap LRU ≤ 3 pages; no full-page ARGB copies during writing |
| N7 | **Storage** — 500-page notebook with strokes | ≤ 100 MB (vector strokes are small; PDF backgrounds dominate) |
| N8 | **Offline behavior** | Everything except summon works with airplane mode on; summon shows friendly error + retry |
| N9 | **E-ink hygiene** | No full-screen flash during writing; ≤ 1 flash per page-turn/import; no >500 ms animations; no gradients that dither badly |
| N10 | **Security** | API key never in plaintext at rest (Keystore-backed); all LLM traffic TLS 1.2+; no logging of prompts or keys |
| N11 | **Legibility** | Body cursive ≥ ~18sp effective; contrast ≥ 7:1 on parchment; table cell text ≥ 14sp |
| N12 | **Cold start** | App open → notebook shelf ≤ 1.5 s on the Boox |

## 4. Hard constraints (from the device)

| Constraint | Implication |
|---|---|
| E-ink refresh latency & ghosting | Discrete animation steps; A2/Regal modes; no alpha-fade animations |
| Color e-ink is 150 ppi, B/W is 300 ppi | Fine detail in *text*; avoid thin colored lines (< 2px at color resolution) |
| No GPU-accelerated glow/shadow | Depth via texture, line weight, and spacing — not shadows |
| Wacom EMR hover events available | Palm rejection via hover-gating (04 §4) |
| Wi-Fi only | Timeouts and retry UX matter; requests are user-visible, not background |
| Android 13+ Boox OS, unknown OEM quirks | Standard APIs first; Boox SDK as optional enhancement (04 §2, ADR-002) |
| The screen is 2480×1860 @ 300 ppi | Page space = 2480×1860 (blank) / A4@300dpi (PDF); coordinates are integer-safe |

## 5. Open questions (tracked until resolved)

| # | Question | Current default | Decided? |
|---|---|---|---|
| OQ1 | Exact Android version / Boox SDK availability on the Note Air 5c | Assume Android 13+, standard APIs; verify on device in M1 | ⏳ verify on device |
| OQ2 | Does `deepseek-v4-flash-vision-exp` accept vision input exactly per OpenAI image_url format? | Assume yes (OpenAI-compatible); settings allow model override | ⏳ verify with live API in M3 |
| OQ3 | Max output tokens / JSON reliability of the model | 4096 max; robust parser + one "repair" retry (07 §7) | ⏳ verify |
| OQ4 | Should auto-summon also fire when the notebook is *not* foregrounded? | No — foreground only | ✅ |
| OQ5 | Cursive font legibility at small sizes on e-ink | Body ≥ 18sp; test with real device photos | ⏳ M4 |
| OQ6 | Per-page or per-notebook conversation context? | Per-notebook transcript, current page image | ✅ |
