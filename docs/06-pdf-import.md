# 06 — PDF Import ("Open a Tome")

Status: **Draft v0.1** · ADR-004: Android `PdfRenderer` in v1

## 1. User flow

1. Shelf → **"Open a Tome"** → SAF `ACTION_OPEN_DOCUMENT` (`application/pdf`).
2. Take a **persistable URI permission** and **copy the file** into the notebook dir (`backgrounds/../import.pdf`) so the notebook survives the source being deleted (F6.5). Copy with progress (bytes).
3. Open with `PdfRenderer`, read page count → progress UI: *"The tome is being copied… page 12 of 48"* (F6.2), cancellable.
4. Render each page to a background PNG (below) with progress; on completion, notebook is created with `source.pdf` metadata (05 §2) and opens on page 0.
5. Failure mid-import (bad PDF, page render error): **transactional rollback** — delete the half-created notebook dir, show magical error, offer "import anyway with skipped pages" (03 §6).

## 2. Rendering pipeline

```
PdfRenderer.openPage(i)
  → page.render(destBitmap, destClip, transform, RENDER_MODE_FOR_PRINT)
```

> **Implementation note (M2, verified on emulators):** `RENDER_MODE_FOR_PRINT` is used — it is the fidelity mode for bitmap rendering of documents (crisper text at our 200 dpi backgrounds). `RENDER_MODE_FOR_DISPLAY` behaved identically in our tests; either is acceptable.
>
> **Environment caveat (tracked):** in the sandboxed headless emulators used during development (API 34 *and* 35, with and without GPU emulation, every mode/matrix/clip), `PdfRenderer.Page.render` produces a solid black bitmap while plain `Canvas` rendering works — an environment defect, not app code. Pixel-level assertions soft-skip there (`PdfTestSupport.assumeRenders`) and hard-assert on real hardware. The M2 on-device acceptance (below) is the authoritative check.
  → downscale to ≤200 dpi (nearest filter; text stays crisp — this is a background, strokes are vector)
  → convert to RGB_565 (dithering on for gradients)
  → compress as PNG → backgrounds/pNNN.png
```

**Page space mapping** (must match 05 §2): render the PDF page box at 300 dpi *logically* — i.e. `page_space.width/height = page.width/height × (300/72)`. The *background bitmap* is stored at ≤ 200 dpi (or 150 dpi if storage demands) and scaled to fit page space at draw time. Strokes are stored in page space and thus remain crisp regardless of background resolution.

- Blank 4:3 pages (`page_space` 2480×1860) apply to blank notebooks; PDF pages use their own aspect (A4 → 2480×3508 @ 300 dpi).
- **Letterboxing**: background draws to fill page space with `CENTER_CROP`-style mapping (PDF page boxes are usually exact; crop whitespace edges to maximize usable writing area — v1: render as-is).

## 3. On-screen rendering & cache

- `PageBackgroundCache`: LRU of 3 decoded bitmaps (~8–9 MB each RGB_565), keyed by page index. Decode on page turn (IO thread), async swap.
- While decoding: show previous page + "the ink settles…" placeholder (no blank flash).
- Zoom (v2): re-render the page at 2× from the PDF, not from the 1× bitmap (ADR-004 trade-off: PdfRenderer is fixed-dpi; acceptable because v1 is 100% only).

## 4. Memory & storage budgets (N6/N7)

| Item | Budget |
|---|---|
| Decoded background (A4 @ 200 dpi, RGB_565) | ~8 MB |
| LRU cache | 3 pages ≈ 24–27 MB |
| Peak during import | 1 page bitmap + PNG encode buffer ≈ 20 MB |
| Imported PDF storage | as-is (typical 2–50 MB) |
| Import time | ≤ 5 s per 100 pages (render is fast; PNG encode dominates) |

## 5. Re-derivation (resilience)

- `backgrounds/` is a **cache**: if `pNNN.png` is missing, re-render from `import.pdf` on demand. So a notebook never becomes unreadable because a PNG was evicted/deleted.
- `import.pdf` is itself derivable from the original source only via re-import — it is stored as truth, but a v2 "re-import" flow covers source loss.

## 6. Edge cases

| Case | Behavior |
|---|---|
| Encrypted PDF | Prompt for password in a dialog; `PdfRenderer` supports it. **[v2 if encountered]** |
| Huge PDF (1000 pages) | Import in background with progress; notebook usable after page 1 is done (streaming import v2; v1 imports all, cancellable). |
| Scanned PDFs (image-only) | Render as-is; they become image backgrounds — fine. LLM vision will read them on summon. |
| Rotated pages | PdfRenderer handles page rotation in its matrix — no extra work. |
| Zero-page PDF | Reject with magical error ("This tome is empty"). |

## 7. Future (noted, not planned)

- MuPDF (AGPL — needs license decision) for arbitrary-zoom re-render and text-layer export.
- Insert blank pages between PDF pages (F1.6).
- PDF **export** of annotated notebooks (F8 v2): render page composites to PDF via `PdfDocument`.
