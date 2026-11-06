# Nisaba: features, polish ideas, remaining gaps

*As of 17 October 2026 (branch `fresh-start`, `5dbb1fd` + M23 uncommitted). Personal use only: no store release (DESIGN D18).*

Nisaba is a handwriting notebook for the Boox Note Air 5C. You write with the pen; when you pause, an AI (DeepSeek) answers on the page in synthesized handwriting, in the voice you picked for that notebook. Details of each feature live in DESIGN.md (§1 is the experience); what changed when is in PROGRESS.md §9.

---

## 1. Features

### Writing and the page
- **Pen**: the Onyx pen layer (raw drawing, pressure), with a standard pen as fallback if the Onyx SDK can't start (Settings → Your pen). Only the stylus draws; palms are ignored.
- **Inks and sizes**: 8 inks, Fine / Medium / Bold; a dot on the pen icon shows a non-black ink; remembered.
- **Shapes**: *Auto* turns a rough line, box, triangle or ellipse into a clean one; or pick Line, Arrow, Box, Ellipse, Triangle and drag.
- **Erasers**: stroke eraser (eraser end or the button), area eraser that splits strokes (hold the eraser icon).
- **Lasso**: loop ink (or printed text) to move it, recolour it, delete it, check it, redraw it cleanly or ask about it. Each change is one undo step; the pen comes back afterwards.
- **Undo / redo**: buttons, or a two- / three-finger tap (a notice says so, in case a palm did it).
- **Paper**: blank, lined, grid or dots for a notebook's blank pages; on lined paper the hand writes on the lines.
- **Endless canvas**: one page that pans in every direction, an Overview to jump around, Home.
- **Zoom on the canvas**: 25 % to 200 % in steps; pinch with two fingers (their middle drags too), or − · 100 % · + in the bar (tap the % for 100 %); *See everything* zooms out until all the ink fits. Pen, eraser and answers stay the same size on screen at any zoom; the zoom is remembered.
- **Page turns**: finger swipe; neighbouring pages are kept loaded. A page a swipe added and left blank disappears again.
- **The bar**: auto-hides after writing; a small star in the corner brings it back. The star's badge shows the state: "…" answering, "?" failed, a closed eye when muted, the tool's icon when the eraser, lasso or shapes are on. Hold the star for pen · eraser · undo · listening. Hold the eye for how Nisaba answers here.
- **Saving**: every stroke is on disk within about a second, written atomically with a backup copy; a page whose file can't be read is set aside, never overwritten, and the page says so.

### Asking
- **Write, then pause**: after 3.5 s (adjustable) Nisaba answers what you wrote since the last answer. Writing more before it answers just makes the question longer; pen-down while it writes stops the hand, and tapping the "…" mark continues.
- **Point at things**: circle, underline, bracket or arrow at printed text, old notes or an answer, and write "?" or a question.
- **Ask now**: with listening off (the eye), the quill sends what is written.
- **Answering habits per notebook**: *Everything*, *Questions only* (plain notes stay silent but are transcribed for search), *When I ask*; length *Brief · Normal · Detailed*. Hold the eye in the bar to change them.
- **Check my work**: a ✓ or a ✗ with a short correction beside each line of your working (⋯ or the lasso).
- **Redraw cleanly**: a rough sketch comes back as a clean diagram below it.
- **Tidy this page**: a clean, structured copy of your notes on a new page.
- **Questions kept**: a question left on a page you turned away from, or one that failed offline, is asked when you're back or the network is.

### Answers and the hand
- **The hand**: written stroke by stroke, letter by letter; joined print by default, full cursive as the second style; speed Slow / Natural / Swift; size follows your handwriting.
- **Placement**: below your question, flowing around ink and printed text; a long answer continues on a fresh page inserted after it.
- **Rich blocks**: headings, lists, bold, **math** (fractions, roots, sums, integrals, matrices, Greek, sin/ln as words), **tables** (the hand rules them), **diagrams**, **code** on a monospace grid with highlighting by pen colour for ~30 languages.
- **On a printed PDF page**: answers go in the margin beside the question as short glosses.
- **Long-press an answer**: Erase it (every page it spilled onto), Ask again (thinking on), Shorter, Deeper, An example, Ask another voice… (a second opinion below it), Copy the words, Share….
- **A finger tap** on an answer being written finishes it at once.

### Voices
- **Built in**: The sassy book (default), Gandalf (teaches, or just talks), The writer's companion, The idea explorer, Plain.
- **Each voice its own hand**: ink, slant, tidiness and pace (Settings → The hand → Voices); a sigil on covers and in the picker; its own first-page hint and failure line.
- **Voices of your own** (up to 6): describe someone, Nisaba drafts the voice (name, tagline, manner, first line), you keep it or not.
- **Change the voice** of a notebook any time (⋯ → Voice); old answers keep their ink.

### Memory
- Nisaba remembers facts about you across notebooks (`<remember>` / `<forget>`); a chip says what it noted.
- Settings → Memory lists them; forget any. The list is tidied once it grows past 150.
- **Private notebooks** neither feed nor read memory and never show up in search.

### Learning
- **Learn a PDF**: a tutor teaches the PDF one idea at a time on blank pages, checks with a question, records topics as learned or shaky; *Lesson progress* shows them. Coming back after a while, it recaps and re-checks a shaky topic.
- **Spaced review**: topics come back after 2, 4, 8, 16, 30, 60 days (shaky ones the next day); ⋯ → *Review what's due*.
- **Tutor me on this PDF**: turns a PDF notebook into a lesson in place (and back).
- **Quiz me on this notebook**, **Today's page** (open loops and a question for the day), **This week, recapped**; optionally a page each morning (Settings → Waiting).

### Understand a book
- New → *Understand a book* → a voice → the PDF. Nisaba reads the **whole** book (a long one in parts, four at a time, resumable) and makes a map: the book's title and author, its argument, 5–12 core concepts in the order to learn them.
- Page 1 is the map, written small enough to fit one page. Write "next" (any language) or ⋯ → *Next*: each concept gets a fresh page, explained from its own pages of the book, connected to what came before, ending with a question. Then *The whole picture*.
- Questions written on a concept's page are answered in its light. ⋯ → *The map of the book* shows ✓ walked and › next; tap to go back to a concept.
- The notebook takes the book's real title.

### PDFs
- **PDF notebooks**: every page of the PDF is a page to write on; the page chip shows printed page numbers ("p. 12", an added page "p. 12 +1"); **Go to p.**
- **Open with Nisaba / Share to Nisaba** from other apps.
- Answers know the whole document (a window around where you are for long ones), and cite pages.

### Library and pages
- **Covers**: generated from the notebook's name, with the voice's sigil; six per screen, ‹ Earlier · Later ›; kind, voice, "Private", "edited 2 h ago" under each; a book walk shows how far it got ("3 of 9").
- **Continue ›** opens the notebook written in last, at the page you left.
- **Pin to the front**, **Rename**, **Trash** (kept 30 days; restore, delete, empty, with a second tap to confirm).
- **Page grid**: thumbnails of the pages (printed ones too); long-press for Delete (confirmed), Move, Duplicate.
- **Search**: questions and answers, notebook names, the text of PDFs; opening a hit outlines where it was asked.
- **Start here**: a first notebook the hand writes itself on first run, explaining the gestures.

### Reminders
- Write "remind me tomorrow at 9 to call Ola": a chip offers it; *Add* hands it to a calendar app or, on the Boox, posts Nisaba's own notification at the time (a reminder whose alarm the device refused is posted on the next open). Reminders are set again after a restart of the device.

### Files and safety
- **Export** a notebook as PDF (paper or printed page plus ink; a PDF notebook gives the pages written on) or as Markdown (questions and answers).
- **Backup**: a zip of everything except the API key, now or weekly to a folder. **Restore** asks first, swaps everything in with rollback on failure, and restarts.
- **Crash log**: the last crash is kept on the device (Settings → About: Copy / Clear; `scripts/device.sh crash`).

### Settings
- **Key & model**: DeepSeek key (encrypted, test button), Flash or Pro, thinking.
- **Waiting**: pause length, a page each morning.
- **The hand**: speed, style, each voice its own hand.
- **Ink & colours**: answer ink lapis or black, code pens.
- **Your pen**: Onyx or standard.
- **Memory**: view and forget.
- **Backup**: back up now, weekly folder, restore.
- **Usage**: answers, tokens and estimated cost this month and in total, typical time to first ink.
- **About**: build, prompt version, last crash.

### E-ink and device
- No fades, scrolling or ripples; discrete frames; a clean refresh after answers and when dialogs close.
- The Back key closes any dialog, the lasso bar, the mini bar and the overview.
- Chips over the page are kept out of the pen's reach; notices go away by themselves.
- One app at a time (`singleTask`): opening a PDF from another app reuses the running notebook.

---

## 2. Further polish, recommended

Ordered by how much you would feel it. Size: S ≈ hours, M ≈ a day, L ≈ days.

### Writing feel and speed
1. **Tiled ink layer** (L). The page's ink is one full-screen bitmap, so every stroke and every frame of the hand re-uploads all 18.5 MB of it. Splitting it into tiles that redraw only when touched would make writing and the hand smoother.
2. ~~Instant page turns~~ measured in M23: 55–85 ms from swipe to drawn page in the debug build, the ink rebuild only 5–23 ms of it (461 strokes). Under the 150 ms target; not worth pre-drawn neighbours.
3. ~~Lasso off the UI thread~~ done in M23 (and no full repaint with the Onyx pen).
4. **Smoother long answers on a canvas** (M). Cache each written piece's outline and pan at stroke boundaries, not inside a frame.
5. **Faster first ink** (M). The HTTPS connection now opens at pen-up (M23); still to do: build the page picture for the model while the pause timer runs.
6. **Quicker cold start after an install** (S–M). A baseline profile for the app's own code; load the fonts and create the HTTP client off the UI thread.

### Understand a book
7. ~~Map on one page~~ done in M23 (smaller hand, shorter names).
8. **Progress where you see it** (S). "3 of 9" under the cover is done (M23); *Continue the walk* in the library is not.
9. **Chapters from the PDF's outline** (M). When the PDF has a table of contents, read it chapter by chapter and anchor the concepts to chapters.
10. **Remember what you walked** (M). A short quiz at the end, and spaced review of the concepts (the lesson notebooks' streaks).
11. **A study guide out** (S). Export the walk as Markdown: the map, then each concept's page.
12. **EPUB** (L). Most books are EPUBs; turn them into pages (or into the same reading path) so they can be walked too.
13. **Scanned books** (L). Read the page images with the vision model, page by page, to get text to map from (costlier).

### Answers and voices
14. ~~Answering habits in reach~~ done in M23: hold the eye.
15. **Legibility check** (M). Run the legibility gate on math and tables, and tune the code inks and lapis on the Kaleido colour panel.

### Library, pages, files
16. **Undo across pages** (M). Undo is per page; at least make a deleted page undoable.
17. **Cancel** (S). Cancel for PDF import and export, with progress for long exports.
18. **Export a PDF notebook onto the original PDF** (M). Write the ink onto the PDF's own pages with PdfBox: the whole book, full quality, small file.
19. **Find your notebooks** (M), once there are many: filter by kind or voice, or simple folders.
20. **Mornings setting** (S): it sits under *Waiting*; give it a clearer home.

### Robustness and tooling
21. ~~Reminders after a reboot~~ done in M23.
22. **A device smoke test** (S). One script that installs, opens, writes, gets an answer, turns pages, opens the grid and exports; run it after each change.
23. **CI with the emulator** (M). Push once so the new job runs; add app-level instrumented tests (open a notebook, a stroke persists, a page turn).
24. **Measure** (S–M). A Perfetto trace against the targets (page turn ≤ 150 ms, hand ≤ 8 ms per frame) and the pen spikes (latency, refresh modes, colour on Kaleido, Onyx handover).

### Bigger, later
25. **Search your handwriting** in *When I ask* notebooks (transcribe pages in the background) (M).
26. **Memory stage 2**: keyword recall once facts pass ~2,000 (M).
27. **Arabic (right-to-left) hand** (L).
28. **Sync** between devices (L); a weekly backup to a cloud-synced folder works today.

---

## 3. Remaining gaps

### Known limitations
- The ink layer is one full-screen bitmap: every change uploads it whole.
- Page ink is rebuilt on the UI thread (5–23 ms for 461 strokes; a whole turn 55–85 ms in the debug build).
- Zoom is on the endless canvas only, not on pages or PDFs.
- Long answers on an endless canvas can stutter when the hand reaches the bottom of the view.
- The page picture for the model is made only when the request starts (the connection opens at pen-up).
- Exporting a PDF notebook gives the pages written on, printed pages at half resolution.
- Undo is per page.
- Book walk: scanned PDFs are refused; no EPUB.
- PDF import and export can't be cancelled.
- The Boox refuses wake-up alarms: a reminder due while the device sleeps is posted when it wakes or on the next open.
- Memory has no keyword recall (fine until ~2,000 facts).
- No right-to-left handwriting; no sync.

### Built but not verified on the Boox
- A book walk all the way to *The whole picture* (checked: reading, the map, "next" to concept 1, the map dialog, the title).
- A reminder handed to a calendar app (the notification path was checked).
- The CI emulator job (runs on the first push).
- Pen latency, refresh modes, ink legibility on the Kaleido panel, the Onyx-layer handover (spikes S1, S2, S3, S5, S6).
- A Perfetto trace against the targets.
- The answer overlay redrawn mid-answer, and the switch from the Onyx pen to the standard pen when the SDK fails.
- A two-finger pinch by hand (synthetic events were used), writing with the Onyx pen while zoomed, reminders after a real reboot.
