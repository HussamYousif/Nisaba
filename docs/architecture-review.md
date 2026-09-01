# Architecture Review — Nisaba

Status: **Review + decisions** · Date: after commit `1dacdc7` (conventions ratified) · Scope: the Android frontend (`app/`), the only implemented tree today.

## 1. What was reviewed

Every `com.nisaba.*` file (35 main + 23 test), the cross-module import map, `AGENTS.md`/`docs/architecture.md` rules, and the verification state (60 JVM + 77 instrumented tests — 72 passing, 5 deliberate skips — lint clean with baseline, emulator-verified flows; audit P4-5).

## 2. Findings

### Strengths (keep as-is)

1. **Feature-per-package with honest layers.** `ink/`, `pdf/`, `llm/`, `render/`, `magic/` are self-contained; pure logic (StrokeBuilder, Eraser, PageTransform, PromptBuilder, AnswerParser, SummonController) is separated from Android views and I/O. The D2/D3 `InkPageRenderer` fix removed the last internal reaches.
2. **Leaves are real.** DeepSeekClient, PdfImporter, PageBackgroundCache, InkPageRenderer, AnswerInk call nothing inside their own modules.
3. **Typed errors + failures-as-data.** `ApiException`/`NetworkException`/`ParseException`/`ImportCancelled`; `AnswerGroup.status` records pending/complete/cancelled/error in the model instead of toasts.
4. **Composition root is honest.** `MainActivity` wires; business logic is elsewhere.
5. **Tests mirror modules** and mostly go through public surfaces.

### Problems

| # | Problem | Severity |
|---|---|---|
| P1 | **`ui/NotebookScreen` is a 477-line god-object**: view wiring + undo/redo + page navigation + autosave + content tracking + summon orchestration + answer rendering + stir indicator in one class, importing 6 modules. It mixes *state* (mutable notebook, stacks, versions) with *view* concerns. The state half is untestable without an activity and the view half is unreadable. | High |
| P2 | **No single mutation path.** The screen calls `markDirty()`/`renderAnswers()`/`updatePageUi()` ad hoc after each mutation; state can drift (e.g. an answer appended without re-render). | Medium |
| P3 | **Thumbnail rendering lived in the data layer** (`PageThumbnail` inside `NotebookStore.kt`): `notebook/data` imported `ink`'s renderer — a data-layer file doing presentation. *Resolved by D-B (`render/PageThumbnail`).* | Low |
| P4 | **Answer parsing merges two jobs** (`AnswerParser` validates *and* maps failures to placeholder text). Small today; will grow with the SVG/table contract. | Low |
| P5 | **Reveal/state coupling risk for M4**: `renderAnswers()` rebuilds the whole section; M4's ink-reveal animations need incremental, skippable presentation — must be designed in before the renderers land, or the screen grows worse. | Medium (future) |

### Non-issues (deliberately left alone — deletability beats fashion)

- No ViewModel/LiveData/Flow/DI framework: plain listeners and one scope keep each class independently readable and deletable. Adding a framework would be the biggest coupling risk in the codebase.
- No navigation library: three screens, one container.
- `AppSettings` lives in `llm/` — it *is* the LLM feature's settings; moving it would be churn without benefit.
- Models are plain `@Serializable` data classes mutated by wholesale list replacement — no ORM, no database.

## 3. Decisions

**D-A. Extract `NotebookSession` (core, `notebook` domain) — fixes P1/P2.**
The session owns: notebook state, page index, undo/redo stacks, content version (summon gate), save scheduling, and a single mutation path with a `Listener(reason)` event. The screen keeps only: view wiring + the summon orchestration (which is legitimately ui-layer composition of client/facade/core). The screen's public surface (`bind(notebook, settings, onBack, onSave)`) is unchanged so existing tests pass untouched.

**D-B. Move thumbnails to the `render` domain (P3).** `render/PageThumbnail` becomes the presentation-side entry; `NotebookStore` (data) stops doing presentation.

**D-C. M4 renderers arrive as leaves with their own pure core (P5).**
`render/` grew into: `SvgPathParser` (pure string→commands, JVM-testable), `SvgInkRenderer` (commands→android Paths), and `AnswerInk` — which composes text/tables/drawings into one page-space layout with per-item reveal ticks. The planned `DrawingInkView`/`TableInkRenderer`/`InkReveal`/`AnswerSectionRenderer` collapsed into `AnswerInk` + the reveal loop inside `InkSurfaceView` (audit P4-2).

**D-D. Explicitly rejected:** DI frameworks, ViewModels, Flow/LiveData, navigation libraries, a shared cross-module "utils" package. Each would couple modules that today can be deleted in isolation.

## 4. Consequences & verification

- `NotebookSession` is JVM-testable (injectable `SaveScheduler`), so undo/redo/page/content-gate logic gets real unit tests that were impossible inside the screen.
- D1 was resolved by extracting `NotebookSession`: the screen is now view wiring + summon orchestration only, and every mutation flows through `session.changed(reason)`, so the view can no longer drift.
- Existing tests must pass **unchanged** except the summon flow test's *additions* for M4 block types.
- M4 continues after the refactor in the same commit series.

## 5. Debt register after this review

| # | Item | Status |
|---|---|---|
| D1 | NotebookScreen god-object | ✅ split via `NotebookSession` (D-A); screen keeps orchestration only |
| D2/D3 | data/facade → ink internals | ✅ (previous round) |
| D4 | `AnswerParser` parse+map merge | ⏳ split when the SVG/table contract grows (M4 keeps it; the renderers validate separately) |
| D5 | thumbnails in data layer | ✅ moved to `render/PageThumbnail` (D-B) |
