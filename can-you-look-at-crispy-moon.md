# Nisaba — full-codebase defect audit and fix plan

## Context

You asked for a whole-codebase review with detailed tasks to fix what's wrong.
`app/` is ~6.1k lines of Kotlin across 35 main files and 18 test files. The
audit read every main source file, ran `git diff` on the two dirty files
(`InkSurfaceView.kt`, `SummonInk.kt`), grepped git history for secrets, and
cross-checked all 13 design docs against the code.

**Headline:** the architecture is sound — layers are honest, leaves are real,
errors are typed, `.env` never leaked into git, the JSON write really is
atomic, OkHttp bodies really are closed. The problems are concentrated in four
places: **stroke identity**, **view lifecycle**, **the main thread**, and
**docs that describe an app that doesn't exist**.

**One bug destroys user data on every use of Undo.** That is P0-1 below.

Tiers: **P0** = data loss / wrong output, fix before the next Boox session ·
**P1** = leaks + main-thread stalls, the "feels broken on e-ink" tier ·
**P2** = security & release readiness · **P3** = tests · **P4** = docs drift.

---

# P0 — Data loss and wrong output

### P0-1. Every stroke is saved with the id `"s-preview"` — one Undo erases the whole page

`app/src/main/java/com/nisaba/ink/StrokeBuilder.kt:51`

```kotlin
fun build(): Stroke? { val s = buildPreview() ?: return null; … }   // :40
fun buildPreview(): Stroke? {
    return Stroke(id = "s-preview", …)                              // :51
```

`build()` is the only producer of persisted strokes (`InkSurfaceView.finishStroke():506`
→ `NotebookScreen.onStrokeFinished():203` → `NotebookSession.addStroke():100`).
`java.util.UUID` is imported at `StrokeBuilder.kt:5` and never used — id
generation was dropped.

Undo matches by id:

```kotlin
// NotebookSession.kt:105
undo = { page.strokes = page.strokes.filterNot { it.id == stroke.id } },
// NotebookSession.kt:121 (erase redo)
redo = { page.strokes = page.strokes.filterNot { s -> removed.any { it.id == s.id } } },
```

**Failure:** write five words → five strokes, all `id = "s-preview"` → tap Undo
once → `filterNot` removes **all five**. Page goes blank; autosave persists the
empty page 800 ms later. Redo restores one. Unrecoverable.

**Fix (two parts, ~6 lines):**
1. `NotebookSession.addStroke`/`eraseStroke`: capture the previous list and
   restore it, instead of filtering by id. Strokes are already replaced
   wholesale (`page.strokes = page.strokes + stroke`), so the snapshot is a
   free reference copy — and it heals notebooks already on disk that are full
   of duplicate ids.
   ```kotlin
   val before = page.strokes
   page.strokes = before + stroke
   push(reason = Reason.STROKES, undo = { page.strokes = before },
        redo = { page.strokes = before + stroke })
   ```
2. `StrokeBuilder.build()`: return `s.copy(id = "s-" + UUID.randomUUID().toString().take(8))`.
   Leave `buildPreview()` as-is — it is never persisted.

**Verify:** new JVM test in `NotebookSessionTest` — add 3 strokes, undo once,
assert 2 remain and the surviving ids are the first two. New
`StrokeBuilderTest` — two consecutive builds have different ids.

---

### P0-2. Turning to a page with no answers leaves the previous page's answer ink on screen

`app/src/main/java/com/nisaba/ui/NotebookScreen.kt:336`

```kotlin
private fun renderAnswerInk(animateLast: Boolean) {
    val page = session?.currentPage() ?: return
    val groups = page.answers.filter { it.status == "complete" }
    if (groups.isEmpty()) return          // ← never calls setAnswers(emptyList())
```

`Reason.PAGE`/`Reason.STRUCTURE` (`:126-133`) set `inkView.page` then call this.
If the new page has no complete answers, `setAnswers` is never called, so
`answerLayouts` and `answerDisplayBottom` in `InkSurfaceView` keep page 1's
values.

**Failure:** summon an answer on page 1 → next page → page 2 renders blank
parchment **with page 1's answer text, tables and drawings still inked below
it**, and `onMeasure` (`InkSurfaceView.kt:324-334`) still sizes to page 1's
`answerDisplayBottom`, so page 2 scrolls far past its own bottom.

**Fix:**
```kotlin
if (groups.isEmpty()) { inkView.setAnswers(emptyList(), page.height.toFloat(), false); return }
```

**Verify:** instrumented — bind a 2-page notebook with an answer on page 1,
turn the page, assert `inkView.answerItemCount == 0`.

---

### P0-3. Setting `page` switches the transform to contain-fit — all ink and touch coordinates shift

`app/src/main/java/com/nisaba/ink/InkSurfaceView.kt:44`

```kotlin
set(value) {
    …
    transform.setViewport(width, height)   // contain-fit + vertical centering
```

Everywhere else runs fit-width: `onSizeChanged():319` calls
`setViewportFitWidth(w)`, which forces `offsetY = 0f`
(`PageTransform.kt:34-39`). `setViewport` instead computes
`offsetY = (viewHeight - pageHeight * scale) / 2f` (`PageTransform.kt:26`).

**Failure:** page 1 has answers, so the view's measured height is ~2× the page
height. Turn to page 2 → the setter runs `setViewport(w, tallHeight)` →
`offsetY` becomes several hundred px. Combined with P0-2 the measured height
never changes, so `onSizeChanged` never fires to correct it. Page 2's
background, rules and strokes draw shifted down, and `toPageX/toPageY`
(`:467-468`) map the stylus to the wrong page coordinates — **ink appears
offset from the pen tip, permanently**.

**Fix:** `transform.setViewportFitWidth(width)` in the setter. One line.

**Verify:** extend `PageTransformTest` to cover `setViewportFitWidth` (see
P3-2); instrumented — set a page, assert `transform` offsetY is 0.

---

### P0-4. Integer overflow in the reveal accumulator — the second answer on a page never animates

`app/src/main/java/com/nisaba/ink/InkSurfaceView.kt:370, 401`

```kotlin
val layoutTicks = if (isLast) layout.totalTicks else Int.MAX_VALUE   // :370
var itemStart = cumTicks
…
cumTicks += layoutTicks                                              // :401 — overflows
```

**Failure:** page already has one complete answer; summon again →
`answerLayouts = [A, B]`, `animateLast = true`. After A, `cumTicks = Int.MAX_VALUE`.
For B, `itemStart = Int.MAX_VALUE`, `answerTicks` starts at 0 → `local == 0f`
for every item → **B is invisible for the whole reveal**, then snaps in at
once. With three answers `cumTicks` wraps to `-2` and everything reveals
instantly. Uncovered — `InkAnswerTest` only ever passes one layout.

**Fix:** delete `cumTicks` entirely and start `itemStart = 0` inside each
layout's loop. Only the last layout ever animates, and its item offsets are
relative to its own start — the accumulator was never needed.

**Verify:** instrumented — `setAnswers(listOf(a, b), …, animateLast = true)`,
tick once, assert some of B's items are partially revealed (`local > 0`).

---

### P0-5. Every cancel-by-writing writes a junk answer group into the notebook forever

`app/src/main/java/com/nisaba/ui/NotebookScreen.kt:291-293` + `notebook/SummonController.kt:42-51`

```kotlin
} catch (e: kotlinx.coroutines.CancellationException) {
    if (generation != summonGeneration) return@launch   // superseded
    recordAborted(s, groupId, "cancelled", e.message)
}
```

`SummonController.onStrokePoint` cancels the in-flight job **without** bumping
`summonGeneration` (only `startSummon():225` does), so the guard doesn't fire
and `recordAborted` appends an `AnswerGroup(status = "cancelled")`, which
`changed(ANSWERS)` then persists.

**Failure:** the normal rhythm is write → pause 3 s → resume. Every cycle
writes one dead group into `page.answers` forever. `renderAnswerInk:335`
filters to `status == "complete"`, so the user never sees them —
`notebook.json` just grows without bound, feeding P1-3 and P1-4.

**Fix:** user-cancellation is not an event worth persisting. In the
`CancellationException` branch: clear `pendingSummon`, call
`inkView.endSummonInk(keep = false)`, and rethrow `e` (structured concurrency
requires the cancellation to propagate). Keep `recordAborted` for the
`"error"` path only.

**Verify:** extend `SummonFlowTest` — start a summon, write mid-flight, assert
`page.answers.isEmpty()`.

---

### P0-6. `save()`'s rename fallback deletes the only good copy of the notebook

`app/src/main/java/com/nisaba/notebook/NotebookStore.kt:94-97`

```kotlin
if (!tmp.renameTo(final)) {
    final.delete()
    if (!tmp.renameTo(final)) throw IllegalStateException("rename failed for ${nb.id}")
}
```

The point of tmp+rename is that `notebook.json` is never invalid. This fallback
deliberately deletes the last known-good file and retries the operation that
just failed. If the second rename also fails (disk full, EACCES, `tmp` gone),
the notebook is **permanently lost** — `list():56` filters on
`File(it, "notebook.json").exists()`, so it silently vanishes from the shelf
with no error shown.

**Fix:** delete the `final.delete()` fallback; a single failed rename throws
and leaves `notebook.json` intact. Then implement the recovery docs/05:120
already promises: in `parse()`, if `notebook.json` is missing but
`notebook.json.tmp` exists, read the tmp.

**Verify:** `NotebookStoreTest` — write a notebook, delete `notebook.json`,
rename a valid file to `notebook.json.tmp`, assert `load()` recovers it.

---

### P0-7. The summon blot never clears — it follows you to the next page

`app/src/main/java/com/nisaba/ink/InkSurfaceView.kt:150-163, 189-199`

`endSummonInk(keep = true)` parks the state machine in `SETTLED`
(`:153`), and **nothing ever leaves `SETTLED`** — not `setAnswers`, not the
`page` setter, not `onClose`. `drawSummonInk` (`:202`) keeps painting the
thread and blot at their page-space coordinates. Separately,
`endSummonInk(keep = false)` from `SETTLED` sets `RECEDE` without re-posting
`inkTick` (callbacks were removed at `:154`), so the thread freezes mid-screen
at `threadProgress = 1`.

**Fix:** (a) reset `inkPhase = IDLE; threadPath = null; inkHandler.removeCallbacks(inkTick)`
in the `page` setter; (b) in the `RECEDE` branch of `endSummonInk`, re-post
`inkTick`.

**Verify:** extend `SummonInkTest` — settle the ink, set a new page, assert
`isSummonInkActive == false`.

---

### P0-8. A typed API key is silently discarded when you leave Settings

`app/src/main/java/com/nisaba/ui/SettingsScreen.kt:150-155, 189-191`

```kotlin
keyField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) settings.apiKey = keyField.text.toString() }
…
fun onClose() { scope.cancel() }
```

Persistence happens **only** on focus loss. The back affordance is a `TextView`
with a click listener (`:62-68`) — a `TextView` is not focusable in touch mode,
so tapping it never moves focus off the `EditText`, no focus-change fires, and
`onClose()` doesn't flush either.

**Failure:** paste your key → tap ← → summon → *"the quill has no ink"*. Same
loss for `baseField`, `modelField`, `idleField`.

**Fix:** extract the four assignments into `private fun persist()`; call it
from `onClose()` and from the back `TextView`'s click listener before
`onBack?.invoke()`. `testConnection()` already does this inline (`:161-163`) —
reuse the same function there.

**Verify:** instrumented — bind, `setText` on the key field, call `onClose()`,
assert `settings.apiKey` matches.

---

# P1 — Leaks, lifecycle, and the main thread

### P1-1. `InkSurfaceView` has no `onDetachedFromWindow` — the summon ticker runs forever and leaks the Activity

`app/src/main/java/com/nisaba/ink/InkSurfaceView.kt:112-121` (and `:233`, `:262`, `:78`)

```kotlin
private val inkTick: Runnable = object : Runnable {
    override fun run() {
        advanceInk()
        if (inkPhase != InkPhase.IDLE && inkPhase != InkPhase.SETTLED)
            inkHandler.postDelayed(this, INK_STEP_MS)   // self-reposting, 100 ms
        invalidate()
    }
}
```

Only `endSummonInk()` stops it, and `NotebookScreen.onClose():392-400` bumps
`summonGeneration` *before* cancelling, so the coroutine bails at the
generation check (`:292`) before it can call `endSummonInk`.

**Failure:** start a summon, press Back. `inkPhase` stays `FLOW`; `inkTick`
reposts on the main looper every 100 ms **for the life of the process**,
calling `invalidate()` on a detached view, holding `InkSurfaceView` →
`NotebookScreen` → `MainActivity`, plus the `bgLayer` bitmap (~5 MB RGB_565 at
Boox resolution, 10-15 MB with answers). Open and close five notebooks with a
pending summon each → ~50 MB of unreclaimable bitmap memory and a permanently
busy main looper.

**Fix:** one override:
```kotlin
override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    inkHandler.removeCallbacks(inkTick)
    answerHandler.removeCallbacks(answerTick)
    flushHandler.removeCallbacks(flushRunnable)
    flushHandler.removeCallbacks(cleanRunnable)
    inkPhase = InkPhase.IDLE
    threadPath = null
    bgLayer?.recycle(); bgLayer = null
}
```

---

### P1-2. `MainActivity.onDestroy` never closes the notebook or settings screen

`app/src/main/java/com/nisaba/MainActivity.kt:57-60`

```kotlin
override fun onDestroy() { super.onDestroy(); importScope.cancel() }
```

`NotebookScreen.onClose()` — which cancels `scope`, shuts down
`summonController`, closes the session and calls `bgCache.shutdown()` — is
reachable only from `showShelf()`/`showSettings()`.

**Failure:** kill the activity with a notebook open (system reclaim, `finish()`,
a config change outside the `configChanges` list) → the `SupervisorJob +
Dispatchers.Main` scope stays alive with an in-flight HTTP call, the `nisaba-bg`
executor thread is never shut down (one leaked thread per notebook opened), and
the whole `Notebook` plus up to 3 cached bitmaps are retained.

**Fix:** `notebookScreen?.onClose(); settingsScreen?.onClose()` in `onDestroy`
before `importScope.cancel()`. `onClose()` is already idempotent.

---

### P1-3. Autosave does JSON + `fsync` + a full-resolution bitmap decode + PNG encode **on the UI thread**, every 800 ms

`app/src/main/java/com/nisaba/notebook/NotebookStore.kt:83-118`, reached from
`NotebookSession.autosave:56` via `HandlerSaveScheduler` (`SaveScheduler.kt:17`,
`Looper.getMainLooper()`).

```kotlin
FileOutputStream(tmp).use { out { …; out.fd.sync() } }   // blocking fsync, UI thread
writeThumbnail(nb)                                        // decode + render + PNG, UI thread
…
BitmapFactory.decodeFile(File(dirOf(nb.id), rel).absolutePath)   // :106 — no Options → ARGB_8888
```

A4 @200 dpi = 1654×2339 ARGB_8888 ≈ **15.5 MB decoded, every save**.
`InkSurfaceView.onStrokePoint` calls `requestSave()` per point, so the 800 ms
debounce fires right after every writing pause — the exact moment the summon
animation is supposed to start. On a low-RAM device the decode also OOMs, and
`catch (_: Exception)` at `:115` does **not** catch `OutOfMemoryError`, so it
crashes instead of degrading.

This also directly contradicts `docs/03-architecture.md:100` ("Storage I/O:
`Dispatchers.IO`, serialized through a single `NotebookStore` mutex") and
`docs/04:187` ("Autosave serialize ≤ 50 ms (**IO thread**)").

**Fix (three parts):**
1. Split `save()`: serialize to a `String` on the caller thread (the model is
   mutated from main, so serialization must stay there), then hand the write +
   rename to a single-thread executor owned by `NotebookStore`. Single-thread
   ordering is the mutex docs/03 already claims.
2. Write the thumbnail **only from `saveNow()`** (page turn, screen close,
   `onPause`) — not from the 800 ms debounce. Add a `thumbnail: Boolean = false`
   parameter to `save()`.
3. Decode the background with `BitmapFactory.Options.inSampleSize` sized for
   `PageThumbnail`'s 480×360 target, and catch `Throwable` rather than
   `Exception` in `writeThumbnail`.

**Verify:** `NotebookStoreTest` — assert `thumb.png` is not rewritten by a
debounced save but is by `saveNow()`; manual — write continuously on the Boox
and confirm no stall at the pause.

---

### P1-4. Opening the shelf fully deserializes every notebook — all strokes — on the main thread, to read four fields

`app/src/main/java/com/nisaba/notebook/NotebookStore.kt:53-66` + `ui/ShelfScreen.kt:92`

```kotlin
val nb = parse(File(dir, "notebook.json"))
NotebookSummary(nb.id, nb.title, nb.updatedAt, nb.lastOpenedPage)
```

A written-on 300-page imported PDF holds hundreds of thousands of
`StrokePoint` objects. `ShelfScreen.bind()` calls this synchronously for every
notebook on the shelf. App-open latency scales with total ink ever written.
`ShelfScreen.kt:130-135` compounds it: `BitmapFactory.decodeFile` per bind, no
cache, no `inSampleSize`, and `submit()` calls `notifyDataSetChanged()` so
every refresh re-decodes everything visible.

**Fix:**
1. Add a private `@Serializable data class NotebookHead(val id: String, val title: String, val updatedAt: String, val lastOpenedPage: Int = 0)`
   and decode with it in `list()` — `ignoreUnknownKeys = true` is already set,
   so pages are skipped without allocating.
2. Call `store.list()` off the main thread in `ShelfScreen.bind()` and post the
   result.
3. Give the adapter a small `LruCache<String, Bitmap>` and decode thumbnails
   with `inSampleSize`.

---

### P1-5. `PageBackgroundCache` recycles bitmaps the view and the in-flight summon still hold

`app/src/main/java/com/nisaba/pdf/PageBackgroundCache.kt:34-42`

```kotlin
override fun removeEldestEntry(eldest: …): Boolean {
    if (size > maxEntries) { eldest.value.recycle(); return true }
```

The cache hands out raw `Bitmap` references with no refcount.
`NotebookScreen.loadBackground():378-382` stores one in `inkView.background`;
`startSummon():244-247` holds one across a suspension point. The recycle runs
on the `nisaba-bg` thread while `onDraw`/`rebuildBgLayer` may be reading it on
the UI thread.

**Failure:** summon on page 5, flip through 6/7/8 while the request is in
flight → page 5 is evicted and recycled → `InkPageRenderer.render` calls
`canvas.drawBitmap` → `IllegalArgumentException: Cannot draw a recycled Bitmap`.
Hard crash.

**Fix:** delete the `recycle()` call — let GC reclaim them. Entries are
RGB_565 and `maxEntries = 3`; explicit recycling buys nothing and costs a
crash. Same for `shutdown():91-93` (which today doesn't recycle survivors
anyway, so behaviour is already inconsistent).

---

### P1-6. `getSuspending` is not cancellable and throws after shutdown

`app/src/main/java/com/nisaba/pdf/PageBackgroundCache.kt:62-65`

```kotlin
suspend fun getSuspending(index: Int): Bitmap? =
    kotlin.coroutines.suspendCoroutine { cont -> get(index, onReady = { cont.resume(it) }) }
```

Plain `suspendCoroutine`. Cancel-on-resume during a background decode doesn't
abort it — and `AGENTS.md:43` makes cancellation-safety binding. Worse, if
`shutdown()` has already run (`NotebookScreen.onClose():399`, after
`scope.cancel()` at `:398` with no ordering guarantee), `executor.execute` at
`:52` throws `RejectedExecutionException` synchronously.

**Fix:** `suspendCancellableCoroutine`, and guard `executor.isShutdown` in
`get()` → invoke `onReady(null)`.

---

### P1-7. The page image sent to DeepSeek is 2× the documented size and peaks at ~30 MB of transient allocation

`app/src/main/java/com/nisaba/llm/PageImagePreparer.kt:21-43`

```kotlin
const val MAX_LONG_EDGE = 2000        // docstring claims "≤ ~1.5 MP, ≤ ~2 MB payload"
…
bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
return "data:image/png;base64,$b64"
```

Default page 2480×1860 → scale 0.806 → **2000×1500 = 3.0 MP**, ARGB_8888
(`InkPageRenderer.kt:51`) = **12 MB**. Then simultaneously live: the
`ByteArrayOutputStream` buffer, its full `toByteArray()` copy, the base64
`String` (1.33×), the data-URI `String` (another copy), the serialized
`ChatRequest`, and its `toRequestBody()` bytes. `quality = 90` is **ignored for
PNG** — it's lossless, so there is no size control at all.
`docs/07-llm-integration.md:26` specifies **1600 px**.

**Failure:** summon on a dense scanned-PDF page with 3 cached backgrounds
resident → `OutOfMemoryError`, which is an `Error` and so is **not** caught by
`catch (e: Exception)` at `NotebookScreen.kt:294` → the app dies mid-summon.

**Fix:** `MAX_LONG_EDGE = 1600` (matches docs); switch to
`Bitmap.CompressFormat.JPEG` at quality 85 and `data:image/jpeg;base64,` —
handwriting on parchment compresses an order of magnitude better and the
`quality` parameter starts doing something; fix the docstring's MP/payload
numbers. Separately, widen the summon's catch in `NotebookScreen.kt:294` to
`Throwable` so an OOM surfaces as a failed summon rather than a crash.

---

### P1-8. PDF import reopens and re-parses the whole document once per page — O(n²) on the target device

`app/src/main/java/com/nisaba/pdf/PdfImporter.kt:104-107, 129-141` + `pdf/PdfPageRenderer.kt:44-45, 66`

```kotlin
// PdfImporter.kt:129-133 — a fresh PdfRenderer per page, just to read width/height
private fun pageSpaceOf(pdfFile: File, index: Int): Pair<Int, Int> {
    val fd = ParcelFileDescriptor.open(pdfFile, MODE_READ_ONLY)
    val renderer = PdfRenderer(fd)
// PdfPageRenderer.kt:66 — PdfBox re-parses the entire document for every page
PDDocument.load(file).use { doc ->
```

`renderAll` already holds an open `PdfRenderer` (`:99`) but `renderPage` ignores
it and the seam opens its own; `pageSpaceOf` opens a third. On the PdfBox
fallback path — which is *the* Boox Note Air 5c path this file exists for
(`PdfPageRenderer.kt:15-20`) — `PDDocument.load` re-parses the xref and object
graph N times for N pages.

**Failure:** a 300-page lecture PDF = 300 full document parses plus 600
framework opens. The import dialog sits for minutes. `renderWithFramework:52-55`
additionally keeps the 15.5 MB ARGB_8888 render and its RGB_565 copy alive
simultaneously, and `looksBlack` calls `getPixel` ~15k times per page.

**Fix:** open both handles once. Give `PdfPageRenderer` a `Session` (an
`AutoCloseable` holding the `PdfRenderer` and a lazily-opened `PDDocument`) and
have `PdfImporter.renderAll` wrap the loop in `use { }`. Read page dimensions
from the already-open renderer and delete `pageSpaceOf`. Recycle the ARGB
render immediately after the RGB_565 copy.

---

### P1-9. A single-dot tap leaves the e-ink panel stuck in fast (A2) refresh mode

`app/src/main/java/com/nisaba/ink/InkSurfaceView.kt:502-512`

```kotlin
private fun finishStroke() {
    …
    val stroke = current.build() ?: return         // ← :506 bails out here
    onStrokeComplete?.invoke(stroke)
    invalidate()
    flushHandler.postDelayed(cleanRunnable, CLEAN_PASS_MS)   // only place disableFastMode runs
}
```

`Epd.enableFastMode(this)` fires on every `ACTION_DOWN` (`:457`);
`Epd.disableFastMode` exists **only** inside `cleanRunnable` (`:93`).
`build()` returns null whenever `pts.size < 2` (`StrokeBuilder.kt:49`).

**Failure:** tap the page once with the stylus (a dot, an accidental tap) → the
clean pass is never scheduled → the view stays in EPD fast mode with heavy
ghosting until the next *successful* stroke. `ACTION_CANCEL` (`:484-492`) has
the same hole. Secondary: `build()` returns before `started = false`/`pts.clear()`,
leaving the builder half-started with a stale point.

**Fix:** move the `removeCallbacks`/`postDelayed(cleanRunnable, …)` pair above
the `?: return`, add the same to the `ACTION_CANCEL` branch, and make
`StrokeBuilder.build()` reset state even when it returns null.

---

### P1-10. HTTP client: no write/call timeout, no retry, a new client per connection test

`app/src/main/java/com/nisaba/llm/DeepSeekClient.kt:29-32` + `ui/SettingsScreen.kt:171`

```kotlin
private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()
```

No `writeTimeout` (OkHttp default 10 s) while POSTing a multi-MB base64 body
over a weak connection — that alone will fail summons on tethered Wi-Fi. No
`callTimeout` as an overall bound. No retry/backoff for 429 or 5xx, though
`docs/03:126`, `docs/07:131` and `docs/09:92` all promise "auto-retry once
after 5 s". And every "Test the connection" tap builds a fresh
`DeepSeekClient` → a fresh `Dispatcher` executor, `ConnectionPool` and cleanup
thread, never shut down.

**Fix:** `writeTimeout(60s)` + `callTimeout(180s)`; hoist the `OkHttpClient`
into a `companion object` singleton shared by all `DeepSeekClient` instances;
add one retry with a 5 s delay on HTTP 429/5xx (`docs/07 §6`).

---

### P1-11. `runCatching` swallows `CancellationException`, then writes to a detached view

`app/src/main/java/com/nisaba/ui/SettingsScreen.kt:169-186` (same shape at `MainActivity.kt:123, 142`)

`runCatching` catches `Throwable`, including the `CancellationException` thrown
when `onClose()` cancels the scope. The coroutine then continues past the catch
and sets text on a detached view — the user sees
*"No answer — StandaloneCoroutine was cancelled"*.

**Fix:** rethrow `CancellationException` before the `isSuccess` branch, or
replace `runCatching` with an explicit `try/catch (e: Exception)` that lets
cancellation propagate. `AGENTS.md:43` already requires this
("`CancellationException` always propagates").

---

# P2 — Security and release readiness

### P2-1. The debug APK embeds the live API key, and the same variant permits cleartext HTTP

`app/build.gradle.kts:49` + `app/src/debug/AndroidManifest.xml:7` + `llm/AppSettings.kt:38-40`

`.env` is correctly gitignored and **has never been tracked** — `git log --all -p -- .env`
is empty and the only `sk-` in history is the `sk-your-key-here` placeholder in
`.env.example`. So this is an artifact-distribution risk, not a repo leak. But
`BuildConfig` string constants are plain UTF-8 in `classes.dex` and minification
is off, so `strings nisaba-debug.apk | grep sk-` recovers the key in one
command — and the debug APK is what gets sideloaded on the Boox. Compounding
it, `baseUrl` is a free-text field with no scheme validation while the debug
variant allows cleartext, so a typo'd `http://…` sends
`Authorization: Bearer sk-…` in plaintext.

**Fix:** keep the .env convenience (it's the right call for a keyboard-less
device), but bound it: add a debug `network_security_config.xml` allowing
cleartext only for `10.0.2.2` and `localhost`; reject non-`https` base URLs in
release; document the extraction risk in README §"Development secrets".

### P2-2. The release build is signed with the public Android debug key

`app/build.gradle.kts:54-56` — `signingConfig = signingConfigs.getByName("debug")`,
`isMinifyEnabled = false`, `proguardFiles(…)` declared but inert.
`docs/10-build-deploy.md:47-58` already specifies the intended
`signingConfigs.create("release")` + gitignored `keystore.properties`; none of
it exists. **Fix:** implement docs/10 §3 as written; enable minify with
keep-rules for kotlinx.serialization and PdfBox. *(This is M6 scope — do it
when you cut the first real APK, not before.)*

### P2-3. No lint gate, and the checked-in lint report predates the code

No `lint { }` block in `app/build.gradle.kts`, so lint can never fail a build.
`app/build/reports/lint-results-debug.txt:128` references
`render/DrawingInkView.kt` — a file that no longer exists — and line 228
references `R.string.stir_scribes`, deleted from `strings.xml`. The "lint clean"
claims in `AGENTS.md:53`, `docs/11:72` and `docs/architecture-review.md:7` are
unverified against HEAD; that stale run reported **0 errors, 65 warnings**.
**Fix:** add `lint { abortOnError = true; baseline = file("lint-baseline.xml") }`,
regenerate the baseline, delete the stale committed report.

### P2-4. `EncryptedSharedPreferences` 1.0.0 crashes permanently on Keystore invalidation

`gradle/libs.versions.toml:10` pins the 2021 release; `AppSettings.kt:17` uses
the deprecated `MasterKeys.getOrCreate`. A lock-screen change can invalidate the
master key, after which `create()` throws on **first settings access** — and
there is no try/catch, so the app is bricked until data is cleared.
**Fix:** wrap the `secure` lazy in try/catch → on failure delete the
`nisaba_secure` prefs file and recreate once; move to `MasterKey.Builder`
(security-crypto 1.1.0).

### P2-5. `schemaVersion` is write-only and `load()` is unguarded

`Models.kt:77` declares `schemaVersion` and nothing ever reads it (grep
confirms). `Notebook.id`/`title` have no defaults, so a renamed field throws
`MissingFieldException` — and `MainActivity.openNotebook:165` calls
`store.load(id)` with no try/catch → hard crash on open.
`docs/05-data-model.md:137` claims the opposite: *"Unknown-newer versions refuse
to open."*
**Fix:** check `schemaVersion > CURRENT` in `load()` and throw a typed
`UnsupportedSchemaException`; wrap `openNotebook` in try/catch → toast and stay
on the shelf.

### P2-6. No CI anywhere

`.github/` does not exist; no `.gitlab-ci.yml`, no `Jenkinsfile`. Meanwhile
`AGENTS.md:53` makes "full suite green; lint clean" part of Done — an
unenforced rule, which is how the drift in P4 accumulated.
**Fix:** one workflow running `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
Leave the instrumented suite out of CI initially — it needs an emulator and
takes minutes (P3-5).

---

# P3 — Tests

Actual counts (verified): **27 JVM tests / 4 classes, 51 instrumented / 13
classes**. Every published count in the repo is wrong (see P4-5).

### P3-1. `SummonController` — the core-loop state machine — has zero tests
`notebook/SummonController.kt`, zero references in any test file. Idle timer,
cancel-on-resume, `setPendingRequest`/`clearPendingRequest`, `manualSummon`,
`cancelAll`, `shutdown` — all only observed indirectly through the 20-second
`SummonFlowTest`. **Fix:** change the constructor's `handler: Handler` to the
existing `SaveScheduler` interface (`notebook/SaveScheduler.kt` already has
exactly `schedule`/`cancel`, and JVM tests already inject a synchronous fake)
— then test it on the JVM in ~40 lines. Reuse over invention.

### P3-2. The one `PageTransform` function production actually uses is untested
`PageTransformTest` covers only `setViewport`; `InkSurfaceView.onSizeChanged:319`
calls `setViewportFitWidth`. Five tests exercise a path the app doesn't take —
which is precisely why P0-3 went unnoticed. **Fix:** add fit-width cases
(offsetY always 0, scale clamped at `MAX_SCALE`, round-trip `toPageX/toViewX`).

### P3-3. Pure logic with no tests: `PromptBuilder`, `StrokeBuilder`, `Eraser`, `AppSettings`
All zero-Android or near-zero. `PromptBuilder.buildTranscriptContext` has real
branching (role dispatch, group lookup + `status == "complete"` filter,
`takeLast`, `«drawing»/«table»` fallbacks). `Eraser.distanceToSegment` has a
degenerate `len2 <= 0` branch and `t` clamping. `AppSettings` has
`effectiveApiKey()` fallback, `chatCompletionsUrl()` normalization and
`idlePauseSeconds.coerceIn(1, 30)` — exactly the paths that break a summon
silently. **Fix:** four small JVM test classes.

### P3-4. Tautological and no-op tests inflate the green count
- `ink/InkAnswerTest.kt:86` — `assertFalse(v.skipAnswerReveal().let { false })`
  asserts `false == false`; can never fail.
- `magic/SummonInkTest.kt:71` — `assertTrue(bounds.contains(…) || bounds.width() > 0f)`;
  the second disjunct is true for any non-degenerate path.
- `magic/SummonInkTest.kt:46-50` — "determinism" compares only path *length*,
  not geometry.
- `epd/EpdTest.kt:30` — literal `assertTrue(true)`.
- `epd/OecProbeTest.kt:31, 43, 72, 114` — 3 tests, all `assertTrue(true)`, all
  early-returning off-Boox; `dumpTransactionCodesAndSweepModes` writes 49
  refresh modes into a live system service. These are probe scripts, not tests.
- `pdf/PdfTestSupport.kt:18-24` — `assumeTrue(false)` when `dark > light*10`,
  i.e. it self-skips **exactly when the renderer is broken**. ~9 of 51
  instrumented tests assert nothing meaningful in the environments they run in.

**Fix:** make the first three assert real invariants; move the EPD/OEC probes
out of the test source set (a debug-variant `@Suppress`ed tool, or
`@Ignore("device probe")` with a reason) so "51 green" means something. Leave
`assumeRenders` alone — it's deliberate and documented — but stop counting the
soft-skipped tests as coverage.

### P3-5. Instrumented tests corrupt real app state and fail on re-run
- `pdf/PdfImporterTest.kt:128-134` — `assertNoOrphanedNotebooks()` asserts
  **zero** directories contain `import.pdf`, but two other tests in the same
  class create successful imports and never delete them. It passes only by
  JUnit4 method-ordering luck on a clean install and is **guaranteed to fail on
  a second run**.
- `llm/DeepSeekClientTest.kt:34-35` and `SummonFlowTest.kt:47-51` write into
  the production `EncryptedSharedPreferences`/`SharedPreferences`:
  `apiKey = "test-key-123"`, `baseUrl = <MockWebServer localhost>`,
  `idlePauseSeconds = 1`, and never restore them. After running the suite, the
  real app points at a dead localhost URL with a bogus key.
- `notebook/NotebookStoreTest.kt:33, 60` leaks 3+ notebooks per run; `:49`
  weakened its own assertion (`// exact number depends on shelf state`) to
  tolerate it.

**Fix:** add `testInstrumentationRunnerArguments["clearPackageData"] = "true"`;
snapshot-and-restore settings in `@After`; scope
`assertNoOrphanedNotebooks` to the ids the test created.

---

# P4 — Documentation drift

`AGENTS.md:4` binds every change to `docs/architecture.md`, so drift here isn't
cosmetic — it's the spec being wrong.

### P4-1. `docs/05-data-model.md` describes a JSON schema the app does not write
The doc uses snake_case and nested shapes; `Models.kt` serializes camelCase and
flat ones, with no `@SerialName` anywhere:
`schema_version`/`created_at`/`ink_color`/`last_opened_page`/`page_count` →
`schemaVersion`/`createdAt`/`inkColor`/`lastOpenedPage`/`pageCount`;
`"page_space": {width, height}` (`05:41`) → flat `width`/`height`
(`Models.kt:52-53`); `question_page_snapshot` (`05:75`) → `questionPage: Int`;
nested `TranscriptEntry.user{page_index,note}` (`05:92`) → flat
`role`/`at`/`pageIndex`/`answerGroupId`; `"eraser-stroke"` (`05:57`) →
`"eraser"`. Background filenames are documented `p000.png` (`05:14`,
`06:25`) but written `p0.png` (`PdfImporter.kt:62`). For a doc whose §1 calls
notebook.json "the single source of truth", this is the highest-value fix in
the set. **Fix:** regenerate §2 from the actual `@Serializable` classes.

### P4-2. The conventions doc names classes that do not exist
`docs/architecture.md:77` declares the `render` domain's public surface to be
`AnswerSectionRenderer, MarkdownInk` — neither exists (the real surface is
`AnswerInk`, `SvgInkRenderer`, `SvgPathParser`, `PageThumbnail`).
`architecture-review.md:14` cites `MarkdownInk` as an exemplary leaf; `:44`
(decision D-C) describes `DrawingInkView`, `TableInkRenderer` and `InkReveal`,
all collapsed into `AnswerInk` + the reveal loop in `InkSurfaceView:262-310`;
`:25` names `ThumbnailRenderer` (actually `PageThumbnail`).
`docs/03-architecture.md:36-72` lists **11 nonexistent files**
(`StrokeCapture`, `NotebookRepository`, `AnswerBlockView`, `MarkdownInk`,
`TableInkRenderer`, `InkReveal`, `FlourishDrawable`, `SummonIndicator`,
`settings/SettingsActivity`, `settings/AppSettings`, `export/PageExporter`) and
omits `epd/`, `ui/`, `NotebookSession`, `SaveScheduler`. `README.md:67-86` lists
a `settings/` package and a `keystore/` dir that don't exist and omits `ui/`,
`epd/`, `magic/`. **Fix:** regenerate all four layout sections from `find app/src/main`.

### P4-3. Hover-based palm rejection is documented as the primary defence and is dead code
`docs/04-eink-input.md:100-101`: *"While `hoverActive == true`, any `ACTION_DOWN`
that is not from the stylus tool type is treated as palm and ignored."* In
`InkSurfaceView.kt`, `penHovering` is written at `:437`/`:439` and **never
read** — palm rejection is entirely the tool-type check at `:444`. Worse, the
`ACTION_HOVER_*` branches are unreachable: Android dispatches those to
`onHoverEvent`/`onGenericMotionEvent`, neither overridden. Consequently
`InkSurfaceViewTest.kt:106-118` (`fingerTouchWhilePenHovering_isRejectedAsPalm`)
is a duplicate of `fingerTouch_neverDraws` and would keep passing if the hover
gate were deleted — which it effectively already is. The documented 1.5 s
post-pen-use finger lockout (`04:104`) is also absent.
**Fix:** delete `penHovering` and the unreachable branches, correct the class
KDoc (`:30`) and docs/04 §to say palm rejection is tool-type gating, and rename
the duplicate test. Tool-type gating is sufficient on Boox EMR — don't build
the hover path speculatively.

### P4-4. Documented behaviour that isn't implemented
- **429/5xx retry-once** — `docs/03:126`, `07:131`, `09:92`. Not implemented
  (see P1-10). Only the JSON *repair* retry exists.
- **1600 px image cap** — `docs/07:26` vs `MAX_LONG_EDGE = 2000` (P1-7);
  `07:30`'s "≤ ~1.5 MP" is really 3.0 MP.
- **tmp-file recovery on read** — `docs/05:120`. Not implemented (P0-6).
- **Ink colours** (F2.5: Ink Black / Sepia / Deep Blue) — `MagicTheme.inkColor()`
  exists and is never called; `InkSurfaceView.inkColor` is never assigned;
  `Stroke.color` is persisted and never read; lint flags `R.color.blue_ink`
  unused.
- **Settings F7.3** — `temperature`/`maxTokens` exist in `AppSettings` with no
  UI; there is no `language` setting; `soundsEnabled` is never read.
- **Rename a notebook** (F1.5), **onboarding** (`09:59`), **toolbar auto-hide
  after 4 s** (`09:101`), **per-answer regenerate/smudge controls** (F4.6,
  `08:16`, `09:39`), **PNG export / `PageExporter`** (`05:130`, F8), **cancel ✕
  on the waiting indicator** (F3.5), **the >30 s second-line copy**
  (`09:65`, whose `stir_scribes` string was deleted from `strings.xml`).
**Fix:** each is either a P1 code fix (the first three) or a docs correction
moving it from present-tense v1 contract to the M5 backlog. Don't implement
them now — the roadmap already schedules them.

### P4-5. Every published test count and status marker is wrong
`README.md:18` "28/28", `:19` "40/40", `docs/11:20` "40/40",
`architecture-review.md:7` "27 main + 11 test / 28/28", `:52` "Existing tests
(28)". Actual: 27 JVM + 51 instrumented, 35 main files, 18 test files.
`architecture-review.md:23` still calls `NotebookScreen` a "506-line
god-object" — it's 424 lines, and the predicted "~50% shrink" was ~16%.
`README.md:19` marks M4 ✅ while `docs/11:22-24` gives M4 no ✅ and lists
undelivered scope (parchment textures, e-ink reveal tuning, the N11 legibility
photo test). `docs/11:18` marks M3 ✅ while `docs/02:123` still lists OQ3 as
⏳ and `11:70` says "runs on the real device" is a precondition for *any*
milestone. **Fix:** one pass replacing counts with a `./gradlew test`-derived
number, and demote M3/M4 to "code complete, device pass pending" — which is
what the text already says in prose.

### P4-6. ADR-004 and the README still name `PdfRenderer` as the PDF engine
The app ships and depends on **PdfBox-Android**
(`libs.versions.toml:11`, `PdfPageRenderer.kt:65-74`, `NisabaApp.kt:13`) as the
working engine on the target device. `docs/06:22` and `docs/11:54` (R9) document
the seam correctly, but `docs/03:20`, ADR-004 (`03:149`) and `README.md:63`
were never updated, and the README tech snapshot omits PdfBox entirely — the
licence/size trade-off ADR-004 exists to reason about. **Fix:** amend ADR-004
with the black-render finding and the PdfBox fallback decision.

### P4-7. Loose ends (all Low, batch them)
- **`SettingsScreen.kt` is 14 hardcoded English strings** (`:63, 70, 78, 83, 88,
  96, 106, 113, 123, 141, 165, 168, 182, 184`); two of them duplicate copy that
  already exists in `strings.xml`.
- **`notebook_screen.xml:22, 43, 62, 78, 88, 98, 108`** — 7 hardcoded glyphs
  (`←◀▶↶↷✕⇢`) while `strings.xml:19-25` **already defines** `nb_back`,
  `nb_undo`, `nb_redo`, `nb_eraser`, `nb_new_page`, `nb_prev_page`,
  `nb_next_page` — and lint reports all seven as unused. The buttons also have
  no `contentDescription`, so they are unlabelled for TalkBack. **Fix: use the
  resources that already exist**; add contentDescription. This one is
  accessibility, not polish.
- `magic/MagicTheme.kt:10-16` duplicates every colour from `colors.xml:4-10` by
  hand ("Values mirror res/values/colors.xml") — two sources of truth.
- `MainActivity.kt:67` `onBackPressed()` is deprecated on targetSdk 35 →
  `OnBackPressedDispatcher`.
- `SettingsScreen.kt:43, 104` raw `Switch` under an AppCompat theme →
  `SwitchCompat`.
- `InkSurfaceView.kt:406` `drawRules` allocates a `Paint` per call from
  `onDraw` — contradicts `docs/04:135` ("no per-point allocations").
- `InkSurfaceView.kt:54` `var background: Bitmap?` shadows
  `View.getBackground(): Drawable`. It compiles and every call site resolves as
  intended, but it is a landmine — rename to `pageBackground`.
- `NotebookScreen.kt:421-423` — a `companion object` holding mutable
  `epdHintShown`: process-global UI state on a View class.
- **Clean:** zero TODO/FIXME/HACK/`@Ignore`/`println` anywhere in `app/src`;
  no commented-out code; the only 5 `Log.*` calls are in `epd/` and log nothing
  sensitive. `AGENTS.md:45`'s "never log keys/prompts/images" is genuinely
  honoured.

---

## Suggested order

1. **P0-1 → P0-8** in one commit series. These are small, independent, and P0-1
   is destroying data today.
2. **P1-1, P1-2, P1-5, P1-9** — the leak/crash quartet, all a few lines each.
3. **P1-3, P1-4, P1-7, P1-8** — the main-thread and memory work; this is the
   batch that changes how the app *feels* on the Boox.
4. **P3-1, P3-2, P3-3** alongside the fixes above — each new test should be
   written against the bug it would have caught.
5. **P2-6** (CI) before the docs pass, so the counts stay honest.
6. **P4** as one documentation commit.

Left out deliberately: P2-2 (release signing/minify) is M6 scope, and the
unimplemented F-features in P4-4 are already scheduled for M5 — those are
roadmap items, not defects.

## Verification

- `./gradlew :app:testDebugUnitTest` — the JVM suite, including the new
  `StrokeBuilderTest`, `EraserTest`, `PromptBuilderTest`, `AppSettingsTest`,
  `SummonControllerTest`, and the extended `PageTransformTest` /
  `NotebookSessionTest`.
- `./gradlew :app:connectedDebugAndroidTest` on an emulator — the full suite,
  **run twice back-to-back** to prove P3-5 (order-dependence) is fixed.
- `./gradlew :app:lintDebug` with `abortOnError` on and a fresh baseline.
- Manual on the Boox Note Air 5c, in this order:
  1. Write five words, tap Undo once → **four words remain** (P0-1).
  2. Summon on page 1, turn to page 2 → **blank page, no stale answer ink, no
     blot, pen tip aligned with the ink** (P0-2, P0-3, P0-7).
  3. Summon twice on one page → **the second answer draws itself in** (P0-4).
  4. Write, pause 3 s, resume writing → no stall at the pause (P1-3), and
     `notebook.json` gains **no** `"status":"cancelled"` group (P0-5).
  5. Tap a single dot with the stylus → the panel returns to normal refresh
     (P1-9).
  6. Enter an API key in Settings, tap ← immediately, summon → **it works**
     (P0-8).
  7. Start a summon, press Back → `adb shell dumpsys meminfo com.nisaba` shows
     no growing bitmap total across five repetitions (P1-1, P1-2).
  8. Import a 100+ page PDF and time it against the current build (P1-8).
