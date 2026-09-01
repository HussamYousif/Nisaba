# 07 — LLM Integration (DeepSeek)

Status: **Draft v0.1** · Provider: DeepSeek, model `deepseek-v4-flash-vision-exp` (configurable) · ADR-001: vision-first input

## 1. Endpoint

| Item | Value |
|---|---|
| Base URL (default) | `https://api.deepseek.com` (Settings-overridable, F7.2) |
| Path | `/chat/completions` (OpenAI-compatible) |
| Auth | `Authorization: Bearer <API key>` (key in EncryptedSharedPreferences, 03 §7) |
| Model | `deepseek-v4-flash-vision-exp` (string in Settings; default = this) |
| Body | JSON, see §4 |
| Timeout | connect 15 s / read 120 s (generation is slow; no aggressive read timeout) |
| TLS | OkHttp defaults; `usesCleartextTraffic=false` |

> **OQ2 (resolved, live 2026-08-31):** the model accepts the OpenAI `image_url` data-URI format exactly as implemented — verified with a generated test image (model correctly described it) and with a full on-device summon (blank page → "I see no writing on this page").

## 2. The vision input (page image)

`PageImagePreparer` — the page (strokes only, **no prior answers**, no UI chrome) rendered to a grayscale JPEG:

```
strokes + background(none for blank pages) 
  → render at min(original, 1600px long edge)   // handwriting stays legible at this size (audit P1-7: was 2000px/PNG)
  → grayscale (luminance), no thresholding in v1 (model reads tones fine; PDF text too)
  → JPEG quality 85 → base64 → data:image/jpeg;base64,…
```

- Target size ≈ 1.9 MP (1600×1200) and a few hundred KB payload — JPEG, not PNG: `quality` actually does something, and handwriting on parchment compresses an order of magnitude better.
- Strokes are rendered with the *ink color*; sepia ink on parchment grayscales to good contrast. PDF backgrounds included (the model must read printed text too).
- **Why not OCR first (ADR-001):** the vision model reads any handwriting directly, sees layout/doodles/arrows, and needs no ML Kit dependency. Cost: bigger payloads and model-bound latency — accepted.

## 3. Request construction

```jsonc
{
  "model": "deepseek-v4-flash-vision-exp",
  "temperature": 0.7,            // F7.3
  "max_tokens": 4096,            // F7.3
  "response_format": { "type": "json_object" },
  "messages": [
    { "role": "system", "content": "<Nisaba system prompt, §5>" },
    { "role": "user",  "content": [
        { "type": "text",  "text": "<question wrapper + transcript, §6>" },
        { "type": "image_url", "image_url": { "url": "<data URI>" } }
      ] }
  ]
}
```

**Cancellation (F3.2):** the OkHttp call runs in a `CoroutineScope` owned by the summon job; cancel-on-resume cancels the job → OkHttp aborts the call → partial response discarded. A cancelled group is recorded in the transcript (status `cancelled`, 05 §2).

## 4. System prompt (v1 draft — iterate in M3)

```
You are Nisaba, the ancient Sumerian goddess of writing, and the soul of an
enchanted notebook. The user writes by hand on the page and pauses; you read
their handwriting and answer IN INK beneath their writing.

Rules:
1. Answer in English unless the user's writing is in another language, in
   which case answer in that language.
2. Be warm, concise, and precise. Answer what was asked; do not pad.
3. Structure your entire reply as ONE valid JSON object, no markdown fences,
   no text outside the JSON:
   {"blocks": [ <block>, ... ]}
   Each block is one of:
   - {"type":"text","markdown":"<text>"}          — paragraphs, lists, emphasis
   - {"type":"drawing","svg":"<svg>…</svg>"}      — a picture you draw
   - {"type":"table","title":"…","headers":[…],"rows":[[…]]}
4. Use a drawing block for diagrams, graphs, sketches, maps, math figures,
   or anything visual. Use a table block for structured comparisons or data.
5. Text block markdown may use only: ## headers, **bold**, *italics*,
   - bullet lists, 1. numbered lists, and line breaks. No links, no code.
6. SVG rules (the app renders it as ink):
   - <svg viewBox="0 0 1000 1000"> only; width/height ignored.
   - Elements allowed: <path>, <polyline>, <line>, <rect>, <circle>, <ellipse>.
   - Stroke color only (#3A2E25); stroke-width 4–10; stroke-linecap="round";
     stroke-linejoin="round"; fill only for simple closed shapes (use #3A2E25);
     opacity only 1.0 or 0.35 (for hatching/shading).
   - NO <text>, NO gradients, NO filters, NO transforms beyond translate/scale.
   - Keep it under 40 elements and 2500 characters total.
7. Tables: ≤ 8 columns, ≤ 20 rows, short cell text (≤ 40 chars). Title optional.
8. 1–8 blocks per answer. Put the most important content first.
9. If the handwriting is illegible or the request is unclear, say so kindly in
   a text block ("Your ink is hard to read here — could you write it again?").
```

## 5. User message composition

```
Question wrapper (text part):
  "The image is the current page of the notebook. The handwritten marks are the
   user's message. If there are clear questions/requests, answer them. Ignore
   doodles and margin scribbles. {language instruction}. {note}"

Transcript context (prepended to the wrapper when ≥ 2 exchanges exist):
  "Earlier in this notebook:"
  Q: <summary of previous question page (text-only; page images are not resent)>
  A: <the answer blocks' text content, tables flattened, drawings described as
      "«drawing»">          // last ≤ 10 exchanges, newest last
```

- Previous pages are **not** re-sent as images (payload discipline); their text content is enough for continuity (F5.2). Re-sending the current page's image every summon keeps vision fresh.
- When the user summons again after an error (F3.3), the same composition is re-sent verbatim (idempotent retry).

## 6. Response contract (verified at parse time)

`AnswerParser` validates:

```
AnswerJson:
  blocks: Block[]            (required, 1..8)
Block:
  type: "text" | "drawing" | "table"
  text:     { markdown: string, max 8000 chars }
  drawing:  { svg: string, max 2500 chars, validated against §4.6 rules }
  table:    { title?: string, headers: string[] (≤8), rows: string[][] (≤20, ≤40 chars/cell) }
```

- Strict parse with `kotlinx.serialization`; on failure → **one repair retry** (same image+transcript, system prompt appended: *"Your previous reply was not valid JSON. Return ONLY the JSON object."*); on second failure → error state (09 §7) with "Write it again" retry.
- Block-level validation: a malformed *drawing* is dropped with a "«the drawing smudged»" placeholder rather than failing the whole answer (robustness).

## 7. Errors & limits

| Condition | Detect | Action |
|---|---|---|
| No network | IOException | Magical error + retry (03 §6) |
| 401 | HTTP 401 | "The quill has no ink — check your API key." → Settings shortcut |
| 429 | HTTP 429 (+Retry-After) | "The scribe is busy." auto-retry once after 5 s |
| 4xx other | code | Surface message with code |
| 5xx / timeout | 500s / 120 s read timeout | Retry once; then error state |
| Malformed JSON | parse fail | Repair retry once (above) |
| Oversized image | request build | Downscale to ≤ 1.5 MP before send (never fail) |

## 8. Cost & latency (estimates, to measure in M3)

| Metric | Estimate |
|---|---|
| Image payload | 200 KB – 1.5 MB per summon |
| Tokens out (typical answer) | 200–1500 |
| Latency | 10–60 s typical (vision + generation); budget 120 s |
| Cost per summon | negligible for personal use; measure with real key |

**v2 ideas (noted):** streaming responses (render blocks as they arrive), `json_schema` response_format if the provider supports it (stronger validation than prompt), local fallback model (unsuitable for Boox-class hardware).

## 9. Privacy

- The page image + transcript leave the device **only** on summon, with the visible "the ink stirs…" indicator (F3.5). Documented in-app ("Your page is sent to DeepSeek when you summon").
- No logging of prompts/images; key never logged; no telemetry SDKs (03 §7).
