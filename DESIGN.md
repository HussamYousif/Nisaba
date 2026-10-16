# Nisaba — Design

> A notebook for the Boox Note Air 5C that answers you in ink.
> You write. You pause. A hand you can't see writes back, beneath your words, one letter at a time.

Status: **v1 design, 2026-09-29** · Fresh start (prior attempt lives in git history at `08de353`, reference only).

---

## 0. Decisions made for you (veto any)

| # | Decision | Why |
|---|---|---|
| D1 | Name **Nisaba** (Sumerian goddess of writing & scribes) | Fits the concept; brand below builds on it |
| D2 | **Paged** notebook, not infinite canvas. Pages = screen aspect (portrait). Long answers spill onto a fresh page inserted after the current one | Scrolling on e-ink smears and ghosts; page turns are instant and clean |
| D3 | Trigger = **3.5 s pen-idle** (setting: 2–6 s). Pen-down at any point before the answer starts cancels the request silently | Your spec |
| D4 | Pen-down **while the answer is being written** stops the hand; what's written stays; a small "…" mark lets you tap to finish the rest | You're never blocked, nothing vanishes |
| D5 | **The model decides if you're talking to it.** Plain notes → it replies `<silent/>` and nothing happens. Plus a *Listening* toggle in the toolbar to mute it entirely | Every pause triggers a request; notes must not get answered |
| D6 | Model **`deepseek-flash`** (DeepSeek V4.1 Flash, native image input). `deepseek-v4-pro` selectable. Thinking **off** by default (latency) | Verified in API docs 2026-09 |
| D7 | The AI **reads your handwriting from an image** of the page (no OCR step), and returns a transcription of what it read, which becomes the notebook's text memory | One model, sees layout/arrows/doodles too |
| D8 | Memory is **per notebook**: all prior exchanges (text) + the entire attached PDF (text) + current page (image) | Your spec |
| D9 | Answers are **true pen strokes** from a single-line handwriting font, humanized, drawn along the pen path. Tables, math and diagrams are drawn by the same hand | Your spec; nothing is a pasted font render |
| D10 | After it's written, an answer is **ordinary ink**: erasable, undoable, same as yours | Your spec |
| D11 | Answer ink = **lapis blue** (`#27407A`), your ink = black. Falls back to black if lapis reads poorly on Kaleido (device test T5) | Tells voices apart at a glance; lapis = Mesopotamian |
| D12 | Kotlin; **Compose** for library/settings chrome; a custom **View** for the page (Onyx TouchHelper needs a View) | Compose Canvas can't host the Onyx raw-drawing layer |
| D13 | Manual constructor DI via one composition root (`AppGraph`). No Hilt/Koin | Explicit, fast builds, nothing magic |
| D14 | Bring-your-own API key (entered in Settings, stored Keystore-encrypted). Debug builds may read `.env` | Required for Play Store; no key ever ships in a release |
| D15 | PDF only. Export (PDF, Markdown) and a backup zip came in M17; no sync | Your spec; backup and export by the improvement programme |
| D16 | **Long-term memory across notebooks** (§4.5): a SQLite `facts` table the model writes via `<remember>`/`<forget>` tags; all facts ride in every request (stage 1). No embeddings, no vector DB | Your ask (2026-10-02); keyword/LLM selection covers it at notebook scale |
| D17 | **Voices (personas), per notebook** (§5.3a): picked in "New" right after the kind; changeable in ⋯ → Voice. The sassy book (default, the Weiss persona every older notebook keeps) · Gandalf (teaches, or simply talks; was the wise teacher, same id) · The writer's companion · The idea explorer · Yukino (banter, flirting, talk, understanding yourself; the flirt folded into her) · Zagreus · Adam Jensen (talk about our dystopia) · Plain. A voice = one prompt paragraph + a word budget + a temperature; the reply contract is shared | Your ask (2026-10-14; Gandalf, Yukino's new role and Adam Jensen 2026-10-16). The characters are fan homages: fine, since Nisaba is for personal use (D18) |
| D18 | **Personal use only**: no store release. Every voice in every build; the hidden-API exemption stays wide open; the release build (R8, debug key) is simply the faster one | Your call (2026-10-16) |

---

## 1. Experience

### 1.1 Flow

```
Library ──tap──▶ Notebook (page view) ──write…pause──▶ ink stirs ──▶ the hand writes the answer
   │                                         ▲
   └─ "New notebook" / "New from PDF"        └─ keep writing below; it remembers
```

### 1.2 The ask loop (the heart)

States (pure state machine, §4.4):

| State | What you see | Exit |
|---|---|---|
| **Idle** | Nothing | pen-down → Writing |
| **Writing** | Your ink, instantly | pen-up → Pausing |
| **Pausing** (3.5 s) | Nothing. (Optional: faint 3-dot "breath" in the margin at 1 s, 2 s, 3 s — off by default) | pen-down → Writing (same utterance). Timer → Thinking |
| **Thinking** | Nothing is shown (the inkwell animation was removed at the user's request, 2026-10-02): the answer simply starts writing. An endless canvas still pans to where it will start | pen-down → **cancel request**, back to Writing (utterance keeps growing). First answer tokens → Scribing. `<silent/>` → Idle (drop dries and vanishes) |
| **Scribing** | The hand writes, letter by letter (no nib; ink just appears) | pen-down → Interrupted. Done → Idle |
| **Interrupted** | Partial answer + "…" mark at its end | tap "…" → Scribing resumes. Anything else → the answer stays partial |
| **Failed** | Drop turns into a small hand-written "?" with one line: "the ink wouldn't flow — tap to retry" | tap → Thinking. Pen-down → Writing |

**Utterance** = every stroke you've written on this page since the last exchange. Written 2 s, paused, wrote more → one utterance. Pen-down during Thinking → the request is cancelled and later resent with the extra strokes.

### 1.2a Other ways to ask (M15)

- **Point at it**: circle, underline, bracket or arrow at something (printed text, old notes, an answer) and write "?" or a question. The contract says marks point at what they mean; a flat stroke's focus crop reaches up to the line it underlines (`focusArea`).
- **Lasso** (toolbar, the dashed loop): a loop picks the strokes mostly inside it (`strokesInLoop`), or, around printed text only, just the area. A bar along the bottom (the pen keeps clear of it) offers **Ask about it** (`Purpose.About`), **Check it**, **Redraw cleanly**, **Colour**, **Delete**, **Done**; a pen drag that starts inside the dashed box moves the ink. Move, recolour and delete are each one undo step (`ReplaceStrokesUseCase`, `RemoveStrokesUseCase`); an answer lassoed away whole leaves the history like an erased one. With the Onyx pen the loop leaves no ink (raw drawing off, like the eraser).
- **Check my work** (⋯, or the lasso): their handwriting is split into lines (`inkLines`), the images get small red numbers beside each line (`SnapshotLabel`, drawn only in what the model sees), and the reply starts with one `<mark line="N" ok="true|false">correction</mark>` per line, then one closing sentence in the voice. Marks are blocks (`Block.Mark`): a green ✓ or a red ✗ + correction is written beside its line (`markRegion`: right of it, else in the left margin, else under it) on the same clock as the rest; the closing sentence goes under the work. Low temperature and thinking on. (Live example: the teacher's normal reply called "3x = 9 → x = 4" correct.)
- **Redraw cleanly**: a sketch comes back as one diagram block below it (`Purpose.Redraw`, no voice, temperature 0.3).
- **Margin glosses**: on a printed PDF page a reply to writing goes in the free column beside it (`glossRegion` over the printed-text occupancy: the column it was written in, else the widest, level with the question), in a hand 0.65× theirs, and the instruction asks for a short gloss (≤ 35 words unless asked for more). Not when there is room to write below as usual (6 free full-width lines within 3 lines under the question: a sparse page), no printed text alongside, or no column 7 x-heights wide.
- Asked-for requests (again, tidy, check, redraw, a lasso) go even while muted and take over writing that was only waiting for its pause.

### 1.2b Learning and habits (M16)

- **Fresh-page requests** (⋯, paged notebooks): **Quiz me** (3 numbered questions on what matters most, hard things first; their written answers are judged by the next reply), **Today's page** (the date, ≤ 3 open loops from the history, a question for the day), **This week, recapped**. Each adds a page at the end and writes from its top. They are text-only (`Purpose.textOnly`: no snapshot, no images, cheaper) and work from the history the request already carries.
- **A page each morning** (Settings → Waiting → Mornings, off by default): the first open of a notebook on a new day, if it was written in on an earlier day, starts today's page (once a day per notebook; not in lessons or on a canvas).
- **Spaced review** (lesson notebooks): topics carry a `streak` (Room v10); learned again → the gap grows 2, 4, 8, 16, 30, 60 days; shaky → tomorrow (`judged`, `dueAt`, `dueTopics`). ⋯ → **Review what's due (N)** asks one question on each (≤ 5) on a fresh page; the answers are judged with `<topic>` tags as usual, which reschedules them.
- **Private notebooks** (⋯ toggle, Room v10 `isPrivate`): no memory is sent or learned (and the instruction asks for no memory tags), never found by search, "· Private" under the cover.
- **Reminders**: every instruction carries the local date and time ("It is now Thursday 15 October 2026, 21:40"); a reply may add `<task when="2026-10-16T09:00">call Ola</task>` after `</answer>` (stripped from the ink). The page shows a chip "⏰ call Ola · Fri 16 Oct, 09:00 [Add] [×]"; Add opens the calendar app with the event filled in; a Boox has none, so Nisaba posts its own notification at the time (`ReminderDevice`: an inexact alarm → `ReminderAlarmReceiver`; asks for notification permission the first time; lost on a reboot); failing both, the words go on the clipboard.

### 1.2c Understanding a book (M21)
New → **Understand a book** → a voice (Gandalf suits it) → the PDF. Like a lesson, the notebook is blank pages with the book beside them; unlike a lesson, Nisaba first reads the whole book and makes a **map** of it, then walks you through it a page at a time, for the overall conceptual understanding (The Burnout Society, an introduction to Postgres, Designing Data-Intensive Applications…).
- **Reading** (`ReadBookUseCase`, text-only, in the app's scope via `BookReaderRegistry` so leaving the notebook doesn't stop it): up to 160k characters in one request; a longer book in parts of ~60k characters, four at a time, each part's ideas (`<idea pages="12-15">Name :: sentence</idea>`) saved as it comes, so a restart resumes where it stopped. Then the map: `<book>Title — Author</book>`, `<thesis>…</thesis>` and 5–12 core concepts in learning order (`<concept pages="12-30">Name :: sentence</concept>`). A chip shows "Reading the book… 3 of 7 parts"; a failure chip retries from where it stopped; a PDF with almost no text (a scan) is refused. The notebook takes the book's title, unless it was renamed meanwhile. *The Prince* (~300k characters, 7 parts) reads in about 15 s.
- **The walk**: page 1 is the map of the book (greeting in the voice, title, argument, the concepts' names). Writing "next" (in any language: the reply is `<next/>`, which writes nothing) or ⋯ → *Next: 2. … ›* writes the next concept on a fresh page: a heading, about 200 words for understanding (what it is, why it matters to the argument, an example, how it builds on what came before), a question, and what comes next. The document window centres on the concept's pages (the map and the whole picture send no book text); questions written on a concept's page are answered in its light. After the last concept, *The whole picture*. ⋯ → *The map of the book (3 of 9)*: ✓ walked (tap: back to its page), › next.
- **Stored** in Room v12: `notebooks.book`, and `book_maps` (one JSON row per notebook: title, thesis, concepts with the page each was walked on, the reading's progress). Prompt v17.

### 1.3 Where the answer goes

1. Anchor = the utterance's bounding box. The answer starts one line-gap below its lowest stroke, left-aligned to the utterance's left edge (clamped to the page margin). Width runs to the right margin.
2. The layout **flows around existing ink** and, on PDF pages, around printed content (occupancy map of the rendered background). It never writes over anything.
3. Doesn't fit → continues on a **fresh blank page inserted right after**, with a small hand-drawn "↳" at the break on both pages. The page turns automatically when the hand reaches it.
4. **Hand size follows yours**: x-height is estimated from your utterance's strokes and the answer matches it (clamped to 28–60 page units), so it reads like the same notebook.

### 1.4 The hand (how it writes)

- **Speed:** starts at ~14 chars/s (fast human ≈ 6) and ramps to ~30 chars/s after the first ~150 chars, so short answers feel written and long ones don't drag. Setting: Slow / Natural / Swift.
- **Rhythm:** each stroke eases in and out (slow at pen-down and lift, fast mid-stroke); pen-lifts pause ~40 ms, word gaps ~90 ms, line breaks ~200 ms; i-dots and t-bars come after their word.
- **Humanity (seeded, deterministic):** slight per-letter scale, slant and rotation jitter, baseline wander, variable spacing, width varying with direction (thicker downstrokes).
- **Nib:** removed after user feedback (2026-09-30). Ink appears with no floating pen. Default hand is joined print (`joinLetters`); full cursive is the second style.
- **Blocks:**
  - *Paragraphs / lists:* bullets are hand-drawn dashes; hanging indent.
  - *Headings:* 1.3× size, underlined with one quick stroke.
  - *Bold:* higher pressure (thicker), same hand.
  - *Math:* in-house layout (§5.4): fractions, super/subscripts, roots, Σ ∫ ∏, Greek, matrices. Written left-to-right, bars before denominators.
  - *Tables:* the hand rules the lines (slightly imperfect), then fills cells row by row in a smaller hand.
  - *Diagrams:* SVG subset from the model → paths → gently wobbled → drawn in stroke order.
- **Skip:** a finger tap on the answer finishes it instantly.
- **One answer:** a long finger press on an answer offers *Erase answer* (all its strokes, every page it spilled onto, one undo step; it leaves the conversation's history), *Ask again* (erase, re-ask with thinking on), *Shorter* (erase, the heart of it in a line or two), *Deeper* and *An example* (written below the answer, which stays), *Ask another voice…* (a second opinion in another voice, below), *Copy the words* and *Report* (the question and answer to the share sheet).
- **Code:** fenced code is written on a monospace grid in a ruled box (indentation kept, letters never joined, long lines wrap after a ↳ hook, smaller hand in narrow spots). **Highlighted with different pens** (`CodeHighlightRules`, ~30 languages incl. Haskell, Clojure/Lisp, C#/F#/VB.NET, Java, Kotlin, C/C++, Go, Rust, Swift, JS/TS, Python, Ruby, PHP, SQL, shell; generic fallback): keywords red-brown, strings/numbers/`:keywords` green, comments a thin grey pencil, the rest lapis. With black answer ink: keywords bold, comments thin grey. Inks in `canvas/AnswerInkColors.kt`, to be tuned on the Kaleido panel.

### 1.5 Pen, eraser, gestures

| Input | Action |
|---|---|
| Stylus tip | Write (Onyx raw drawing, pressure) |
| Stylus button held / eraser end | Stroke eraser (whole stroke). Toolbar toggle → area eraser (splits strokes) |
| Stylus tip, shape tool | Auto: a rough line, triangle, box or ellipse is replaced by a clean one (anything else stays as drawn). Line/Arrow/Box/Ellipse/Triangle: drag corner to corner |
| Finger swipe ←/→ | Turn page (instant swap) |
| Finger two-tap / three-tap | Undo / redo |
| Finger tap on answer being written | Finish instantly |
| Palm | Ignored (only stylus draws, ever) |

### 1.6 Screens

1. **Splash (≤ 900 ms):** paper background; the hand writes "Nisaba" in lapis, then the wedge-star mark stamps in. Cold-start work runs in parallel; the splash never waits past readiness plus the end of the stroke in progress.
2. **Library:** a grid of notebook "tomes" on paper. Each cover is **generated from the notebook name** (seeded cuneiform-wedge pattern + name in the hand), so every notebook looks unique without you picking art; under it, pages/kind and the voice. **New** walks three steps: *what kind* (Notebook · Blank canvas · PDF: its pages as backgrounds · Learn a PDF: a **lesson notebook**: blank pages, the PDF as hidden context, a hidden teaching prompt plus an optional goal; Nisaba writes the first lesson unprompted, then teaches one idea at a time and checks with a question) → *who answers* (the voice picker: one row per voice, no scrolling; the hand writes a sample line in the picked voice; it starts at the last voice picked) → a *name*, or the system PDF picker (then the goal, for a lesson). Long-press → rename / delete. Header: wordmark, Search, Settings, New.
3. **Notebook:** the page, edge to edge. A top bar of hand-drawn icons that auto-hides 3 s after writing, 5 s after the bar itself was used (never while its menu, the page grid or a picker is open); a tap within 0.5 s of it hiding does not bring it back (no flashing); while hidden, a small Nisaba star in the bottom-right corner brings it back, and its badge tells the state at a glance ("…" answering, "?" failed, a closed eye when muted). Writing still waiting for an answer when the page is turned (or that failed offline) is kept for its page and asked when the page is back (or the network is): ‹ back · title (rename) · **page n/m ▾** (page grid, 36 per screen) · pen (tap again: 8 inks, Fine/Medium/Bold; a dot on the icon shows a non-black ink; remembered in Settings) | shapes (tap again: Auto, Line, Arrow, Box, Ellipse, Triangle; current ink and size; undo as usual) | eraser (hold for area eraser) · undo · redo · **eye** (Listening; closed eye = muted) · **quill** (Ask now, only when muted, enabled when there is unsent writing) · **⋯** (+ Page, Tidy this page, Overview/Home on an endless canvas, Lesson progress in a lesson notebook, **Voice: <name>** (the same picker; the next reply uses the new voice), Rename, Settings). Undo/redo: two-/three-finger tap. Neighbouring pages are kept loaded (instant turns, undo survives). *Tidy this page* writes a clean, structured copy of the user's notes on a new page after it. *Overview* shows the whole endless canvas shrunk to fit; a tap jumps there.
4. **Settings:** two panes, no scrolling: a list on the left (Key & model · Waiting · The hand · Ink & colours · Memory · Usage), one short screen on the right. API key (masked, "test key" button) · model · thinking on/off · pause length · hand speed · hand style (2 hands) · **Ink & colours** (answer ink lapis / black; code marked with coloured pens / bold & thin / plain; a preview written by the hand in exactly those inks, laid out as a real code block) · memory · **Usage** (answers and tokens this month / all time, cost estimated from list prices with the peak-hour double) · about / privacy.
5. **Search** (library): every question and answer across notebooks, the 10 newest matches; a tap opens that page. Lesson notebooks record `<topic status="learned|shaky">` tags as progress (*Lesson progress* in ⋯); coming back after 4 h, the tutor recaps and re-checks a shaky topic below the last ink.

### 1.6a Library, pages and files (M17)

- **Library v2:** a screenful of six covers with ‹ Earlier · n of m · Later › (no scrolling); **Continue ›** opens the notebook written in last; under each cover the kind, the voice, "· Private" and "edited 2 h ago". Long-press: Rename · Pin to the front / Unpin (pinned first, marked "•") · Move to the trash. **Trash (n)** in the header: Restore or Delete each, Empty the trash; what has been there 30 days is deleted for good when the library opens (Room v11 `deletedAt`).
- **Pages:** the page chip says "3 / 9", or in a PDF notebook the printed page ("p. 12", an added page "p. 12 +1"). The page grid shows 12 small pictures per screen (`ThumbnailRenderer`, cached while the ink is unchanged); long-press one for Delete · ‹ Move · Move › · Duplicate; a PDF notebook has **Go to p.** for printed numbers. Erasing an answer also drops the spill pages it alone had filled.
- **Paper** (⋯ → Paper): blank, lined, grid or dots for a notebook's blank pages (Room v11 `paper`; drawn by `BackgroundRenderer`, in the view, the model's images, thumbnails and exports). On lined paper the hand writes at the lines' size with its baselines on the lines.
- **PDF:** "Open with Nisaba" / "Share to Nisaba" for a PDF (intent filters): the library asks who answers, then imports it (no system picker). ⋯ → **Tutor me on this PDF** turns a PDF notebook into a lesson in place (its pages stay); **Stop tutoring** turns it back. Robustness: the PdfBox fallback keeps one document open instead of parsing per page; a page's picture is capped at three page-widths tall; any import failure cleans up and says so.
- **Search v2:** questions and answers, then notebook names, then the text of PDF pages (never private notebooks or the trash); six hits per screen, Later fetches more; opening a question outlines where it was asked until the pen comes down.
- **Export** (⋯): the notebook as a PDF (each page: paper or printed page, then ink; an endless canvas as one page around its ink) or as Markdown (questions and answers page by page), saved where the person picks.
- **Backup** (Settings → Backup): a zip of the database, ink, PDFs, settings and per-page memories (not the API key: it is sealed to this device), now to a picked file or weekly to a picked folder (checked at start); **Restore** replaces everything after a confirmation and restarts the app.
- **First run:** the Welcome copy fits one screen and says what really happens; a **Start here** notebook is written by the hand itself (no network): how to ask, turn, undo, the long-press menu, the lasso, circling, the star (hold it for pen · eraser · undo · eye), the three dots.
- **Accidents:** a two-/three-finger tap undo or redo says so in a notice (a palm can do it: on the Boox one silently undid a whole answer during testing).

### 1.7 Brand

- **Mark:** three cuneiform wedges arranged as an eight-point star (Nisaba's star), one wedge elongated into a reed-pen nib that drips a single drop. Monoline, works at 16 px and on e-ink.
- **Wordmark:** "Nisaba" written by the app's own hand (generated once, frozen as a vector).
- **Palette** (tokens in `ui/theme/NisabaColors.kt`):

  | Token | Hex | Use |
  |---|---|---|
  | `paper` | `#F6F2EA` | Page & app background (warm; near-white on e-ink) |
  | `ink` | `#111111` | Your strokes, primary text |
  | `lapis` | `#27407A` | Answer ink, the drop, accents |
  | `ochre` | `#B7892F` | Rare accent (the star on the splash, the Listening fill) |
  | `faint` | `#8C877E` | Secondary text, rules |

- **Type:** chrome text in *EB Garamond* (OFL) for titles and *Inter* for small UI labels. No Compose animations beyond discrete e-ink-safe steps.
- **App icon:** the mark in lapis on paper (adaptive icon + 512 px Play asset).

### 1.8 E-ink rules (non-negotiable)

1. No alpha fades, no smooth motion, no scrolling. Animations are **discrete frames** (≤ 20 fps for the hand, ~3 fps for the inkwell).
2. While writing or scribing: **fast waveform**, dirty rect only. When the ink goes idle: one **clean pass** (regal/GC) over the changed region.
3. Full-screen flash only on page turn (every 5th turn, or when ghosting is detected), notebook open, and PDF page change.
4. Contrast first: everything must read with the frontlight off.

---

## 2. Targets

| Metric | Target |
|---|---|
| Pen-to-ink latency | ≤ 20 ms (Onyx raw drawing) |
| Pause end → inkwell visible | ≤ 100 ms |
| Pause end → first answer stroke | ≤ 2.5 s (flash, thinking off) |
| Hand frame budget | ≤ 8 ms CPU per frame (stroke planning is precomputed) |
| Page turn | ≤ 150 ms to ink |
| Cold start → library | ≤ 900 ms (measured M18: 331–334 ms release, 566 ms first launch after install; ~1.2 s debug) |
| Save | Stroke durable ≤ 1 s after pen-up; kill-safe (atomic writes) |

---

## 3. Architecture

### 3.1 Principles

- **Dependency rule:** `app → infra → domain`, `app → scribe → domain`. `domain` and `scribe` are **pure Kotlin/JVM** (no Android imports), which Gradle enforces because they're JVM modules.
- **Ports & adapters:** the domain declares interfaces (`*Ports.kt`); infra implements them. Swapping DeepSeek for another model = one new `*Client.kt`, zero domain changes (**Open/Closed**).
- **Registries over `when`:** new document types, answer block kinds and pen tools are added by registering an implementation, not by editing a switch (**OCP**).
- **One reason to change per file (SRP),** and the file's suffix says what that reason is (§3.3).
- **Pure core, imperative shell:** business decisions are pure functions `(state, event) → (state, effects)`; use cases and sessions execute the effects.
- **Immutable models.** Mutation only in stores and the canvas's render caches.
- **Coroutines + Flow** everywhere async; structured concurrency; cancellation is how "pen-down cancels the request" works.

### 3.2 Gradle modules

| Module | Kind | Contains | Depends on |
|---|---|---|---|
| `:domain` | Kotlin/JVM | Models, rules, ports, use cases, sessions, per bounded context | coroutines-core, serialization |
| `:scribe` | Kotlin/JVM | Handwriting synthesis: answer document → timed pen strokes | `:domain` |
| `:infra` | Android lib | Room, ink files, DeepSeek client, PDF, secrets, page snapshots | `:domain`, `:scribe` (for snapshot render) |
| `:app` | Android app | UI, canvas view, Onyx device adapters, composition root | all |
| `:tools` | Kotlin/JVM | `scribe-preview` CLI: markup → SVG/PNG for tuning the hand on a laptop | `:scribe` |

### 3.3 File-role convention (the "where is X" rule)

Every bounded context is one package, and **every file ends with one of these suffixes**. The suffix tells you the role, the layer and what the file may import. If a file passes ~300 lines, split it with a qualifier (`ConversationRules.kt` → `ConversationTriggerRules.kt` + `ConversationContextRules.kt`), never with a new suffix.

| Suffix | Role | Module | May depend on |
|---|---|---|---|
| `*Model.kt` | Entities, value objects, sealed states. Immutable data only. No logic beyond trivial derived properties | domain | other `*Model` |
| `*Rules.kt` | **Business logic**: pure top-level functions. No I/O, no clock, no randomness (seed passed in) | domain, scribe (infra only for provider-specific pure logic) | `*Model` |
| `*Ports.kt` | Interfaces the context needs from the outside world | domain | `*Model` |
| `*UseCase.kt` | One application action: `suspend operator fun invoke(...)`. Orchestrates rules + ports. Stateless | domain | `*Model`, `*Rules`, `*Ports` |
| `*Session.kt` | Stateful, long-lived orchestrator with a lifecycle (`start/stop`) that drives a `*Rules` state machine and runs its effects | domain | same as UseCase |
| `*Parser.kt` | Text → model (incremental where needed). Pure | domain | `*Model` |
| `*Client.kt` | Adapter to an external service; implements a port | infra | domain, `*Dto`, `*Mapper` |
| `*Store.kt` | Persistence adapter; implements a port | infra | domain, `*Dto`, `*Mapper` |
| `*Dto.kt` | Wire / DB shapes (`@Serializable`, `@Entity`) | infra | — |
| `*Mapper.kt` | `Dto ↔ Model` conversion functions | infra | `*Dto`, `*Model` |
| `*Renderer.kt` | Draws a model onto an `android.graphics.Canvas` | app, infra | domain, scribe |
| `*ViewModel.kt` | UI state holder, exposes `StateFlow<*UiState>` | app | domain |
| `*UiState.kt` | UI state data | app | — |
| `*Screen.kt` | Compose screen (stateless; takes UiState + callbacks) | app | `*UiState`, theme |
| `*Component.kt` | Reusable stateless Composables that aren't whole screens | app | theme |
| `*View.kt` | Custom Android `View` | app | `*Renderer` |
| `*Device.kt` | Hardware adapter (pen, e-ink) behind an app-level interface | app | Onyx SDK |
| `*Registry.kt` | Maps a kind/key → strategy implementation (the OCP seam) | any | the strategy interface |
| `*Animator.kt` | Frame loop that plays a precomputed plan | app | `*Renderer` |
| `*Cache.kt` | Owns rebuildable derived data (bitmaps, rasters) | app, infra | `*Renderer` |
| `*Database.kt` | Room database declaration | infra | `*Dto` |
| `*Colors.kt` `*Type.kt` `*Theme.kt` | Design tokens and theme wiring (Compose) | app | — |

Entry points are the only exempt files: `NisabaApp.kt`, `MainActivity.kt`, `AppGraph.kt`, `Navigation.kt` (all in `com.nisaba.app`) and `ToolsMain.kt` (`:tools`). A Gradle check (`verifyFileRoles`) fails the build on any other file without an allowed suffix, **and on any source file over 300 lines** (tests excepted).

Splitting a big class keeps one owner of its state and moves cohesive groups of behaviour into sibling files as extension functions, named with a qualifier: `NotebookViewModel.kt` + `NotebookInkViewModel.kt`, `NotebookAnswerViewModel.kt`, `NotebookToolbarViewModel.kt`, `NotebookDebugViewModel.kt`; `PageView.kt` + `PageGestureView.kt`, `PageRegionView.kt`; `ConversationSession.kt` + `ConversationRecordSession.kt`. Logic with a life of its own moves into its own tested class instead (`InkEditSession`). Two app-side uses of domain suffixes are deliberate: `*Session` for a stateful per-page UI orchestrator (`AnswerScribeSession`), `*Ports` for a ViewModel's dependency bundle (`NotebookViewModelPorts.kt`).

Test files mirror their subject: `ConversationRulesTest.kt`.

### 3.4 Package tree

```
:domain  com.nisaba.domain
├── shared/            SharedModel.kt (Id, PagePoint, PageRect, Size)  SharedRules.kt (geometry)  SharedPorts.kt (Clock, IdGen)
├── notebook/          NotebookModel.kt   NotebookRules.kt   NotebookPorts.kt (NotebookStore)
│                      CreateNotebookUseCase.kt  RenameNotebookUseCase.kt  DeleteNotebookUseCase.kt
│                      InsertPageUseCase.kt  ListNotebooksUseCase.kt
├── ink/               InkModel.kt (Stroke, InkPoint, StrokeOrigin, PageInk, InkCommand)
│                      InkRules.kt (hit-test, erase/split, bounds, x-height estimate)
│                      ShapeRules.kt (clean shapes from a drag; Auto: recognise a rough line/triangle/box/ellipse)
│                      InkHistoryRules.kt (undo/redo stack — command pattern)
│                      InkPorts.kt (InkStore)
│                      CommitStrokeUseCase.kt  EraseUseCase.kt  UndoUseCase.kt  RedoUseCase.kt
├── document/          DocumentModel.kt (SourceDocument, DocumentText, PageRaster)
│                      DocumentPorts.kt (DocumentReader, DocumentReaderRegistry, DocumentStore)
│                      ImportDocumentUseCase.kt
├── answer/            AnswerModel.kt (AnswerDocument, Block, Inline, MathNode, Table, Diagram)
│                      AnswerParser.kt (streaming markup → blocks, §5.2)
│                      MathParser.kt (LaTeX subset → MathNode)  DiagramParser.kt (SVG subset → paths)
├── conversation/      ConversationModel.kt (Utterance, Exchange, ConversationState, Event, Effect)
│                      ConversationRules.kt (state machine reducer, §4.4)
│                      ConversationContextRules.kt (what goes in the request, token budgeting)
│                      ConversationPromptRules.kt (system prompt + markup contract text — provider-agnostic)
│                      ConversationPorts.kt (Oracle, PageSnapshotter, ExchangeStore)
│                      ConversationSession.kt (runs the state machine per open page)
│                      RecordExchangeUseCase.kt
├── persona/           PersonaModel.kt (Persona: voice paragraph, word budget, temperature, sample line)
│                      PersonaRegistry.kt (the voices, in picker order; unknown id → the default)
├── memory/            MemoryModel.kt (Fact, FactCategory, MemoryOp, MemoryPlan)  MemoryParser.kt (reply tags → ops)
│                      MemoryRules.kt (dedupe/plan, context format + budget, toolbar note)  MemoryPorts.kt (MemoryStore)
│                      ApplyMemoryUseCase.kt  ObserveMemoryUseCase.kt  ForgetFactUseCase.kt
└── settings/          SettingsModel.kt  SettingsPorts.kt (SettingsStore, SecretStore)

:scribe  com.nisaba.scribe
├── hand/              HandModel.kt (Glyph, GlyphStroke, HandStyle)  HandRules.kt (humanize, pressure profile)
│                      GlyphSetParser.kt (bundled single-line font → Glyphs)  GlyphCompositionRules.kt (diacritics)
├── layout/            LayoutModel.kt (Region, Occupancy, LaidLine, PlacedGlyph)
│                      TextLayoutRules.kt (line breaking, flow-around, page spill)
│                      MathLayoutRules.kt  TableLayoutRules.kt  DiagramLayoutRules.kt
│                      BlockLayoutRegistry.kt (Block kind → layouter, OCP)
└── motion/            MotionModel.kt (TimedStroke, ScribePlan)  MotionRules.kt (speed ramp, easing, pauses)
                       ScribePlanUseCase.kt (AnswerDocument + region + seed → ScribePlan, incremental per block)

:infra   com.nisaba.infra
├── db/                NisabaDatabase.kt  NotebookDto.kt  NotebookMapper.kt  NotebookStore.kt (Room)
│                      ExchangeDto.kt  ExchangeMapper.kt  ExchangeStore.kt
├── inkfile/           InkFileDto.kt  InkFileMapper.kt  InkFileStore.kt (per-page binary, atomic)
├── deepseek/          DeepSeekDto.kt  DeepSeekMapper.kt (OracleRequest → wire)  DeepSeekClient.kt (implements Oracle, SSE)
├── pdf/               PdfDocumentReader.kt (PdfRenderer raster, PdfBox fallback + text extraction)
│                      DocumentFileStore.kt (copies source into app storage)
├── render/            InkRenderer.kt (variable-width strokes; outline math = scribe HandRules.strokeOutline)  BackgroundRenderer.kt
├── snapshot/          PageSnapshotRenderer.kt (implements PageSnapshotter: page → JPEG for the model)
├── document/          DocumentReaderRegistry.kt
├── log/               LogClient.kt (implements Logger)
├── settings/          SettingsStore.kt (DataStore)  SecretStore.kt (Keystore AES-GCM)
└── http/              HttpClient.kt (OkHttp singleton, timeouts)  SseParser.kt

:app     com.nisaba
├── app/               NisabaApp.kt  AppGraph.kt (composition root)  MainActivity.kt  Navigation.kt
├── ui/theme/          NisabaColors.kt  NisabaType.kt  NisabaTheme.kt  NisabaComponent.kt
├── ui/brand/          MarkRenderer.kt  SplashScreen.kt  InkwellRenderer.kt
├── library/           LibraryScreen.kt  LibraryViewModel.kt  LibraryUiState.kt  CoverRenderer.kt
├── notebook/          NotebookScreen.kt  NotebookViewModel.kt  NotebookUiState.kt  ToolbarScreen.kt
├── canvas/            PageView.kt  ScribeRenderer.kt (nib, "…" mark)
│                      ScribeAnimator.kt (Choreographer loop, plays a ScribePlan)
│                      PageLayerCache.kt (background + committed-ink bitmaps)
├── device/            PenDevice.kt (interface)  OnyxPenDevice.kt  MotionEventPenDevice.kt (emulator fallback)
│                      EinkDevice.kt (interface)  OnyxEinkDevice.kt  NoopEinkDevice.kt
└── settings/          SettingsScreen.kt  SettingsViewModel.kt  SettingsUiState.kt
```

### 3.5 Patterns used, and where

| Pattern | Where | Purpose |
|---|---|---|
| Ports & Adapters (Hexagonal) | `*Ports.kt` ↔ `*Client/*Store` | Domain is testable without Android; providers swappable |
| State machine / reducer | `ConversationRules.reduce` | Timing/cancel logic is pure and exhaustively unit-tested |
| Command | `InkCommand` + `InkHistoryRules` | Undo/redo, including "undo an entire answer" |
| Strategy + Registry | `DocumentReaderRegistry`, `BlockLayoutRegistry`, `PenDevice`/`EinkDevice` | Add types without editing callers (OCP) |
| Observer (Flow) | Stores expose `Flow`, ViewModels `StateFlow` | Reactive UI |
| Builder (incremental) | `AnswerParser`, `ScribePlanUseCase` | Start writing before the model finishes |
| Composition root | `AppGraph` | One place wires everything (DIP) |
| Null Object | `NoopEinkDevice`, `MotionEventPenDevice` | Runs on the emulator; no `if (isBoox)` scattered around |

---

## 4. Domain

### 4.1 Model (core types)

```kotlin
// notebook
data class Notebook(val id: NotebookId, val name: String, val createdAt: Instant,
                    val updatedAt: Instant, val documentId: DocumentId?, val pageIds: List<PageId>,
                    val listening: Boolean, val teachGoal: String?, val personaId: String /* D17 */)
data class Page(val id: PageId, val notebookId: NotebookId, val size: PageSize,
                val background: PageBackground)
sealed interface PageBackground { data object Blank; data class DocumentPage(val documentId: DocumentId, val index: Int) }

// ink — page units: portrait page is 1860 × 2480 (= Air 5C panel px); PDF pages fit width, height by aspect
data class InkPoint(val x: Float, val y: Float, val pressure: Float, val tMillis: Int)
data class Stroke(val id: StrokeId, val points: List<InkPoint>, val width: Float,
                  val color: InkColor, val origin: StrokeOrigin)
sealed interface StrokeOrigin { data object User; data class Oracle(val exchangeId: ExchangeId) }

// conversation
data class Utterance(val pageId: PageId, val strokeIds: Set<StrokeId>, val bounds: PageRect)
data class Exchange(val id: ExchangeId, val notebookId: NotebookId, val pageId: PageId,
                    val heard: String, val answerMarkup: String, val status: ExchangeStatus,
                    val createdAt: Instant)
enum class ExchangeStatus { Complete, Interrupted, Silent, Failed }
```

### 4.2 Storage

- **Room** (`nisaba.db`): notebooks, pages, documents, exchanges, facts (v3), lesson goal (v4), usage columns + lesson topics (v5), the voice of each notebook and of each exchange (v6, older notebooks get `book`). Real migrations from v1 on (Play Store).
- **Ink files**: `files/ink/<pageId>.ink`, one per page. Versioned binary (`kotlinx.serialization` ProtoBuf over `InkFileDto`). Written atomically (temp + rename), debounced 500 ms after pen-up, flushed on `onPause`.
- **Documents**: the imported PDF is copied to `files/docs/<documentId>.pdf`; extracted text is cached in `files/docs/<documentId>.txt`; page rasters are cached in `cache/raster/<documentId>/<index>.webp` (can be rebuilt).

### 4.3 Conversation context (what the model gets)

The request order is chosen so DeepSeek's **prefix cache** hits (stable parts first):

1. System prompt (§5.1) (static)
2. Document text: the entire PDF text, page-tagged `[p.12] …` (static per notebook). Scanned or no-text PDFs send nothing here; the current page image still carries it.
2b. Memory (§4.5): `# Memory` then `## category` sections of `#id fact` lines (global, changes only when a fact does). After the document so a new fact leaves the bigger per-notebook prefix cached. Budget 25 % of the token budget (~2,000 facts); over it, the newest facts that fit.
3. Prior exchanges, oldest first, as alternating `user: <heard>` / `assistant: <answerMarkup>` text messages (append-only). `ConversationContextRules` drops the oldest once the budget passes ~120k tokens. With 1M context, that's rarely hit.
4. Current turn (user message): the text instruction plus two images:
   - **page**: the whole current page (background + all ink incl. prior answers), grayscale JPEG, ~1600 px long edge, `detail: auto`
   - **focus**: a crop of the utterance bounds + 10 % margin, `detail: high`

### 4.4 The state machine (`ConversationRules`)

```kotlin
sealed interface ConvState { Idle; Writing(utt); Pausing(utt, deadline); Thinking(utt, reqId);
                             Scribing(exId, plan, progress); Interrupted(exId, remaining); Failed(utt, error) }
sealed interface ConvEvent { PenDown; PenUp(stroke); Tick(now); AnswerStarted(reqId); AnswerChunk(reqId, blocks);
                             Silent(reqId); AnswerDone(reqId, heard); AnswerFailed(reqId, e);
                             ScribeFinished; TapContinue; TapSkip; ListeningToggled(on); AskNow }
sealed interface ConvEffect { StartTimer(at); CancelTimer; SendRequest(utt, reqId); CancelRequest(reqId);
                              ShowInkwell(anchor); HideInkwell; PlanBlocks(blocks); StopScribe; SaveExchange(...) }

fun reduce(state: ConvState, event: ConvEvent, settings: ConvSettings): Pair<ConvState, List<ConvEffect>>
```

Invariants (each is a unit test):
- A PenDown in `Pausing` or `Thinking` never produces a visible answer for the old request.
- `reqId` is monotonic. Chunks from a stale `reqId` are dropped.
- Every `Thinking` ends in exactly one of: Scribing, Idle (silent), Failed, Writing (cancelled).
- With Listening off, only `AskNow` leaves `Pausing` for `Thinking`.

`ConversationSession` owns one page's session: it feeds pen/stream/timer events into `reduce` and executes effects (starting/cancelling a coroutine job for the request, which cancels the OkHttp call).

### 4.5 Memory (D16)

What Nisaba knows about the person, shared by every notebook.
- **Storage:** Room `facts(id INTEGER autoincrement, category, text, sourceExchangeId?, createdAt)`. No foreign key: facts outlive their notebook. Short numeric ids because the model sees and names them.
- **Categories (fixed):** me, people, projects, books, screen, music, preferences, goals, other. Unknown → other.
- **Writing:** the model appends `<remember category="books">Loved Dune</remember>` / `<forget id="12"/>` after `</answer>` (§5.2). A change = forget + remember. After the exchange is saved (Complete/Interrupted only), `ApplyMemoryUseCase` parses the tags, `planMemory` drops blanks, duplicates (normalised text), unknown ids, >5 adds per reply, cuts at 200 chars; one transaction. `AnswerParser` strips tags so they are never ink, even if the model puts them inside `<answer>`.
- **Reading (stage 1):** all facts in every request (§4.3 2b); the model picks what is relevant.
- **Feedback:** toolbar chip "✎ Remembered: …" for 6 s (tap dismisses). **Settings → Memory → View**: paged list by category (no scrolling), Forget with confirm.
- **Stage 2 (later, when >~2,000 facts):** a `recall(query)` tool over an FTS index; vectors could slot in behind the same port.

---

## 5. AI integration (DeepSeek)

### 5.1 Endpoint

| | |
|---|---|
| URL | `POST https://api.deepseek.com/chat/completions` (OpenAI-compatible) |
| Auth | `Authorization: Bearer <key>` |
| Model | `deepseek-flash` (default) · `deepseek-v4-pro` |
| Images | `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…","detail":"high"}}`, **user messages only**, ≤ 8192 px/side, ~1024 tokens/image cap |
| Stream | `"stream": true` (SSE). We parse `choices[0].delta.content` and ignore `reasoning_content` |
| Thinking | Off by default. Setting on → `"thinking":{"type":"enabled"}`, `"reasoning_effort":"high"`. The inkwell keeps stirring while it reasons |
| Timeouts | connect 10 s, read 90 s, no retry on the user's behalf (Failed state → tap to retry) |

### 5.2 Response markup (streamable; JSON is not)

The model must reply with exactly one of:

```
<silent/><heard>…verbatim transcription…</heard>
```
```
<answer>
…markdown subset…
</answer>
<remember category="…">…</remember>   (optional, zero or more; §4.5)
<forget id="N"/>                        (optional)
<heard>…verbatim transcription of the new writing…</heard>
```

The markdown subset inside `<answer>`:
- paragraphs, `#`/`##` headings, `-` and `1.` lists, `**bold**`
- inline math `$…$` and display math `$$…$$` (LaTeX subset, §5.4)
- GFM pipe tables (≤ 6 cols, ≤ 12 rows, short cells)
- diagrams as a fenced block ` ```diagram ` containing `<svg viewBox="0 0 1000 1000">` with only `path` (M L Q C Z), `line`, `polyline`, `circle`, `ellipse`, `rect`, and **no** `<text>`. Labels go in a `labels` list beneath: `A @ 120,340`, which the hand writes at those points

`AnswerParser` is incremental: it emits a `Block` as soon as the block closes (blank line, closing fence or table end), so the hand starts writing the first paragraph while the rest is still streaming. Unknown constructs degrade to plain handwritten text, never to an error.

### 5.2a Why `<heard>` comes last
It costs latency if it comes first. Coming last, it still arrives in the same response, and it becomes the `user` turn text in future context (§4.3).

### 5.3 System prompt (v1 draft)

```
You are Nisaba, the spirit of this notebook. The person writes by hand; you see
the page as images and answer in ink, beneath their writing.

Decide first: is the NEW writing (the focus image) addressed to you: a question,
a request, a "?" or anything that expects a reply? If it is just notes, a
drawing, or a crossed-out mistake, reply exactly <silent/> followed by <heard>.

When you answer:
- Be brief and exact; it will be handwritten. Default ≤ 120 words unless asked
  for more. Prefer a short table, formula or small diagram over long prose.
- Answer in the language they wrote in.
- Use only the markup described below. No links, no code blocks, no emoji.
- The attached document text and earlier exchanges are context; cite pages
  as (p. 12) when you use the document.
- If the writing is illegible, say so in one line and ask them to rewrite it.
<markup spec from §5.2>
```

### 5.3a Voices (D17)
Since prompt v11 the system prompt is `systemPrompt(persona)` = the voice's own paragraph + the shared contract (everything else in §5.2–5.3, with the voice's word budget filled in). Rules that hold for every voice live in the contract: the answer is never replaced by the act, no cruelty about anything real, never repeat yourself, answer in their language whatever the voice. The prefix stays stable per notebook, so changing the voice costs one cache miss.
- **Voice line:** the final instruction (never cached) ends with "Stay in your voice (name), in the language they wrote in, at most N words… Always end with `<heard>`": after a change of voice the history is full of the old one, and the end of the context is what the model follows. Without the `<heard>` clause the longer line made replies drop the tag (4/20 in the smoke test).
- **Temperature** per voice (0.5–0.8); a Tidy copy always runs at 0.3. Lessons keep the voice; the teaching prompt sets the length.
- **Exchanges** record the voice that answered (`personaId`), and since v7 how long the answer took to start (`firstTokenMillis`, `firstBlockMillis`; Settings → Usage shows the typical time to first ink).
- **Answering habits** (v8), picked with the voice and changeable with it: *Everything* (every pause gets a reply), *Questions only* (notes come back `<silent/>` with their transcription, so search still finds them), *When I ask* (= listening off; the eye toggles it); *Brief · Normal · Detailed* scales the voice's word budget (×0.6 / ×1 / ×2).
- **What the model sees** (prompt v12+): colour JPEGs; the new writing as written, older ink faded, earlier answers always in lapis; "put the direct answer alone in a short first paragraph" so the hand starts sooner. Pen-down stops the hand but not the reply (its `<heard>` and memory still arrive; Continue writes the rest). Asking again thinks. Cut-short replies (`finish_reason`) keep their ink and teach nothing. Requests the pen cancels are recorded as billed (estimated). Past 150 facts, every 25 more, one text-only request merges and tidies memory.
- **Each voice its own hand** (Settings → The hand → Voices; on by default): `Persona.hand` = ink, slant, tidiness (scales every jitter of `HumanParams`), pace. The pace replaces only the default *Natural* speed; a chosen speed wins. The ink is stored on each stroke (`BlockRef.ink` → `answerPen(voiceInk)`), so an answer keeps its voice's colour after the voice changes; the model still sees every answer in lapis. Inks: book = the answer ink, teacher green, writer brown, explorer violet, flirt plum, Yukino blue-black (upright, neat), Zagreus blood red (slanted, swift).
- **Signs and lines:** every voice has a sigil (`PersonaSigilRenderer`: open book, wizard's hat, nib, compass star, snowflake, flame, angular shades, ring; own voices a star of 5–8 rays) on its notebooks' covers and in the picker, a first-page hint ("Write clearly. Then pause.") and a failure opener ("Bah." + "The ink wouldn't flow: no connection").
- **Voices of their own** (Room v9 `own_voices`, at most 6): "+ A voice of your own" in any picker → describe someone → one text-only request drafts name, tagline, voice paragraph (English, starts "You are Nisaba…", always helpful, never cruel or explicit), first line, hint, opener and length (`DraftVoiceUseCase`, tags parsed by `parseVoiceDraft`) → the hand writes its first line in its new ink → Keep / Try again. Ids start `own-`; temperature 0.75; forgetting one leaves its notebooks on the default voice. `Voices` (built-in + own) is the one lookup everywhere (`LocalVoices`, `NotebookSources.voices`, `ConversationContextPorts.voice`).
- **Eval:** `./gradlew :tools:run --args="eval [--persona …] [--case …] [--temperature-scale x]"` runs the real request builder and parser against DeepSeek with questions written by the app's own hand; 8 cases × 8 voices (63/64 at the shipped temperatures; ×1.6 drifted into the wrong language).
- **Gates:** `scripts/persona_gate.py [ids…] [--case N]` (5 handwritten-style questions per voice: contract, length, language); `scripts/prompt_gate.py --persona ID`. Both read the prompt from the Kotlin sources (`scripts/prompt_source.py`).

### 5.4 Math subset (`MathParser` → `MathNode`)
`\frac \sqrt[n]{} ^ _ \sum \prod \int \lim \infty \pm \cdot \times \le \ge \ne \approx \to`, Greek letters, `\left( \right)`, `\begin{pmatrix}`, `\text{}`. Unsupported commands are written literally in the hand (a graceful fallback, never a crash).

### 5.5 Privacy
The page images, the PDF text, the transcriptions and the remembered facts go to DeepSeek, under your own key; the first-run screen says so. Nothing else leaves the device; no analytics. Private notebooks send no memory. Backups leave the sealed key out.

---

## 6. Scribe (handwriting synthesis) — `:scribe`

Pipeline, pure and deterministic (seed = exchangeId hash):

```
AnswerDocument blocks ──(BlockLayoutRegistry)──▶ laid glyphs in page space, flowing around Occupancy,
                                                 spilling to next page
                     ──(HandRules.humanize)───▶ jittered glyph strokes, pressure profile
                     ──(MotionRules)──────────▶ ScribePlan: TimedStroke[] (points with t offsets)
```

- **Glyph source:** a bundled **single-line (stroke) font**, Hershey-derived, e.g. *EMS Casual Hand* / *EMS Delight* from the Hershey Text project (SIL OFL, OK for the Play Store; confirm the license in M3). Hershey symbol sets cover Greek and math operators. Two hands ship: *Casual* (default) and *Formal*. Letters the font lacks (æ ø å é ü ñ …) are **composed** from base glyph + diacritic mark (`GlyphCompositionRules`), so Norwegian and other Latin languages write correctly.
- **Legibility gate** (automated): render sample answers with `:tools` → send to `deepseek-flash` → it must transcribe them word for word. It runs in CI on demand when glyphs or the humanizer change.
- **Incremental:** `ScribePlanUseCase` plans block by block as blocks stream in. The animator plays block N while block N+1 is planned.
- **Output ink:** when a plan completes (or is interrupted), its strokes are committed through `CommitStrokeUseCase` with `origin = Oracle(exchangeId)` as one undoable `InkCommand`.

---

## 7. Device layer (Onyx) — `:app/device`

- **SDK:** `com.onyx.android.sdk:onyxsdk-pen` (≈ 1.5.4.x; confirm the version in spike S1) from `http://repo.boox.com/repository/maven-public/`. It likely also needs `onyxsdk-device`/`onyxsdk-base` and `org.lsposed.hiddenapibypass` on Android 11+ (verify). Everything is exempted (`""`), so a firmware update can't quietly take the pen away; a narrower list (tried in M18, PROGRESS §4 lesson 9) would only matter for a store build (D18).
- **`OnyxPenDevice`:** `TouchHelper.create(pageView, callback)`, `setLimitRect(pageRect, excludeRects = toolbar)`, `openRawDrawing()`, `setRawDrawingEnabled(true)`, `setStrokeStyle(FOUNTAIN)`. On `onRawDrawingTouchPointListReceived` it emits a completed `Stroke`. Eraser callbacks → erase events. Committed strokes are rendered with the same pressure→width curve as the raw layer, so ink doesn't shift at handover.
- **Raw layer vs our animations:** raw drawing locks screen refresh. Before the hand writes, pause raw drawing (`setRawDrawingRenderEnabled(false)` or `setRawDrawingEnabled(false)`), keep pen-*down detection* alive to interrupt, and restore it after. **This is spike S2, the biggest technical risk.**
- **`OnyxEinkDevice`:** the per-app refresh waveform via the framework OEC binder (`oec_service` → `IOECService.setAppScopeRefreshMode(mode, false)`). Proven on this device in the prior attempt. Classic `EPDController` is **absent** on the Air 5C Android 15 firmware. Modes: FAST while writing/scribing, NORMAL at idle + clean pass. Everything is reflective and defensive: failure = no-op.
- **Fallbacks:** `MotionEventPenDevice` + `NoopEinkDevice` for the emulator and non-Boox devices (dev only; the Play listing targets Boox).

---

## 8. Rendering — `:app/canvas`

`PageView` composes three layers:
1. **Background** (cached bitmap): paper, or PDF page raster (`PdfRenderer`; PdfBox fallback, because the prior attempt saw black renders on Android 15 for some PDFs).
2. **Committed ink** (cached bitmap, incrementally updated per stroke; full rebuild only on erase/undo).
3. **Live overlay:** the scribe's current strokes + nib, the inkwell, the eraser trail.

`ScribeAnimator` is a Choreographer loop capped at 20 fps. Each frame it draws the new points since the last frame into layer 2, then invalidates their dirty rect plus the nib rect. No allocations per frame.

`PageSnapshotter` (infra) reuses the renderers off-screen to build the JPEG sent to the model.

---

## 9. PDF

- "New from PDF" → SAF picker → `ImportDocumentUseCase`: copy the file, extract text (PdfBox-Android), count pages, create the notebook with one page per PDF page (`PageBackground.DocumentPage`).
- Blank pages can be inserted anywhere (toolbar "+ page after"). Answer spill pages are blank pages too.
- The whole PDF text is context for every question in that notebook (§4.3). PDFs > ~400k tokens of text: send a page-window around the current page (±30 pages) plus the table of contents, if one exists.
- `DocumentReader` is a port with a registry keyed by MIME type, so EPUB/DOCX later = new reader, no other changes.

---

## 10. Quality

| Layer | Tests |
|---|---|
| `:domain` | JVM unit: state machine invariants (§4.4), erase/split, undo/redo, context budgeting, `AnswerParser` (streamed in random chunk sizes → same result), `MathParser` |
| `:scribe` | JVM unit + golden SVGs: layout, flow-around, spill, deterministic plans; the legibility gate (§6) |
| `:infra` | MockWebServer: SSE parsing, cancellation aborts the socket, error mapping; Room migration tests; ink file round-trip + corruption safety |
| `:app` | JVM: view models, `AnswerScribeSession`, `ScribeAnimator`, redraw bookkeeping (`PageRedrawRules`); a few instrumented tests on the device: open notebook, stroke persists, page turn |
| Manual | Device checklist per milestone (latency, ghosting, lit/unlit contrast) |

Tooling: ktlint + detekt, `verifyFileRoles` (§3.3), lint `abortOnError` (Android imports in `:domain`/`:scribe` can't compile — they're JVM modules), and CI on push (unit tests + assemble; since M18 also the `:infra` instrumented suite (migrations, backup, PDF, settings) on an API 34 emulator). No test asserts tight wall-clock times. The Android SDK and Gradle live in `.tools/` (gitignored).

**Definition of done** for any change: tests green, lint clean, the file-role convention respected, verified on the device if it touches input/rendering.

**Release build (M18).** AGP 8.10.1, compile/target SDK 36. R8 shrinks and minifies release builds; keep rules in `app/proguard-rules.pro` (kotlinx.serialization, PdfBox, `com.onyx.**`, `android.onyx.**`, HiddenApiBypass; Room keeps its own). Signed with the debug key (D18), so it installs over the debug build and keeps the notebooks; it carries no `.env` key (it uses the one saved in Settings) and starts in ~0.33 s against ~1.2 s for debug. Off the main thread: request building (`ConversationSession(compute = Dispatchers.Default)`: PDF text, history, images) and hand planning; the hand's glyph file is parsed lazily (251 ms, after the first frame); a page redraw that only adds strokes draws just those (`PageRedrawRules.redrawOf`).

---

## 11. Device spikes (do first; they decide details above)

| # | Question | Pass criteria |
|---|---|---|
| S1 | Onyx Pen SDK version + deps that work on Air 5C / Android 15 | Raw drawing at ≤ 20 ms latency, pressure works, eraser callbacks fire |
| S2 | Can we animate our own drawing while TouchHelper is open, and still detect pen-down to interrupt? | The hand writes at ≥ 15 fps; pen-down detected ≤ 50 ms |
| S3 | OEC refresh modes: which mode values = fast / normal / clean? | Visibly faster refresh during scribing, clean after |
| S4 | `deepseek-flash` reads real handwriting from our JPEG at 1600 px + a focus crop | ≥ 95 % words correct on 10 sample pages; TTFT measured |
| S5 | Lapis `#27407A` at 2–4 px stroke on Kaleido 3 | Readable unlit at arm's length, else fall back to black |
| S6 | Handover: raw-layer stroke → our committed render | No visible shift or weight change |

---

## 12. Milestones

| M | Deliverable | Done when |
|---|---|---|
| **M0** | Scaffold: modules, convention checks, CI, theme tokens, spikes S1–S6 | The spikes table is filled in with measurements |
| **M1** | Ink notebook: library, create/rename/delete, pages, Onyx pen, eraser, undo/redo, persistence | Write 20 pages, kill the app, everything is back; latency ≤ 20 ms |
| **M2** | The ask loop, text only: state machine, DeepSeek streaming, inkwell, answer rendered as plain (non-animated) strokes | Pause → answer ≤ 2.5 s; pen-down cancels; notes stay silent |
| **M3** | The hand: glyph set, humanizer, motion, animator, nib, interrupt/continue, hand size matching | Passes the legibility gate; feels written, not printed |
| **M4** | Rich blocks: tables, math, diagrams; flow-around; page spill | Sample "explain X with a table and a formula" renders beautifully |
| **M5** | PDF notebooks: import, render, whole-document context, flow around printed text | Ask about p. 40 while on p. 3 and get it right |
| **M6** | Brand & polish: splash, mark, generated covers, settings, first-run, privacy notice | Screenshots you'd put on a store page |
| **M7** | ~~Play Store readiness~~: dropped, personal use only (D18); SDK 36 and an R8 release build came with M18 | — |

---

## 13. Out of scope (v1) / later

Sync (export and a backup zip exist since M17; lasso since M15), EPUB/DOCX/TXT, right-to-left hands (Arabic), zoom, voice, other AI providers (the `Oracle` port is ready), handwriting-to-text search, memory stage 2 (`recall` tool + FTS, §4.5).
