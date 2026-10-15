# Nisaba — Implementation Plan

Companion to `DESIGN.md` (the *what* and *why*). This file is the *how, in what order, and how we know it works*.
§ references like "D§4.4" point into DESIGN.md.

Status: **v1, 2026-09-29**

---

## How to read this

- Work is split into **milestones (M0–M7)**, each into **tasks (`M1.4`)**. Tasks are ordered. A task depends on the ones above it unless it's marked ∥ (parallelisable).
- Each task lists **Files** (created or changed), **Details** (the non-obvious parts), **Tests**, and **Done when**.
- Size: **S** ≤ ½ day · **M** ≈ 1 day · **L** 2–3 days.
- Every task ends at a **checkpoint**: build green, tests green, `verifyFileRoles` green, and a short entry in `docs/log.md`. Commits happen only when you ask.
- "On device" = Boox Note Air 5C over USB (`adb`). "Emulator" = the Pixel AVD in `.tools/avd` (it runs the `MotionEventPenDevice`/`NoopEinkDevice` fallbacks).

Critical path: **M0 spikes → M1 ink → M2 ask loop → M3 the hand → M4 rich blocks → M6 polish → M7 store**. **M5 (PDF)** can run ∥ with M3/M4 once M2 is done.

---

## M0 — Foundation & device spikes

Goal: a compiling multi-module skeleton with conventions enforced, plus measured answers to the six device questions (D§11) before we commit to the input/render design.

### M0.1 Toolchain & environment · S
**Files:** `scripts/env.sh`, `scripts/device.sh`, `.env.example`, `.gitignore`
**Details**
- `env.sh` exports `ANDROID_HOME=$PWD/.tools/android-sdk`, `GRADLE_USER_HOME=$PWD/.tools/gradle-home`, `JAVA_HOME` (JDK 17), and puts `$ANDROID_HOME/platform-tools` on `PATH`. Every command in this plan assumes `source scripts/env.sh`.
- `device.sh` subcommands: `install` (assemble + `adb install -r`), `log` (`adb logcat -s 'Nisaba*'`), `shot` (`adb exec-out screencap -p > captures/<ts>.png`), `props` (`getprop ro.build.version.release`, `wm size`, `wm density`, firmware `ro.build.display.id`).
- `.env.example` has `DEEPSEEK_API_KEY=` (debug builds only, D14). `.gitignore`: keep the existing one, add `captures/`.
**Done when:** `source scripts/env.sh && adb devices` lists the Boox; `scripts/device.sh props` prints Android 15 and 1860×2480 (record these in `docs/spikes.md`).

### M0.2 Gradle skeleton · M
**Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradle.properties`, `{domain,scribe,infra,app,tools}/build.gradle.kts`
**Details**
- Regenerate the wrapper: `.tools/gradle-8.13/bin/gradle wrapper --gradle-version 8.13`.
- Versions: start from the known-good baseline on this machine (**AGP 8.7.3 · Kotlin 2.1.10 · Gradle 8.13 · compileSdk 35 · minSdk 30 · JDK 17**). Bump to the newest stable that still builds with SDK 35 installed. `targetSdk` moves to 36 in M7 (Play policy).
- Modules:
  - `:domain`, `:scribe`, `:tools`: `kotlin("jvm")` + serialization plugin. **No Android on the classpath, so the dependency rule is enforced by the compiler.**
  - `:infra`: `com.android.library` + KSP (Room compiler).
  - `:app`: `com.android.application` + Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`).
- Repositories (`settings.gradle.kts`):
  - `google()` and `mavenCentral()`
  - the Onyx repo, filtered by content so it can't serve anything else:
    ```kotlin
    maven("https://repo.boox.com/repository/maven-public/") {  // try https first; fall back to http + isAllowInsecureProtocol only if needed
        content { includeGroupByRegex("com\\.onyx.*") }
    }
    ```
- Catalog entries:
  - Compose BOM (ui, foundation, material3, ui-tooling-preview)
  - activity-compose, lifecycle-{runtime,viewmodel}-compose, navigation-compose
  - core-ktx, core-splashscreen, datastore-preferences
  - room-{runtime,ktx,compiler}, ksp
  - kotlinx-coroutines-{core,android,test}, kotlinx-serialization-{json,protobuf}
  - okhttp, okhttp-mockwebserver, okhttp-sse (optional; we hand-roll SSE in M2.6)
  - pdfbox-android, onyxsdk-pen (+ whatever S1 finds), hiddenapibypass
  - junit4, kotlin-test, turbine, truth
  - detekt (+ formatting)
- `gradle.properties`: `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, `kotlin.code.style=official`, `android.nonTransitiveRClass=true`.
**Tests:** a placeholder test per JVM module (`SharedRulesTest`).
**Done when:** `./gradlew assembleDebug test` is green on the laptop.

### M0.3 Convention enforcement · S
**Files:** `build-logic/` (included build) with `NisabaConventionsPlugin.kt` and `VerifyFileRolesTask.kt`, `config/detekt.yml`
**Details**
- `verifyFileRoles`:
  - Walks each module's `src/main/kotlin` and `src/main/java`.
  - Fails if a file name doesn't end in an allowed suffix (D§3.3: Model, Rules, Ports, UseCase, Session, Parser, Client, Store, Dto, Mapper, Renderer, ViewModel, UiState, Screen, Component, View, Device, Registry, Animator, Cache, Database).
  - Exempts only the entry points (`NisabaApp`, `MainActivity`, `AppGraph`, `Navigation` in `com.nisaba.app`; `ToolsMain` in `:tools`).
  - Also fails if a `*Rules.kt` file declares a `class` holding a `var` (rules are pure functions and immutable data).
  - Test file names are checked as `<Subject>Test.kt`.
- detekt: default rules + `LargeClass`/`LongMethod` thresholds, `ForbiddenImport` for `android.util.Log` outside `infra/log` and `app` (logging goes through a `Logger` port).
- Hook `verifyFileRoles` and `detekt` into `check`.
**Done when:** adding a file `Foo.kt` breaks `./gradlew check`, and renaming it `FooRules.kt` fixes it.

### M0.4 CI · S ∥
**Files:** `.github/workflows/ci.yml`
**Details:** JDK 17 + setup-android; `./gradlew check assembleDebug --no-daemon`; upload test reports on failure. No instrumented tests in CI (they need the device).
**Done when:** the workflow is green on a push.

### M0.5 Agent/contributor guide · S ∥
**Files:** `CLAUDE.md`
**Details:** a short orientation: read DESIGN.md + PLAN.md; the file-role table (copy of D§3.3); the dependency rule; commands (`source scripts/env.sh`, `scripts/device.sh install|log|shot`); the definition of done; "never commit unless asked".

### M0.6 Spike harness · M
**Files:** `app/src/debug/kotlin/com/nisaba/spike/SpikeActivity.kt` + one screen per spike (debug source set only, so it never ships; exempt from `verifyFileRoles` via a `spike` package allowlist that is deleted at the end of M0)
**Details:** a launcher listing S1–S6. Results are recorded in `docs/spikes.md`, one table per spike: environment, what was tried, measurements, **decision**.

### M0.7 Spike S1 — Onyx pen SDK · M
**Details**
- Add `onyxsdk-pen` (start with the newest listed; the search found 1.5.4.3 reported working on an Air 5C with Android 15). Resolve its transitive deps; exclude what conflicts (old support libs, RxJava 2 if unused). Likely also needed: `HiddenApiBypass.addHiddenApiExemptions("")` in `Application.onCreate` (Android 11+). Confirm.
- Screen: full-screen `SurfaceView`, `TouchHelper.create(view, callback)`, `setLimitRect`, `openRawDrawing()`, `setRawDrawingEnabled(true)`, `setStrokeStyle(STROKE_STYLE_FOUNTAIN)`, `setStrokeWidth(3f)`.
- Log every callback with timestamps: `onBeginRawDrawing`, `onRawDrawingTouchPointMoveReceived`, `onRawDrawingTouchPointListReceived`, `onEndRawDrawing`, and the eraser chain. Record points/stroke, pressure range (`EpdController.getMaxTouchPressure()` or the TouchPoint max), and the timestamp units.
- Test the side button and the eraser end. Check that finger touches do **not** draw.
- Latency: film the screen with a phone at 240 fps while drawing a fast line. Count frames from nib to ink.
**Decision to record:** the SDK version + dependency set, pressure normalisation, and whether side-button erase arrives via the eraser chain.

### M0.8 Spike S2 + S6 — our drawing vs the raw layer · L (biggest risk)
**Details.** Try in order and stop at the first that passes (D§11, S2 criteria: the hand writes at ≥ 15 fps, pen-down detected ≤ 50 ms):
1. **Hybrid toggle:**
   - Before animating: `setRawDrawingEnabled(false)`.
   - Animate by drawing into the view's bitmap + `invalidate(dirty)` at 20 fps.
   - Detect stylus `ACTION_DOWN` in `onTouchEvent` (tool type STYLUS), then **interrupt**: re-enable raw drawing, and render the rest of that first stroke ourselves from MotionEvents.
2. **Render-off only:** keep raw drawing enabled but `setRawDrawingRenderEnabled(false)` while animating. Does `invalidate()` refresh, and do raw callbacks still fire (that's how we'd get pen-down)?
3. **Overlay window:** animate in a separate `SurfaceView` layered above, with raw drawing enabled on the page view.

S6, handover: draw a stroke with the raw layer, commit it to our bitmap with our renderer, toggle raw drawing off/on to reveal our layer, and photograph both. Tune our pressure→width curve and smoothing until there's no visible shift. Record the constants (`INK_WIDTH_BASE`, `INK_WIDTH_K`, `INK_PRESSURE_EXP`).
**Decision to record:** the chosen strategy (1/2/3), the measured fps and interrupt latency, the toggle cost (ms + flicker yes/no), and the handover constants.

### M0.9 Spike S3 — refresh modes · M
**Details**
- Reflect `oec_service` → `IOECService$Stub.asInterface` → `setAppScopeRefreshMode(int, boolean)` (the prior code used 0 = normal, 2 = fast). Sweep mode values 0–6 while animating a moving bar. Record ghosting and speed per mode.
- Find a **clean pass** lever, in this order: (a) `onyxsdk-device` `EpdController.invalidate(view, UpdateMode.GC)` if the class loads; (b) a mode switch to normal + a full `invalidate()`; (c) toggling raw drawing (which forces a refresh).
**Decision to record:** the mode constants for FAST / NORMAL, the clean-pass method, and the cost in ms.

### M0.10 Spike S5 — ink colour on Kaleido · S ∥
**Details:** a swatch page with the hand's sample text at widths 2/3/4/5 in `#27407A`, `#1F3A5F`, `#2B2B6B`, `#3A2E25` and black, plus a lapis nib and drop. Judge with the frontlight off and on at 40 cm.
**Decision to record:** the answer-ink colour, minimum width and fallback.

### M0.11 Spike S4 — DeepSeek reads handwriting · M ∥
**Files:** `tools/src/main/kotlin/com/nisaba/tools/VisionProbeClient.kt` (temporary)
**Details**
- In the S1 screen, write 10 sample pages: a question, a formula, a messy scrawl, Norwegian text with æøå, a list, an arrow to a word, a crossed-out word, and pure notes (which should come back `<silent/>`). Export each as a JPEG at 1600 px + a focus crop (`adb pull`).
- The probe sends each to `deepseek-flash` with the D§5.3 prompt, `stream:true`, and measures TTFT and total time. Compute word accuracy against a hand transcription.
- Also try `detail: high` vs `auto`, and 1200 vs 1600 px.
**Decision to record:** image size/detail, measured TTFT p50/p90, accuracy, and whether silent detection works (≥ 9/10).

### M0.12 Close M0 · S
Delete the `spike` package and its allowlist entry. Update DESIGN.md where spike results changed a decision. Fill `docs/spikes.md`.
**Done when:** every spike has a recorded decision, and DESIGN.md agrees with them.

---

## M1 — The ink notebook

Goal: a real notebook app with no AI yet. Create notebooks, write on pages with Onyx latency, erase, undo, turn pages; everything persists.

### M1.1 Shared kernel · S
**Files:** `domain/.../shared/SharedModel.kt`, `SharedRules.kt`, `SharedPorts.kt`
**Details**
- `@JvmInline value class` ids: `NotebookId`, `PageId`, `StrokeId`, `ExchangeId`, `DocumentId` (UUID strings).
- `PagePoint(x, y)`, `PageRect(l, t, r, b)` with `union`, `inflate`, `intersects`, `contains`. `PageSize(w, h)` with the constant `PageSize.PORTRAIT = PageSize(1860f, 2480f)`.
- Ports: `Clock { fun now(): Instant }`, `IdGen { fun next(): String }`, `Logger { d/i/w/e }`.
- `Outcome<T>` = `Ok(value) | Err(DomainError)`. `DomainError` is sealed: `NotFound`, `Storage`, `Network`, `Auth`, `Quota`, `Invalid(msg)`.
**Tests:** rect algebra.

### M1.2 Notebook context · M
**Files:** `domain/.../notebook/NotebookModel.kt`, `NotebookRules.kt`, `NotebookPorts.kt`, `CreateNotebookUseCase.kt`, `RenameNotebookUseCase.kt`, `DeleteNotebookUseCase.kt`, `InsertPageUseCase.kt`, `ListNotebooksUseCase.kt`, `ObserveNotebookUseCase.kt`
**Details**
- Model per D§4.1. `Page.position: Double` gives **fractional ordering**: inserting between a and b uses `(a+b)/2`, so no renumbering. `NotebookRules.positionBetween(prev, next)` rebalances only when the gap < 1e-9 (returns a `Rebalance` instruction).
- `NotebookRules.validateName` (trimmed, 1–60 chars, defaults to "Untitled" + a counter), `NotebookRules.coverSeed(name, id)`.
- `NotebookStore` port: `observeAll(): Flow<List<NotebookSummary>>`, `observe(id): Flow<NotebookWithPages?>`, `create`, `rename`, `delete`, `insertPage(notebookId, page)`, `setListening`.
- `DeleteNotebookUseCase` also calls `InkStore.deletePages(ids)` and `DocumentStore.delete(documentId)`. It deletes the DB row first, then files (orphan files are harmless; orphan rows aren't).
**Tests:** positions (insert at head, tail and middle, 60 consecutive midpoint inserts trigger a rebalance), name validation, the delete-use-case ordering (with fakes).

### M1.3 Ink context · L
**Files:** `domain/.../ink/InkModel.kt`, `InkRules.kt`, `InkHistoryRules.kt`, `InkPorts.kt`, `CommitStrokeUseCase.kt`, `EraseUseCase.kt`, `UndoUseCase.kt`, `RedoUseCase.kt`, `LoadPageInkUseCase.kt`
**Details**
- `Stroke` per D§4.1, plus a cached `bounds`. Points are stored as a parallel `FloatArray`/`IntArray` inside an immutable wrapper (`InkPoints`) to avoid boxing. It exposes `size`, `x(i)`, `y(i)`, `p(i)`, `t(i)`.
- `PageInk(pageId, strokes: List<Stroke>, index: StrokeGrid)`. `StrokeGrid` is an immutable uniform grid (cell = 128 units) mapping cell → stroke ids, rebuilt on change (fine at ≤ a few thousand strokes per page).
- `InkRules`:
  - `hitStrokes(ink, path: List<PagePoint>, radius)`: stroke-eraser hits. Candidate cells from the path bounds, then segment-to-segment distance ≤ radius + stroke.width/2.
  - `splitByEraser(stroke, path, radius): List<Stroke>`: area eraser. Walk the points, drop those within the radius of the eraser path, emit runs of ≥ 2 points as new strokes (new ids, same origin).
  - `estimateXHeight(strokes)`: median of stroke bbox heights over strokes with height < 2.5× the overall median (filters loops/ascenders), × 0.55, clamped to [28, 60] (D§1.3). Fixtures cover small and large writers.
  - `utteranceBounds(strokes)`: the union of bounds.
- `InkCommand`: `Add(strokes)`, `Remove(strokes)`, `Replace(removed, added)`. `InkHistoryRules`: `apply(ink, cmd)`, `invert(cmd)`, and `InkHistory(undo: List, redo: List)` with a cap of 200 and `push/undo/redo` returning a new history + the command to apply.
- `InkStore` port: `load(pageId): PageInk`, `save(pageId, strokes)`, `deletePages(ids)`.
- Ownership: the `NotebookViewModel` holds the current `PageInk` + `InkHistory` and calls the pure rules. The use cases only persist (`CommitStrokeUseCase` → `InkStore.save`; the store adapter debounces). Use cases stay thin and stateless.
**Tests:**
- Hit-test (crossing, near-miss, width-aware).
- Split (middle cut → 2 strokes, end cut → 1, full wipe → 0).
- Undo/redo round-trips restore identical ink.
- x-height estimate on 3 fixture sets (captured in M0.11).
- Property test: applying a command then its inverse = identity.

### M1.4 Settings context (minimal) · S
**Files:** `domain/.../settings/SettingsModel.kt`, `SettingsPorts.kt`
**Details**
- `Settings(pauseMillis=3500, handSpeed=Natural, handStyle=Casual, answerInk=Lapis, nib=true, model="deepseek-flash", thinking=false, listeningDefault=true, eraserMode=Stroke)`.
- Ports: `SettingsStore { observe(): Flow<Settings>; update(transform) }` and `SecretStore { getApiKey(): String?; setApiKey(String?) }`.

### M1.5 Persistence adapters · L
**Files:** `infra/.../db/NisabaDatabase.kt`, `NotebookDto.kt`, `NotebookMapper.kt`, `NotebookStore.kt` (class `RoomNotebookStore : domain NotebookStore`), `infra/.../inkfile/InkFileDto.kt`, `InkFileMapper.kt`, `InkFileStore.kt`, `infra/.../settings/SettingsStore.kt`, `SecretStore.kt`
**Details**
- Room v1 schema, exported to `infra/schemas/`:
  - `notebooks(id TEXT PK, name, created_at, updated_at, document_id NULL, listening INT, cover_seed INT)`
  - `pages(id PK, notebook_id FK→notebooks ON DELETE CASCADE, position REAL, width REAL, height REAL, bg_kind INT, bg_document_id NULL, bg_index NULL)`, indexed on `(notebook_id, position)`
  - `documents(id PK, mime, title, file_name, page_count, has_text INT, status INT)`
  - `exchanges(...)`, added in M2.2 as schema **v2** so the migration path gets exercised early
- Ink files:
  - `InkFileDto(version=1, strokes: List<StrokeDto>)` where `StrokeDto(id, origin: Int, exchangeId: String?, color: Int, width: Float, xs: FloatArray, ys: FloatArray, ps: FloatArray, ts: IntArray)`, encoded with kotlinx ProtoBuf (packed arrays).
  - Location `filesDir/ink/<pageId>.ink`. Write: `.tmp` → `fd.sync()` → rename the previous file to `.bak` → rename `.tmp` into place. Read: main, else `.bak`, else empty + a logged warning.
  - Saves are debounced 500 ms per page on an `IO` single-thread dispatcher (serialised per page). `flush()` is called from `onPause`.
- `SecretStore`: an Android Keystore AES-256-GCM key (alias `nisaba.apikey`). Ciphertext + IV live in a small DataStore. In debug builds, if no key is set, it returns `BuildConfig.DEV_DEEPSEEK_API_KEY` (from `.env`, D14). Release has that field `""`.
- `SettingsStore`: preferences DataStore.
**Tests (JVM/Robolectric-free where possible):**
- Mappers (round-trip).
- The ink file format: round-trip, a truncated main file falls back to `.bak`, garbage → empty.
- Room (instrumented, on the emulator): CRUD + cascade delete; the migration test is added in M2.2.

### M1.6 Composition root & navigation · M
**Files:** `app/.../app/NisabaApp.kt`, `AppGraph.kt`, `MainActivity.kt`, `Navigation.kt`
**Details**
- `AppGraph(context)` holds `by lazy` singletons: the database, stores, devices, and use cases. It picks devices with `if (OnyxPenDevice.isAvailable()) OnyxPenDevice(…) else MotionEventPenDevice(…)` (same for e-ink). This is the only device check in the codebase.
- `NisabaApp.onCreate`: `HiddenApiBypass` (if S1 needs it), then create the graph.
- ViewModels are built via `viewModelFactory { initializer { LibraryViewModel(graph.listNotebooks, …) } }` exposed from the graph.
- `MainActivity`: `installSplashScreen()`, immersive (hide system bars; keep the status bar hidden on the notebook screen), `setContent { NisabaTheme { NisabaNavHost(graph) } }`.
- Routes: `library`, `notebook/{id}?page={pageId}`, `settings`.

### M1.7 Theme & brand primitives · M ∥
**Files:** `app/.../ui/theme/NisabaColors.kt`, `NisabaType.kt`, `NisabaTheme.kt`, `app/.../ui/brand/MarkRenderer.kt`, `res/font/*`, `res/drawable/ic_mark.xml`, `mipmap-anydpi-v26/ic_launcher.xml`
**Details**
- Colour tokens from D§1.7, in a `NisabaColors` data class provided via `CompositionLocal`. There's no dark theme; e-ink is the theme. Fonts: EB Garamond + Inter (OFL, bundled).
- The mark (D§1.7) as a hand-authored path set in `MarkRenderer` (Canvas) + an identical `VectorDrawable` for the launcher/splash. Geometry: 8 wedges at 45° steps; each is an isoceles triangle (length 1.0, base 0.28) from the centre outward; the south wedge is extended 1.35× and ends in a split nib; a teardrop 0.12 below it.
- Components in `ui/theme/NisabaComponent.kt` (`*Component.kt` = a reusable stateless Composable that isn't a whole screen, D§3.3): `NisabaButton` (outlined, ink border, no ripple, `indication = null`), `NisabaTextField`, `NisabaDialog`.
- Global: `LocalIndication` = none (ripples ghost on e-ink), no animated transitions (`EnterTransition.None`).

### M1.8 Library screen · M
**Files:** `app/.../library/LibraryScreen.kt`, `LibraryViewModel.kt`, `LibraryUiState.kt`, `CoverRenderer.kt`
**Details**
- `LibraryUiState(notebooks: List<CoverItem>, dialog: None|Create|Rename(id)|ConfirmDelete(id))`.
- Grid: 3 columns portrait, tome aspect 3:4. For now `CoverRenderer` draws a seeded wedge rosette (5–9 wedges) + the name in EB Garamond; M6.3 swaps the name to the hand. Covers are cached as `ImageBitmap` keyed by `(id, name)`.
- Actions: "New notebook" (dialog with the name, keyboard OK here), long-press → rename/delete. "New from PDF" arrives in M5.
- No list animations. Tap → navigate.
**Done when:** create/rename/delete works and survives a restart (emulator + device).

### M1.9 Page view: rendering · L
**Files:** `app/.../canvas/PageView.kt`, `PageLayerCache.kt`, `infra/.../render/InkRenderer.kt`, `infra/.../render/BackgroundRenderer.kt`, `domain/.../shared/SharedRules.kt` (+ `fitTransform`)
**Details**
- The renderers live in `:infra/render/` (not `:app`) because the off-screen page snapshot for the model (M2.5) reuses them, and `:app` depends on `:infra`.
- Page↔view transform is pure math: `SharedRules.fitTransform(page, viewW, viewH): Affine(scale, dx, dy)` + `invert`.
- `PageView : View`. It owns a `PageLayerCache`: a `background: Bitmap` (paper colour or PDF raster, M5), an `ink: Bitmap` (committed strokes), and a `Matrix` for page→view.
- `InkRenderer.drawStroke(canvas, stroke, paint)`: variable-width outline.
  - Smooth the points with centripetal Catmull-Rom (resample ~2 units).
  - Width `w(p) = base + k·p^exp` (constants from S6).
  - Build left/right offset polylines, add round caps, fill as one `Path`.
  - Cache outline `Path`s in an `LruCache<StrokeId, Path>` (sized by point count) for fast full rebuilds.
- Incremental: `onStrokeCommitted` draws just that stroke into `ink`, then `invalidate(dirtyRect)`. Erase/undo → full ink-layer rebuild on `Dispatchers.Default` into a spare bitmap, then swap (never on the UI thread for > 4 ms).
- Bitmaps are `ARGB_8888` at page size (≈ 18 MB each). Reuse them across page turns (`eraseColor`) and don't reallocate.
- `onDraw`: `drawBitmap(background)`, `drawBitmap(ink)`, then the overlay hook (M2/M3).
**Tests:** `fitTransform` (JVM). A manual device check: 200 strokes, open/close the page, timing of the full rebuild ≤ 120 ms (log it).

### M1.10 Pen devices · L
**Files:** `app/.../device/PenDevice.kt`, `OnyxPenDevice.kt`, `MotionEventPenDevice.kt`, `EinkDevice.kt`, `OnyxEinkDevice.kt`, `NoopEinkDevice.kt`
**Details**
- ```kotlin
  interface PenDevice {
      val events: SharedFlow<PenEvent>          // PenDown(pt), StrokeDone(points), EraseDone(path), PenHover?
      fun attach(view: View, drawArea: Rect, exclude: List<Rect>)
      fun setExclusions(exclude: List<Rect>)
      fun suspendRawInk()                        // S2 strategy — before the app animates or refreshes
      fun resumeRawInk()
      fun setTool(tool: PenTool)                 // Pen | StrokeEraser | AreaEraser
      fun detach()
  }
  interface EinkDevice { fun fast(); fun normal(); fun cleanPass(view: View, rect: Rect?); fun fullRefresh(view: View) }
  ```
  `PenEvent` coordinates are **view px**. The ViewModel converts them to page units using the inverse transform.
- `OnyxPenDevice` implements the S1/S2 findings exactly. `suspendRawInk/resumeRawInk` are ref-counted (nested suspends from animation + a page turn are safe).
- `MotionEventPenDevice`: stylus-or-mouse `MotionEvent`s. In dev on the emulator it accepts finger input when `BuildConfig.DEBUG && !isBoox`, so it can be tested with a mouse. It draws the live stroke itself via an overlay callback.
- `OnyxEinkDevice` uses the S3 constants; every call is wrapped in `runCatching` + a log, and is deduplicated.
- Lifecycle: attach in `onAttachedToWindow` + after the first layout; detach in `onDetachedFromWindow`; the Activity `onPause` → `detach` (Onyx requires `closeRawDrawing`), `onResume` → re-attach.

### M1.11 Notebook screen & ViewModel · L
**Files:** `app/.../notebook/NotebookScreen.kt`, `NotebookViewModel.kt`, `NotebookUiState.kt`, `ToolbarScreen.kt`
**Details**
- `NotebookUiState(title, pageIndex, pageCount, tool, canUndo, canRedo, listening, toolbarVisible, conversation: ConversationUi)`. `ConversationUi` is added in M2.
- The ViewModel holds `currentPage: StateFlow<PageInk>` and `history: InkHistory`. Flow: the pen device's `StrokeDone` → convert to page units → a `Stroke` (origin User, current colour/width) → `InkHistoryRules.push(Add)` → emit → `CommitStrokeUseCase` (store save, debounced).
- Erase: `EraseDone(path)` → hit or split per the tool → `Remove`/`Replace` command → full layer rebuild → `EinkDevice.cleanPass(rect)`.
- Page turns: preload ink for pages ±1 (`LoadPageInkUseCase`). A turn = `suspendRawInk` → swap the layers → `invalidate` → `fullRefresh` every 5th turn, else `cleanPass` → `resumeRawInk`. The page counter shows in the toolbar. At the last page, swiping ← appends a new blank page (a notebook grows naturally).
- Finger gestures (inside `PageView.onTouchEvent`, TOOL_TYPE_FINGER only):
  - horizontal swipe (|dx| > 120 px, |dy| < |dx|/2): page turn
  - 2-finger tap: undo; 3-finger tap: redo
  - a tap in the top 64 px: show the toolbar
- Toolbar (Compose overlay at the top, 56 dp; its bounds are passed to the pen device as an exclusion rect):
  - ‹ back · title · page n/m · pen · eraser (tap toggles stroke/area) · undo · redo
  - **Listening** · **Ask now**: disabled placeholders until M2
  - auto-hides after 3 s of pen activity: collapse → update the exclusion → `cleanPass` on that strip
- Title rename by tapping the title (dialog).
**Tests:** ViewModel with fake stores/devices (Turbine): stroke → saved; erase → the correct command; undo/redo state flags; a page turn preloads.
**Done when (device):** write across 20 pages, erase (both modes), undo/redo, force-stop (`adb shell am force-stop com.nisaba`), and reopen: identical. Pen latency matches the S1 measurement (no regression from our layers).

### M1.12 M1 hardening · M
- A 1000-stroke page: full rebuild time, memory (`adb shell dumpsys meminfo com.nisaba` < 250 MB), save time.
- Rotation locked to portrait (`screenOrientation="portrait"`) because the Air 5C use is portrait. Landscape is a later concern.
- Crash-free: StrictMode on in debug (disk/network on main = crash).

---

## M2 — The ask loop (text answers, not yet animated)

Goal: write → pause → the inkwell stirs → the answer appears as ink beneath (drawn all at once with a placeholder font-stroke renderer). Every timing and cancellation rule is correct.

### M2.1 Answer context: model + streaming parser · L
**Files:** `domain/.../answer/AnswerModel.kt`, `AnswerParser.kt`
**Details**
- Model:
  ```kotlin
  sealed interface Block { Heading(level, inlines); Paragraph(inlines); ListItem(ordered, index, depth, inlines);
                           MathBlock(latex); Table(header: List<Cell>, rows: List<List<Cell>>); Diagram(svg, labels); Rule }
  sealed interface Inline { Text(s, bold); Math(latex) }
  data class AnswerDocument(val blocks: List<Block>)
  ```
- `AnswerParser` is incremental and stateful (one instance per request): `feed(chunk: String): List<ParseEvent>`, `finish(): List<ParseEvent>`. `ParseEvent = Silent | BlockReady(block) | Heard(text) | Malformed(reason)`.
  - Outer states: `Start → (sees "<silent/>") Silent → Heard`; `Start → (sees "<answer>") InAnswer → (sees "</answer>") AfterAnswer → Heard`. Tags may be split across chunks, so keep a lookahead buffer of ≤ 16 chars.
  - Leniency: if the first 40 non-space chars contain neither tag, assume an implicit `<answer>` (models sometimes forget). A missing `<heard>` gives `heard = ""`.
  - Inner block splitter (line-based, emits only on block completion):
    - blank line: ends a paragraph
    - `#`/`##`: heading
    - `- `, `* `, `1. `: a list item (each line is its own item block; a continuation line with indent ≥ 2 appends)
    - `$$`…`$$`: MathBlock
    - pipe-table rows, with the header separator `|---|` required: the table ends at the first non-pipe line
    - `` ```diagram `` … `` ``` ``: Diagram, where `labels` are the lines after `</svg>` matching `^(.+?)\s*@\s*(\d+)\s*,\s*(\d+)$`
  - The inline parser: `**…**` for bold, `$…$` for math (an unpaired `$` = literal), and strip `*`/`_` italics markers (the hand has one style). Unknown markdown → literal text.
**Tests (the important ones):**
- ~30 fixture responses (from M0.11 real responses + handwritten edge cases). For each, feed it **whole**, **per char**, and in **1000 random chunkings** (seeded), and assert identical event lists.
- Silent detection, a missing `</answer>`, an unterminated fence at EOF (→ emitted as a paragraph), a table without a separator (→ paragraphs).

### M2.2 Conversation model & exchange store · M
**Files:** `domain/.../conversation/ConversationModel.kt`, `ConversationPorts.kt`, `RecordExchangeUseCase.kt`, `infra/.../db/ExchangeDto.kt`, `ExchangeMapper.kt`, `ExchangeStore.kt`, Room migration 1→2
**Details**
- `Exchange` per D§4.1 plus `model`, `latencyMs`, `inputTokens`, `outputTokens` (from the stream's final `usage`). `ExchangeStore`: `append`, `update(status)`, `observeByNotebook`, `listByNotebook`.
- Ports:
  ```kotlin
  interface Oracle { fun ask(request: OracleRequest): Flow<OracleEvent> }   // cold; cancellation = collector cancelled
  sealed interface OracleEvent { Delta(text); Usage(inTok, outTok, cachedTok); }   // errors thrown as OracleException(DomainError)
  interface PageSnapshotter { suspend fun snapshot(pageId, focus: PageRect?): PageSnapshot }  // page JPEG + focus JPEG
  data class OracleRequest(system, documentContext: String?, history: List<Turn>, instruction, images: List<OracleImage>, options: OracleOptions)
  ```
- Migration test: v1 DB with data → v2, data intact.

### M2.3 Conversation state machine · L
**Files:** `domain/.../conversation/ConversationRules.kt`
**Details.** Implement `reduce` exactly per D§4.4. Full transition table (anything not listed = no-op):

| State | Event | → State | Effects |
|---|---|---|---|
| Idle | PenDown | Writing(utt=∅) | — |
| Writing | PenUp(s) | Pausing(utt+s, now+pause) | StartTimer |
| Pausing | PenDown | Writing(utt) | CancelTimer |
| Pausing | Tick ≥ deadline, listening | Thinking(utt, ++req) | SendRequest, ShowInkwell(anchor) |
| Pausing | Tick ≥ deadline, !listening | Idle | — (the utterance is kept, so AskNow can still send it) |
| any ≠ Thinking/Scribing | AskNow, utt ≠ ∅ | Thinking(utt, ++req) | CancelTimer, SendRequest, ShowInkwell |
| Thinking | PenDown | Writing(utt) | CancelRequest(req), HideInkwell |
| Thinking | Silent(req) | Idle(utt cleared) | HideInkwell(dry), SaveExchange(Silent) |
| Thinking | BlockReady(req) | Scribing(ex, [block]) | HideInkwell, PlanBlocks |
| Thinking | Failed(req, e) | Failed(utt, e) | ShowFailure |
| Scribing | BlockReady(req) | Scribing(+block) | PlanBlocks |
| Scribing | PenDown | Interrupted(ex) | StopScribe, CommitPartial, CancelRequest *only if the stream is still open → mark the exchange Interrupted with the markup so far* |
| Scribing | StreamDone(heard) + ScribeFinished | Idle | CommitAll, SaveExchange(Complete) |
| Interrupted | TapContinue | Scribing | ResumeScribe |
| Interrupted | PenUp | Pausing(new utt) | StartTimer (the partial answer stays as ink) |
| Failed | TapRetry | Thinking(utt, ++req) | SendRequest |
| Failed | PenDown | Writing(utt) | ClearFailure |
| * | event with a stale reqId | unchanged | — |

- The **utterance** accumulates across Writing↔Pausing↔Thinking-cancel and resets only when an exchange completes, goes silent, or is interrupted.
- The **anchor** = `(utt.bounds.left clamped to the margin, utt.bounds.bottom + lineGap)`, computed in `ConversationRules.anchor(utt, page)`.
- `Tick` comes from the timer effect (a single delayed event), not polling.
**Tests:** one test per table row, plus scenario tests: "write, pause 2 s, write, pause 4 s → exactly one request"; "pen during thinking → the request is cancelled and the resend includes both strokes"; "stale chunk after cancel is ignored"; a property test (random event sequences never produce two concurrent `SendRequest` without a `CancelRequest` between them).

### M2.4 Context & prompt composition · M
**Files:** `domain/.../conversation/ConversationContextRules.kt`, `ConversationPromptRules.kt`
**Details**
- `buildRequest(notebook, documentText?, exchanges, snapshot, settings): OracleRequest`, in the D§4.3 order: system → document → history → current.
- History: exchanges with status Complete/Interrupted become `Turn.User(heard)` / `Turn.Assistant(markup)`. Silent exchanges become user turns with a `(notes)` suffix merged into the next user turn (keeps role alternation valid). Failed ones are skipped.
- Token budget: `estimateTokens(text) = ceil(chars / 3.5)`, images = 1024 each. If the total > 120k, drop the oldest history pairs, then window the document (±30 pages around the current page + the ToC, D§9).
- `ConversationPromptRules.SYSTEM` = D§5.3 + the §5.2 markup spec, verbatim, versioned (`PROMPT_VERSION = 1`, stored on each Exchange for later analysis).
- The instruction text for the current turn: "The first image is the whole page; the second is the new writing (focus). Answer only the new writing." + a language hint (none; the model infers it).
**Tests:** ordering, alternation validity, budget trimming, stable-prefix property (adding a new exchange never changes the bytes of earlier messages; that's what keeps DeepSeek's prefix cache hitting).

### M2.5 Page snapshot adapter · M
**Files:** `infra/.../snapshot/PageSnapshotRenderer.kt`
**Details**
- Renders `background + all ink` (reusing `infra/render/InkRenderer` + `BackgroundRenderer`) at a scale giving a 1600 px long edge. Grayscale via `ColorMatrix` saturation 0, JPEG q85.
- Focus: `utt.bounds.inflate(10 %)`, rendered at 1:1 page units, capped at 1600 px, min 512 px (upscaled if tiny).
- Runs on `Dispatchers.Default` and takes < 150 ms (measure).

### M2.6 DeepSeek client · L
**Files:** `infra/.../http/HttpClient.kt` (class `NisabaHttpClient` wrapping one `OkHttpClient`), `SseParser.kt`, `infra/.../deepseek/DeepSeekDto.kt`, `DeepSeekMapper.kt`, `DeepSeekClient.kt`
**Details**
- `OkHttpClient`: connect 10 s, read 90 s, call timeout none, and `retryOnConnectionFailure=false`.
- `DeepSeekMapper.toWire(OracleRequest, apiKey)` → `ChatRequestDto(model, messages, stream=true, stream_options={include_usage:true}, temperature=0.6, max_tokens=2048, thinking?)`. Images go into the **final user message** only (the API rejects images elsewhere).
- `SseParser`: reads `BufferedSource` lines. `data: {json}` → a `ChatChunkDto` → `delta.content` → `OracleEvent.Delta`; `data: [DONE]` → end; the `usage` chunk → `Usage`. It ignores `reasoning_content`, comments (`:`), and blank keep-alives.
- `DeepSeekClient.ask` = `callbackFlow`/`flow { … }` on `Dispatchers.IO`. `awaitClose { call.cancel() }` means **collector cancellation aborts the socket**.
- Error mapping: 401 → `Auth`, 402 → `Quota` (insufficient balance), 429 → `Network(rateLimited)`, 5xx → `Network(server)`, `IOException` → `Network(offline)`, a malformed chunk → skipped + logged (never fatal unless nothing parses).
- A first-token watchdog: no `Delta` within 30 s (thinking off) or 120 s (thinking on) → `Network(timeout)`.
**Tests (MockWebServer):** happy stream in 1-byte TCP chunks; `[DONE]` without usage; 401/402/429/500; a mid-stream disconnect → error; **cancellation**: cancel the collector and assert the server sees the connection closed within 200 ms; images only in the last message.

### M2.7 Conversation session · L
**Files:** `domain/.../conversation/ConversationSession.kt`
**Details**
- `class ConversationSession(page, notebook, deps…, scope: CoroutineScope, clock)` holds `state: StateFlow<ConvState>` and `effects` for the UI (`SharedFlow<UiEffect>`: ShowInkwell, HideInkwell, PlanBlocks, StopScribe…).
- `onEvent(e)`: `reduce` under a `Mutex`, then execute the effects:
  - `StartTimer`: `timerJob = scope.launch { delay(pause); onEvent(Tick(clock.now())) }`
  - `SendRequest`: `requestJob = scope.launch { snapshot → buildRequest → oracle.ask().collect { parser.feed → onEvent(BlockReady/Silent/…) } }`
  - `CancelRequest`: `requestJob.cancel()`
  - `SaveExchange`/`RecordExchangeUseCase`: persists
- One session per open page; recreated on page turn (a page turn while Thinking = cancel; while Scribing = interrupt and commit the partial).
**Tests (`runTest` virtual time + fakes):** end-to-end scenarios from M2.3 but through real coroutines; verify the fake Oracle's flow was cancelled; verify an Exchange is saved with the correct status for each ending.

### M2.8 Answer rendering, placeholder (M3 replaces it) · M
**Files:** `scribe/.../layout/LayoutModel.kt`, `TextLayoutRules.kt` (a first version, reused by M3), `app/.../canvas/ScribeRenderer.kt`
**Details**
- The first real layout: a paragraph flow using **fixed-advance monoline glyphs** (a crude stroke alphabet: just use the chosen single-line font from M3.1 early if it's ready, else Hershey Simplex). Line breaking is greedy by word. Region = anchor → right margin. Page spill is ignored for now (clip at the page bottom + log).
- The result: strokes with `origin = Oracle(exId)`, committed as one `InkCommand.Add` when the stream is done, all at once.
**Done when:** answers appear under the question as real ink, erasable and undoable.

### M2.9 Inkwell indicator · M
**Files:** `app/.../ui/brand/InkwellRenderer.kt`, overlay hook in `PageView`
**Details**
- 6 precomputed vector frames (drop forming → full → ripple 1 → ripple 2 → ripple 3 → settle), 28×28 page units, lapis, at the anchor. It loops frames 2–6 at 3 fps.
- Each frame change: `invalidate(rect)` of the drop only, with `EinkDevice.fast()`. On hide: 2 "drying" frames (shrinking), then clear + `cleanPass(rect)`.
- Needs `suspendRawInk` while visible **only if S2 says our invalidate is blocked by the raw layer**. Otherwise keep raw ink live (the pen must stay instant during Thinking).

### M2.10 Toolbar: Listening, Ask now, failures · S
- Listening persists on the notebook (`setListening`). The quill glyph is filled/outlined. Ask now → `AskNow`.
- Failure UI: a handwritten "?" at the anchor (reuses placeholder glyphs) plus a one-line message chip under it:
  - "The ink wouldn't flow: no connection"
  - "Key rejected, check Settings"
  - "Out of credit at DeepSeek"

  Tap → `TapRetry`.

### M2.11 Settings screen v1 · M ∥
**Files:** `app/.../settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `SettingsUiState.kt`
**Details:** API key (masked, paste button, **Test** = a 1-token text request → ✓/✗ with the error class); model; thinking; pause length (2–6 s slider, 0.5 s steps); Listening default.

**M2 done when (device):**
- (1) A question → an answer in ink ≤ 2.5 s after the pause ends (log it: `pauseEnd→firstBlock` ms; ≥ 8/10 under 2.5 s on home Wi-Fi).
- (2) Pure notes produce no ink ≥ 9/10.
- (3) Pen-down during the inkwell never produces an answer to the old utterance, and the next pause sends both parts.
- (4) Airplane mode → the failure UI, then retry works after reconnecting.

---

## M3 — The hand

Goal: answers are *written*: humanized single-line glyphs drawn along the pen path, at the right pace, with the nib, interruptible and resumable, matching your size.

### M3.1 Glyph source & conversion tool · L
**Files:** `tools/src/main/kotlin/com/nisaba/tools/GlyphImportParser.kt`, `tools/.../ToolsMain.kt` (CLI entry point, exempt); output `scribe/src/main/resources/hands/casual.json`, `formal.json`
**Details**
- Evaluate candidate single-line fonts: EMS Casual Hand, EMS Delight, EMS Allure, EMS Felix, Hershey Script Simplex + Hershey Symbol (Greek, math). Criteria:
  - legibility (the M3.9 gate)
  - handwriting feel
  - licence: must be OFL or public domain; record it in `docs/licenses.md`
- `GlyphImportParser` reads SVG-font XML (`<glyph unicode= horiz-adv-x= d=>`) and normalises to em units with the baseline at 0, x-height = 1.0.
  - Path `d` → polylines: flatten curves to ≤ 0.01 em error.
  - Split into strokes at `M` commands.
  - Order strokes top-to-bottom, left-to-right, with a manual override table for letters like `t`, `f`, `x` and `i`/`j` dots, which are flagged `delayed=true`.
- Output JSON:
  ```json
  {"name":"casual","xHeight":1.0,"ascender":1.7,"descender":-0.7,"glyphs":{"a":{"adv":0.95,"strokes":[{"pts":[x,y,…],"delayed":false}]}}}
  ```
- Also emit **math glyphs** (Greek, ∑ ∫ ∏ √ ∞ ± × · ≤ ≥ ≠ ≈ → ( ) [ ] { } |) from Hershey Symbol into the same JSON under `"math"`.
**Done when:** `./gradlew :tools:run --args="import-hand …"` produces both JSONs, and `scribe-preview` (M3.8) renders a pangram.

### M3.2 Hand model & glyph set · M
**Files:** `scribe/.../hand/HandModel.kt`, `GlyphSetParser.kt`, `GlyphCompositionRules.kt`
**Details**
- `GlyphSet(name, metrics, glyphs: Map<Char, Glyph>, math: Map<String, Glyph>)`, loaded from resources via kotlinx-json (pure JVM; on Android, `:scribe` resources are packaged in the jar and read via `javaClass.getResourceAsStream`).
- Composition: a Unicode NFD decomposition (`java.text.Normalizer`) → base + combining marks. Mark glyphs are authored in code as short strokes (acute, grave, circumflex, diaeresis, ring, tilde, caron, cedilla, ogonek) positioned over the base's x-centre at the x-height/ascender. Special cases: `ø` = o + a diagonal slash, `æ` = a+e with the overlap tightened (advance 1.45), `œ`, `ß` from a fallback template. Unknown char → a small scribble mark (D§5.2).
**Tests:** every char of `"Hvordan går det? Æøå ÆØÅ — naïve café señor"` resolves to strokes; no empty glyphs.

### M3.3 Humanizer · M
**Files:** `scribe/.../hand/HandRules.kt`
**Details.** Pure: `humanize(placed: List<PlacedGlyph>, seed: Long, style: HandStyle): List<PlacedGlyph>`. Deterministic via `Random(seed)`.
- Per glyph: scale ~ N(1, 0.035), slant +8° base (casual) ± N(0, 1.5°), rotation N(0, 1°), x-jitter N(0, 0.02 em), y-bob N(0, 0.025 em).
- Per word: spacing × U(0.9, 1.12), a small x-height drift ±3 %.
- Per line: baseline wander = the sum of 2 sines (random phase, period 3–7 words, amplitude 0.05 em) + a slope drift ±0.6°.
- Per point: low-frequency wobble (1D value noise, amplitude 0.008 em), so identical letters never look stamped.
- Pressure profile along each stroke (arc-length s ∈ [0,1]): ramp in 0→1 over the first 8 %, ramp out over the last 12 %, base 0.62; a downstroke bonus of `+0.28·max(0, dy/ds)`; bold multiplies by 1.35. Dots/short strokes (< 0.15 em) get a stab profile (p=0.9 throughout, width 1.2×).
**Tests:** determinism (same seed → identical output); bounded distortion (the glyph bbox stays within ±12 % of the original); a golden SVG snapshot of a sentence (updated only deliberately).

### M3.4 Text layout (full) · L
**Files:** `scribe/.../layout/LayoutModel.kt`, `TextLayoutRules.kt`, `LayoutOccupancyRules.kt`
**Details**
- `Occupancy`: a grid of 16-unit cells over the page. Marked from (a) every existing stroke bbox inflated by 12 units and (b) (M5) the background raster: downsample, luminance < 0.85 → occupied. Built by the caller (the app) and passed in as an immutable `BitSet` wrapper.
- `LayoutRegion(pageSize, anchor, margins(l=120, r=120, t=140, b=160), xHeight, lineHeight = 2.6·xHeight)`.
- Line placement: for line band `[y, y+lineHeight)`, compute the free horizontal intervals within `[max(anchor.x, left margin), page.w − right margin]`. Use the leftmost interval with width ≥ 45 % of the region width; if none, `y += lineHeight/2` and retry. If `y + lineHeight > page.h − bottom margin` → emit `PageBreak` and continue in a fresh region (next page, anchor = the top-left margin, empty occupancy).
- Word wrapping: greedy, with word widths from glyph advances × xHeight after humanize-free measurement (humanize runs after, so widths are approximately stable; allow a 4 % overflow tolerance and then shrink the line's glyph scale to fit). Long words > the line width: hyphenate at a syllable-ish boundary (vowel-consonant) with a hand-drawn hyphen.
- Blocks: paragraph gap = 0.8 lineHeight; heading = 1.3× xHeight + an underline stroke (a humanized line of the heading's width + 4 %); list items get a hand dash `–` or `1.` with a hanging indent of 1.6 em per depth.
- The output `LaidBlock(pageOffset, glyphs: List<PlacedGlyph>, extraStrokes, bounds)`.
- Layout is **incremental**: `layoutNext(state: LayoutCursor, block) → (LaidBlock, LayoutCursor)`, so blocks are laid out as they stream.
**Tests:** flow around a blocking rectangle (lines skip the band), spill to a new page, an overlong word, determinism, a list indent. Golden SVGs of 5 sample answers.

### M3.5 Motion planning · M
**Files:** `scribe/.../motion/MotionModel.kt`, `MotionRules.kt`, `ScribePlanUseCase.kt`
**Details**
- `TimedStroke(pageOffset, points: InkPoints /* t = ms from plan start */, width, color)` and `ScribePlan(strokes, totalMs, blockEnds: List<Int>)`.
- Speed: `cps(n) = lerp(cps0, cps1, clamp((n − 150)/150, 0, 1))`, with Natural = 14 → 30, Slow = 9 → 18, Swift = 22 → 45. The time budget per glyph = 1000/cps(n) ms, split across the glyph's strokes ∝ arc length, with a minimum of 35 ms per stroke.
- Within a stroke, the minimum-jerk profile `s(τ) = 10τ³ − 15τ⁴ + 6τ⁵`: resample the stroke at uniform τ steps of 8 ms, placing points at arc length s(τ)·L.
- Pauses: pen-lift inside a glyph 25 ms, between glyphs 40 ms, word 90 ms, line 200 ms, block 350 ms. Delayed strokes (i-dots, t-bars) are queued and drawn at the end of their word.
- Table rules/diagrams: a fixed speed of 1.4 page-units/ms with minimum-jerk per stroke.
- `ScribePlanUseCase` = layout → humanize → motion, incremental per block, appending to a growing plan. It runs on `Dispatchers.Default` and must finish block N+1 before the animator reaches the end of block N (it's ~1 ms per block in practice; assert < 20 ms in a benchmark test).
**Tests:** monotonic timestamps; total time for a 100-char answer ≈ 7 s at Natural; delayed strokes come after their word; determinism.

### M3.6 Scribe animator & nib · L
**Files:** `app/.../canvas/ScribeAnimator.kt`, `ScribeRenderer.kt` (replaces the M2.8 placeholder), `PageView` overlay integration
**Details**
- A `Choreographer.FrameCallback` loop, throttled to 20 fps (it skips frames until ≥ 50 ms have passed). Each frame, `T = now − start − pausedTotal`: for every stroke with points in `(Tprev, T]`, draw those segments into the **ink layer** (with `InkRenderer` segment drawing, so it's identical to committed ink), union the dirty rect, and `invalidate(dirty ∪ nibRect)`.
- Nib: a 6×10-unit lapis nib shape at the pen-tip point, drawn in the overlay (not the ink layer), angled 40°. `nib=false` hides it.
- E-ink: `EinkDevice.fast()` at start; `cleanPass(answerBounds)` at the end of each block (≤ once per 1.5 s) and at completion; `normal()` after.
- Raw ink: `suspendRawInk()` for the whole playback if S2 chose strategy 1. Stylus `ACTION_DOWN` (via `PageView.onTouchEvent`) → `ConvEvent.PenDown`.
- Page spill: when the next stroke has `pageOffset > current`, the session emits `EnsureSpillPage` (creates the blank page after the current one if absent, via `InsertPageUseCase`), then the animator pauses, turns the page (instant swap + full refresh), and continues. The "↳" marks are extra strokes at the break on both pages.
- Interrupt: `stop()` returns T. The session commits the strokes truncated at T as an `InkCommand.Add` with origin Oracle. The remaining plan is kept in the session (`Interrupted.remaining`).
- Continue: a tap on the "…" mark (a lapis ellipsis drawn at the stop point, in the overlay; its hit rect is 48×48) → resume from T.
- Skip: a finger tap inside the answer bounds while scribing → draw everything remaining at once, then commit.
- Completion: commit all strokes as one `InkCommand.Add` (undo removes the whole answer), and clear the in-memory plan.
- The ink layer gets animated strokes drawn into it directly, so commit = only a model update (no re-render). The stroke ids were assigned at plan time.
**Tests:** unit-test the time→points slicing logic (pull it into `MotionRules.sliceUntil(plan, t)`, pure), and the interrupt split. On the device: record a video; frame pacing log (p95 frame work < 8 ms).

### M3.7 Hand size & settings · S
- `xHeight = InkRules.estimateXHeight(utt strokes)` → the layout region. Settings: hand speed, hand style (casual/formal), answer ink colour (S5 result), nib on/off.

### M3.8 `scribe-preview` CLI · M ∥ (start early in M3; it speeds up everything else)
**Files:** `tools/.../ScribePreviewRenderer.kt`, `ToolsMain.kt`
**Details:** `./gradlew :tools:run --args="preview --in sample.md --out out.svg [--png] [--seed 42] [--hand casual] [--width 1620]"`. It renders the laid, humanized strokes as an SVG with the variable-width outline. The outline geometry is one pure function, `HandRules.strokeOutline()` in `:scribe`, used by both `InkRenderer` and the preview, so the preview matches the device exactly. (Introduce it in M1.9 and put it in `:scribe` from the start.) PNG via Java AWT. `--animate` emits an SVG with `stroke-dashoffset` animation for a quick feel check in the browser.

### M3.9 Legibility gate · M
**Files:** `tools/.../LegibilityGateClient.kt`, `tools/fixtures/legibility/*.md`
**Details:** 12 fixture answers (EN + NO, lists, numbers). Render them to PNG at 1600 px, send them to `deepseek-flash` ("Transcribe exactly"), and compute word accuracy. **Gate: ≥ 97 % per fixture.** It runs on demand (`./gradlew :tools:run --args=legibility`) whenever glyphs/humanizer/layout change. The results are appended to `docs/legibility.md`.

**M3 done when (device):** a 2–3 sentence answer is written in ~5–8 s, visibly letter by letter, with the nib; it reads like handwriting (not a font); pen-down stops it within 50 ms and "…" resumes it; its size tracks yours; the legibility gate passes.

---

## M4 — Rich blocks: math, tables, diagrams

### M4.1 Block layout registry · S
**Files:** `scribe/.../layout/BlockLayoutRegistry.kt`
**Details:** `interface BlockLayouter<B : Block> { fun layout(block: B, cursor: LayoutCursor, ctx: LayoutContext): Pair<LaidBlock, LayoutCursor> }`, with a registry `Map<KClass<out Block>, BlockLayouter<*>>` built in one place. Text/heading/list layouters move behind it (from M3.4). New kinds = a new layouter + a registration line (OCP).

### M4.2 Math parser · M
**Files:** `domain/.../answer/MathParser.kt`, the `MathNode` types added to `AnswerModel.kt`
**Details:** a recursive descent over the D§5.4 subset. `MathNode = Row(list) | Sym(char/name) | Frac(num, den) | Sup(base, sup) | Sub(base, sub) | SubSup | Sqrt(index?, body) | BigOp(name, sub?, sup?) | Fenced(left, body, right) | Matrix(rows) | TextRun(s)`. Unknown `\cmd` → `TextRun("\\cmd")`. It never throws; malformed input → best-effort + `Malformed` logged.
**Tests:** 40 expressions (quadratic formula, sums, integrals with limits, nested fractions, pmatrix, unbalanced braces).

### M4.3 Math layout · L
**Files:** `scribe/.../layout/MathLayoutRules.kt`
**Details**
- TeX-lite boxes: `Box(width, ascent, descent, strokes)`.
- Scale factors: script 0.7, scriptscript 0.55; the sup raise is 0.45 em, the sub drop 0.2 em.
- Fraction: the bar extends 0.1 em beyond the wider part; num/den centred; the axis sits at 0.25 em.
- Sqrt: the radical path is generated to the body's height (hook + long stroke + overline).
- Big operators: 1.4× with limits above/below in display mode, and to the right when inline.
- Fences: parens/brackets generated as curves scaled to the content height.
- Matrix: a grid with column gap 0.8 em.
- Stroke order (the hand writes naturally): left to right by box; fraction = numerator → bar → denominator; sqrt = radical → body.
- Inline math sits in text lines (the box height may increase the line height locally); display math is centred in the region.
**Tests:** golden SVGs for 10 expressions; the box-metric invariants.

### M4.4 Table layout · M
**Files:** `scribe/.../layout/TableLayoutRules.kt`
**Details**
- Cell text in the hand at 0.8× xHeight.
- Columns: natural width = the max measured cell width, clamped to [3 em, 40 % of the region]; if the sum > the region, shrink proportionally and wrap cells.
- Ruling (D§1.4): a double rule under the header, a single rule under each row, vertical rules between columns only. Rules are humanized lines (3 jittered control points).
- Draw order: all rules first (the hand "rules the page"), then the header cells, then the rows, left→right.
- A table taller than the page remainder breaks between rows, repeating the header on the new page.
**Tests:** width allocation, wrapping, row breaking, golden SVGs.

### M4.5 Diagram parser + layout · L
**Files:** `domain/.../answer/DiagramParser.kt`, `scribe/.../layout/DiagramLayoutRules.kt`
**Details**
- The parser accepts only the D§5.2 subset (a tiny hand-written XML-ish tokenizer; no DOM, pure). Path `d` supports M L H V Q C Z (abs + rel). It outputs `DiagramShape(polyline points in 0–1000 space)` with curves flattened. Limits: ≤ 60 elements, ≤ 4000 chars, else a `Malformed` paragraph "(the drawing smudged)".
- Layout: content bounds → scale to fit `min(region width, 900 units)` wide and ≤ 1100 units tall, centred. Labels are written in the hand at 0.8× xHeight at the mapped positions, nudged to avoid overlapping shapes (greedy 8-direction search).
- Humanize: resample every 6 units + a low-frequency wobble (amplitude 2 units). Circles are started at a random angle and overshoot 4 % (like a real hand).
- Order: document order, which the prompt asks for in "draw big shapes first, then details, then labels".
**Tests:** a parser against 20 real model outputs gathered in M2 (saved in fixtures), rejection of bad elements, fit/scale.

### M4.6 Prompt tuning for rich blocks · M
- Collect 30 real prompts from your usage (math homework, a comparison, "draw a ...", a PDF question).
- Tune `ConversationPromptRules` (bumping `PROMPT_VERSION`) until ≥ 90 % of answers use the right block type and stay within limits.
- Keep the fixtures in `domain/src/test/resources/answers/` as parser regression tests.

**M4 done when (device):** "Solve x² − 5x + 6 = 0 and show it as a table of steps" gets a neatly written table with inline math; "draw the water cycle" gets a drawn, labelled diagram. None of it overlaps existing ink, and spill pages work.

---

## M5 — PDF notebooks (∥ after M2)

### M5.1 Document context & registry · S
**Files:** `domain/.../document/DocumentModel.kt`, `DocumentPorts.kt`, `ImportDocumentUseCase.kt`, `infra/.../document/DocumentReaderRegistry.kt`
**Details**
- The port `DocumentReader { mimeTypes; suspend fun open(uri): OpenedDocument }` with `OpenedDocument { pageCount; pageSize(i); suspend fun renderPage(i, widthPx): PageRaster; suspend fun extractText(): DocumentText }`.
- `DocumentReaderRegistry(readers: List<DocumentReader>)` resolves by MIME type.
- `DocumentText(pages: List<String>, toc: List<TocEntry>)`.

### M5.2 PDF reader adapter · L
**Files:** `infra/.../pdf/PdfDocumentReader.kt`, `DocumentFileStore.kt`
**Details**
- Copy the SAF URI to `files/docs/<id>.pdf` (streamed, with progress).
- Page count and sizes come from **PdfBox** (the prior attempt found it authoritative where `PdfRenderer` disagreed).
- Render with `PdfRenderer` at 1860 px wide (ARGB, white bg). Detect an all-black/blank render (sample 64 pixels) → fall back to the PdfBox `PDFRenderer`. Cache as WebP q90 in `cache/raster/<docId>/<i>.webp`.
- Text via PdfBox `PDFTextStripper` per page, on IO, with progress. ToC from the outline (`PDDocumentOutline`). `hasText = total chars > 50·pageCount`.
- Encrypted PDFs → an `Invalid("password-protected PDFs aren't supported yet")` error.
**Tests (instrumented, emulator):** 3 fixture PDFs (text, scanned, mixed sizes): page count, sizes, text contains the known phrases, the fallback path is triggered by a synthetic failing renderer.

### M5.3 Import flow & library UI · M
- "New from PDF" → `ActivityResultContracts.OpenDocument(["application/pdf"])` → `ImportDocumentUseCase`.
- It creates the notebook (named after the PDF title or filename) with `status=Importing`, pages with `DocumentPage(i)`. It extracts text in the background (an app-scope coroutine held by `AppGraph`). The card shows "pouring the ink… 42 %". On process death, any `Importing` document is resumed at startup.

### M5.4 PDF pages in the canvas · M
- `BackgroundRenderer` draws the cached raster (rendered on demand if missing, with a paper placeholder meanwhile). The page size comes from the PDF aspect at width 1860 (D§4.1).
- Insert blank page after (toolbar "+").
- Background occupancy for answer layout (M3.4 (b)): computed once per page from a 116×155 downsample of the raster and cached with it.

### M5.5 Document context in requests · S
- `buildRequest` includes the `DocumentText` for the notebook with the page tags `[p.N]`, plus the current page number in the instruction ("They are on p. 12."). Budget windowing per M2.4. Scanned PDFs (`hasText=false`) → no document text; the current page image carries it.
**Tests:** context ordering keeps the document text in the stable prefix; windowing picks the right pages.

**M5 done when (device):** import a 300-page PDF; write "what does chapter 4 say about X?" on p. 3 and get a correct answer citing a page; answers on PDF pages flow around the printed text or spill to an inserted blank page.

---

## M6 — Brand & polish

### M6.1 Splash & wordmark · M
- The system splash: `ic_mark` on paper (`Theme.SplashScreen`), with `setKeepOnScreenCondition` until the graph's DB is open (≤ 300 ms).
- The library header wordmark "Nisaba" is written by the hand (`ScribeAnimator` in a small `AndroidView`) once per cold start (~700 ms, fast profile). It's static afterwards and skippable by tap. The wordmark strokes are generated with a fixed seed so they're identical every time (brand consistency).

### M6.2 App icon & store graphics · S
- Adaptive icon (foreground mark, background paper), a monochrome icon (Android 13 themed icons), and a 512×512 Play icon + a 1024×500 feature graphic (the mark + the handwritten wordmark), exported via `:tools` from the same geometry.

### M6.3 Generated covers v2 · M
- `CoverRenderer`: the name is written in the hand (static plan render) under a seeded wedge motif: pattern family (rosette / border band / scattered stars) from `seed % 3`, wedge count and rotation from the seed, lapis or ink per seed. PDF notebooks get a small page-corner fold glyph. Cached bitmaps are invalidated on rename.

### M6.4 Micro-interactions audit · M
- Walk every state from D§1.2 on the device and fix any refresh artifacts: toolbar show/hide, dialog open/close (`cleanPass` after dismiss), page turn cadence, inkwell edge cases (the anchor off-page → clamp; the anchor inside the toolbar → below it).
- Empty states: the library with no notebooks shows the mark + a handwritten "Begin a notebook". An empty page's first open shows a faint handwritten "write, then pause" hint that disappears on the first stroke (shown once per install).

### M6.5 First run & privacy · S
- A first launch after install: 3 quiet pages (Write → Pause → Nisaba answers; the API key entry; the privacy notice: what goes to DeepSeek, D§5.5). Store the flag in DataStore.

### M6.6 Performance pass · M
- Measure and meet D§2 on the device: cold start (`adb shell am start -W`), page turn, pause→first stroke, animator frame times (`Trace` sections + Perfetto), memory.
- Baseline Profile for startup + the page view (`androidx.baselineprofile`), generated with a macrobenchmark on the device.

---

## M7 — Play Store readiness

### M7.1 Release build · M
- A release keystore (created by you, stored outside the repo), `keystore.properties` (gitignored), `signingConfigs.release`; the release build fails if it's missing (never falls back to the debug key).
- R8: `isMinifyEnabled = true`, `isShrinkResources = true`; keep rules for kotlinx.serialization, Room, PdfBox, and **Onyx SDK reflection** (`-keep class com.onyx.** { *; }` and `-keep class android.onyx.** { *; }` for the OEC reflection).
- `targetSdk`/`compileSdk` 36 (install platform 36 in `.tools`); re-test all device flows (behaviour changes: edge-to-edge enforcement, predictive back).
- Release has `DEV_DEEPSEEK_API_KEY = ""` (assert it in a unit test on `BuildConfig` for the release variant).

### M7.2 Compliance · S
- The privacy policy page (a static page; host it where you prefer, since publishing is your call); the data-safety form answers (data sent: images of your handwriting + PDF text to DeepSeek; not sold; no analytics).
- The Onyx SDK licence check: confirm redistribution in a Play app is allowed (see `docs/licenses.md`). If unclear, ask Onyx support before submission.
- Content: `android:allowBackup="false"` (no backup in v1, D15), `usesCleartextTraffic=false`.

### M7.3 Internal testing track · S
- Build the AAB (`bundleRelease`), upload to the internal testing track, install via Play on the Boox, and smoke-test the full flow.

---

## M8 — Memory (DESIGN §4.5, D16)

### M8.1 Stage 1: facts in every request · L ✅
- Domain `memory/`: model, `MemoryParser` (tags), `MemoryRules` (plan/dedupe, context format + budget, note), `MemoryStore` port, use cases. `buildRequest` adds `memoryContext` (25 % budget); `DeepSeekMapper` appends `# Memory` after `# Document`. Session applies tags after saving the exchange; surface `showMemoryNote`.
- Infra: `facts` table, Room v3 + `MIGRATION_2_3`, `RoomFactStore`. App: toolbar chip, `MemoryScreen` (paged), Settings section. Prompt v5.
**Tests:** parser, rules, use cases, session (facts in request, tag → fact + note, failures teach nothing), context budget, wire order, migration 2→3 + store (device), `MemoryViewModelTest`.

### M8.2 Stage 2: recall tool · M (when facts > ~2,000)
- FTS4 over `facts.text`; `recall(query)` via DeepSeek function calling (stream → tool call → second request); always-on core summary instead of all facts.

---

## M9 — Voices (DESIGN D17, §5.3a)

### M9.1 Personas per notebook · M ✅
- Domain `persona/` (`PersonaModel`, `PersonaRegistry`: 8 voices, ids stored, unknown → `book`); `systemPrompt(persona)` = voice + shared contract (`{words}`); `voiceReminder` at the end of the instruction; Tidy at `TIDY_TEMPERATURE`; `Notebook.personaId`, `Exchange.personaId`; `SetPersonaUseCase`; `Settings.lastPersona`.
- Infra: Room v6 (`MIGRATION_5_6`: `notebooks.persona` default `book`, `exchanges.persona`); summary row carries the voice; DataStore `lastPersona`; settings `update` made atomic (one `edit`).
- App: Library **New** = kind → voice → name/PDF (`NewNotebookComponent`, `PersonaPickerComponent` with a hand-written sample); ⋯ → Voice in the notebook (`VoiceDialogComponent`); cover line shows the voice. Debug: `--es persona ID` with `import_pdf`.
- Scribe: ✓/✗ glyphs (unknown characters used to come out as "?").
**Tests:** registry, prompt per voice (contract shared, words, temperature, Tidy), session (the voice of the moment reaches the request and the exchange), create/set/import with a voice, `LibraryViewModelTest` (the three paths + cancelled picker), VM voice change, migration 5→6 + store (device).
**Gates:** `scripts/persona_gate.py` (contract, length, language per voice).

### M9.2 Next for voices (not started)
- Per-voice touches: failure lines and the first-page hint in the voice; an optional hand/ink per voice.
- An eval runner in `:tools` using the real `buildRequest` + `AnswerParser` (persona × case, voice switch with 20 old-voice turns, memory precision); see `docs/improvements.md`.

---

## Cross-cutting

### Logging & diagnostics
- The `Logger` port → `infra/log/LogClient.kt` (`AndroidLogClient`, wraps `android.util.Log`), tag prefix `Nisaba/<context>`.
- A debug-only overlay (Settings → Developer): last request `pauseEnd→firstToken`, `→firstStroke`, tokens in/out/cached, and animator fps. Off in release.

### Error-handling policy
- Domain returns `Outcome` for expected failures; exceptions only for programmer errors.
- Adapters map every third-party exception to a `DomainError` at the boundary; nothing from OkHttp/Room/PdfBox leaks into the domain.
- The UI shows one line in the notebook's voice ("the ink wouldn't flow…"), never a stack trace or dialog storm.

### Testing summary

| Suite | Runs where | Gate |
|---|---|---|
| `:domain`, `:scribe` unit + golden | JVM, CI | every change |
| `:infra` unit (MockWebServer, mappers, ink file) | JVM, CI | every change |
| Room / PDF instrumented | emulator, local | infra changes |
| Device checklist (`docs/device-checklist.md`) | Boox | end of each milestone |
| Legibility gate | JVM + API | hand/layout changes |

### Risk register

| Risk | Signal | Mitigation |
|---|---|---|
| S2 fails: can't animate while the raw layer is on | M0.8 | Strategy 1 → 2 → 3. Worst case: raw ink off during scribing and plain MotionEvent drawing for interrupt strokes (latency ~40 ms on those only) |
| The Onyx SDK breaks on a firmware update | a crash / no ink after an update | `PenDevice` fallback to `MotionEventPenDevice` at runtime if the Onyx attach throws; an OEC no-op |
| DeepSeek latency > target | M0.11, M2 logs | Smaller images, `detail:auto`, prefix caching, show the inkwell sooner; option to use `deepseek-v4-pro` only on demand |
| Handwriting misread | the M0.11 accuracy | The focus crop at high detail; the prompt asks the model to say when it can't read something; a manual "Ask now" retry |
| Every pause costs a request (notes) | usage logs | Silent short-circuit; the Listening toggle; later a local heuristic (e.g. skip if the utterance has < 3 strokes) |
| A hand font licence problem | M3.1 | Only OFL/public-domain candidates; `docs/licenses.md` |
| Kaleido colour too faint | S5 | Black answer ink + the lapis nib only |

---

## Definition of done (every task)

1. `./gradlew check` green (unit tests, detekt, `verifyFileRoles`, lint).
2. New logic has tests; the rules/parsers are covered by table- or fixture-driven tests.
3. Anything touching input, rendering or refresh was checked on the device (note it in `docs/log.md` with the measurement).
4. DESIGN.md is updated if a decision changed.
5. No commit until you say so.
