# Architecture Conventions — Nisaba monorepo

Status: **Ratified** · Applies to all code written in this repository · Companion to `docs/03-architecture.md` (system design) — this document is the *rules of structure*, that one is the *shape of the system*.

---

## 1. Design philosophy: low coupling

**Coupling is the biggest evil — on both frontend and backend.** Every feature is as self-contained as possible: a new feature lives in its own folder with its own files, so it can be understood, changed, and deleted in isolation. Prefer this modularity over DRY — code that is easy to delete beats code that is cleverly shared. Reach across module boundaries only through a module's public surface, never into its internals.

**Additive, not disruptive.** Implementing a new feature must not change the behaviour of existing unrelated features. New functionality is added alongside what's there — it does not modify shared code, state, or APIs in ways that could break code paths outside the feature being built. If a change to shared code is unavoidable, it must be backwards-compatible.

**A leaf** is a file at the edge of a module: it is called by the module but calls nothing else inside it (no back-references into the module's other layers). Leaves keep dependencies acyclic.

---

## 2. Monorepo layout

```
nisaba/
├── AGENTS.md               # harness settings — must-follow rules for every change
├── docs/                   # design docs (this is one of them)
├── app/                    # FRONTEND — the Android client (the shipped product)
│   └── src/main/java/com/nisaba/<domain>/…
└── server/                 # BACKEND — intentionally ABSENT today (offline-first design)
                            # Reserved for: LLM key-proxy, notebook sync/backup,
                            # export service. When it lands it follows §4 below.
```

- The frontend is the product; it must remain fully functional with **no backend present** (offline-first). A backend may only ever be *additive*: the client degrades gracefully when it is absent.
- **No shared runtime code across the frontend/backend boundary.** The wire contract between them is defined in prose (a `docs/api-contract.md`, when one exists) and implemented *independently on each side behind its own facade* (§3/§4 `facade` layer). Shared code is the first step to shared breakage.
- Toolchain (`tools/`-style local installs, build caches) stays gitignored and outside both trees.

---

## 3. Frontend module structure (Android)

### 3.1 Layer roles

Each feature (`<domain>`) is a package under `com.nisaba.<domain>/`. The layers mirror the backend table — Android's "api" is the UI, the "db" is persistence:

```
ui ──────────────► core ──────────────► model (leaf)
    \                │
     \               ├─► facade (leaf)   wire ↔ model mapping
      \              └─► data ──► data-files (leaf, raw storage)
       \► client (leaf, external services)
```

| File / folder | Backend analog | Responsibility | Leaf? |
|---|---|---|---|
| `ui/` | `api.clj` | Android-only concerns: view inflation, touch/input events, view formatting, user-facing strings. The **only** layer allowed to touch `android.view`/`android.widget` | |
| *(public surface)* | `interface.clj` | The domain's public entry points and listener interfaces (e.g. `NotebookScreen.bind(...)`, `SummonController.Listener`, `NotebookStore` methods). Other modules call **only** these | |
| `core/` | `core.clj` | Business logic only: no `android.view`, no storage, no network. Throws typed domain exceptions on illegal operations | |
| `data/` | `db.clj` | Persistence & settings interface from code (owns files, prefs); no business rules | |
| `data/` leaf files | `db.sql` | Raw storage: atomic JSON writes, `SharedPreferences` | leaf |
| `client/` | `client.clj` | Calls to external services and framework facilities (DeepSeek HTTP, `PdfRenderer`); maps transport failures to typed exceptions | leaf |
| `facade/` | `facade.clj` | Wire ↔ model mapping (chat contract, page-image prep, prompt assembly) | leaf |
| `model/` | — | `@Serializable` data types of the domain | leaf |

**Rules that follow:**
- `core` never imports `ui`, `data`, or `client`; it may import `model` and `facade`.
- `data` never imports `core` or `ui`; it may import `model` and other domains' **public surfaces only** — never their internals.
- `client`/`facade` are leaves of their own domain: they may import `model` and other domains' public surfaces, nothing internal.
- `ui` may import anything **through public surfaces** — it is the composition point, but it composes, it does not reach in.
- Cross-domain calls are allowed only as `com.nisaba.<other>.<public>` — e.g. `com.nisaba.ink.InkPageRenderer.render(...)`. If a call needs more than the public surface, the public surface is missing: add a leaf to the owning domain instead of reaching in.
- The composition root (`MainActivity`) wires modules together; it contains no business logic.

### 3.2 Existing domains (as of the audit)

| Domain | Owns | Public surface |
|---|---|---|
| `ink` | stylus input, stroke model mapping, page-space math, ink rendering | `InkSurfaceView` (input/display), `InkPageRenderer` (bitmap rendering leaf), `Eraser`, `PageTransform` |
| `notebook` | notebook model, persistence, summon detection | `NotebookStore`, `SummonController`, `model.*` |
| `llm` | DeepSeek client, prompt building, answer parsing, settings | `DeepSeekClient`, `AnswerParser`, `AppSettings`, `ChatContract` |
| `pdf` | PDF import + background cache | `PdfImporter`, `PageBackgroundCache` |
| `render` | answer-block rendering (text now; drawing/table in M4) | `AnswerSectionRenderer`, `MarkdownInk` |
| `magic` | parchment theme + bundled fonts (shared leaves) | `MagicTheme`, `Fonts` |
| `ui` | shell screens (shelf, settings) + notebook screen | — (composition layer) |

---

## 4. Backend module structure (server/ — template for when it exists)

Each feature is a self-contained module folder under `server/src/<domain>/`:

```
api -> interface -> core -> db (db code layer) -> db.sql        (DB path)
                         \-> client        (external-service path)
                         \-> facade
```

| File | Responsibility | Leaf? |
|------|----------------|-------|
| `api.clj` | Handler logic — HTTP concerns: auth, param unpacking, response formatting | |
| `interface.clj` | Public boundary the `api` calls — documented passthrough fns | |
| `core.clj` | Business logic only (no HTTP/mapping); throws on illegal ops | |
| `db.clj` | DB interface from code — calls `db.sql` | |
| `db.sql` | Raw DB calls | leaf |
| `client.clj` | Calls to external services | leaf |
| `facade.clj` | Model mapping (our models ↔ external models) when needed | leaf |

The same rules as §3.1 apply by analogy: `core` is pure, `api` is the only HTTP layer, `client`/`facade`/`db.sql` are leaves, everything crosses boundaries through `interface`.

---

## 5. Error handling

- **Core throws typed domain exceptions** on illegal operations (e.g. `AnswerParser.ParseException`, `PdfImporter.ImportCancelled`). Core never catches-and-hides.
- **Clients map transport failures to typed exceptions** (`DeepSeekClient.ApiException(code)`, `NetworkException`, timeouts) — raw HTTP/IO never escapes a client.
- **The UI layer maps exceptions to user-facing copy** (strings.xml: "The ink smudged — the spirit world was unreachable."). Business state is recorded in the domain model (`AnswerGroup.status` = pending/complete/cancelled/error) — failures are data, not toasts.
- **Cancellation is sacred:** `CancellationException` always propagates; a cancelled request never leaves a partial side effect (see `SummonController` cancel-on-resume + generation token).
- **Never log** API keys, prompts, or page images. Secrets live in Keystore-backed storage (`AppSettings`).
- **Failures are transactional where state is involved:** `PdfImporter` rolls back the half-created notebook directory; autosave is atomic (tmp + rename + fsync).

## 6. Testing

- Tests mirror the module: `app/src/test/...` (JVM) and `app/src/androidTest/...` (instrumented) use the same `com.nisaba.<domain>` packages as the code under test.
- **Test through public surfaces only** — never reach into a module's internals from a test of another module.
- Environment-gated tests are explicit: `PdfTestSupport.assumeRenders` skips pixel assertions where the sandbox cannot render PDFs; the skipped cases are documented and hard-assert on real hardware.
- External services are mocked in-process (`MockWebServer`) — no shared test doubles across domains unless they are leaves of their own domain.
- The full suite must stay green after every feature (additive rule, verified mechanically).

## 7. Definition of done — the additive checklist

A feature is done when **all** hold:

- [ ] New behaviour lives in its own folder/files under a domain (or a new domain); nothing was bolted onto an existing class.
- [ ] Zero changes to existing unrelated files; if a shared file had to change, the change is backwards-compatible and every pre-existing test passes **unchanged**.
- [ ] No new cross-module imports except through public surfaces; no new back-references into a module's internals; dependency graph stays acyclic.
- [ ] Errors are typed at the boundary and mapped to copy at the UI; no raw exceptions leak; cancellation propagates.
- [ ] New tests cover the feature through its public surface; full suite green; `lint` clean.
- [ ] `docs/` updated where behaviour diverged from the design.

## 8. Current-state audit & debt register

Audited against §3 (commit `e3433c8`). The codebase was written feature-first, so most of it already complies:

**Compliant:** ink core split from the view (`StrokeBuilder`, `Eraser`, `PageTransform` pure; `InkSurfaceView` = ui) · `PdfImporter`/`PageBackgroundCache` self-contained and transactional · `DeepSeekClient` leaf with typed errors · `PromptBuilder`/`AnswerParser` pure · `magic/` and `render/` leaves · per-domain tests · `MainActivity` = composition root · typed error hierarchy (`ApiException`, `NetworkException`, `ParseException`, `ImportCancelled`).

**Debt (fixed or to fix additively, one item per future milestone):**

| # | Violation | Fix (additive) | Status |
|---|---|---|---|
| D2 | `notebook/NotebookStore` (data layer) calls `ink.StrokeRenderer` directly — data reaching into another module's core | New `ink/InkPageRenderer.kt` leaf: the *single* public rendering entry (strokes + optional background → bitmap). `NotebookStore` thumbnails call only it | ✅ fixed in this commit |
| D3 | `llm/PageImagePreparer` (facade) also calls `ink.StrokeRenderer` directly | Same `InkPageRenderer` leaf; `PageImagePreparer` calls the public surface | ✅ fixed in this commit |
| D1 | `ui/NotebookScreen` is a god-object: input, undo, pages, autosave, summon orchestration and answer rendering in one class (imports 6 modules) | Extract `core/SummonFlow` (summon orchestration + state) and keep the screen as wiring; do it when M4/M5 next touches notebook/llm | ⏳ M4/M5 |
| D4 | `llm/AnswerParser` mixes validation (core) with placeholder mapping (facade) | Split only if it grows; both halves are small today | ⏳ when needed |

**Migration path:** files are moved between layer folders **only when the feature is already being touched** — never as a standalone mass rename. The rules above bind from today; the physical layout catches up feature by feature.

---

## 9. Where this lives

- `AGENTS.md` (repo root) — the one-page must-follow summary loaded by the harness on every session.
- This document — the full ruleset; extend it when a rule is missing, never when a rule is inconvenient.
- `docs/03-architecture.md` — system design (stack, modules, threading) and the original ADRs.
