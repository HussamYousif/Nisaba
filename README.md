# Nisaba

> *"The notebook knows your hand."*

Nisaba is an Android app for e-ink tablets — built for the **Boox Note Air 5c** — that turns a stylus notebook into a living, magical tome. You write by hand, pause, and the ink answers: text, tables, and drawings flow back onto the page beneath your writing, rendered like a scribe's cursive script. Like the books in Harry Potter — without the moving portraits.

The answers come from **DeepSeek** (vision model: `deepseek-v4-flash-vision-exp`), which reads your handwriting directly from the page image.

---

## Status

| Phase | State |
|---|---|
| Design docs (`docs/`) | ✅ Complete (+ architecture conventions & review) |
| M1 – Drawing skeleton (APK + ink engine + persistence) | ✅ Code complete, tests green; on-device probe on the Boox pending |
| M2 – PDF import ("Open a Tome") | ✅ Code complete, tests green; PDF pixel-render env-gated (docs/06) |
| M3 – The summon (core loop) | ✅ Code complete, live API verified (2026-08-31); on-device pass on the Boox pending (docs/11) |
| M4 – Magic presentation | ✅ Code complete (SVG-subset ink renderer, self-inking drawings, ruled tables, tap-to-skip reveal); device pass pending — parchment textures + reveal tuning + N11 legibility photo test remain (docs/11) |
| M5 – Conversation polish (regenerate, smudge-away, copy, export, rename, onboarding, settings extras) | ✅ Code complete, tests green; on-device pass pending |
| Signed release APK | ✅ M6: release keystore (gitignored), R8 minification, release APK verified on emulator |

## Target device

- **Boox Note Air 5c** — 10.3" Kaleido 3 color e-ink, 2480×1860 (300 ppi B/W, 150 ppi color), Wacom EMR stylus, Android 13+.
- See `docs/04-eink-input.md` for the full hardware strategy.

## The core loop

1. **Start** — open a *blank parchment* or *import a PDF tome*.
2. **Write** — stylus strokes with pressure, like a real notebook.
3. **Pause** — stop writing for ~3 seconds and the app "notices".
4. **Summon** — the page is sent (as an image) to DeepSeek; if you keep writing, the request is cancelled.
5. **Answer** — cursive text, ruled tables, or inked drawings appear beneath your page, drawing themselves in.
6. **Repeat** — the notebook remembers everything.

## Design docs index

> **Architecture conventions** (`docs/architecture.md` + `AGENTS.md`): the rules
> that bind every change — low coupling, additive-not-disruptive, leaf files,
> layer boundaries, monorepo layout. Read these before writing code.

| Doc | Contents |
|---|---|
| [Architecture Conventions](docs/architecture.md) | **The rules**: philosophy, monorepo layout, layer tables, error handling, additive checklist, audit |
| [01 — Vision](docs/01-vision.md) | The product idea, the "living notebook" metaphor, design principles |
| [02 — Requirements](docs/02-requirements.md) | Scenarios, functional & non-functional requirements, constraints |
| [03 — Architecture](docs/03-architecture.md) | Stack, modules, layers, threading, decisions (ADRs) |
| [04 — E-ink & Input](docs/04-eink-input.md) | Boox hardware specifics, refresh strategy, stylus pipeline, palm rejection |
| [05 — Data Model](docs/05-data-model.md) | Notebook/page/stroke schema, storage, atomicity, export |
| [06 — PDF Import](docs/06-pdf-import.md) | Rendering PDFs as page backgrounds, memory strategy |
| [07 — LLM Integration](docs/07-llm-integration.md) | DeepSeek API, vision input, prompt design, response contract |
| [08 — Response Rendering](docs/08-response-rendering.md) | Text/tables/drawings, cursive fonts, ink animation |
| [09 — Magic UX](docs/09-magic-ux.md) | Visual language, motion on e-ink, summon flow, waiting states |
| [10 — Build & Deploy](docs/10-build-deploy.md) | Gradle, signing, installing on the Boox, device settings |
| [11 — Roadmap](docs/11-roadmap.md) | Milestones, acceptance criteria, risks, backlog |

## Tech snapshot (v1)

- **Language / UI**: Kotlin, single-activity, classic Android Views with a custom `InkSurfaceView` (deliberately *not* Compose — see ADR-003).
- **Storage**: JSON notebook files with atomic writes; strokes as vector points with pressure.
- **LLM**: OpenAI-compatible `/chat/completions` → `https://api.deepseek.com`, model `deepseek-v4-flash-vision-exp` (configurable), vision input as a page image, structured JSON output.
- **PDF**: import backgrounds via the `PdfPageRenderer` seam — Android 15's framework `PdfRenderer` renders black on the Boox, so PdfBox-Android is the working engine there (ADR-004).
- **Fonts**: bundled OFL fonts — Dancing Script (body), Great Vibes (headings), Kalam (small labels).
- **minSdk** 26, **targetSdk** 35.

## Repository layout

```
nisaba/
├── README.md
├── docs/                  # design docs (this phase)
├── app/                   # Android application module
│   ├── src/main/java/com/nisaba/
│   │   ├── MainActivity.kt / NisabaApp.kt
│   │   ├── ink/           # stylus input, stroke model, page-space math, ink rendering
│   │   ├── notebook/      # data model + storage + summon detection
│   │   │   └── model/     # kotlinx-serialization DTOs
│   │   ├── pdf/           # PDF import & page-background rendering
│   │   ├── llm/           # DeepSeek client, image prep, prompt builder, settings
│   │   ├── render/        # answers → page-space ink layout, SVG, thumbnails
│   │   ├── magic/         # parchment theme, bundled fonts, on-page summon indicator
│   │   ├── epd/           # e-ink control (Boox/Onyx)
│   │   └── ui/            # screens: shelf, notebook, settings
│   └── src/main/assets/fonts/
└── gradle/
```

## License

Private project. Fonts are SIL Open Font License (redistribution OK).

## Development secrets (`.env`)

Copy `.env.example` → `.env` and set your key:

```
DEEPSEEK_API_KEY=sk-your-key-here
```

- Injected into **debug builds only** (`BuildConfig.DEEPSEEK_API_KEY`); the
  app uses it automatically when no key is entered in Settings — nothing to
  type on the Boox. A key you enter in Settings always wins.
- `.env` is gitignored; release builds never embed it.
- The Settings screen shows *"Dev key from .env is active"* when it's in use.
- ⚠️ The debug APK's `BuildConfig` string is recoverable (`strings app-debug.apk
  | grep sk-`) because minification is off — treat any distributed debug APK
  as key-exposing (audit P2-1). Release builds are clean, and release refuses
  non-`https` base URLs outright.
