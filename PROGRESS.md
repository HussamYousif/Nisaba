# Nisaba — Progression Plan

The living tracker. **DESIGN.md** = what & why · **PLAN.md** = how, task by task · **PROGRESS.md** (this) = where we are, what's next, and how to pick up with no one else around.

---

## 1. How to use this file

- One checkbox per task (IDs match PLAN.md). Sub-steps are the concrete actions; tick them as you go.
- Status markers after a task title: `⏳ in progress` · `✅ done YYYY-MM-DD` · `⛔ blocked: <why>` · `↷ changed: <see log>`.
- **Keep §2 "Current state" true.** Update it whenever you stop working. It's the first thing you (or an agent) read next time.
- Every finished task gets a line in **§9 Log**. Every spike result goes in **§6 Decisions**.
- If reality disagrees with DESIGN/PLAN, change those docs and note it in the log. Don't let them drift.

Size → rough solo effort: **S** ½ day · **M** 1 day · **L** 2–3 days.

| Milestone | Effort | Running total |
|---|---|---|
| M0 Foundation & spikes | ~10 days | 10 |
| M1 Ink notebook | ~18 days | 28 |
| M2 Ask loop | ~16 days | 44 |
| M3 The hand | ~13 days | 57 |
| M4 Rich blocks | ~8 days | 65 |
| M5 PDF (∥ after M2) | ~6 days | 71 |
| M6 Polish | ~5 days | 76 |
| M7 Play Store | ~2 days | ~78 focused days |

**Usable checkpoints** (where the app is already worth using, if you stop there):
- after **M2**: it answers your questions in ink (plain strokes)
- after **M3**: the magic hand
- after **M5**: with PDFs

---

## 2. Current state

> Update this block every time you stop.

- **Date:** 2026-09-30 (session 2)
- **Branch:** `scribes-hand` (old code deleted in the working tree, **nothing committed**)
- **Done:** M0.1–M0.6, M1, M2, M3 core, **M4 (math, tables, diagrams, prompt v2)**, **M5 (PDF)**, answer **spill pages**, M6 partial (icon, splash, animated hand-written wordmark + cover names, **Welcome/privacy screen**, **empty-page hint**).
- **Not done:** M3.9 formal gate (`scripts/legibility_gate.py`; not run on math/table samples), M6 remainder (perf pass), all of M7, pen spikes S1/S2/S3/S5/S6.
- **Tests:** ~200 JVM tests + 15 instrumented on the Boox. `./gradlew check assembleDebug` green (10 s incremental) after Welcome + hint.
- **Feedback round 1 (user):** PDF entry point → Library **Open a PDF**; lag → **fixed, confirmed by user** (Onyx path from app icon); floating nib removed; more cursive → joined print default (`joinLetters`), full cursive as second style.
- **Verified live on the Boox:** full loop; PDF answer from p.3 with citation; rich answer (ruled table + 10²⁴, display √(2GM/R), diagram) spilling to page 2; prompt gate 84/84 (`scripts/prompt_gate.py`); wordmark and covers render (screenshot checked; short names OK; "Rich" overlaps the cover rule slightly: minor).
- **Design facts added:** prompt `PROMPT_VERSION=4` (Weiss persona; replies to everything; `<silent/>` only when literally empty; `<answer>` + `<heard>`); `LayoutRegion.pageTop` for spill pages; `LaidBlock.pathsFirst` (rules before text); Greek glyphs in both hands; `TypographyRules` normalises dashes/quotes; spatial resampling ≤2.5 px in motion.
- **Debug harness:** `adb shell "am start -W -n com.nisaba/.app.MainActivity --ez pen_motion true --es import_pdf file:///data/data/com.nisaba/files/incoming/x.pdf --es write 'question'"` (inner quotes: adb re-splits). PDFs: `adb push` to `/data/local/tmp`, then `run-as com.nisaba cp` into `files/incoming/`. Keep awake: `svc power stayon true`. "Offline": `svc wifi disable; svc wifi enable`.
- **NOT verified:** pen latency numbers; refresh modes (S3); ink colour on Kaleido (lapis legibility, S5); Onyx-layer handover (S6).
- **Known gaps:** undo is per page; no page preload; no "↳" continuation marks; scanned PDFs rely on page image only; large PDFs import synchronously with progress dialog; Onyx SDK logs harmless `ReflectUtil ClassNotFoundException`.
- **Housekeeping for user:** test notebooks in the library ("Rich", 3× "norway-notes", "Geography") are mine; delete when done.
- **Next action (in order):** (1) `./gradlew check`; (2) perf pass (`am start -W`, memory); (5) M7: signing via `keystore.properties` (**you** create the keystore: `keytool -genkeypair -v -keystore nisaba.jks -alias nisaba -keyalg RSA -keysize 2048 -validity 10000`), R8 + keep rules (kotlinx.serialization, Room, PdfBox, `com.onyx.**`, `android.onyx.**`, HiddenApiBypass), target/compile SDK 36 (AGP ≥ 8.9), `docs/privacy.md`, data-safety notes, feature graphic; **you** confirm Onyx SDK redistribution licence.

---

## 3. One-time setup (before M0)

- [ ] **Clean slate in git**
  - [ ] `git switch -c fresh-start` (keeps `scribes-hand` intact as the reference)
  - [ ] `git add -A && git status` (stages the already-deleted old code + the new docs; `.env` and `.tools/` stay ignored). Check the list before committing.
  - [ ] commit: `chore: fresh start — design, plan, progress`
  - Old code stays readable anytime: `git show 08de353:<path>` or `git log scribes-hand`
- [ ] **Boox settings** (on the device)
  - [ ] Settings → About → tap the build number 7× → Developer options → **USB debugging on**
  - [ ] Plug in via USB → accept the RSA prompt on the device
  - [ ] Later, once the app is installed: Boox **App Optimization → Nisaba**: refresh mode **Fast** (or whatever S3 decides), animations off, battery optimization **exempt**
- [ ] **Laptop**
  - [ ] JDK 17 available (`/usr/libexec/java_home -v 17`)
  - [ ] Android Studio (optional, but recommended for the layout inspector + debugger): point it at `.tools/android-sdk`
  - [ ] DeepSeek key in `.env` works:
    `curl -s https://api.deepseek.com/chat/completions -H "Authorization: Bearer $(grep KEY .env|cut -d= -f2)" -H 'Content-Type: application/json' -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"hi"}],"max_tokens":5}'`
- [ ] **Bookmark these references**
  - Onyx Pen SDK: https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md (+ the demo app source in the same repo, the best example)
  - Onyx Maven: https://repo.boox.com/repository/maven-public/
  - DeepSeek API: https://api-docs.deepseek.com/ · Vision: https://api-docs.deepseek.com/guides/vision/
  - Hershey/EMS single-line fonts: the "Hershey Text" Inkscape extension (Evil Mad Scientist), SVG fonts, OFL
  - PdfBox-Android: https://github.com/TomRoush/PdfBox-Android

---

## 4. Lessons already learned (from the first attempt; don't relearn them)

1. The Air 5C runs **Android 15**, not 13. Check with `adb shell getprop ro.build.version.release`.
2. The classic `com.onyx.android.sdk.api.device.epd.EPDController` **doesn't exist** on this firmware. The refresh lever that works is the framework binder `oec_service` → `IOECService.setAppScopeRefreshMode(mode, false)`; the old values were 0 = normal, 2 = fast. The reference implementation is `git show 08de353:app/src/main/java/com/nisaba/epd/Oec.kt`.
3. When the panel refreshes slowly, **stylus points get starved** (~12 points/stroke instead of 50–300). Strokes look jagged and the AI misreads handwriting. Keep the pen path on the raw (Onyx) layer and set the fast refresh mode.
4. `PdfRenderer` renders some PDFs **all black on Android 15**. Detect it and fall back to PdfBox. Trust **PdfBox** for page count and page box sizes.
5. `deepseek-flash` (formerly `deepseek-v4-flash-vision-exp`) accepts OpenAI-style `image_url` data URIs; that's verified live. Images are allowed in **user messages only**.
6. JPEG (not PNG) at ~1600 px is the right size for handwriting sent to the model.
7. Handwriting via fonts looked fake; true pen-stroke glyphs were the right call, and legibility must be tested by letting the model read the result back.

---

## 5. Milestone checklists

### M0 — Foundation & spikes

- [x] **M0.1 Toolchain & env** (S)
  - [ ] `scripts/env.sh` (ANDROID_HOME, GRADLE_USER_HOME, JAVA_HOME, PATH)
  - [ ] `scripts/device.sh` with `install | log | shot | props`
  - [ ] `.gitignore` += `captures/`
  - [ ] ✔ verify: `source scripts/env.sh && adb devices` shows the Boox; `scripts/device.sh props` → record it in §6 S0
- [x] **M0.2 Gradle skeleton** (M)
  - [ ] `.tools/gradle-8.13/bin/gradle wrapper --gradle-version 8.13`
  - [ ] `settings.gradle.kts` with 5 modules + the Onyx repo (content-filtered)
  - [ ] `gradle/libs.versions.toml` (PLAN M0.2 list)
  - [ ] `:domain` `:scribe` `:tools` = `kotlin("jvm")`; `:infra` = android library + KSP; `:app` = android app + Compose
  - [ ] one placeholder test per JVM module
  - [ ] ✔ verify: `./gradlew assembleDebug test`
- [x] **M0.3 Conventions** (S)
  - [ ] `build-logic/` included build with the `verifyFileRoles` task
  - [ ] detekt config + hook both into `check`
  - [ ] ✔ verify: a dummy `Foo.kt` fails `./gradlew check`; `FooRules.kt` passes
- [x] **M0.4 CI** (S) — `.github/workflows/ci.yml` runs `./gradlew check assembleDebug`; ✔ green on push
- [x] **M0.5 CLAUDE.md** (S): orientation + the file-role table + commands + definition of done
- [x] **M0.6 Spike harness** (M): a debug-only `SpikeActivity` with S1–S6 entries; install on the Boox
- [ ] **M0.7 S1 Onyx pen** (M)
  - [ ] resolve `onyxsdk-pen` (try 1.5.4.x) + transitive deps; add HiddenApiBypass if needed
  - [ ] TouchHelper on a full-screen view; log all callbacks + point counts + pressure range
  - [ ] side button + eraser end + finger rejection
  - [ ] 240 fps phone video → latency
  - [ ] → fill §6 S1
- [ ] **M0.8 S2 + S6 raw layer vs our drawing** (L) ⚠ biggest risk
  - [ ] strategy 1 (toggle raw off, animate, stylus ACTION_DOWN interrupts)
  - [ ] strategy 2 (render-off) if 1 fails
  - [ ] strategy 3 (overlay SurfaceView) if 2 fails
  - [ ] S6: tune the width curve until the handover is invisible; record the constants
  - [ ] → fill §6 S2, S6
- [ ] **M0.9 S3 refresh modes** (M): sweep OEC modes 0–6, find the clean-pass lever → §6 S3
- [ ] **M0.10 S5 ink colour** (S): the swatch page, frontlight on/off → §6 S5
- [ ] **M0.11 S4 vision** (M)
  - [ ] write 10 sample pages on the device (incl. æøå, math, pure notes) → export JPEGs + focus crops → `tools/fixtures/vision/`
  - [ ] the probe measures TTFT + accuracy + silent detection; try 1200 vs 1600 px, `high` vs `auto`
  - [ ] → §6 S4
- [ ] **M0.12 Close M0** (S): delete the spike code; update DESIGN.md with the decisions
- [ ] 🏁 **M0 exit:** all of §6 filled in; DESIGN.md agrees; `./gradlew check` green

### M1 — Ink notebook

- [x] **M1.1 Shared kernel** (S): ids, PagePoint/PageRect/PageSize, Clock/IdGen/Logger ports, Outcome/DomainError, tests
- [x] **M1.2 Notebook context** (M): model, fractional page positions, name rules, store port, the 6 use cases, tests
- [x] **M1.3 Ink context** (L)
  - [ ] `InkPoints` (primitive arrays), `Stroke`, `PageInk`, `StrokeGrid`
  - [ ] `InkRules`: hitStrokes, splitByEraser, estimateXHeight, utteranceBounds
  - [ ] `InkCommand` + `InkHistoryRules` (cap 200)
  - [ ] tests incl. the inverse-command property test
- [x] **M1.4 Settings model + ports** (S)
- [x] **M1.5 Persistence** (L)
  - [ ] Room v1 (notebooks, pages, documents) + schema export
  - [ ] ink files: ProtoBuf DTO, atomic write + `.bak`, debounced per-page saver, `flush()`
  - [ ] SettingsStore (DataStore), SecretStore (Keystore AES-GCM, debug `.env` fallback)
  - [ ] tests: mappers, ink file corruption fallback; instrumented Room CRUD
- [x] **M1.6 AppGraph + navigation** (M): the composition root, device selection, routes, immersive mode
- [~] **M1.7 Theme & brand primitives** (M) ∥ — theme, colours, components, no-ripple done; **still to do:** bundled fonts (EB Garamond/Inter), the mark vector, launcher icon: colour tokens, fonts, `NisabaComponent.kt`, the mark vector, launcher icon, no ripples
- [x] **M1.8 Library** (M): grid, create/rename/delete dialogs, placeholder covers; ✔ survives a restart
- [x] **M1.9 Page rendering** (L)
  - [ ] `SharedRules.fitTransform`; `HandRules.strokeOutline` in `:scribe` (the shared outline math)
  - [ ] `infra/render/InkRenderer` + `BackgroundRenderer`; `PageLayerCache` (reused bitmaps)
  - [ ] incremental draw + off-thread full rebuild; ✔ 200 strokes rebuild ≤ 120 ms
- [x] **M1.10 Pen & e-ink devices** (L): `PenDevice`/`EinkDevice` interfaces, the Onyx + fallback impls using the §6 constants, lifecycle
- [x] **M1.11 Notebook screen** (L)
  - [ ] ViewModel: stroke commit, erase (both modes), undo/redo, page preload ±1
  - [ ] page turns (instant swap, refresh cadence), grow at the last page
  - [ ] finger gestures, the auto-hide toolbar + exclusion rects, title rename
  - [ ] ViewModel tests with fakes (Turbine)
- [ ] **M1.12 Hardening** (M): the 1000-stroke page, meminfo < 250 MB, StrictMode, portrait lock
- [ ] 🏁 **M1 exit (device):** write 20 pages → erase → undo/redo → `adb shell am force-stop com.nisaba` → reopen: identical. Pen latency = the S1 number.

### M2 — Ask loop

- [x] **M2.1 Answer model + streaming parser** (L): blocks/inlines; `AnswerParser.feed/finish`; ~30 fixtures × whole / per-char / 1000 random chunkings
- [x] **M2.2 Exchanges** (M): model, ports (`Oracle`, `PageSnapshotter`, `ExchangeStore`), Room v2 + **migration test**
- [x] **M2.3 State machine** (L): `ConversationRules.reduce` per the PLAN table; one test per row + scenarios + the property test
- [x] **M2.4 Context & prompt** (M): request ordering, role alternation, token budget, the stable-prefix test, `PROMPT_VERSION`
- [x] **M2.5 Page snapshot** (M): full page 1600 px grayscale JPEG + focus crop; < 150 ms
- [x] **M2.6 DeepSeek client** (L): OkHttp, SSE parser, mapper (images in the last user msg), error mapping, first-token watchdog; MockWebServer tests incl. **cancel closes the socket**
- [x] **M2.7 Conversation session** (L): runs reduce + effects; timer/request jobs; per-page lifecycle; virtual-time tests
- [x] **M2.8 Placeholder answer rendering** — skipped: went straight to the real hand (M3), so answers are animated pen strokes from day one
- [x] **M2.9 Inkwell** (M): 6 frames at 3 fps, drying on hide, clean pass
- [x] **M2.10 Toolbar Listening/Ask now + failure UI** (S)
- [x] **M2.11 Settings v1** (M) ∥: key + Test button, model, thinking, pause slider, listening default
- [ ] 🏁 **M2 exit (device):**
  - [ ] ≥ 8/10 answers within 2.5 s of the pause ending (log `pauseEnd→firstBlock`)
  - [ ] ≥ 9/10 pure-note pages stay silent
  - [ ] pen-down during the inkwell never answers the old utterance; the next pause sends both parts
  - [ ] airplane mode → the failure UI → retry works

> 🎉 First genuinely usable version. It answers in ink.

### M3 — The hand

- [x] **M3.8 `scribe-preview` CLI** (M): do it **first** in M3; everything else is tuned with it
- [x] **M3.1 Glyph import** (L)
  - [ ] shortlist the fonts; check licences → `docs/licenses.md`
  - [ ] `GlyphImportParser`: SVG font → normalised strokes, stroke order overrides, delayed dots/bars, math symbols
  - [ ] output `hands/casual.json`, `hands/formal.json`
- [x] **M3.2 GlyphSet + composition** (M): loading from resources; diacritics via NFD; ø/æ/œ/ß special cases; ✔ the Norwegian test string
- [x] **M3.3 Humanizer** (M): glyph/word/line/point variation, the pressure profile; determinism + bounded distortion + golden tests
- [x] **M3.4 Full text layout** (L): occupancy grid, line bands flowing around ink, page spill, wrapping/hyphenation, headings/lists; incremental cursor; golden SVGs
- [x] **M3.5 Motion** (M): the cps ramp, minimum-jerk strokes, pauses, delayed strokes; ✔ 100 chars ≈ 7 s
- [x] **M3.6 Animator + nib** (L): 20 fps Choreographer loop, drawing straight into the ink layer, the nib, e-ink modes, spill page turns, interrupt/continue "…", skip, commit as one command
- [x] **M3.7 Hand size + settings** (S)
- [ ] **M3.9 Legibility gate** (M): 12 fixtures, ≥ 97 % words → `docs/legibility.md`
- [ ] 🏁 **M3 exit (device):** 2–3 sentences in ~5–8 s, letter by letter with the nib; pen-down stops ≤ 50 ms; "…" resumes; the size tracks yours; the gate passes

### M4 — Rich blocks

- [x] **M4.1 BlockLayoutRegistry** (S): text/math/table/diagram layouters (implemented as separate Rules files)
- [x] **M4.2 MathParser** (M): recursive descent, never throws; 40 expression tests
- [x] **M4.3 Math layout** (L): TeX-lite boxes, fractions/scripts/roots/big ops/fences/matrices, natural stroke order; golden SVGs
- [x] **M4.4 Table layout** (M): width allocation, wrapping, ruled lines first, row breaks with a repeated header
- [x] **M4.5 Diagrams** (L): SVG-subset tokenizer, fit/scale, label placement, wobble; tests on real model outputs
- [x] **M4.6 Prompt tuning** (M): 30 real prompts → ≥ 90 % right block type; fixtures saved as parser regressions
- [ ] 🏁 **M4 exit (device):** the quadratic-as-table and water-cycle-diagram cases look great; no overlap; spill works

### M5 — PDF (can start any time after M2)

- [x] **M5.1 Document context + registry** (S)
- [x] **M5.2 PDF reader** (L): copy, PdfBox count/sizes, PdfRenderer + black-render fallback, WebP cache, text + ToC, encrypted → error; instrumented fixture tests
- [x] **M5.3 Import flow** (M): SAF picker, notebook creation, background extraction with progress, resume after process death
- [x] **M5.4 PDF pages in the canvas** (M): raster background, "+ blank page after", background occupancy for layout
- [x] **M5.5 Document text in requests** (S): the stable prefix, page tags, windowing for huge PDFs
- [ ] 🏁 **M5 exit (device):** 300-page PDF; a question on p. 3 about chapter 4 gets a correct, page-cited answer that avoids the printed text

### M6 — Polish

- [x] **M6.1 Splash + handwritten wordmark** (M)
- [x] **M6.2 Icons + store graphics** (S)
- [x] **M6.3 Generated covers v2** (M)
- [ ] **M6.4 Micro-interaction audit** (M): every state in D§1.2 on the device; empty states; the first-page hint
- [x] **M6.5 First run + privacy notice** (S): `WelcomeScreen`, `Settings.onboarded/hintSeen`
- [ ] **M6.6 Performance pass** (M): meet D§2; baseline profile
- [ ] 🏁 **M6 exit:** screenshots good enough for a store listing; D§2 targets met (numbers in the log)

### M7 — Play Store

- [ ] **M7.1 Release build** (M): **you** create the keystore; R8 + keep rules (Onyx, serialization, Room, PdfBox); targetSdk 36; no dev key in release (test)
- [ ] **M7.2 Compliance** (S): privacy policy page, the data-safety form, **you** confirm the Onyx SDK licence
- [ ] **M7.3 Internal testing track** (S): upload the AAB, install via Play on the Boox, smoke-test
- [ ] 🏁 **M7 exit:** installed from Play on the Boox; the full flow works

---

## 6. Decisions (fill in during M0; the code reads these numbers)

| Spike | Question | Result | Decision | Date |
|---|---|---|---|---|
| S0 | Device facts | Android 15, fw 4.2.1-rel (2026-08-03), NoteAir5C, 1860×2480 @300dpi | as designed | 2026-09-29 |
| S1 | Onyx SDK version + deps; pressure max; timestamp units; side-button behaviour; latency ms | **Partial (no pen available).** `onyxsdk-pen` 1.5.4.3 + base 1.8.5.4 + device 1.3.5.2 resolve from repo.boox.com; raw drawing opens on Air 5C fw 4.2.1 with HiddenApiBypass, no crash. Digitizer `event5` Wacom: X 0–20832, Y 0–15624 (landscape-native), pressure 0–4095, tilt ±63, BTN_STYLUS/2, BTN_TOOL_RUBBER. **Still needs a real pen:** points/stroke, pressure scale in `TouchPoint`, timestamps, eraser/side-button callbacks, latency | Use 1.5.4.3. Injection via `sendevent` is impossible (SELinux enforcing, no root) | 2026-09-29 |
| S2 | Animate-with-raw-layer strategy (1/2/3); fps; interrupt latency; toggle cost | Animation on SurfaceView: **19.8 fps, 6–7 ms frame work** for all three strategies (raw off / render off / raw on); ink shows correctly. Strategy 1 interrupt: stylus-typed `ACTION_DOWN` reaches `onTouchEvent` in 1 ms and the animation stops within one frame (~43 ms). **Unverified with real pen:** that a real pen delivers MotionEvents while raw drawing is disabled (A1), and raw callbacks while animating (A3) | Provisional: **strategy 1** (toggle raw off during scribing). Confirm with pen | 2026-09-29 |
| S3 | OEC mode values (fast/normal); clean-pass method + ms | | | |
| S4 | Image size/detail; TTFT p50/p90; word accuracy; silent detection x/10 | Proxy test with macOS handwriting fonts (not real handwriting): 6 cases, model `deepseek-flash`, streaming, thinking disabled. TTFT p50 1.24 s (max 1.56 s) at 1600 px; 1.28 s at 1200 px. Transcription 1.00, silent detection 2/2 on notes/lists, 4/4 questions answered, Norwegian æøå OK. ~1.5k prompt tokens/request. **Real handwriting still to test** | 1600 px page (auto) + focus crop (high), JPEG q85; `thinking:{type:disabled}`. Script: `scripts/spike_vision.py` | 2026-09-29 |
| S5 | Answer ink colour + min width | | | |
| S6 | Ink width constants (`BASE`, `K`, `EXP`), smoothing | | | |
| F1 | Hand font(s) chosen + licence (M3.1) | | | |

---

## 7. When you're stuck

| Symptom | First thing to check |
|---|---|
| No ink appears with the Onyx SDK | Is `openRawDrawing()` called **after** layout (view has a size)? Is `setLimitRect` in view coordinates? Is `setRawDrawingEnabled(true)` set? Is HiddenApiBypass installed? |
| Ink appears but our layer never updates | Raw drawing locks refresh. Suspend raw ink (S2 strategy) before invalidating |
| Jagged strokes / few points | The refresh mode isn't fast (lesson 3); check the OEC call's logs (`scripts/device.sh log`) |
| Everything flickers | Too many clean passes / full refreshes; follow D§1.8 cadence |
| Model answers notes | Tune the prompt's silent rule; check that the focus crop contains only the new strokes |
| Model misreads | Look at the actual JPEG sent (a debug-only "save last snapshot" button saves it to Downloads) |
| Answer overlaps ink | Occupancy not built from the latest ink, or the background occupancy is missing on PDF pages |
| Build fails on file name | You invented a new role. Use an existing suffix (D§3.3), or add one deliberately in DESIGN + `verifyFileRoles` |
| Gradle can't find the Onyx artifact | Try https then http (`isAllowInsecureProtocol = true`) for repo.boox.com; check the content filter regex |

---

## 8. Resuming with an AI agent

Paste this to start a session, e.g. in Claude Code in this repo:

```
Read DESIGN.md, PLAN.md and PROGRESS.md (start with PROGRESS §2 "Current state").
Continue with the next unchecked task in PROGRESS §5. Follow PLAN.md for details and
DESIGN §3.3 for file naming. Definition of done is in PLAN.md (end). Verify on device
when the task touches input/rendering (Boox connected via adb; source scripts/env.sh).
When done: tick the boxes, add a §9 log line, update §2. Don't commit unless I ask.
```

Good session size: one **L** task or 2–3 **S/M** tasks. Review the diff + the log line before starting the next.

---

## 9. Log

Newest first. One line per finished task or notable event: `date · task · what changed · measurement / note`.

| Date | Task | Note |
|---|---|---|
| 2026-10-02 | Endless canvas | New-notebook choice **Pages | Endless canvas**. An endless notebook = one 200 000² px page (`PageSize.ENDLESS`, `isEndless`) with writing starting at `ENDLESS_ORIGIN` (100 000,100 000) so every direction has room; no DB change/migration (the size is the flag; library summary query derives `endless`). `PageView`: one-finger drag pans (shift the bitmap, repaint only the exposed strips, ≤1 step/120 ms, no animation), view origin remembered per page (`NotebookMemory`, SharedPreferences "views"), auto-pan to the inkwell and to follow the hand near the bottom edge. Answers: `LayoutRegion.below` = 1400-px column, 8000 tall (never spills); `Occupancy.window` tracks only a window around the answer; snapshot = window around the new writing (2000+ px wide, 1400 above, 500 below). Toolbar hides page counter/+Page. Tests: `EndlessLayoutRulesTest`, create-endless use case. Verified on the Boox: create, write, Weiss answer under the question, pan both ways, restart keeps position. NOT verified: real pen live-ink while panned; pen latency on the canvas. Gaps: no zoom, no PDF-as-canvas, no minimap/"go home", undo is canvas-wide only by existing per-page rule, panning during the hand's animation can drop its uncommitted strokes from view. |
| 2026-09-30 | Persona + polish | Prompt v4: Grimoire-Weiss persona (vain, sarcastic, banter, still correct; drops the act for grief; ≤50 words; no asterisks). Bundled EB Garamond + Inter (`res/font`, OFL in docs/licenses.md). Text fields in Settings/Welcome no longer pop the keyboard. "AI not working" was the Boox Wi-Fi having no IP (DHCP stall): `svc wifi disable; svc wifi enable` fixes it. |
| 2026-09-30 | Prompt v3 | User wants a reply to everything (statements too). `<silent/>` now only for a blank page/stray dot; `scripts/prompt_gate.py` inverted (56/56; needs `.tools/venv` with pillow). |
| 2026-09-30 | M6.5 | Welcome screen (what leaves the device, key save+test, Begin), start route from `onboarded`; hand-written "Write, then pause." hint on first blank page until first stroke (`hintSeen`). Fixed 3 detekt issues (LibraryScreen split into grid + dialogs). Verified on the Boox. Test notebook "Hint" left in library. |
| 2026-09-30 | M4 + spill + prompt v2 + M6 partial | Math parser/layout (fractions, scripts, roots, big ops, Greek), tables (column floors, double header rule, header repeated across pages), SVG-subset diagrams (fit, closed-shape overshoot, label nudging), spill pages with `pageTop`, strict-silence prompt (gate 84/84), adaptive icon + splash theme, `HandTextComponent` wordmark/cover names. Bugs: Hershey Greek stored in alphabet order under a–x; `\to/\le` aliases missing; spill started at question height; table text overflowed columns. Remaining: Welcome/privacy, hint, fonts, perf, M7. |
| 2026-09-30 | User feedback round 1 + M5 | Nib removed; joined semi-cursive default (joins only exit-right→entry-left letter pairs; model read-back 100% of words); incremental live ink for the fallback pen; **PDF support**: import (copy + PdfBox text + sizes), `PdfRenderer` with PdfBox black-page fallback, WebP raster cache, printed-text occupancy, document text in the stable prefix, `+ Page`. Bugs found: `adb shell` re-splits `--es` values (only first word delivered); Wi-Fi lease can stall (toggle radio); two pen sources at once interleave. |
| 2026-09-29 | M2 + M3 core | Domain: streaming `AnswerParser` (chunk-invariant, 500 random chunkings), pure `ConversationRules` state machine + random-walk invariant, context/prompt rules (stable prefix, token budget), `ConversationSession` (virtual-time tests). Infra: DeepSeek SSE client (cancel aborts socket; 1-byte-chunk test), Room v2 + migration, `PageSnapshotRenderer`. Scribe: Hershey glyphs exported (`tools/scripts/export_hershey.py`; casual=futural print, formal=cursive), diacritic composition, humanizer, layout (flow-around, spill, hard-break long words), min-jerk motion. App: animator @20fps + nib, inkwell, failure chip, settings screen, debug write harness. Findings: cursive Hershey is illegible (o≈a, n≈u) → print default; fast pen strokes need spatial resampling or curves turn into polygons; pressure ramps need densified strokes; models emit typographic dashes → normalise. |
| 2026-09-29 | M1.6–M1.11 | App layer: AppGraph, nav (no transitions), theme, library (create/rename/delete dialogs, generated covers), PageView (paper+ink bitmap, incremental strokes, finger gestures), PenDevice/EinkDevice (Onyx provisional + MotionEvent fallback), NotebookViewModel (+9 tests), toolbar auto-hide. Bugs found on device: toolbar title tap area too wide (fixed); `advanceUntilIdle` doesn't run backgroundScope (use `runCurrent` in tests). Onyx path untested without pen. |
| 2026-09-29 | M1.1–M1.5 | Domain (shared, notebook, ink, settings) + infra (Room v1, atomic ink files, DataStore settings, Keystore secret). 35 domain + 8 infra JVM tests, 7 instrumented tests on the Boox (Room, settings, Keystore). Deviation: `StrokeGrid` dropped (YAGNI; linear bounds prefilter is enough at page sizes). Note: right after `adb install` of a test APK the system may kill the first instrumentation run; just rerun. |
| 2026-09-29 | M0.7–M0.11 partial | No pen available. Done without it: S2 animation fps/interrupt path, S4 vision proxy (`scripts/spike_vision.py`), digitizer capabilities. Blocked on pen: raw-callback stats, eraser/side button, latency, handover (S6), palm/finger rejection; S3 (refresh modes) and S5 (ink colour) also need eyes on the panel. |
| 2026-09-29 | M0.1–M0.6 | Toolchain scripts, Gradle skeleton (5 modules), verifyFileRoles + detekt, CI, CLAUDE.md, spike harness. Onyx SDK needed: Jetifier, `pickFirst libc++_shared.so`, manifest `tools:replace`, and Onyx repo scoped to pub.devrel/com.tencent/com.jakewharton.hugo.fix. Build 37 s. |
| 2026-09-29 | — | Fresh start. DESIGN.md, PLAN.md, PROGRESS.md written. The old implementation is kept for reference at `08de353` (branch `scribes-hand`). |
