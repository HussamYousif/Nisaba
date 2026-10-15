# Nisaba — improvement analysis (2026-10-15)

Three review passes (product/UX, AI/prompting, engineering health) plus what the voices work turned up.
**✔** = confirmed by reading the code · **◦** = reported by a review, not reproduced yet. Effort: S ≤ ½ day · M ~1 day · L 2–3 days.
Line numbers drift; search for the named symbol.

## Done in this pass
- **Voices** (DESIGN D17, §5.3a): 8 personas per notebook, picked in New after the kind, changeable in ⋯ → Voice.
- ✔ **"✓" was written as "?"**: the hand turns any glyph it lacks into `?`, so "4−10+6=0 ✓" read as doubt. ✓/✗ glyphs added (`GlyphCompositionRules`).
- ✔ **Settings writes could undo each other**: `update` read outside `edit {}` and rewrote every key; now one atomic `edit`.
- ✔ **`prompt_gate.py` was broken** by the prompt split; it reads the prompt via `scripts/prompt_source.py`, takes `--persona`.

## 1 · Bugs that lose work or break the loop (do first)
| # | Finding | Fix | Effort |
|---|---|---|---|
| 1 | ✔ **A follow-up on a spill page hangs the hand.** `ScribeAnimator.finish()` leaves `page = 1` and `writingOn` = the question's page; the session (and `Play.pageId`) stay bound to the old page after `openPage(keepAnswer = true)`. The next answer's page-0 strokes call `breakForPage(0)`, the VM drops it (`from != pageIndex`), the hand waits forever with raw ink suspended | Reset `page`/`writingOn` when a new `reqId` starts; `Play` carries the utterance's page; rebind the session once a spilled answer ends. Regression test (spill → second answer) | S |
| 2 | ✔ **An unreadable ink file becomes a blank page, then is overwritten.** `readOrNull` uses `runCatching` (catches OOM too); a newer file version also reads as empty. Main and `.bak` both unreadable (big endless canvas + OOM) → empty → the next two saves rotate the real data away | Tell "missing" from "unreadable": quarantine as `.corrupt`, refuse to save over it, show a line. Before any ink-format change | S |
| 3 | ◦ **A streaming answer lives only in memory** until the hand finishes (commit on drain/page break/stop; the exchange is saved at `ScribeFinished`; `onPause` doesn't wait for the flush). A background kill loses ink and history | Commit per finished block; save the exchange when the stream ends; treat ON_STOP like leaving the screen; try/catch around `planBlock` | S–M |
| 4 | ◦ **The hand's ink is only in the committed bitmap**: a rebuild mid-answer (two-finger undo, erase, a PDF raster arriving) wipes it on screen; `setPage` skips identical blank pages (ghost answer) | The overlay layer DESIGN §8 describes; key `setPage` on the page id | M |
| 5 | ✔ **`finish_reason` is ignored** (`ChoiceDto` has no field). A `length` cut-off is saved as Complete, memory tags apply, the missing `<heard>` becomes "(illegible)" in history | Pass the reason through; `length`/`insufficient_system_resource` → Interrupted + retry chip; `max_tokens` per purpose (~700 for Writing) | S |
| 6 | ✔ **Document window**: centred on `currentPage ?: 0`; lesson pages are blank, so a big PDF is taught from p.1–31 only. In PDF notebooks the window moves on every page turn, which changes the system message: document + memory + history all miss the cache (≈50× the cached price per request) | Snap to fixed 20-page blocks; centre lessons on the last cited (p. N) | S |

## 2 · Answer quality, latency, cost
| # | Finding | Fix | Effort |
|---|---|---|---|
| 7 | ✔ **The images can't tell new ink from old.** The focus crop draws every stroke in its rectangle (a follow-up under an answer includes the answer); JPEGs are grayscale, so lapis ≈ black, yet Tidy says "leave out … the blue ink" | Pass `utt.strokeIds`; draw new strokes dark, fade the rest; colour JPEG; say so in the instruction | S–M |
| 8 | ◦ **First ink waits for the whole first paragraph** (blocks close on a blank line). One-paragraph replies = TTFT + full generation | Prompt: direct answer alone in a short first paragraph; log TTFT and first-block time (`latencyMillis` measures to the hand's end) | S |
| 9 | ◦ **Thinking mode**: `reasoning_content` doesn't count as activity → the watchdog times out live reasoning; nothing on screen meanwhile | Count reasoning as progress; show a mark | S |
| 10 | ◦ **Pen-down mid-answer cancels the stream**: `<heard>` and memory tags are lost, Continue has only the blocks so far | Stop the hand, let the stream finish in the background | M |
| 11 | ◦ **Wasted requests**: erasing what you just wrote still sends; stray dots round-trip; requests cancelled by pen-down are billed but not in Usage | Shrink the utterance on erase (empty → Idle); skip tiny utterances; record cancelled requests | S |
| 12 | ◦ **Cache hygiene**: past the budget `trimHistory` drops one pair per request, so every later request misses; chars/3.5 under-counts CJK 2–3× | Trim to ~70 %; calibrate from stored `inputTokens` | S |
| 13 | ◦ **Memory will bloat**: recency-only cap, exact-text dedupe, "anything about themselves deserves a remember" | Periodic cleanup request past ~150 facts; memory-precision cases in the eval | M |
| 14 | ◦ **Ask again doesn't escalate**: same model/temperature; quotes raw markup with tags; erased answers stay in history | Thinking on (or pro) for Again; quote clean text; drop erased exchanges from history | S |
| 15 | ◦ **No real eval**: the gates use system fonts and a copy of the request | `:tools` runner on the real `buildRequest` + `AnswerParser`, handwriting from `:scribe`; persona × case, a voice switch with 20 old-voice turns, grief, memory precision | M |
| 16 | ◦ **Temperature mapping**: if DeepSeek still maps API T ≤ 1 to 0.3 × T (V3 behaviour), voices at 0.5–0.8 run at 0.15–0.24 and converge | Check the current docs; tune per voice with #15 | S |

## 3 · Product and UX
| # | Finding | Fix | Effort |
|---|---|---|---|
| 17 | ✔ **Reopen where you left off**: notebooks always open on page 1; the library sorts by `updatedAt`, which only create/rename write, so the daily notebook sinks | Remember the last page; bump `updatedAt` on ink and exchanges; optional "open my last notebook" | S |
| 18 | ◦ **Status you can see**: nothing shows while thinking (up to the 45/120 s watchdog); mute is invisible once the bar hides; a page turn while thinking drops the question silently | Fixed-state star (listening / working / muted / failed), one partial refresh; carry a pending question across a turn | S–M |
| 19 | ◦ **Answer modes per notebook**: Everything · Questions only · When I ask (next to the voice picker). Notes then come back `<silent/><heard>`, which also gives handwriting search | S–M |
| 20 | ◦ **Never lose a notebook**: no backup, no export, delete is final; the M7 release key forces an uninstall of the debug build | Weekly zip backup to a SAF folder + restore; 30-day trash; export page/notebook to PDF and Markdown | M |
| 21 | ◦ **Pages**: no delete/move/duplicate; erased answers leave empty spill pages; the grid shows bare numbers | Thumbnail grid with long-press actions; drop empty spill pages | M–L |
| 22 | ✔ **E-ink mode sticks in NORMAL**: `cleanPass`/`fullRefresh` call `normal()` and nothing switches back (toolbar hide, undo, every answer's end), against D§1.8; no 5th-turn flash; every picker is a full-screen veil; `faint` text ≈3.2:1 | Track the wanted mode and restore it; flash cadence; anchored popovers; darker `faint`. Finish spike S3 | S–M |
| 23 | ◦ **More answer actions**: Shorter · Deeper · Example · Copy text · Report (Play's AI-content policy wants in-app reporting, more so with "The flirt") | `Purpose.Again` variants | S |
| 24 | ◦ **PDF flows**: inserted pages make "n / m" drift from the cited (p. N); no "Open with Nisaba" intent filter; a lesson never shows its PDF | Page labels "p. 12 +1", Go to p., intent filters, a "Tutor me" switch on a PDF notebook | M |
| 25 | ◦ **Paper**: lined / grid / dots per notebook, answers snapped to lines | M |
| 26 | ◦ **Library v2**: the grid scrolls (D§1.8); paged grid, Continue card, pinning, "edited 2 h ago"; folders later | M |
| 27 | ◦ **Onboarding**: the Welcome copy says Nisaba answers "if you asked" (untrue since prompt v3) and scrolls; nothing teaches the gestures | A "Start here" notebook written by the hand offline | M |
| 28 | ◦ **Search v2**: stops at 10; titles and PDF text not searched; the hit isn't marked on the page | Paging, more sources, outline the stored focus rect | S–M |
| 29 | ◦ **Lasso**: move / delete / recolour / ask about a selection | L |
| 30 | ◦ **Controls within reach**: long-press the star for a mini-bar (pen, eraser, undo, eye) | M |

## 4 · Engineering health and release
| # | Finding | Fix | Effort |
|---|---|---|---|
| 31 | ◦ **No fallback if the Onyx pen SDK breaks** (attach errors swallowed, stylus events eaten) — a firmware update could make writing impossible | Fall back to `MotionEventPenDevice` on attach failure / no raw callbacks | S |
| 32 | ◦ **M7 blockers**: compile/target SDK 35 (Play needs 36); no signing; minify off, no keep rules; no privacy policy/data safety; Onyx licence unconfirmed; `addHiddenApiExemptions("")` exempts everything; debug APKs embed the `.env` key | The M7 list, plus rename/drop the character voices (IP) | M |
| 33 | ◦ **Main-thread work**: hand planning runs on Main inside the session mutex; PDF text is formatted on Main; `rebuild()` is synchronous; area erase tests every point of every stroke (ANR risk on an endless canvas) | Plan on a single background dispatcher; bounds prefilter; off-thread rebuild; a Perfetto pass vs D§2 | M |
| 34 | ◦ **Shared `InkRenderer` paint** used by the UI and the snapshot thread → wrong colours on a race | A paint per call | S |
| 35 | ✔ **Files past ~300 lines**: NotebookViewModel ~760 (6 classes: toolbar timing, preload, ink locking, endless view, spills, debug harness), CodeHighlightRules 425, PageView 393, AnswerParser 345, ConversationSession ~330; `AnswerScribeSession` is a `*Session` in `:app` (D§3.3 puts sessions in domain) | Extract an ink-edit session and toolbar rules; move the harness to `src/debug`; make `verifyFileRoles` fail > 300 lines | M–L |
| 36 | ◦ **Tests/CI**: instrumented suites (migrations, PDF, settings) never run in CI; nothing covers AnswerScribeSession, PageView redraw bookkeeping, spill → second answer; DeepSeekClientTest asserts wall-clock times | Extract and unit-test; an emulator job | M |
| 37 | ◦ **PDF robustness**: the PdfBox fallback re-parses the whole PDF per page; import catches only IOException; raster height uncapped | Cache the PDDocument; catch + clean up; cap | S |
| 38 | ✔ **Dead setting**: `Settings.listeningDefault` is stored but neither shown nor applied | Wire it into New, or delete it | S |

## Suggested order
1. **Batch A (≈2 days): 1, 2, 5, 6, 17, 31.** The loop stops breaking, data can't vanish, the PDF cache works, the notebook you use is on top.
2. **Batch B: 3, 7, 8, 18, 19, 22.** Answers are about the right ink, arrive sooner, and you can see what Nisaba is doing.
3. **Batch C (before Play): 20, 32, 23 (Report), 27, 35**, and an IP decision on the character voices.

Most of batch A needs the Boox online to verify (spill answers, real requests).

## 5 · New ideas (features, not fixes)
| # | Idea | Why | How (hook) | Effort |
|---|---|---|---|---|
| 39 | **Circle to ask**: circle or underline anything (printed PDF text, old ink, an answer), write "?" or a question | The best way to ask about a PDF; no lasso needed | The focus crop already renders background + ink inside the utterance's box; add a contract line ("a circle/underline/arrow points at what they ask about") and draw new strokes darker (pairs with #7) | S |
| 40 | **Check my work**: ✓/✗ in the margin beside each line of a handwritten solution, a short correction only where wrong | The strongest learning feature; tick/cross glyphs exist now | `Purpose.Check`; cluster the utterance's strokes into lines (`InkRules`); a `<mark line="3" ok="false">sign error</mark>` tag; a margin layout region | M–L |
| 41 | **Each voice writes in its own hand**: Yukino neat and upright, Zagreus bold in red-brown, the flirt loopier, the teacher steady and slow | Makes voices visible on paper; very on-brand | Persona gets optional `HumanParams` overrides, ink and speed; `AnswerScribeSession` reads them instead of Settings (a "voices use their own hand" switch) | M |
| 42 | **Second opinion**: long-press an answer → "Ask Zagreus…" → another voice answers below it, in its hand | Fun, and useful for writing/ideas (explorer proposes, Yukino critiques) | `Purpose.Again` + a persona override for one request; keep the first answer | S |
| 43 | **Write your own voice**, or "someone like Sherlock Holmes" and Nisaba drafts the voice paragraph for you to edit | Endless voices without code; avoids IP issues in a Play build | `custom_personas` table (Room v7); registry merges built-ins + custom; one text request drafts the paragraph | M |
| 44 | **Length per notebook**: Brief · Normal · Detailed beside the voice | The most common wish with answer bots | Multiplies the persona's `{words}` | S |
| 45 | **Offline queue**: writing done offline gets a small ⧗; it's answered automatically when Wi-Fi is back (on next open of that page) | The Boox is often offline away from home | Keep `Failed(Network)` utterances per page (table); retry on connectivity / page open | S–M |
| 46 | **Review page**: a daily page of 3–5 due questions from shaky lesson topics and flashcards, graded when answered | Turns lessons into spaced repetition | Schedule from `lesson_topics` (+ a cards table); a "Review" notebook; `Purpose.Review` | M–L |
| 47 | **Quiz me on this notebook** (⋯) | Studying your own notes | `Purpose.Quiz` over the notebook's exchanges (+ silent transcriptions once #19 lands) | S |
| 48 | **Daily page / weekly recap** in the notebook's voice: a morning prompt with yesterday's open loops; a one-page recap of the week's questions | A reason to open the notebook every day | Text-only requests (no images, cheap) over recent exchanges + memory | S–M each |
| 49 | **Private notebooks**: nothing remembered from them, nothing recalled into them, left out of search and recaps | Journals | `ConversationDeps.memory` is already nullable per session; a notebook flag | S |
| 50 | **Margin glosses on PDFs**: short notes beside the paragraph instead of spilling to a new page | Reading flow stays on the page | Free columns from the printed-text occupancy; a small-hand region; "gloss" instruction | M |
| 51 | **Sketch → clean diagram**: boxes, arrows and words redrawn cleanly with labels | Notes that look finished | `Purpose.Redraw` returns a diagram block for the selection/page | M |
| 52 | **Ink to reminders**: "remind me tomorrow 9: call Ola" → a confirm chip → calendar/alarm | Notebook as assistant | A `<task when=…>` tag like `<remember>`; Android intent after confirmation | M |
| 53 | **Voice sigils + voice lines**: a small mark per voice on covers (quill, owl, compass, heart, cat, laurel…); failure lines and the first-page hint in the voice | Delight, recognisable notebooks | `CoverRenderer`; a few strings per persona | S |
| 54 | **Local metrics**: time to first ink, silent rate, cost per notebook in Settings → Usage | Tune pause length, models, voices with numbers | Exchanges already store tokens; add first-token/first-ink times | S |

**Best value next:** 39 (cheap, transforms PDF reading), 41 (voices become visible), 40 (check my work).
