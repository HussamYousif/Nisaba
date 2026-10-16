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

- **Date:** 2026-10-16
- **Branch:** `fresh-start`; M10–M18 committed, one commit per milestone, then the personal-use follow-up (D18). Nothing pushed.
- **Done:** M0–M5, most of M6, M8.1, M9, and the improvement programme **M10–M18** (`docs/improvements.md` #1–#54, PLAN M10–M18; what each milestone changed and measured is in §9). No store release (D18): M7 dropped.
- **Tests:** 426 JVM (domain 241, scribe 108, app 54, infra 23) + 31 instrumented (`:infra`, run on the Boox). `./gradlew check assembleDebug` green.
- **Verified on the Boox:** each milestone's drawing and input features (§9); the release APK (R8): notebooks open, answers come, cold start 331–334 ms.
- **Not verified:** the CI emulator job (runs on the first push); every feature under R8 (only the main flows above); restore from a backup on your Boox (only the instrumented round trip, so your notebooks stayed untouched); reminders handed to a calendar app (the notification path was checked); spikes S1/S2/S3/S5/S6 and pen latency numbers; a Perfetto trace.
- **Installing:** `scripts/device.sh install` (debug; reads `.env`; ~1.2 s cold start). Faster: `./gradlew :app:installRelease` (R8; ~0.33 s; same debug key, so it installs over the debug build and keeps the notebooks; uses the API key saved in Settings).
- **Device state:** debug build installed; my test notebooks, test voice and test PDFs removed (your `prosjektledelse` notebook and the 4 memory facts untouched); stay-awake off; `adb reverse` cleared; dev proxy stopped. Kept for the harness: `norway-notes.pdf` in `/data/local/tmp` and `files/incoming/`. Older screenshots and dumps from September (`/sdcard/*.png`, `/sdcard/u.xml`) were left alone.
- **Debug harness:** `adb shell "am start -W -n com.nisaba/.app.MainActivity --ez pen_motion true --es import_pdf file:///data/data/com.nisaba/files/incoming/x.pdf --es write 'question'"` (inner quotes: adb re-splits). More extras: `persona`, `learn_goal`, `canvas`, `new_notebook`, `open_first`, `delete_prefix`, `start_here` (recreates the Start here notebook), `proxy 127.0.0.1:8899` (offline Boox: `scripts/dev_proxy.py` + `adb reverse tcp:8899 tcp:8899`). `scripts/device_ask.sh "question" [voice]` asks end to end; `--clean` removes its `check-…` notebooks. PDFs: `adb push` to `/data/local/tmp`, then `run-as com.nisaba cp` into `files/incoming/`. Keep awake: `svc power stayon true` (and `false` after).
- **Understand a book (M21):** New → *Understand a book* → voice → PDF: Nisaba reads it into a map of its core concepts, then walks you through them a page at a time (write "next"). See DESIGN §1.2c.
- **Voices (2026-10-16):** book · Gandalf · writer · explorer · Yukino · Zagreus · Adam Jensen · Plain (+ up to 6 of your own). Not yet run against DeepSeek: `scripts/persona_gate.py teacher yukino jensen`.
- **Known gaps:** undo is per page; page `rebuild()` runs on the main thread (34–114 ms in debug); memory stage 2; right-to-left hands; sync.

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
8. Boox quirks met in M10–M18: the **app freezer** can disable Nisaba (`adb shell pm enable com.nisaba`); **RTC_WAKEUP alarms are refused**, so reminders also set a plain RTC alarm and catch up on resume; **`screencap` can return a stale frame** (read the UI with `uiautomator dump`); the device clock ran ~23 min ahead of the Mac.
9. The Onyx pen SDK reaches hidden APIs beyond `android.onyx.*` (`InputManager`, `ActivityManager`, `Environment`, `PowerManager`, `View.invalidate`), which is why `NisabaApp` exempts everything. If you ever narrow it, write with the pen and grep logcat for hidden-API denials.
10. A SQLite pragma run through `query()` does nothing until its cursor is stepped (`wal_checkpoint` in the backup); back up the `-wal` too.

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

Dropped 2026-10-16: Nisaba is for personal use only (DESIGN D18). M18 kept SDK 36 and an R8 release build.

### M8 — Memory

- [x] **M8.1 Stage 1** (L): `facts` table (Room v3), `<remember>`/`<forget>` tags, all facts in every request, toolbar chip, Settings → Memory screen; prompt v5
- [ ] **M8.2 Stage 2** (M): `recall` tool + FTS, only once facts pass ~2,000

### M9 — Voices

- [x] **M9.1 Personas per notebook** (M) ✅ 2026-10-15: registry + prompt split (v11), Room v6, New wizard, ⋯ → Voice, gates
- [x] **M9.2** per-voice failure lines/hint, `:tools` eval runner (PLAN M9.2) ✅ 2026-10-15 (eval in M12, lines in M14)

### M10–M18 — Improvement programme (PLAN M10–M18, `docs/improvements.md`)

- [x] **M10** data safety and the loop (#1–#6, #17, #31, #33 part, #34) + dev proxy ✅ 2026-10-15
- [x] **M11** headroom: big files split, 300-line rule (#35) ✅ 2026-10-15
- [x] **M12** answer quality and cost (#7–#16, #54) ✅ 2026-10-15
- [x] **M13** visibility and control (#18, #19/#38, #22, #23, #42, #44, #45) ✅ 2026-10-15
- [x] **M14** voices alive (#41, #43, #53) ✅ 2026-10-15
- [x] **M15** new ways to ask (#39, #40, #50, #51, #29) ✅ 2026-10-15
- [x] **M16** learning and habits (#46–#49, #52) ✅ 2026-10-15
- [x] **M17** library, pages, PDF, search, onboarding (#20, #21, #24–#28, #30, #37) ✅ 2026-10-15
- [x] **M18** release readiness (#32, #36, #33) ✅ 2026-10-16 (its store-only parts undone the same day: D18)

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
| `am start` says "Activity class … does not exist" | The Boox froze the app in the background (`adb shell dumpsys package com.nisaba \| grep enabled=` shows `enabled=3`): `adb shell pm enable com.nisaba`. Data is untouched |
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
| 2026-10-16 | M21 understand a book | Your ask: a mode that reads a book, finds its core concepts and walks you through them in order (DESIGN §1.2c). New → **Understand a book**; `ReadBookUseCase` reads it (one request up to 160k characters, else ~60k-character parts four at a time, resumable) into a map (`<book>`, `<thesis>`, 5–12 `<concept pages>`); the walk: the map of the book on page 1, then \"next\" (any language → `<next/>`, nothing written) or ⋯ → *Next* writes each concept on a fresh page with the document window on its pages, then *The whole picture*; ⋯ → *The map of the book* (✓ / ›). Room v12 (`notebooks.book`, `book_maps`), prompt v17. Also: AppGraph slimmed (`HandRegistry`, `ConnectivityDevice`). | Boox, *The Prince* (Gutenberg, 7 parts) with Gandalf: read in ~15 s; map page written (title, argument, 9 concepts; two lines spill to page 2); "next" written by the app's own hand → "1. Kinds of principalities" on a fresh page; map dialog fits. Tests: 13 book domain/session tests, a VM test, instrumented v11→v12 + store (33/33). |
| 2026-10-16 | M20 robustness | From a code review for crashes and data loss: **the pen mapped through the previous page's fit** after a turn between pages of different sizes (PDF ↔ blank page): ink landed up to ~140 px off and the eraser hit the wrong strokes; now every page uses its own fit (regression test). **Restore** restarts the app from the restore itself (non-cancellable), not from the Settings screen: leaving Settings mid-restore no longer leaves stale ink, prefs and a closed database to write over the restored files; restore stages beside the data, **swaps folders by rename and rolls back** on any error (the sealed key carried across); a backup writes its marker **last** (a cut-off zip is refused) and a failed one is deleted. **Page grid delete** cleared its selection after the pages shrank: a crash mid-frame; `pageLabel` is bounds-safe. **`singleTask`**: "Open with Nisaba" no longer starts a second app whose page could overwrite the first's ink. **Endless canvas**: a long answer grows down its column instead of spilling to an invisible portrait page; `findLine` can no longer loop forever on a page too short for one line. **Erasing** hit-tests on `Dispatchers.Default`. **PDF export** of a PDF notebook gives the pages written on, printed pages at half size (whole books at full size ran out of memory). "Start here" is created outside the welcome screen's scope; page rasters are written atomically. | Instrumented `:infra` 31/31 on the Boox (backup round trip with the swap). |
| 2026-10-16 | M19 polish (part 1) | From a UX review and live use: **no ripples** for real (Material3's `MaterialTheme` re-provided its ripple inside our no-indication theme); **Back** closes every dialog, the lasso bar, the held star's mini bar and the canvas overview (it used to leave the screen); dialogs being typed in ignore taps outside; keyboard **Done** puts the keyboard away, Search only autofocuses when empty, "Go to p." gets digits; the **chips** over the page are kept out of the pen's reach and **notices go by themselves** after 8 s (the unreadable-ink warning stays); after a lasso action the **pen comes back**, and the star shows a **tool badge** when the eraser, lasso or shapes are on; the mini bar no longer reappears. **Math**: `\\sin \\cos \\ln \\log \\exp …` written as words with a thin space (was "k_B\\lnW"); inline math keeps punctuation written against it ("W." not "W ."). **Settings → About**: build, prompt version, and the last crash kept on the device (`CrashLogStore`, Copy/Clear; `scripts/device.sh crash`). | Boox: Back closes the New dialog and stays in the library; eraser badge on the star; voices live (Gandalf, Yukino, Jensen). |
| 2026-10-16 | Voices, round 2 | Your asks: **Gandalf** replaces the wise teacher (same id `teacher`, so its notebooks are his): teaches, or simply talks; grey-blue ink, wizard's-hat sigil. **Yukino** (just "Yukino") takes over the flirt: banter, flirting, general talk and understanding yourself; `flirt` removed, its id answers as Yukino (`PersonaRegistry.MERGED`). **Adam Jensen** (`jensen`): talking about how the world became a dystopia, grounded (no invented news, no party line), amber ink, angular-shades sigil, "I never asked for this." as his failure opener. Prompt v16. | Not yet checked live (Boox offline): eval/gates for the three voices, the sigils on the device (shapes checked as SVG). |
| 2026-10-16 | Personal use (D18) | Not releasing, so the store-only parts of M18 are undone: Yukino and Zagreus are back in every build (`Persona.character`, `PersonaRegistry.offered`, `CHARACTER_VOICES` removed); the hidden-API exemption is wide open again (`""`; a firmware update can't quietly take the pen); release is signed with the debug key (keystore hook removed); `docs/privacy.md` and `docs/data-safety.md` removed; M7 dropped. Kept: SDK 36, the R8 release build (faster start), CI emulator job, perf work. | |
| 2026-10-16 | M18 release readiness | **#32** AGP 8.10.1, compile/target SDK 36; release = R8 minify + resource shrink with keep rules (`app/proguard-rules.pro`: serialization, PdfBox, Onyx, HiddenApiBypass; dontwarn joda/slf4j), signing from `keystore.properties` (gitignored with `*.jks`; falls back to debug signing), empty dev key, Yukino/Zagreus hidden (`Persona.character`, `PersonaRegistry.offered`, `BuildConfig.CHARACTER_VOICES`). Hidden-API exemption narrowed from `""` to `NisabaApp.ONYX_HIDDEN_APIS` (found on the Boox: the SDK also needs `InputManager`, `ActivityManager`, `Environment`, `PowerManager`). `docs/privacy.md`, `docs/data-safety.md` (claims checked against the code). **#36** CI `instrumented` job (API 34 emulator, `:infra:connectedDebugAndroidTest`; not run yet: nothing pushed); DeepSeekClientTest bounds loosened; `PageRedrawRulesTest`. Harness: `delete_prefix` (and `device_ask.sh --clean`) deleted nothing on a cold start (it filtered the library before Room answered); it now waits for the list (regression test). **#33** request building on `Dispatchers.Default` (`ConversationSession.compute`); `PageView.setStrokes` draws only appended strokes (`redrawOf`); hand parse time logged. | Boox, release APK: cold start 331–334 ms (566 ms first after install; target ≤ 900), notebooks open, answers with the narrowed exemption, no character voices in the picker. Debug: cold start ~1.2 s, page rebuild 34–114 ms, hand parse 251 ms after the first frame. Not done: off-thread `rebuild()` (fast enough), a Perfetto trace. |
| 2026-10-15 | M17 library, pages, files | **#26** library v2: six covers per screen with Earlier/Later, Continue ›, pin (front, "•"), "edited 2 h ago"; Room v11 (`pinned`, `deletedAt`, `paper`). **#20** trash (30 days, Restore / Delete / Empty), export as PDF (`ExportRenderer`, `PdfDocument`) or Markdown via the system save dialog, backup zip now or weekly to a folder + restore with restart (`BackupStore`, `FileRegistry`; key left out). **#21** page grid: 12 thumbnails per screen (`ThumbnailRenderer`), long-press Delete / Move / Duplicate (`PageUseCase.kt`), empty spill pages dropped after an answer is erased. **#24** labels "p. 12 +1", Go to p., "Open with Nisaba"/Share intent filters → voice step → import, ⋯ Tutor me / Stop tutoring. **#25** paper blank/lined/grid/dots (view, snapshot, thumbnails, export); on lined paper answers sit on the lines. **#27** Welcome fits one screen with true copy; "Start here" notebook written by the hand offline. **#28** search v2: names + PDF text, 6 per screen + Later, the hit outlined on its page. **#30** hold the star: pen · eraser · undo · eye. **#37** PdfBox document kept open, raster capped at 3 page-widths, imports catch everything and clean up. Also: `DebugRegistry` + `NotebookPrefsStore` out of AppGraph, `visibleStrokes` out of PageView (300-line rule); gesture undo/redo now says so. Tests: +6 domain, +4 app, instrumented 31/31 (v10→v11, backup round trip). **Found on device:** a page's whole answer gone from its file while the app sat idle (exchange still Complete, `.bak` intact) = an undo of the merged answer, most likely a stray two-finger touch → the notice; page restored from `.bak`. The first backup build ran `PRAGMA wal_checkpoint` without stepping its cursor (a no-op) and dropped the WAL: the instrumented round trip caught it before any restore. **Verified on the Boox:** library v2, Start here page, thumbnails + labels + Go to p. + duplicate/delete, lined paper + answer on the lines, export PDF (opened on the Mac: matches the page), backup zip (contents checked; test files deleted afterwards). NOT verified on the device: restore (only in the instrumented test, to keep the person's notebooks safe), "Open with Nisaba" from another app, weekly backups (time-based). |
| 2026-10-15 | M16 learning and habits | **#47/#48** ⋯ → Quiz me · Today's page · This week, recapped: a page at the end, written from the top; text-only requests (`Purpose.textOnly`: no snapshot, no images); Settings → Waiting → Mornings: a day's page on the first open of a notebook on a new day (off by default). **#46** spaced review: `lesson_topics.streak` (Room v10), gaps 2/4/8/16/30/60 days, shaky = tomorrow (`judged`, `dueAt`, `dueTopics`); ⋯ → Review what's due (N) in lesson notebooks. **#49** private notebooks (Room v10 `isPrivate`, ⋯ toggle): no memory in or out (+ "write no memory tags"), out of search, "· Private" under the cover. **#52** reminders: every instruction carries the local time; `<task when>` tags (stripped from ink) → chip → calendar app; a Boox has none and refuses wake-up alarms ("Do not allow … type=0"), so `ReminderDevice` sets a plain RTC alarm too and posts due reminders on resume; asks for notification permission once. Prompt v15. Tests: +9 domain, +1 app, migration 9→10 (29/29 instrumented). **Verified on the Boox:** quiz page (3 questions from the fjord PDF + history), "Remind me tomorrow at 9 to call Ola" → "call Ola · Fri 16 Oct, 09:00" chip; "in 2 minutes … drink water" → Add → permission → notification "Nisaba — drink water" at 22:08; private toggle → "· Private" in the library. NOT verified: review on device (no lesson notebook with due topics left; unit-tested), a morning page (needs a notebook last used on an earlier day). |
| 2026-10-15 | M15 new ways to ask | **#39** contract: circles/underlines/arrows point at what they mean; `focusArea` reaches up from an underline (prompt v14). **#40** Check my work (⋯ or lasso): `inkLines` splits their writing into lines, red line numbers drawn only into the model's images (`SnapshotLabel`), reply = `<mark line ok>` blocks (`Block.Mark`, streamed) then one closing sentence; green ✓ / red ✗ + correction written beside each line (`markRegion`, `MarkPlanner` on the answer's clock), thinking on, temperature 0.3. **#50** margin glosses on printed pages (`glossRegion`: the free column beside the question, 0.65× hand, ≤ 35 words; a sparse page with room under the question writes below as usual). **#51** Redraw cleanly → one diagram block below the sketch. **#29** lasso tool: loop picks ink (or a printed area), bottom bar: Ask about it · Check it · Redraw cleanly · Colour · Delete · Done; pen drag inside the box moves; each one undo step (`ReplaceStrokesUseCase`, `RemoveStrokesUseCase`); explicit asks take over a pending pause. Fix found on device: `InkRenderer` cached outlines by stroke id, so a moved stroke drew where it was → key includes position. Tests: +13 domain, +4 scribe, +5 app. **Verified on the Boox:** a 6-line worked solution: the teacher's normal reply wrongly said "Both correct" (3x = 9 → x = 4); ⋯ → Check my work wrote ✓ beside 5 lines and "✗ 9/3 = 3, so x=3" beside the last, plus "I was wrong last time…"; lasso select → red → move → move back → delete → undo; explorer gloss in the right margin of a PDF. Correction math came out as "$x=3$" → notes now parsed for inline math, ✓/✗ bold. |
| 2026-10-15 | M14 voices alive | **#41** each voice its own hand: `VoiceHand` (ink, slant, tidiness, pace) per persona; `HumanParams.voiced`; `Persona.handFor(settings)` (Settings → The hand → Voices: each its own / all alike; a voice's pace only replaces *Natural*); `planBlock` carries the persona → `BlockRef.ink` → animator pen + saved strokes. **#53** sigils (`PersonaSigilRenderer`) on covers and picker rows, per-voice first-page hint and failure opener ("Bah. The ink wouldn't flow…"). **#43** voices of their own: Room v9 `own_voices` (`MigrationDatabase.kt` now holds the migrations), `DraftVoiceUseCase` (one text-only request, tags → `parseVoiceDraft`), `KeepVoiceUseCase`/`ForgetVoiceUseCase`, `Voices` lookup (`LocalVoices`, `NotebookSources.voices`, `ConversationContextPorts.voice`), `OwnVoiceViewModel` + `OwnVoiceHost` above any picker; picker in two columns. Yukino: blue-black ink, no "The method:" label. `NotebookPrefsStore` out of AppGraph (300-line rule). Tests: +12 domain (`OwnVoiceTest`), +1 scribe, +2 app. **Verified on the Boox:** Zagreus answer in red slanted hand; covers with flame/book sigils; picker grid; "a grumpy old pirate captain who loves maths" → Captain Skewbald drafted (first line in teal), kept, selected, notebook created in it, "What is 17 times 23?" → "Arr, 391, matey. 17×23=17×20+17×3=340+51=391" in its teal hand. First draft dialog overflowed (sample not fitted) and showed the picker through it → shared `VoiceSampleComponent` + paper backdrop. |
| 2026-10-15 | M13 visibility + control | **#18** corner-star badges (… / ? / closed eye; `working` from the session) + questions pending at a page turn kept per page (prefs `pending_questions`) and asked when the page is back. **#45** `ConnectivityManager` → `online` flow; a question that failed for want of a network is retried when it returns (`retryIfOffline`); `ACCESS_NETWORK_STATE`. **#22** `OnyxEinkDevice` restores the wanted mode 400 ms after a clean pass (writing used to stay in NORMAL); flash on opening and every 5th turn; dialogs without the full-screen veil; `faint` #66625B. OEC has no "refresh now" call (methods logged: GC interval, modes, colour; left untouched). **#23/#42** answer menu: Shorter · Deeper · An example · Ask another voice… · Copy the words · Report (`AgainHow`; keep-variants write below the old answer; another voice's exchange records its persona). **#19/#44** done in M12 commit. Split `ConversationBeginSession.kt` off (300-line rule). Verified on the Boox: menu (no veil), Zagreus answer → Yukino second opinion written below it, in character. |
| 2026-10-15 | M12 answers + M13 habits | **#7** colour snapshots: new writing as written, older ink faded (α 0x70), answers in lapis; instructions say so (prompt v12). **#8/#54** "direct answer alone in a short first paragraph"; Room v7 `firstTokenMillis`/`firstBlockMillis`; Usage shows median first ink + silent share. **#9** `reasoning_content` keeps the first-token watchdog quiet. **#10** pen-down stops the hand, not the reply (`Interrupted.streamOpen`; later blocks planned for Continue; heard + memory kept). **#11** dot-sized utterances skipped; erasing shrinks the utterance (`ConvEvent.Erased`); cancelled requests saved as `Cancelled` with estimated tokens. **#12** history trimmed to 70 % once over budget; CJK-aware token estimate. **#13** `ConsolidateMemoryUseCase` (≥150 facts, every 25). **#14** ask-again thinks, quotes plain text; `Erased` exchanges leave history (`MarkAnswerErasedUseCase`). **#15** `:tools eval` (real `buildRequest` + `AnswerParser`, questions in the app's hand). **#16** no documented API temperature mapping (docs recommend 1.0–1.5); eval at ×1.0 63/64, ×1.6 62/64 but the explorer answered English in Italian → modest bumps only (book 0.7, writer 0.75, explorer 0.8, flirt 0.85, zagreus 0.8). Unicode sub/superscripts (H₂O) → inline math (they were "?"). **M13 (part):** answer modes (Everything · Questions only · When I ask) + length (Brief · Normal · Detailed) per notebook, Room v8, in New and ⋯ → Voice; `listeningDefault` removed. Verified on the Boox: 27/27 instrumented (v5→v8), live answer + follow-up (answer-first paragraph). |
| 2026-10-15 | M11 headroom | `InkEditSession` (domain, tested): open page + neighbours + edit lock + preload, out of the VM. `NotebookViewModel` 798 → 241 lines + `NotebookViewModelPorts` (deps), `…InkViewModel`, `…AnswerViewModel`, `…ToolbarViewModel`, `…DebugViewModel` (extension functions, no logic change; fields `internal`; `_state` → `mutableState`). `PageView` 412 → 283 (+ `PageGestureView`, `PageRegionView`), `NotebookScreen` 306 → 256 (+ `NotebookChipsComponent`), `CodeHighlightRules` 425 → 109 (+ `CodeLanguageRules`, `CodeLanguageMoreRules`), `AnswerParser` 345 → 159 (+ `AnswerBlockParser`), `ConversationSession` 369 → 289 (+ `ConversationRecordSession`). `verifyFileRoles` now fails source files over 300 lines. Verified on the Boox: ask + follow-up (Yukino) after the split. |
| 2026-10-15 | M10 data safety + loop | **#1** spill follow-up hang (animator kept `page=1` + old page id): each answer starts on its question's page, `Play` carries the utterance page; tests fail without the fix. **#2** unreadable ink → `.corrupt-<time>` (never overwritten), page notice. **#3** per-block commits (merged into one undo step per answer), exchange saved Interrupted at stream end then replaced, ON_STOP = pen-down, layout/save failures contained. **#4** uncommitted hand segments redrawn after rebuilds; `setPage` keyed on page id. **#5** `finish_reason` → cut short = Interrupted, no memory, notice; `max_tokens` per purpose. **#6** document window in 20-page blocks (cache holds inside a block), lessons centre on the last cited page. **#17** reopen at the last page (prefs), `TouchNotebookUseCase` (≤1/min) bumps `updatedAt`. **#31** `FallbackPenDevice` (Onyx attach failure → MotionEvent pen) + Settings → Your pen. **#34** snapshot has its own renderers. **#33** area-erase bounds prefilter; hand planning on a single background dispatcher. Debug harness: `--es proxy` + `scripts/dev_proxy.py` (offline Boox), `new_notebook/persona/canvas/delete_prefix`, follow-up writes, `scripts/device_ask.sh`. **Verified on the Boox (through the proxy):** live answer (Zagreus), a 5-page spill + skip, a follow-up written on the spill page answered there (was the hang), reopen lands on page 2/2, library order. Found: Unicode sub/superscripts (H₂O) in tables come out as "?" → M12. NOT verified: overlay redraw mid-answer (needs a two-finger undo), the Onyx→fallback switch (needs a broken SDK). |
| 2026-10-15 | M9.1 Voices + analysis | **Voices (D17):** domain `persona/` (`PersonaRegistry`: book (Weiss, default) · teacher · writer · explorer · flirt · yukino · zagreus · plain; ids stored, unknown → book); `systemPrompt(persona)` = voice paragraph + shared contract (`{words}`), universal rules moved into the contract ("tease back" → "play along"); `voiceReminder` ends the instruction (voice, language, words, "always end with `<heard>`"); Tidy at 0.3; teaching prompt "in your own voice"; PROMPT_VERSION 11. Room v6 (`MIGRATION_5_6`: `notebooks.persona` default `book`, `exchanges.persona`). Library **New** = kind (Notebook · Blank canvas · PDF · Learn a PDF) → voice (`PersonaPickerComponent`, hand-written sample on a 1800-unit line) → name or system picker (+ goal); remembers the last voice (`Settings.lastPersona`); cover line shows the voice; ⋯ → Voice in a notebook. Also: ✓/✗ glyphs (unknown chars became "?"), settings `update` in one `edit` (lost writes), `prompt_gate.py` reads the prompt via `scripts/prompt_source.py`. **Smoke test** (`scripts/persona_gate.py`, DeepSeek, 5 cases × 8 voices): run 1 the flirt parroted its example line 3/5 and teacher/Yukino answered Norwegian in English → example removed, language rule explicit; the longer voice line then dropped `<heard>` 4/20 → "always end with `<heard>`" → 0/37; final Norwegian run 8/8 in Norwegian (Plain answered in pure math). Tests: +15 JVM (registry, prompt per voice, session voice switch, create/set/import, LibraryViewModelTest, VM), +2 instrumented (5→6, store) → 25/25 on the Boox. **Verified on the Boox:** v6 migration on real data (library intact), New → kind → voice (Yukino sample written) → name → notebook; ⋯ → Voice: Yukino → Zagreus persisted. **NOT verified on the device:** a live answer in a voice (Boox offline: no saved Wi-Fi in range; the API side is covered by the smoke test). Left on the device: the test notebook "Yukino-test" was deleted. **Analysis:** 3 review agents (UX, AI, engineering) → `docs/improvements.md`; confirmed in code: spill follow-up hang, unreadable ink file → blank, `finish_reason` ignored, document window (lessons p.1–31, cache misses), snapshot old/new ink + grayscale, e-ink stuck in NORMAL, stale library order, dead `listeningDefault`. |
| 2026-10-02 | Polish + colours + shapes | **Settings** split into two panes (list + one short screen, no scrolling); **Ink & colours** pane: answer ink, `CodeHighlight` (Colour / Weight / Off, persisted) and a preview drawn by `HandInkComponent` from a real `Block.Code` layout (first try drew each tone run separately and fit each to the line height → giant quotes). **Toolbar timing:** 5 s after using the bar, 3 s after writing, 500 ms re-show guard. **Pen colours:** tap the picked pen → `PenPanelComponent` (8 `PEN_INKS`, `PenSize` Fine 2.4 / Medium 3.5 / Bold 5.5), saved via `NotebookMemory.savePen`; `PenDevice.setPen` (Onyx: `setStrokeColor/Width`; fallback: `PageView` live ink); dot on the pen icon. **Shapes:** `PenTool.Shape` + `ShapePanelComponent`; domain `ShapeRules` (`shapeStroke`, `recognizeShape`: straightness, closed gap, RDP corners, box-likeness, ellipse radius spread); `NotebookEffect.Repaint` + `PageView.forgetLiveInk` swap the freehand live ink for the clean stroke. **Onyx eraser fix:** with an eraser picked the pen tip's strokes now erase (raw render off) instead of inking. Pen paused while any overlay is open. Tests: 9 `ShapeRulesTest`, 4 VM. Verified on the Boox (fallback pen via `adb input stylus`): Settings panes + preview in all 3 code styles, pen panel, red bold ink + icon dot, Box drag → clean rectangle, rough circle in Auto → ellipse, undo. NOT verified: real Onyx pen (live colour/width, raw-render-off eraser, Repaint over the raw layer), colours by eye on Kaleido. Note for testing: the bar shifts when Ask now appears (muted), and Boox screencaps can show a stale frame; use `uiautomator dump`. |
| 2026-10-02 | PostgreSQL highlighting | Own `Lang` for postgres/psql/pgsql/plpgsql: types, functions, PL/pgSQL words, `::` casts as keywords, double quotes = identifiers (plain), `$$` bodies highlighted as code. Prompt v10 names ```postgresql. Test + verified on the Boox ("review-pg" notebook is mine). |
| 2026-10-02 | Icon toolbar + code highlighting | **Toolbar:** `NisabaIconComponent` (monoline Compose paths: back, nib, eraser, dashed-area eraser, undo, redo, eye/closed eye, quill, ⋯), pen\|eraser pair (long-press = area), undo/redo, tappable `n / m ▾` → `PageGridComponent`, Ask now only when muted (`askable` from session status), star handle bottom-right with pen exclusion. **Highlighting:** `InkTone` on `LaidWord`→`LaidStroke`→`TimedStroke`; `CodeHighlightRules` (per-language `Lang` table: keywords, line/block comments, quotes, char-literal heuristic, case-insensitive, name chars, `:keyword` prefix); code words split where the tone changes; `answerPen(tone, ink)` gives colour + width to the animator (`drawSegment(…, widthScale)`) and the committed stroke. Prompt v9 (always name the language). Tests: highlighter per language family, tone through layout/humanizer/plan, page grid VM. Verified on the Boox: Python (keywords red, string green, comment thin grey), Haskell + Clojure answer, icon bar via star handle, eye icon. NOT verified: colours as seen on the Kaleido panel by a human (screenshots are RGB); black-ink mode; page grid with >36 pages. |
| 2026-10-02 | Feature batch (user list) | **Toolbar:** ‹ Back · title · n/m · Pen/Erase/Erase·area toggle · Listening · Ask now · ⋯ menu (`NotebookToolbarComponent`, menu state in the VM so auto-hide never closes it). **Page preload:** ±1 pages kept as `InkEditState` (undo survives turning back; edits to them update the cache). **Code blocks:** `Block.Code` from non-diagram fences (also when cut off), `CodeLayoutRules` monospace grid + ruled box + ↳ wrap, `LaidWord.joinable=false`; prompt allows ≤15×48 code. **Room v5** (`MIGRATION_4_5`): exchanges `cachedTokens` + focus rect, `lesson_topics`. **Usage:** `UsageRules` (DeepSeek list prices 2026-10-02, peak ×2) → Settings → Usage. **Erase / Ask again:** long press → `answerAt` → menu; `EraseAnswerUseCase` across spill pages; `Purpose.Again` re-sends the stored focus with the previous answer. **Search:** `SearchUseCase` (LIKE over heard+answer, failures skipped, deleted pages dropped) + `SearchScreen`, `notebook/{id}?page=`. **Endless:** Overview (`CanvasOverviewComponent`) + Home (`NotebookEffect.PanTo`). **Tidy:** `Purpose.Tidy` reads the source page, writes on a new page after it. **Lessons remember:** `<topic>` tags → `LessonStore`, progress line in the instruction, `Purpose.Resume` after 4 h, Lesson progress dialog. Prompt v8. Tests: ~40 new (domain, scribe, app, 6 instrumented); `check` green; infra instrumented 23/23 on the Boox. Verified on the Boox: toolbar + menu, code answer, long press → Ask again (different answer, erased the old), search → open page, tidy copy, endless overview/home, lesson tutor marks `Learned: Fjords`, Usage panel, v4→v5 migration on real data. Found on device: menu vanished when the bar auto-hid (fixed); code rows too tight (pitch 2.5). NOT verified: resume-after-4h live (unit-tested), long-press with a real finger vs. pen. |
| 2026-10-02 | Lesson notebooks + no inkwell | **Inkwell removed** (user: the answer should just appear); `ScribeCommand.AnswerAt` only pans an endless canvas. **Learn a PDF:** library button → picker → optional goal dialog → `ImportDocumentUseCase(teachGoal)` makes a notebook with the document as context and one blank page; `Notebook.teachGoal` (Room v4, `MIGRATION_3_4`, nullable column; summary `lesson` flag → "Lesson · PDF"); `teachingPrompt(goal)` appended to the system prompt (PROMPT_VERSION 6); `ConvEvent.Begin` + `ConversationSession.beginLesson` let Nisaba write first on a fresh lesson notebook (no focus crop, `INSTRUCTION_BEGIN_LESSON`, heard = "(opens the lesson notebook)", waits for Ask now when muted, only once: skipped when exchanges exist); opening x-height 32. Debug harness: `--es import_pdf … --es learn_goal 'goal'`. Tests: reducer, session, context, import; migration 3→4 and 1→4. Verified on the Boox: v3→v4 on real data; lesson opens itself with and without a goal, cites pages, uses the goal; library label. NOT verified: the picker → goal dialog path by touch (harness skips the picker); answering the tutor's question with a real pen. |
| 2026-10-02 | Review fixes | Code review → fixes, each with tests. **Hand:** `ScribeAnimator` tracks "active" separately from "playing" (raw ink was suspended once more per spill page and never resumed → dead Onyx pen); testable via `ScribeCanvas`/`ScribeFrames`; skip-tap goes page by page; empty blocks still count; a new answer after a stopped one starts clean. **Session:** `ScribeFinished(reqId, blocksDrawn)` + `Scribing.blocks` (a late finish could end a live answer); `close()` saves a started answer as Interrupted and ignores late events; saves survive scope cancel; strokes carry the real `ExchangeId`; memory tags only applied from complete replies. **VM:** all ink edits serialised (edit lock) and applied to the page they were made on (overlapping saves lost strokes; turn-mid-commit put ink on the wrong page); commits target `animator.writingOn`; leaving the screen keeps what the hand wrote and interrupts like a pen-down, coming back resumes the pause; spill page-insert failure ends the answer; printed occupancy job cancelled on page change. **DeepSeek:** 2 retries before the first byte (IO, 429 w/ Retry-After, 5xx), first-token watchdog (keep-alives don't count), call timeout 180/420 s. **Parsers:** stray `}` in a matrix looped forever (and at top level dropped the rest); `<heard>` before `<answer>`, `<silent />`/`<Silent></Silent>` variants, lone tag lines, cut-off SVG drawn as a diagram. **Layout:** table rows check occupancy per row (break + repeat header below ink), narrow columns squeeze/break words; baseline slope runs from the line start (`LaidWord.lineX`; table cells keep per-word, rules too close); diagram S-after-S / T-after-T reflect. **Threading/perf:** ink file I/O on IO dispatcher with writes outside the map lock, flush no longer `runBlocking` on main, Keystore + doc delete on IO, raster `cached()` memory-only, snapshot + strip paint filter strokes by area, composed glyphs memoized. Verified on the Boox: answer + table, skip-tap, Back mid-answer keeps the partial answer (incl. the half stroke); infra instrumented 17/17. NOT verified: the Onyx raw-ink resume (no pen); Settings mid-answer path (same code as Back). Not done: tiled/append-only ink files for endless canvases (still a full rewrite per save). |
| 2026-10-02 | M8.1 Memory | Cross-notebook long-term memory (D16, DESIGN §4.5). Domain `memory/` (parser, rules, port, 3 use cases); `facts` table, Room v3 + `MIGRATION_2_3`; facts in system message after the document (25 % budget, newest kept); session applies `<remember>`/`<forget>` after save; `AnswerParser` strips tags; toolbar chip 6 s; Settings → Memory (paged, Forget + confirm). Prompt v5 (+ "anything they say about themselves deserves a remember", "never assume gender/age"; v5 first draft missed "I build an app" and the persona guessed "a woman" from "nurse"). Tests: ~20 new JVM + 2 instrumented (17/17 infra on device). Verified on the Boox: migration on real data; learned facts; a fresh notebook answered "what do I do for work?" → nurse; chip shown and cleared; forget via UI; "Lina moved to Oslo" → `<forget id=4>` + new fact. prompt_gate not run (user's choice). |
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
