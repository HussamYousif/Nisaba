# 11 — Roadmap

Status: **Draft v0.1** · Ordered by risk-first (the scary unknowns get disproven first) and by the user's definition of done: *the core loop feels magical on the Boox.*

## 1. Milestones

### M0 — Design docs ✅ (this phase)
**Done when:** this doc set is complete and reviewed; open questions (02 §5) triaged; user decisions recorded (ADR-007, ADR-008).

### M1 — Drawing skeleton on the Boox *(highest risk: hardware unknowns)*
**Scope:** Gradle scaffold (10 §2); shelf screen with blank notebooks; `InkSurfaceView` with pressure strokes, eraser, undo; notebook JSON persistence (05); page turning; orientation decision; **device probe** (04 §1, §8): hover events, pen button, EPD feel, Boox SDK availability.
**Done when:** on the actual Note Air 5c, the user can write 200 strokes with no perceived lag, palm rejection works, restart loses zero strokes, and refresh is acceptable — *or* a documented Boox-SDK/EPD fix is in.

### M2 — PDF tomes
**Scope:** SAF import, copy, render pipeline, background cache, transactional failure handling (06).
**Done when:** a 100-page PDF opens as a notebook, page turns feel clean, memory stays under budget, and a scanned-PDF page reads back crisply at 100%.

### M3 — The summon (core loop v1) ✅ *(code complete; live API verification pending a real key)*
**Scope:** `DeepSeekClient` + `PageImagePreparer` + `PromptBuilder` + `AnswerParser` (07); idle detection with cancel-on-resume + quill button (F3); "ink stirs" indicator (09 §3); errors (09 §7); **live API verification** (OQ2/OQ3: vision input format, JSON reliability).
**Done when (emulator-verified):** the user writes, pauses, and a correct text answer appears below — and writing mid-request visibly cancels it. Verified via instrumented end-to-end tests against an in-process mock server (40/40 green) and a full UI run against a host mock (`10.0.2.2`). **Live API verified (2026-08-31):** real DeepSeek call from the app — blank page summon returned a correct vision-grounded answer, rendered with ✦ flourish and persisted; OQ2/OQ3 closed. **Remaining:** the on-device pass on the Boox.

### M4 — The answer in ink (magic presentation)
**Scope:** cursive typography (08 §3), text/table/drawing renderers, SVG subset + ink-reveal engine (08 §4–6), flourish dividers, parchment palette & textures (09 §1), reveal animation tuning on e-ink; **legibility photo test** on the real screen (N11).
**Done when:** a summon returns a mixed answer (text + table + drawing) that draws itself in, is legible at arm's length, and has no ghosting artifacts after the final clean pass.

### M5 — Conversation & polish
**Scope:** transcript context (F5), regenerate/smudge-away, per-answer controls, settings screen (F7) incl. encrypted key + connection test, PNG export (F8), onboarding screen, sounds (optional), anti-magic audit (09 §9).
**Done when:** a 10-exchange conversation in one notebook stays coherent; everything from S1–S14 passes on-device.

### M6 — Release
**Scope:** lint, signing, fresh-install/upgrade tests, full 04 §8 checklist, version 1.0.0 APK on the Boox (10 §7).
**Done when:** a signed `nisaba-1.0.0.apk` is installed and the user has used it for a full real session (journal/study) without touching a keyboard.

## 2. Dependency map

```
M1 ──▶ M2 ──▶ M3 ──▶ M4 ──▶ M5 ──▶ M6
  (device probe gates M2's refresh choices;
   OQ2/OQ3 verification gates M4's contract)
```

## 3. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | **Model `deepseek-v4-flash-vision-exp` unavailable/renamed or vision input differs** | Med | High (core loop) | Settings-configurable model string; OpenAI-compatible client; verify in M3 day 1; fallback model documented in Settings |
| R2 | **Boox OEM quirks: no hover events / poor third-party refresh** | Med | High (feel) | M1 probe before any polish; Boox SDK as optional path (ADR-002); user-visible App Optimization guidance (10 §5) |
| R3 | **E-ink ghosting during reveals** | Med | Med | Discrete steps + final Regal pass; skip-to-complete on tap; step count tunable |
| R4 | **Model JSON output malformed / schema drift** | Med | Med | Strict parse + repair retry + block-level fallback (07 §6–7); prompt versioning in `PromptBuilder` |
| R5 | **PDF backgrounds blow storage/memory** | Med | Med | ≤200 dpi RGB_565, LRU ≤3, re-derivable cache, 150 dpi fallback (06 §4) |
| R6 | **Cursive legibility on 150 ppi color layer** | Med | Med | ≥18 sp body, arm's-length photo tests, Kalam fallback for dense text (08 §3) |
| R7 | **Long latency (30–60 s) feels broken** | High | Med | "ink stirs" progress + second-line copy; skippable; cancel always available |
| R8 | **Accidental auto-summons while doodling** | Med | Low | Cancel-on-resume; new-content gate (F3.4); 3 s pause is generous |
| R9 | **PdfRenderer black render** — Android 15's new Skia-based `PdfRenderer` renders every page black; NOT emulator-only: reproduced on the Boox Note Air 5c (Android 15) in every mode/matrix/clip | High (was Med) | ✅ **Resolved**: `pdf/PdfPageRenderer` seam renders with the framework first, detects black output (>85% dark), falls back to bundled PdfBox-Android (pure Java, no JNI). Hard-assert regression test `renderSeam_producesContentEverywhere` passes on the Boox and emulators; background PNG verified white+text on-device |

## 4. Backlog (explicitly after v1)

- Pinch zoom + zoom-dependent PDF re-render (F2.7)
- Pixel-level eraser; highlighter tool; multiple ink colors per page
- Insert blank pages between PDF pages; PDF export (F8)
- Notebook backup/restore (zip); cloud sync (WebDAV/Drive) **[privacy review needed]**
- Arabic/RTL answers (08 §8)
- Streaming responses (blocks render as they arrive)
- Boox SDK EPD modes (if M1 probe says needed or for extra smoothness)
- Templates (grids, lined paper, dot grid) as blank-page variants
- MuPDF upgrade for arbitrary zoom + text layer (license review)

## 5. Definition of done (every milestone)

1. Runs on the **real device** (emulator is not a milestone).
2. All acceptance criteria above green.
3. `lint` clean; no known crash paths; kill-test passes (N4).
4. Docs updated where behavior diverged from this design.
5. A build artifact exists and installs cleanly (10 §7).
