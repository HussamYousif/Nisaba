# 03 — Architecture

Status: **Draft v0.1** · Applies to v1 · Package root: `com.nisaba`

> **Structure rules**: this document describes the *shape of the system*. The
> *rules of structure* — low coupling, additive-only changes, layer roles,
> leaf files, monorepo layout — live in `docs/architecture.md` (summary:
> `AGENTS.md` at the repo root). Every change must follow those rules.

## 1. Stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin 2.x | Modern Android standard |
| UI | Classic Android Views (single Activity) | Full, predictable control of drawing & refresh; see ADR-003 |
| Rendering | Custom `InkSurfaceView` (hardware-accelerated `Canvas`) | Vector strokes with pressure; incremental draws |
| Async | Kotlin Coroutines (Dispatchers.IO for network/storage) | Simple, cancellable (needed for cancel-on-resume) |
| JSON | `kotlinx.serialization` | Notebook files + LLM responses |
| Network | OkHttp + `kotlinx.serialization` (no Retrofit needed for one endpoint) | Fewer deps; easy raw-stream cancellation |
| PDF | `PdfPageRenderer` seam: framework `PdfRenderer` first, PdfBox-Android fallback | Android 15's framework renderer outputs black on the Boox; PdfBox is the working engine there; see ADR-004 |
| Secrets | `EncryptedSharedPreferences` (AndroidX Security) | API key at rest |
| Fonts | Bundled TTF in `assets/fonts` (OFL): Dancing Script, Great Vibes, Kalam | No runtime downloads (offline-first) |
| minSdk / target | 26 / 35 | Boox is Android 13+; 26 keeps headroom |
| Build | Gradle Kotlin DSL, AGP 8.x | Standard |

**Dependencies (planned, minimal):**
- `androidx.core`, `androidx.appcompat`, `androidx.activity`, `androidx.recyclerview` (shelf)
- `androidx.security:security-crypto` (key storage)
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `kotlinx-serialization-json`
- `com.squareup.okhttp3:okhttp`
- `com.onyx.android.sdk` — *optional*, added only if device testing demands EPD control (ADR-002)

## 2. Module layout (single Gradle module `app`)

```
app/src/main/java/com/nisaba/
├── MainActivity.kt            # single activity, hosts all screens
├── NisabaApp.kt               # Application class (PdfBox resource init)
├── epd/                       # e-ink control for Onyx/Boox (defensive leaves)
│   ├── Epd.kt                 # EPDController via reflection: fast refresh, clean pass
│   └── Oec.kt                 # OEC binder service: refresh waveform while pen is down
├── ink/                       # THE core: stylus capture + page rendering
│   ├── InkSurfaceView.kt      # custom view: input + draw + answer reveal
│   ├── InkPageRenderer.kt     # strokes + background → bitmap (public leaf)
│   ├── StrokeBuilder.kt       # MotionEvent → Stroke (pressure, tool-type gating)
│   ├── StrokeRenderer.kt      # path batching, pressure width, incremental draw
│   ├── PageTransform.kt       # page-space ↔ view-space matrix
│   └── Eraser.kt              # stroke hit-testing
├── notebook/
│   ├── model/Models.kt        # Notebook, Page, Stroke, AnswerGroup, Transcript (kotlinx DTOs)
│   ├── NotebookStore.kt       # load/save, atomic tmp+rename writes, tmp recovery
│   ├── NotebookSession.kt     # notebook core: state, undo/redo, pages, save, events
│   ├── SaveScheduler.kt       # debounced autosave (HandlerSaveScheduler)
│   └── SummonController.kt    # idle detection + cancel-on-resume (F3)
├── pdf/
│   ├── PdfPageRenderer.kt     # framework render → black-detect → PdfBox fallback
│   ├── PdfImporter.kt         # SAF picker → copy → render pages
│   └── PageBackgroundCache.kt # LRU bitmap cache, ≤200dpi RGB_565
├── llm/
│   ├── DeepSeekClient.kt      # OkHttp → /chat/completions
│   ├── AppSettings.kt         # API key + prefs (Keystore-backed)
│   ├── ChatContract.kt        # wire ↔ model contract
│   ├── PageImagePreparer.kt   # page → grayscale PNG-8 data-URI
│   ├── PromptBuilder.kt       # system prompt + transcript + image
│   └── AnswerParser.kt        # JSON → List<AnswerBlock>, repair retry
├── render/
│   ├── AnswerInk.kt           # answer group → page-space ink layout (text/drawing/table)
│   ├── SvgPathParser.kt       # SVG path string → commands (pure, JVM-testable)
│   ├── SvgInkRenderer.kt      # commands → android Paths
│   └── PageThumbnail.kt       # page → shelf thumbnail bitmap
├── magic/
│   ├── MagicTheme.kt          # parchment palette, ink colors, ornaments
│   ├── Fonts.kt               # bundled cursive fonts (Dancing Script, Great Vibes, Kalam)
│   └── SummonInk.kt           # the summon's on-page ink narrative ("the ink stirs…")
└── ui/
    ├── NotebookScreen.kt      # notebook screen: view wiring + summon orchestration
    ├── ShelfScreen.kt         # shelf (notebook list)
    ├── SettingsScreen.kt      # settings (F7)
    └── InkScrollView.kt       # finger-only ScrollView; stylus passes through
```

## 3. Layers & data flow

```
┌────────────────────────────────────────────────────────────────┐
│  UI layer (main thread only)                                   │
│  MainActivity ── shelf ── notebook screen (InkSurfaceView +    │
│                 AnswerInk + floating quill button)       │
└───────────────┬────────────────────────────────────────────────┘
                │ events            │ invalidate()
┌───────────────▼─────────┐  ┌──────▼───────────────────────────┐
│ ink/ (stroke capture,   │  │ render/ + magic/ (answer blocks, │
│ SummonController)       │  │ reveal loop in InkSurfaceView)   │
└───────────────┬─────────┘  └──────┬───────────────────────────┘
                │ strokes/transcript│ blocks
┌───────────────▼───────────────────▼──────────────────────────┐
│ Domain: NotebookSession / NotebookStore / SummonController │
└───────┬──────────────────────────────┬───────────────────────┘
        │ save (IO)                    │ summon (IO)
┌───────▼──────────┐          ┌────────▼───────────────────┐
│ Storage: JSON    │          │ llm/: PageImagePreparer →  │
│ (atomic writes)  │          │ DeepSeekClient → AnswerParser│
└──────────────────┘          └────────────────────────────┘
```

**Threading rules:**
- `InkSurfaceView` touch handling and drawing: **UI thread only**. Stroke capture appends points and invalidates a dirty rect — no allocations per point beyond a pooled `FloatArray` (N1).
- Storage I/O: `Dispatchers.IO`, serialized through a single `NotebookStore` mutex so saves never interleave with a summon read.
- LLM: `Dispatchers.IO`, **cancellable** (OkHttp call in a `CoroutineScope` tied to the summon job — cancel-on-resume cancels the call, F3.2).
- Image prep: off-main, one pass, no full-size ARGB intermediates (07 §3).
- the reveal loop in `InkSurfaceView` animation: a single `Choreographer`-driven postInvalidate loop that only runs while an answer is drawing itself; steps are discrete and EPD-friendly (08 §5).

## 4. The core loop, end-to-end

1. **Write**: `InkSurfaceView.onTouchEvent` → `StrokeBuilder` (gated by hover palm-rejection) → points appended → dirty-rect `invalidate()` → `StrokeRenderer` batches quads → GPU draws.
2. **Pause**: `SummonController` runs a 3 s `Handler`-based timer (no polling); any new stroke point resets it. On fire: check F3.4 (new content since last summon) → if OK, enqueue summon; while pending, any new point → cancel job + reset timer.
3. **Summon**: freeze the *input* page state (strokes snapshot), render current page → grayscale PNG (07 §3) on IO; build messages (system + transcript + image user message); POST to DeepSeek with 120 s timeout; show "ink stirs" indicator.
4. **Parse**: strict JSON parse of `blocks`; on failure → one repair retry; on double failure → magical error state (09 §7).
5. **Reveal**: blocks appended to `AnswerInk`, each animates in via the reveal loop in `InkSurfaceView` (discrete steps); page re-flows; autosave transcript.
6. **Persist**: `NotebookStore.save()` debounced 800 ms after last mutation.

## 5. State management

- **No ViewModel library needed** for v1: single activity, two screens (shelf / notebook). State lives in a plain `NotebookSession` object owned by `MainActivity`, with lifecycle-safe callbacks.
- Notebook JSON is the single source of truth; in-memory `Notebook` object is mutated and saved. No database in v1 (a notebook file is < 1 MB even with hundreds of pages of strokes; SQLite only if profiling demands).
- Orientation: **locked to landscape** (Boox Note Air 5c is used landscape in its native notes app; revisit after device testing — actually, decide on device: support the orientation the user writes in; default lock landscape).

## 6. Error handling

| Failure | UX |
|---|---|
| No network | "The ink cannot reach the spirit world — check the connection." Retry button. |
| 401 / bad key | Settings link, "The quill has no ink (invalid key)". |
| 429 / rate limit | "The scribe is busy — wait a moment." Auto-retry once after 5 s. |
| Timeout (120 s) | Cancel; friendly error; writing preserved. |
| Malformed JSON | One automatic repair retry (prompt: "return ONLY valid JSON"); else error state. |
| Disk full / write failure | Toast + keep in-memory state; next save retried. |
| PDF import failure | Per-page error with "skip page" option; import is transactional (rollback partial). |

All errors: **never lose strokes** — save happens before summon and on every autosave tick.

## 7. Security & privacy

- API key: `EncryptedSharedPreferences` (Keystore-backed AES), never logged, masked in UI.
- Traffic: TLS only (OkHttp defaults; pinning **not** used in v1 — DeepSeek may change endpoints).
- The page image + transcript are sent **only** when the user summons; a small, always-visible "sending page…" indicator is shown (F3.5).
- No analytics, no crash-reporting SDKs, no ads, no permissions beyond `INTERNET` (and storage access via SAF — no broad storage permission).
- Default `android:usesCleartextTraffic="false"`.

## 8. Key decisions (ADRs — condensed)

| # | Decision | Rationale / consequence |
|---|---|---|
| ADR-001 | **Vision-first input** (send page image, no OCR) | The model is a vision model; reads any handwriting; no ML Kit dependency; the model sees layout & doodles. Cost: bigger payload, model-bound latency. |
| ADR-002 | **Standard Android EPD behavior first; Boox SDK only if needed** | Avoids SDK availability/licensing risk on an unverified device; Boox "App Optimization" settings cover most refresh needs. Revisit after M1 device tests. |
| ADR-003 | **Classic Views, not Compose** | Full control of draw order, dirty-rect invalidation, and refresh batching; Compose's animation/layout pipeline fights e-ink. Trade-off: more boilerplate UI. |
| ADR-004 | **Framework PdfRenderer with PdfBox-Android fallback, not MuPDF/PDF.js** | Zero cost, no AGPL; renders at fixed DPI which fits our background-image model. Amended (audit P4-6): Android 15's Skia-based `PdfRenderer` renders every page solid black on the Boox Note Air 5c (and API 34/35 emulators) — verified on-device — so the `pdf/PdfPageRenderer` seam renders framework-first, detects > 85 % dark output, and falls back to the bundled PdfBox-Android 2.0.27.0 (pure Java; ~5 MB dependency). PdfBox is the engine that actually works on the target device. Trade-off: no interactive re-render at arbitrary zoom (v2 may add MuPDF). |
| ADR-005 | **JSON notebook files, not SQLite** | Notebooks are document-shaped; atomic rename-write is enough; humans can inspect/backup files. Trade-off: full-text search would need work later. |
| ADR-006 | **Structured JSON `blocks` from the model, not raw markdown** | Robust rendering of drawings/tables; one parser; repair-retry path. Trade-off: model must follow the schema (mitigated by prompt + validation + repair). |
| ADR-007 | **Cancel-on-resume auto-summon** (user decision) | The summon is *conditional*: fires on 3 s idle, cancels when the user writes again. Prevents the notebook answering mid-thought; quill button covers explicit summons. |
| ADR-008 | **English default, Latin cursive fonts** (user decision) | Dancing Script / Great Vibes / Kalam. RTL (Arabic) noted as future work in 08 §8. |
