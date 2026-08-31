# 09 — Magic UX (the presentation layer)

Status: **Draft v0.1** · Principle: *paper over chrome, magic in the details* (01 §5)

## 1. Visual language

### Palette (parchment world)

| Token | Value | Use |
|---|---|---|
| `parchment` | `#F3EAD8` | page background (light) |
| `parchment_deep` | `#E9DCC3` | shelf/backdrop, dividers |
| `parchment_dark` | `#241F18` | shelf *tome* spines, dark mode (v2) |
| `ink` | `#3A2E25` | handwriting + answer ink (sepia black) |
| `ink_soft` | `#6B5A45` | secondary ink, captions, "ink stirs" text |
| `accent_red` | `#7A2E1D` | quill button, flourish stars (visible in B/W) |
| `blue_ink` | `#2C3E66` | optional ink color (F2.5) |

- **Texture**: subtle paper grain (procedural noise or bundled PNG, alpha ≤ 6%, dithered) + soft vignette at page edges (pre-computed, not a gradient drawn per frame — N9).
- **No shadows, no glow, no blur** (impossible/ugly on e-ink). Depth comes from line weight, spacing, and ornament.

### Ornaments (drawn as ink, not emoji)

`✦ ✧ ❧ ⁂ ☙` rendered in the cursive fonts or as tiny vector paths; used for: dividers between user writing and answers, end-of-answer flourish, empty-shelf decoration, "new tome" icons. The app icon: a quill over an open book, flat ink style.

### Type scale (see 08 §3 for fonts)

Shelf titles: Kalam 16 · Notebook page headers: hidden until scrolled (paper!) · Buttons: Kalam 14, underlined like annotations (no filled pill buttons) · Answer body: Dancing Script 20.

## 2. Motion on e-ink (the physics of magic)

E-ink cannot do smooth motion — so the magic is **discrete and deliberate**:

1. **Ink reveals** (08 §5): the answer *writes itself* in 6–14 discrete steps. This is the signature moment; it reads as "the notebook is drawing" even though it's stepped.
2. **Ink settling**: 400 ms after pen-up, a single subtle *settle* pass (the ghost-cleaning Regal refresh) — framed as the ink "drying".
3. **The stirring indicator** (waiting state): not a spinner. An ink-blot that slowly *fills* (10 discrete steps, ~6 s) beside the words *"The ink stirs…"* — progress that looks like a spell in progress, not a progress bar.
4. **Page turns**: instant swap + single clear refresh (04 §2.3). No slide.
5. **Summon-cancel feedback**: when writing resumes and the summon cancels, the blot *un-fills* quickly (4 steps) — the notebook "steps back".
6. **Smudge-away delete** (08 §7): the answer streaks into gray smudges (5 steps), then vanishes with a clean pass.

Rule: **every animation is steps, not frames; every step is a partial refresh; every animation completes in ≤ 8 s and is skippable by tap.**

## 3. The summon flow (user decision — auto with cancel)

```
[user writes… last point at t0]
        │ 3.0 s idle (F3.1)  ← any new point resets (F3.2)
        ▼
[notebook notices]  → "the ink stirs…" blot begins filling
        │ user writes again?
        ├── yes → cancel request, blot un-fills, timer re-arms (silent, no toast)
        └── no  → page image prepared → request sent (indicator: "the ink stirs…")
                 │ network 10–60 s
                 ▼
[answer reveals below]  flourish ✦ → blocks draw in → end flourish ✧
```

- The **quill button** (floating, bottom-right, ink-red quill icon) summons immediately — also used after errors and to re-summon the latest question (F3.3).
- **First-run onboarding** (one screen, skip-able): a parchment explaining the three things — *write, pause, the book answers* — with a tiny live demo: a pre-drawn squiggle that gets answered by a script "Well met. Write me a question." (also serves as a live API-key test, F7.6).
- No toasts, no dialogs during the flow — the page itself communicates.

## 4. Waiting-state copy (all in ink_soft, Kalam)

| State | Copy |
|---|---|
| Request sent | *"The ink stirs…"* |
| Still thinking (> 30 s) | *"The scribe turns the page…"* (second line appears) |
| Cancelled by writing | (nothing — silent un-fill; maybe *"…as you were."* at 0.35 opacity, fades via steps) |
| Error | *"The ink smudged — the spirit world was unreachable."* + ✒️ Write it again + 🗝 Check the key (Settings) |
| Offline | same error, with "check your connection" hint |
| Empty summon (no new writing) | quill button does nothing but a tiny ink-drop animation (no error text) |

## 5. Sounds (optional, default OFF — F7.4)

If enabled: a soft quill-scratch loop only while strokes are actively drawing (tied to `ACTION_MOVE`), a single low chime on answer completion, a "thunk" on page turn. All short, generated/CC0 assets; muted by the tablet's silent switch. Never required.

## 6. Shelf (notebook list) magic

- Background: dark library wood (deep brown-black, flat), tomes as vertical spines with Kalam titles and a red quill bookmark on the most recent.
- Empty shelf: centered ornament `❧` + *"The shelf awaits its first tome."* — the two creation actions are the two tomes: **A Blank Parchment** / **Open a Tome**.
- Long-press a tome → "Smudge away" (delete) with a two-step confirm (F1.4).
- Thumbnails: recent page render (05 §2), regenerated on save — a real look at the notebook, not an icon.

## 7. Error magic (turning failures into flavor)

Every error keeps the notebook state intact and reads like the book apologizing, with one clear action:

| Failure | Presentation |
|---|---|
| Network | smudge + "The ink cannot reach the spirit world" |
| API key | smudge + "The quill has no ink — the key is missing or wrong" (Settings shortcut) |
| Rate limit | "The scribe is busy with another tome — try again shortly" (auto-retry once) |
| Model refused / malformed | "The book misread your page — write it again" (regenerate) |
| PDF import failure | "The tome resisted being opened" + retry / skip-page options |

## 8. Accessibility & comfort

- All text ≥ 12 sp; body cursive ≥ 18 sp; contrast ≥ 7:1 (N11).
- Frontlight-agnostic palette (works on unlit e-ink under a lamp and in the dark with frontlight on).
- No motion required to use the app (tap-to-complete reveals; quill button is the primary summon).
- Writing area is the whole screen minus the floating button; toolbar auto-hides after 4 s of writing (appears on a small edge-grip tap or when the pen hovers near the edge).

## 9. Anti-magic list (what we will NOT do)

- ❌ Glowing/neon/parallax effects · ❌ Chat bubbles or typing UI · ❌ Popup keyboards · ❌ "AI" branding or robot iconography (the book is a *scribal spirit*, not a chatbot) · ❌ Skeuomorphic leather that dithers badly · ❌ Animations longer than 8 s · ❌ Toasts interrupting writing.
