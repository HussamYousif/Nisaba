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
3. **The ink creeps out** (waiting state): not a spinner, and no text. Several tips of ink set out from the tail of the user's last stroke; most wander a little way and simply stop, while one channel keeps going — sprouting finer branches as it advances — until it reaches the spot where the answer will write itself and gathers into an irregular blot that *breathes* (~6 s cycle), fed by travelling beads until the answer arrives. Ink that explores and fails is what makes the one that succeeds look alive. The user's writing is never consumed and never drawn over. Five phases on one 100 ms clock:

   | Phase | Steps | What happens |
   |---|---|---|
   | `REACH` | 26 · 2.6 s | The system creeps: three false starts die near the word, ~11 branches sprout along the channel as the front passes, and the channel curls into the locus |
   | `GATHER` | until the answer | The blot breathes, beads feed down the thread, ripples bloom |
   | `DRINK` | 14 · 1.4 s | The system drains *tail-first* into the blot; each branch is reclaimed whole the moment the front passes where it hangs off, so nothing is left floating; the blot deflates to nothing |
   | settle | 1 pass | `Epd.cleanRefresh` clears the ghosting of ~40 partial updates |
   | answer | — | Only now does the answer ink reveal, into a clean page |

   **It seeks; it does not route.** The channel leaves along the direction the pen was actually travelling (two knots on that ray, so the departure survives a long reach, with a whisker of drift so it is not a ruled line), meanders through four uneven waypoints at 0.10–0.22·dist, hesitates at three steps as ink wicking into fibres does, and curls back into the locus rather than arriving at it. Around it, ~26 branches creep: three false starts at 13–25% along the channel — never from the tail point itself, which is the last glyph of the user's word — and eleven branches along it, the longer runners forking again. Every quantity comes from a deterministic sin-hash seeded off the two endpoints, and the whole system is generated once at `beginSummonInk` and revealed by growth order, so it costs **no extra screen refreshes** and cannot jitter between frames.

   **The answer is written with the ink that reached out.** `DRINK` is why the trail disappears, and it is a different gesture from `RECEDE`: consumed (drained tail-first into the blot) versus undone (retracted tip-first back into the stroke). The drain is skippable — a touch finishes it at once.
4. **Page turns**: instant swap + single clear refresh (04 §2.3). No slide.
5. **Summon-cancel feedback**: when writing resumes and the summon cancels, the thread retracts tip-first back into the stroke and the blot goes with it — the notebook "steps back". Deliberately not the same motion as `DRINK`.
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
[thread is drunk]  drain (1.4 s) → one clean pass → the page is empty again
                 │
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
