package com.nisaba.magic

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The summon's organic ink narrative (docs/09 §3): the ink does not send out a
 * thread, it CREEPS. Several tips set out from the tail of the user's last
 * stroke at once; most wander a little way and simply stop, while one channel
 * keeps going, sprouting finer branches as it advances, until it reaches the
 * spot where the answer will write itself and gathers into a blot that slowly
 * breathes while the scribe thinks. When the answer arrives the whole system is
 * drunk — it drains tail-first into the blot and the page is left clean.
 * The user's writing never disappears, and is never drawn over.
 *
 * The thread does not take the short way. It leaves along the direction the pen
 * was actually travelling, meanders through four uneven waypoints, hesitates
 * like ink wicking into paper fibres, and curls back into the locus rather than
 * arriving at it.
 *
 * Pure drawing (page space): the animation state lives in the ink view.
 * Every shape is deterministic — the same arguments always give the same path,
 * because ink that jitters between frames is ghosting hell on e-ink.
 */
object SummonInk {

    /** Steps the REACH phase takes; the view drives one per tick. */
    const val THREAD_STEPS = 26

    /** The wicking underlay: wide, pale, feathered like ink in paper fibres. */
    private val bleedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK_SOFT
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        alpha = 0x3A
    }
    private val threadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val forkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK_SOFT
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
    }
    private val beadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.FILL
    }
    private val blotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK_SOFT
        style = Paint.Style.FILL
        alpha = 0x2E
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MagicTheme.INK_SOFT
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    /**
     * Deterministic wobble in -1..1 — a sin-hash, not a Random: the same index
     * and seed must give the same offset on every frame.
     */
    private fun wobble(i: Int, seed: Float): Float {
        val x = i * 12.9898f + seed * 78.233f
        val s = sin(x) * 43758.547f
        return (s - kotlin.math.floor(s)) * 2f - 1f
    }

    /** 0 at both ends, 1 in the middle — keeps the texture pinned to endpoints. */
    private fun bell(t: Float): Float = sin(t * Math.PI.toFloat())

    /** The seed for one summon: the two endpoints, and nothing else. */
    private fun seedOf(fromX: Float, fromY: Float, toX: Float, toY: Float): Float =
        abs(fromX + fromY * 3f + toX * 7f + toY * 11f) % 97f

    // ------------------------------------------------------------- the thread

    /**
     * A wandering capillary from the last stroke's tail to the answer locus.
     *
     * [exitDx],[exitDy] is the direction the pen was travelling as the stroke
     * ended; the thread follows it before it turns toward the locus, which is
     * what makes it read as growing out of the word. A zero vector falls back
     * to heading straight for the first waypoint.
     */
    @JvmOverloads
    fun buildThread(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        exitDx: Float = 0f,
        exitDy: Float = 0f,
    ): Path {
        val dx = toX - fromX
        val dy = toY - fromY
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val seed = seedOf(fromX, fromY, toX, toY)
        val ux = dx / dist
        val uy = dy / dist
        val px = -uy
        val py = ux
        // Which way the whole meander leans — seeded, so it varies per summon.
        val hand = if (wobble(0, seed) >= 0f) 1f else -1f

        val pts = FloatArray(2 * (WAYPOINT_T.size + 4))
        var n = 0
        fun add(x: Float, y: Float) {
            pts[n * 2] = x
            pts[n * 2 + 1] = y
            n++
        }
        add(fromX, fromY)

        // 1. Leave along the pen's real exit tangent, not toward the target.
        // TWO collinear points, not one: a Catmull-Rom knot's tangent is set by
        // its neighbours, so with a single lead-out point the pull of the first
        // waypoint (which is (w0 - from)/6 — over 80px on a long reach) bends
        // the departure away from the pen's direction immediately, and the
        // whole effect is lost on a big page. Two points on the ray keep the
        // tangent on it at any distance.
        val exitLen = hypot(exitDx, exitDy)
        if (exitLen > 1e-3f) {
            val reach = (dist * EXIT_REACH).coerceIn(20f, 70f)
            val ex = exitDx / exitLen
            val ey = exitDy / exitLen
            // A whisker of perpendicular drift on the outer knot: shifts the
            // departure by only a few degrees, but a dead-straight run of ink
            // reads as a ruled line rather than something growing.
            val drift = reach * LEADOUT_DRIFT * (if (wobble(2, seed) >= 0f) 1f else -1f)
            add(fromX + ex * reach * 0.45f, fromY + ey * reach * 0.45f)
            add(fromX + ex * reach - ey * drift, fromY + ey * reach + ex * drift)
        }

        // 2 + 3. Uneven waypoints on alternating sides — the meander itself,
        // an order of magnitude past the old bell-windowed wobble.
        for (i in WAYPOINT_T.indices) {
            val t = (WAYPOINT_T[i] + wobble(i + 7, seed) * 0.04f).coerceIn(0.05f, 0.95f)
            val off = dist * WAYPOINT_AMP[i] * (0.6f + 0.4f * abs(wobble(i, seed))) *
                hand * (if (i % 2 == 0) 1f else -1f)
            add(fromX + dx * t + px * off, fromY + dy * t + py * off)
        }

        // 4. Curl in: hook past the locus, then come back into it.
        val over = (dist * CURL_OVERSHOOT).coerceIn(12f, 34f)
        add(toX + ux * over + px * over * 0.55f * hand, toY + uy * over + py * over * 0.55f * hand)
        add(toX, toY)

        return capillaryTexture(catmullRom(pts, n), seed, (dist * TEXTURE_AMP).coerceIn(1.5f, 4f))
    }

    /** Catmull-Rom through [n] points (flat x,y pairs), endpoints duplicated. */
    private fun catmullRom(pts: FloatArray, n: Int): Path {
        val path = Path()
        if (n < 2) return path
        fun x(i: Int) = pts[2 * i.coerceIn(0, n - 1)]
        fun y(i: Int) = pts[2 * i.coerceIn(0, n - 1) + 1]
        path.moveTo(x(0), y(0))
        for (i in 0 until n - 1) {
            path.cubicTo(
                x(i) + (x(i + 1) - x(i - 1)) / 6f, y(i) + (y(i + 1) - y(i - 1)) / 6f,
                x(i + 1) - (x(i + 2) - x(i)) / 6f, y(i + 1) - (y(i + 2) - y(i)) / 6f,
                x(i + 1), y(i + 1),
            )
        }
        return path
    }

    /**
     * Surface roughness: resample the spine and push the samples sideways by
     * deterministic noise. Small — the wander lives in the spine now, this is
     * only the fibre texture on top of it.
     */
    private fun capillaryTexture(spine: Path, seed: Float, amp: Float): Path {
        val measure = PathMeasure(spine, false)
        val len = measure.length
        if (len <= 0f) return spine

        // Sample at a roughly constant SPACING, not a constant count: paper
        // fibre has a wavelength, and a fixed 24 samples over a long reach
        // spaces them further apart than the lead-out is long — which quietly
        // resamples the lead-out (and the curl) out of existence.
        val n = (len / TEXTURE_SPACING).toInt().coerceIn(24, 96)

        val pos = FloatArray(2)
        val tan = FloatArray(2)
        val xs = FloatArray(n + 1)
        val ys = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i.toFloat() / n
            measure.getPosTan(len * t, pos, tan)
            val tl = hypot(tan[0], tan[1]).coerceAtLeast(1e-3f)
            val off = wobble(i, seed) * amp * bell(t)
            xs[i] = pos[0] + (-tan[1] / tl) * off
            ys[i] = pos[1] + (tan[0] / tl) * off
        }

        // Smooth through the samples: quads via midpoints (no corners).
        val path = Path()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) {
            path.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2f, (ys[i] + ys[i + 1]) / 2f)
        }
        path.quadTo(xs[n], ys[n], xs[n], ys[n])
        return path
    }

    fun threadLength(path: Path): Float = PathMeasure(path, false).length

    /**
     * Per-step advance along the thread for the REACH phase, summing to 1.
     * Ink wicks in bursts, not at a constant rate: three of these steps are
     * near-stalls, and they are the moments the thread appears to be deciding.
     */
    val threadStepDeltas: FloatArray by lazy {
        val d = FloatArray(THREAD_STEPS)
        var sum = 0f
        for (i in d.indices) {
            d[i] = if (i in STALL_AT) 0.12f else (1f + wobble(i, 11f) * 0.85f).coerceAtLeast(0.1f)
            sum += d[i]
        }
        for (i in d.indices) d[i] /= sum
        d
    }

    /**
     * The ink thread drawn up to [progress] (0..1, from the tail end): a pale
     * wicking underlay, then a core that tapers toward the advancing tip.
     */
    fun drawThread(canvas: Canvas, path: Path, length: Float, progress: Float) {
        if (progress <= 0f || length <= 0f) return
        val measure = PathMeasure(path, false)
        val end = length * progress
        val seg = Path()
        measure.getSegment(0f, end, seg, true)
        if (seg.isEmpty) return
        canvas.drawPath(seg, bleedPaint)
        // Taper: three sub-segments, thinning toward the tip.
        for (i in 0 until TAPER_STEPS) {
            val a = end * i / TAPER_STEPS
            val b = end * (i + 1) / TAPER_STEPS
            val part = Path()
            measure.getSegment(a, b, part, true)
            if (part.isEmpty) continue
            threadPaint.strokeWidth = 3.2f - 0.7f * i
            canvas.drawPath(part, threadPaint)
        }
        threadPaint.strokeWidth = 3f
    }

    /**
     * The thread being drunk: what remains runs from the drain front
     * [drained] (0..1) to the blot, thickening toward the front because the
     * ink is pooling forward as it goes.
     */
    fun drawThreadDraining(canvas: Canvas, path: Path, length: Float, drained: Float) {
        if (drained >= 1f || length <= 0f) return
        val measure = PathMeasure(path, false)
        val start = length * drained.coerceAtLeast(0f)
        val span = length - start
        if (span <= 0f) return
        val seg = Path()
        measure.getSegment(start, length, seg, true)
        if (seg.isEmpty) return
        canvas.drawPath(seg, bleedPaint)
        for (i in 0 until TAPER_STEPS) {
            val a = start + span * i / TAPER_STEPS
            val b = start + span * (i + 1) / TAPER_STEPS
            val part = Path()
            measure.getSegment(a, b, part, true)
            if (part.isEmpty) continue
            threadPaint.strokeWidth = 3.2f + 0.5f * i
            canvas.drawPath(part, threadPaint)
        }
        threadPaint.strokeWidth = 3f
    }

    // --------------------------------------------------------------- the roots

    /**
     * One creeping branch of the root system: ink that went a way of its own.
     *
     * [birth] is the growth progress at which it starts, [span] how much
     * progress it takes to finish, so branches appear as the creep front
     * reaches them rather than all at once. [gate] is where it hangs off the
     * tree: once the drain front passes that, the whole branch is reclaimed at
     * once, so nothing is ever left floating detached from the ink.
     */
    class Tendril internal constructor(
        internal val path: Path,
        internal val birth: Float,
        internal val span: Float,
        internal val gate: Float,
        internal val width: Float,
    )

    /**
     * A short wandering creep from ([x0],[y0]) heading ([dx],[dy]): four steps,
     * each turning a little, like a root finding the softest way through.
     */
    private fun creep(x0: Float, y0: Float, dx: Float, dy: Float, length: Float, seed: Float): Path {
        val n = CREEP_STEPS
        val step = length / n
        var x = x0
        var y = y0
        var ax = dx
        var ay = dy
        val pts = FloatArray(2 * (n + 1))
        pts[0] = x
        pts[1] = y
        for (i in 1..n) {
            val turn = wobble(i * 7, seed) * CREEP_TURN
            val cs = cos(turn)
            val sn = sin(turn)
            val nx = ax * cs - ay * sn
            val ny = ax * sn + ay * cs
            ax = nx
            ay = ny
            x += ax * step
            y += ay * step
            pts[2 * i] = x
            pts[2 * i + 1] = y
        }
        return catmullRom(pts, n + 1)
    }

    /** A thinner creep off [parent] at arc fraction [f], turned harder aside. */
    private fun subCreep(parent: Path, f: Float, seed: Float, length: Float): Path {
        buildMeasure.setPath(parent, false)
        val len = buildMeasure.length
        if (len <= 0f) return Path()
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        buildMeasure.getPosTan(len * f, pos, tan)
        val tl = hypot(tan[0], tan[1]).coerceAtLeast(1e-3f)
        val side = if (wobble(19, seed) >= 0f) 1f else -1f
        val ang = (45f + 30f * abs(wobble(23, seed))) * (Math.PI.toFloat() / 180f) * side
        val cs = cos(ang)
        val sn = sin(ang)
        val tx = tan[0] / tl
        val ty = tan[1] / tl
        return creep(pos[0], pos[1], tx * cs - ty * sn, tx * sn + ty * cs, length, seed + 5f)
    }

    /**
     * The ink does not send out one thread — it creeps. Several tips leave the
     * tail at once, most of them wander a little way and simply stop; more
     * branches sprout off the surviving channel as the creep front passes
     * them. Only one lineage ever reaches the locus, and it should look as
     * though it found it rather than aimed at it.
     *
     * The whole system is generated once, here, and then revealed by growth
     * order — so it costs no extra screen refreshes and cannot jitter between
     * frames.
     */
    fun buildRoots(
        channel: Path,
        channelLen: Float,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        exitDx: Float = 0f,
        exitDy: Float = 0f,
    ): List<Tendril> {
        if (channelLen <= 0f) return emptyList()
        val dist = hypot(toX - fromX, toY - fromY).coerceAtLeast(1f)
        val seed = seedOf(fromX, fromY, toX, toY)
        val out = ArrayList<Tendril>(16)

        val pos = FloatArray(2)
        val tan = FloatArray(2)
        buildMeasure.setPath(channel, false)

        // A. False starts: tips that set out beside the channel and give up.
        // They leave from a little way ALONG the channel, never from the tail
        // point itself — that point is the last glyph of the user's word, and
        // a branch turning back over it would scribble on their handwriting.
        // By 5-15% the channel is already heading into clear page, so both
        // sides are safe.
        for (k in FALSE_START_AT.indices) {
            buildMeasure.getPosTan(channelLen * FALSE_START_AT[k], pos, tan)
            val tl = hypot(tan[0], tan[1]).coerceAtLeast(1e-3f)
            val tx = tan[0] / tl
            val ty = tan[1] / tl
            val side = if (k % 2 == 0) 1f else -1f
            val ang = (30f + 34f * abs(wobble(k + 41, seed))) * (Math.PI.toFloat() / 180f) * side
            val cs = cos(ang)
            val sn = sin(ang)
            val len = (dist * FALSE_START_LEN).coerceIn(24f, 80f) *
                (0.65f + 0.35f * abs(wobble(k + 3, seed)))
            val path = creep(pos[0], pos[1], tx * cs - ty * sn, tx * sn + ty * cs, len, seed + k * 13f)
            out += Tendril(path, birth = 0f, span = 0.24f, gate = 0.03f, width = 1.9f)
            out += Tendril(
                subCreep(path, 0.55f, seed + k * 29f, len * 0.5f),
                birth = 0.09f, span = 0.20f, gate = 0.03f, width = 1.1f,
            )
        }

        // B. Branches off the surviving channel, appearing as the front passes.
        buildMeasure.setPath(channel, false)
        for (i in BRANCH_AT.indices) {
            val at = BRANCH_AT[i]
            buildMeasure.getPosTan(channelLen * at, pos, tan)
            val tl = hypot(tan[0], tan[1]).coerceAtLeast(1e-3f)
            val tx = tan[0] / tl
            val ty = tan[1] / tl
            val side = if (wobble(i + 31, seed) >= 0f) 1f else -1f
            val ang = (34f + 42f * abs(wobble(i + 13, seed))) * (Math.PI.toFloat() / 180f) * side
            val cs = cos(ang)
            val sn = sin(ang)
            // Widely varied lengths: frost is fine hairs with the occasional
            // long runner, not a comb of equal teeth.
            val len = BRANCH_LEN[i] * (0.55f + 0.7f * abs(wobble(i + 53, seed)))
            val path = creep(pos[0], pos[1], tx * cs - ty * sn, tx * sn + ty * cs, len, seed + i * 17f)
            val w = 1.0f + 0.7f * (len / BRANCH_LEN.max())
            out += Tendril(path, birth = at, span = 0.13f, gate = at, width = w)
            // The longer runners fork again — a root system, not a comb.
            if (len > 18f) {
                out += Tendril(
                    subCreep(path, 0.58f, seed + i * 37f, len * 0.5f),
                    birth = (at + 0.05f).coerceAtMost(0.97f), span = 0.12f, gate = at, width = w * 0.62f,
                )
            }
        }
        return out
    }

    /**
     * The root system as it stands at growth [progress] and drain [drained]:
     * a branch is drawn once the creep front has reached it and until the
     * drain front has passed where it hangs off.
     */
    fun drawRoots(canvas: Canvas, roots: List<Tendril>, progress: Float, drained: Float) {
        for (t in roots) {
            if (drained > t.gate) continue
            val g = ((progress - t.birth) / t.span).coerceIn(0f, 1f)
            if (g <= 0f) continue
            drawMeasure.setPath(t.path, false)
            val len = drawMeasure.length
            if (len <= 0f) continue
            rootSeg.reset()
            drawMeasure.getSegment(0f, len * g, rootSeg, true)
            if (rootSeg.isEmpty) continue
            forkPaint.strokeWidth = t.width
            canvas.drawPath(rootSeg, forkPaint)
        }
        forkPaint.strokeWidth = 1.8f
    }

    /** The bead of ink travelling down the thread ([progress] 0..1), with a wake. */
    fun drawBead(canvas: Canvas, path: Path, length: Float, progress: Float) {
        if (progress <= 0f || progress >= 1f || length <= 0f) return
        val measure = PathMeasure(path, false)
        val pos = FloatArray(2)
        measure.getPosTan(length * progress, pos, null)
        // The wake: shrinking, fading drops behind the bead.
        for (i in WAKE_DROPS downTo 1) {
            val d = length * progress - i * WAKE_GAP
            if (d <= 0f) continue
            measure.getPosTan(d, pos, null)
            beadPaint.alpha = 0x60 / i
            canvas.drawCircle(pos[0], pos[1], 3.2f - i * 0.6f, beadPaint)
        }
        beadPaint.alpha = 0xFF
        measure.getPosTan(length * progress, pos, null)
        canvas.drawCircle(pos[0], pos[1], 5f, beadPaint)
    }

    // --------------------------------------------------------------- the blot

    private val buildMeasure = PathMeasure()
    private val drawMeasure = PathMeasure()
    private val rootSeg = Path()

    private var blobBase: Path? = null
    private var blobSeedRadius = 0f
    private val blobMatrix = Matrix()
    private val blobScaled = Path()

    /** A closed, irregular blob of unit-ish radius [radius], cached between frames. */
    private fun blob(radius: Float): Path {
        val cached = blobBase
        if (cached != null && abs(blobSeedRadius - radius) < 0.5f) return cached
        val pts = BLOB_POINTS
        val xs = FloatArray(pts + 1)
        val ys = FloatArray(pts + 1)
        for (i in 0..pts) {
            val a = (i % pts) * 2.0 * Math.PI / pts
            val r = radius * (1f + wobble(i % pts, 3.7f) * 0.22f)
            xs[i] = (cos(a) * r).toFloat()
            ys[i] = (sin(a) * r).toFloat()
        }
        val path = Path()
        path.moveTo((xs[0] + xs[1]) / 2f, (ys[0] + ys[1]) / 2f)
        for (i in 1..pts) {
            val n = if (i == pts) 1 else i + 1
            path.quadTo(xs[i], ys[i], (xs[i] + xs[n % pts]) / 2f, (ys[i] + ys[n % pts]) / 2f)
        }
        path.close()
        blobBase = path
        blobSeedRadius = radius
        return path
    }

    private fun drawBlob(canvas: Canvas, cx: Float, cy: Float, radius: Float, scale: Float, paint: Paint) {
        blobMatrix.setScale(scale, scale)
        blobMatrix.postTranslate(cx, cy)
        blob(radius).transform(blobMatrix, blobScaled)
        canvas.drawPath(blobScaled, paint)
    }

    /**
     * The gathered blot at the answer locus: an irregular blob sitting in a
     * feathered halo, breathing with [breath] (-1..1) while the scribe thinks.
     * [deflate] (1 → 0) shrinks it away as the thread is drunk.
     */
    @JvmOverloads
    fun drawBlot(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breath: Float = 0f,
        deflate: Float = 1f,
    ) {
        if (deflate <= 0.02f) return
        val s = (1f + breath.coerceIn(-1f, 1f) * 0.08f) * deflate
        drawBlob(canvas, cx, cy, radius, s * 1.35f, haloPaint)
        drawBlob(canvas, cx, cy, radius, s, blotPaint)
    }

    /** One bloom of ink diffusing outward; [t] goes 1 → 0. */
    fun drawRipple(canvas: Canvas, cx: Float, cy: Float, radius: Float, t: Float) {
        if (t <= 0f) return
        bloomPaint.alpha = (0xB0 * t).toInt().coerceIn(0, 255)
        drawBlob(canvas, cx, cy, radius, 0.9f + (1f - t) * 1.1f, bloomPaint)
        bloomPaint.alpha = 0xFF
    }

    private const val TEXTURE_SPACING = 34f
    private const val TAPER_STEPS = 3
    private const val BLOB_POINTS = 12
    private const val WAKE_DROPS = 3
    private const val WAKE_GAP = 9f
    private const val EXIT_REACH = 0.18f
    private const val CURL_OVERSHOOT = 0.09f
    private const val TEXTURE_AMP = 0.008f
    private val STALL_AT = intArrayOf(5, 13, 20)
    private val WAYPOINT_T = floatArrayOf(0.18f, 0.44f, 0.63f, 0.85f)
    private val WAYPOINT_AMP = floatArrayOf(0.12f, 0.20f, 0.10f, 0.16f)
    private const val CREEP_STEPS = 4
    private const val CREEP_TURN = 0.42f          // ~24 deg of turn per creep step
    private const val LEADOUT_DRIFT = 0.16f       // x reach, perpendicular
    private const val FALSE_START_LEN = 0.16f     // x dist, clamped 24..80
    // Past the lead-out: at 5-15% the channel is still travelling the pen's own
    // direction, which passes right over the glyph the tail belongs to.
    private val FALSE_START_AT = floatArrayOf(0.13f, 0.19f, 0.25f)
    private val BRANCH_AT = floatArrayOf(
        0.20f, 0.27f, 0.33f, 0.40f, 0.47f, 0.54f, 0.61f, 0.68f, 0.75f, 0.82f, 0.89f,
    )
    private val BRANCH_LEN = floatArrayOf(
        26f, 12f, 34f, 15f, 22f, 11f, 30f, 14f, 24f, 10f, 18f,
    )
}
