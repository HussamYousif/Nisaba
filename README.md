# Nisaba

> *"The notebook knows your hand."*

Nisaba is an Android app for e-ink tablets — built for the **Boox Note Air 5c** — that turns a stylus notebook into a living, magical tome. You write by hand, pause, and the ink answers: text, tables, and drawings flow back onto the page beneath your writing, rendered like a scribe's cursive script. Like the books in Harry Potter — without the moving portraits.

The answers come from **DeepSeek** (vision model: `deepseek-v4-flash-vision-exp`), which reads your handwriting directly from the page image.

---

## Status

| Phase | State |
|---|---|
| Design docs (`docs/`) | ✅ Complete |
| M1 – Drawing skeleton (APK + ink engine + persistence) | ✅ Code complete, tests green; on-device probe on the Boox pending |
| M2 – PDF import ("Open a Tome") | ✅ Code complete, tests green; PDF pixel-render env-gated (docs/06) |
| M3 – The summon (core loop) | ✅ Code complete: idle detection + cancel-on-resume, DeepSeek client, image prep, answer parsing, cursive text rendering, Settings; 28/28 tests green + full UI loop verified against a mock server |
| M4 – Magic presentation (drawing/table renderers, ink reveal) | ⏳ Next |
| M5 – Conversation polish (regenerate, smudge, export, onboarding) | ⏳ Planned |
| Signed release APK | ⏳ Planned |

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

| Doc | Contents |
|---|---|
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
- **PDF**: Android `PdfRenderer` for import backgrounds.
- **Fonts**: bundled OFL fonts — Dancing Script (body), Great Vibes (headings), Kalam (small labels).
- **minSdk** 26, **targetSdk** 35.

## Repository layout (planned)

```
nisaba/
├── README.md
├── docs/                  # design docs (this phase)
├── app/                   # Android application module
│   ├── src/main/java/com/nisaba/
│   │   ├── MainActivity.kt
│   │   ├── ink/           # stroke capture, InkSurfaceView, rendering
│   │   ├── notebook/      # data model, storage, notebook list
│   │   ├── pdf/           # import & rendering
│   │   ├── llm/           # DeepSeek client, image prep, prompt builder
│   │   ├── render/        # answer blocks → ink rendering
│   │   ├── magic/         # summon detection, animations, theming
│   │   └── settings/      # API key, model, preferences
│   └── src/main/assets/fonts/
├── keystore/              # signing (gitignored)
└── gradle/
```

## License

Private project. Fonts are SIL Open Font License (redistribution OK).
