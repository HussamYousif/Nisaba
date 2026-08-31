# 01 — Product Vision: The Living Notebook

## 1. The idea in one paragraph

Nisaba turns an e-ink tablet into an enchanted notebook. You write with a stylus exactly as you would on paper — no keyboards, no menus, no OCR boxes. When you pause, the notebook itself notices: it *reads* your handwriting from the page and answers *in ink*, beneath your writing, in cursive script that looks as if a scribe took your pen. Ask for a summary, a table, a diagram, a recipe — the answer arrives as flowing text, ruled tables, or hand-drawn pictures that sketch themselves onto the page. It is the Harry Potter *"The Monster Book of Monsters"* fantasy, minus the biting.

## 2. Why the name

**Nisaba** is the Sumerian goddess of writing, scribes, and grain — the patron of the first libraries. The name signals: writing is sacred here, the notebook has a soul, and the answer is a gift from an old, patient intelligence. (Also conveniently short, pronounceable, and unique on app stores.)

## 3. Who it is for

**Primary user: one person with a Boox Note Air 5c** who:
- Writes by hand daily (notes, journal, planning, study, sketching) and finds keyboards on e-ink painful.
- Wants an LLM that feels like *part of the notebook*, not a chat app.
- Values calm, beauty, and legibility over feature-count.
- Is comfortable entering a DeepSeek API key once in settings.

Secondary users (later): students, researchers, clinicians, writers — anyone who thinks on paper and wants an answer that doesn't break the spell.

## 4. The experience walkthrough

### Opening the app
A dark, quiet opening: the word *Nisaba* in elegant script over a parchment texture, a quill drawing a single flourish. No splash-screen nonsense, no account wall. In front of you: a shelf of notebooks (tomes), each showing a thumbnail of its first page.

### Starting
Two choices, presented as two tomes:
- **"A Blank Parchment"** — a new, empty notebook.
- **"Open a Tome"** — import a PDF (lecture slides, a book, forms) as the notebook's pages; you write over them.

### Writing
The stylus glides. Ink follows with pressure — light strokes are thin, firm strokes are bold. The screen refreshes the way paper does: no flashes, no lag. Fingers do not draw (palm rejection); a pen-button or toolbar toggle erases.

### The summon
You finish a thought and pause. Three seconds pass. The notebook notices: the ink "settles" — a tiny shimmer as the request begins. If you pick the pen up and keep writing, the summon is cancelled: the notebook politely steps back. (A floating quill button is always available to summon manually.)

### The answer
After a few seconds of "the ink stirs", the response begins beneath your writing:
- **Text** types itself out in a legible cursive hand, slightly irregular, like a scribe's.
- **Tables** appear as ruled ledger lines with the headers written in a finer calligraphic hand.
- **Drawings** draw themselves: lines flowing from a starting flourish, like a quill sketching a diagram.

A small flourish (✦) marks where the answer ends. The whole exchange is saved as part of the page — this is a *conversation in a notebook*, not a chat window.

### Continuing
You can write your next question *below the answer*, pause, and receive the next reply — the model remembers what was said. The page becomes a living document of your dialogue with the book.

## 5. Design principles

1. **E-ink first.** Every animation, color, and interaction is designed for a 10.3" e-ink display: high contrast, no glow effects, no smooth 60fps motion, no white flashes, no battery-hungry framerates. If it can't feel right on e-ink, it doesn't ship.
2. **Paper over chrome.** The UI is a notebook, not an app. Toolbars are sparse, invisible-until-needed. Ink is ink; UI chrome is graphite.
3. **Legibility over whimsy.** The cursive must *read* easily. Flourishes are ornaments, never obstacles. Small text is never script.
4. **The notebook notices, it doesn't nag.** No popups, no "Are you sure?", no empty states with encouragement copy. The app acts on pauses and gestures, quietly.
5. **Offline-first, private.** The notebook is fully usable offline. The only network call is the summon — and it is explicit, visible (a small "ink stirring" indicator), and cancellable.
6. **One magical loop, done well.** Feature discipline: the write → summon → answer loop must be flawless before anything else is added.

## 6. What this is not (non-goals for v1)

- Not a full note-taking app (no handwriting OCR-to-text, no templates, no cloud sync, no tags).
- Not a chat app (no chat bubbles, no typing input for the LLM).
- Not a PDF annotation suite (import as background only; no PDF export in v1).
- Not an always-on assistant (summon is explicit; the camera/mic are never used).
- Not multi-device (single tablet, single user).

## 7. What success looks like

> The user forgets there is an LLM. They think: *"I wrote, and the notebook answered."*

Measurably: the user completes a full study/journal session without touching a keyboard; the summon works on the first try in the dark, with the tablet in their lap; the answers are legible at arm's length under a desk lamp.
