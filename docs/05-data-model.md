# 05 — Data Model & Storage

Status: **Draft v0.1** · ADR-005: JSON files, no SQLite (v1)

## 1. Storage layout

```
filesDir/
└── notebooks/
    └── <notebookId: uuid>/
        ├── notebook.json        # everything except page bitmaps
        ├── thumb.png            # shelf thumbnail (regenerated on save)
        └── backgrounds/         # only for PDF notebooks
            └── p000.png         # page 0 background (RGB_565, ≤200 dpi)
```

- A notebook is a **directory**; deleting a notebook deletes its directory.
- `notebook.json` is the single source of truth (strokes, answers, transcript, settings).
- Backgrounds are derived artifacts: if missing, they re-render from the imported PDF copy (see 06 §5) — so they are a cache, not truth.

## 2. `notebook.json` schema (v1)

```jsonc
{
  "schema_version": 1,
  "id": "3f9c…uuid",
  "title": "Notebook 3",
  "created_at": "2025-08-31T09:00:00Z",
  "updated_at": "2025-08-31T09:30:00Z",
  "theme": "parchment-light",          // F7.5
  "ink_color": "sepia",                 // F2.5 default
  "source": {                           // null for blank notebooks
    "type": "pdf",
    "file": "backgrounds/../import.pdf", // copied import (06 §5)
    "page_count": 24,
    "imported_at": "2025-08-31T08:00:00Z"
  },
  "pages": [
    {
      "index": 0,
      "page_space": { "width": 2480, "height": 1860 },  // blank: 2480×1860; PDF: page box @300dpi
      "background": null,                // null = blank parchment
      "strokes": [ /* Stroke[], see below */ ],
      "answers": [ /* AnswerGroup[], see below */ ]
    }
  ],
  "transcript": [ /* TranscriptEntry[], see below */ ],
  "last_opened_page": 0
}
```

### Stroke

```jsonc
{
  "id": "s-42",
  "tool": "pen",                 // "pen" | "eraser-stroke" (v2: "highlighter")
  "color": "sepia",              // palette key
  "width": 3.2,                  // base width in page px (pressure curve applied at render)
  "points": [
    { "x": 120.5, "y": 300.1, "p": 0.42, "t": 1234 },   // t = ms since stroke start
    { "x": 131.0, "y": 301.0, "p": 0.51, "t": 1310 }
  ]
}
```

- Full absolute coordinates in v1 (simple, robust). **v2 optimization**: delta-encoding (`x`,`y`,`p` relative to previous point) shrinks files ~3× — only if a 500-page notebook exceeds N7 budget.

### AnswerGroup (one summon → one group)

```jsonc
{
  "id": "a-7",
  "summoned_at": "2025-08-31T09:31:00Z",
  "question_page_snapshot": { "page_index": 0, "stroke_count": 34 },
  "model": "deepseek-v4-flash-vision-exp",
  "status": "complete",            // "pending" | "complete" | "cancelled" | "error"
  "error": null,                   // set when status == "error"
  "blocks": [
    { "type": "text",      "markdown": "…" },
    { "type": "drawing",   "svg": "<svg …>…</svg>" },
    { "type": "table",     "title": "…", "headers": ["A","B"], "rows": [["1","2"]] }
  ]
}
```

- A cancelled/error group is kept (marked) so the UI can offer retry without losing the question's page state.

### TranscriptEntry (conversation memory, F5)

```jsonc
{
  "role": "user" | "assistant",
  "at": "2025-08-31T09:31:00Z",
  "user":  { "page_index": 0, "note": null },          // user turn: page ref (image regenerated on demand)
  "assistant": { "answer_group_id": "a-7" }            // assistant turn: link to AnswerGroup
}
```

- The page image is **never stored** — it is re-rendered from `strokes` when a summon needs it (07 §3). Keeps storage tiny and always consistent.
- Deletion of an answer group also removes its transcript entry (F5.3); regeneration swaps the group id.

## 3. Sizes (N7 sanity)

| Content | Approx size |
|---|---|
| 1 stroke, 300 points | ~8 KB JSON |
| 1 page, 80 strokes | ~600 KB |
| 100-page handwritten notebook | ~60 MB (strokes) |
| PDF background @ 200 dpi RGB_565 (A4) | ~8 MB/page PNG |
| 100-page PDF notebook | ~800 MB (backgrounds dominate → **reduce to 150 dpi for imports** if needed; verify legibility) |

> Imported backgrounds are the storage hog. Mitigation options (decide in M3 with real PDFs): 150 dpi backgrounds, or store the PDF and render backgrounds lazily + cache with LRU eviction (v2). N7 target ≤ 100 MB for a 500-page *handwritten* notebook — strokes are tiny.

## 4. Atomicity & durability (N4)

- **Writes**: serialize `Notebook` → JSON string → write `notebook.json.tmp` → `fsync` → rename → delete tmp. Rename is atomic on the same filesystem → no torn files.
- **Autosave**: debounce 800 ms after the last mutation (stroke added/erased, answer appended, block revealed); a final save on `onStop` + on `ACTION_UP` after erase/undo.
- **Crash windows**: a stroke is in memory until pen-up; autosave 800 ms after pen-up. Worst loss = the final stroke if the device dies mid-draw — acceptable, documented.
- **Reads**: on open, if `notebook.json` is missing but `notebook.json.tmp` exists, recover from tmp; if both missing, notebook is corrupt → offer "recover strokes from journal" (v2: write-ahead journal; v1: just report).
- **Thumbnails**: regenerate asynchronously on save; failure is non-fatal (shelf shows parchment icon).

## 5. Undo/redo

- In-memory stack of mutations (`AddStroke`, `EraseStrokes`, `AddAnswerGroup`, `RegenerateAnswer`, `DeleteAnswer`), depth 50.
- Undo/redo applies to the in-memory model + autosave; no journaling in v1.

## 6. Export (F8, v1 = PNG)

`PageExporter`: render background + strokes at page resolution into an `ARGB_8888` bitmap → PNG → share sheet via `FileProvider`. PDF export and full-notebook backup (zip) are v2.

## 7. Migration path

`schema_version` gate in `NotebookStore.load()`: v1 → future versions run sequential migrations (e.g. v2 adds `delta_encoding`, v3 adds per-page zoom state). Unknown-newer versions refuse to open (friendly error) rather than corrupt.
