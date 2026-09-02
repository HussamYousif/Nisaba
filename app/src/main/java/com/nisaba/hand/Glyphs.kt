package com.nisaba.hand

/**
 * The scribe's alphabet (docs/08 §3): every glyph is authored as PEN KNOTS —
 * the points a hand would pass through while writing the letter — and the
 * engine (Scribe) fits smooth Catmull-Rom strokes through them.
 *
 * Authoring space (paper convention, y UP, one unit = 1% of x-height):
 * - baseline   y = 0
 * - x-height   y = 100
 * - ascenders  up to ~y = 160 (b d f h k l t)
 * - caps       up to ~y = 150
 * - descenders down to ~y = -55 (g j p q y)
 *
 * Writing model: every lowercase letter begins at its entry base (0, 0) on
 * the baseline with a rising stroke and its last stroke returns to the
 * baseline near its exit — consecutive letters share the exit point, so the
 * pen never lifts inside a word (the "connected wave"). Letters with pen
 * lifts (caps, digits, punctuation, i-dot/t-cross) are separate strokes.
 *
 * Conventions:
 * - a doubled knot makes a corner; evenly spaced knots make smooth curves.
 * - dot/cross anchors mark where i/j dots and t crossings go — the engine
 *   draws them AFTER the whole word, as a real hand does.
 */
object Glyphs {

    /** A pen path through [knots]; the pen is down for the whole path. */
    class Stroke(val knots: List<FloatArray>)

    class Glyph(
        /** Horizontal distance from this entry base to the next letter's. */
        val advance: Float,
        /** X of the pen point where the glyph ends (its exit, on the baseline). */
        val exitX: Float,
        /** Rightmost ink x — where the next pen-down lands after a lifted glyph. */
        val maxX: Float,
        val strokes: List<Stroke>,
        /** i/j dot anchor (local x, y above the stem), when the letter is dotted. */
        val dot: FloatArray? = null,
        /** t-cross: the stem at (crossX, crossY) is crossed left→right. */
        val crossX: Float = 0f,
        val crossY: Float = 0f,
    )

    private val map = HashMap<Char, Glyph>(160)

    fun of(c: Char): Glyph? = map[c]

    // ------------------------------------------------------------ helpers

    private fun s(vararg pts: Pair<Float, Float>): Stroke =
        Stroke(pts.map { floatArrayOf(it.first, it.second) })

    private fun p(x: Number, y: Number): Pair<Float, Float> = x.toFloat() to y.toFloat()

    private fun register(
        c: Char,
        advance: Float,
        strokes: List<Stroke>,
        dot: Pair<Float, Float>? = null,
        cross: Pair<Float, Float>? = null,
    ) {
        var maxX = 0f
        for (st in strokes) for (k in st.knots) if (k[0] > maxX) maxX = k[0]
        val last = strokes.last().knots.last()
        map[c] = Glyph(advance, last[0], maxX, strokes, dot?.let { floatArrayOf(it.first, it.second) },
            cross?.first ?: 0f, cross?.second ?: 0f)
    }

    // ------------------------------------------------------------ lowercase
    // Wave letters: every letter rises from its base (0,0), travels through
    // its shape, and lands back near the baseline at the last knot.

    private fun lowercase() {
        // Ring letters (a, d, g, o, q): the ring is closed across the top and
        // the pen leaves over the top again — the small overlap reads as a
        // hand's natural retrace. Wave letters (h, i, m, n, u, w...) bounce
        // at the baseline between humps. All end at the baseline (exit).
        //
        // a — ring with a right-side exit tail
        register('a', 92f, listOf(s(
            p(0, 0), p(5, 35), p(8, 78), p(10, 93), p(12, 97),      // entry rise
            p(13, 96), p(10, 60), p(12, 18),                       // ring left side down
            p(32, 6),                                              // ring bottom
            p(52, 28), p(56, 82), p(54, 95),                       // ring right side up
            p(46, 99), p(36, 100), p(26, 99), p(17, 96), p(14, 93), // close the ring across the top
            p(24, 101), p(38, 104), p(52, 100), p(60, 94),         // exit arcs over the ring
            p(66, 60), p(70, 20), p(72, 6), p(73, 4),              // exit tail down the far side
        )))
        // b — tall stem that flows into a belly, then out
        register('b', 100f, listOf(s(
            p(0, 0), p(6, 40), p(10, 120), p(12, 155), p(13, 158), // stem up
            p(16, 155), p(18, 110), p(18, 55), p(16, 14),          // belly's left side down
            p(36, 5),                                              // belly bottom
            p(54, 26), p(58, 74), p(56, 92),                       // belly right side up
            p(48, 97), p(38, 99), p(28, 97), p(20, 92), p(18, 86), // belly closes across the top
            p(26, 99), p(40, 103), p(54, 99), p(62, 92),           // exit arcs over the belly
            p(70, 55), p(76, 15), p(80, 5),                        // exit tail down
            p(82, 4),
        )))
        // c — entry rise then the open curve, exit dropping from the open end
        register('c', 74f, listOf(s(
            p(0, 0), p(4, 30), p(7, 62), p(10, 85), p(12, 95), p(13, 97), // entry rise to the crest
            p(9, 78), p(3, 55), p(2, 35), p(7, 14),                      // down the LEFT, bulging out
            p(30, 4),                                                    // bottom
            p(50, 20), p(58, 50), p(60, 78), p(57, 92), p(52, 96),       // up the right side
            p(63, 72), p(67, 32), p(69, 8), p(70, 4),                    // exit drops from the open end
        )))
        // d — small ring, tall stem on the right
        register('d', 108f, listOf(s(
            p(0, 0), p(5, 35), p(8, 78), p(10, 93), p(12, 97),
            p(13, 96), p(10, 60), p(12, 18),
            p(32, 6),
            p(52, 28), p(56, 82), p(54, 95),
            p(46, 99), p(36, 100), p(26, 99), p(17, 96), p(14, 93), // ring closed at the top-left
            p(26, 100), p(42, 106), p(56, 116), p(62, 140),        // stem climbs out of the ring
            p(64, 156), p(66, 158),                                // to the ascender
            p(72, 150), p(78, 100), p(84, 45), p(90, 10), p(92, 5), // over the top, down the right
        )))
        // e — ring body with a mid tongue (like a c with a bar)
        register('e', 78f, listOf(s(
            p(0, 0), p(5, 35), p(8, 78), p(10, 93), p(12, 96),
            p(13, 96), p(10, 60), p(12, 16),
            p(32, 5),
            p(50, 22), p(56, 55), p(58, 80), p(56, 92), p(51, 96), // up the right side
            p(42, 78), p(30, 62), p(20, 55),                       // the tongue sweeps back
            p(16, 60), p(18, 76), p(24, 90), p(32, 96),            // up and over the top of the loop
            p(44, 98), p(52, 94), p(56, 88),                       // close the top-right
            p(62, 66), p(68, 28), p(72, 6), p(73, 4),              // exit drops from the open end
        )))
        // f — tall stem with a small top loop and a crossbar
        register('f', 74f, listOf(s(
            p(0, 0), p(5, 40), p(8, 100), p(10, 140), p(12, 156),  // stem up
            p(15, 156), p(16, 130), p(14, 105), p(12, 92),         // loop down the right, closing
            p(12, 50), p(13, 10), p(15, 5),                        // stem down to the baseline
            p(22, 25), p(28, 34), p(32, 30), p(34, 14), p(35, 5),  // a short exit tail
        )), cross = p(13f, 60f))
        // g — ring body with a descender tail
        register('g', 100f, listOf(s(
            p(0, 0), p(5, 35), p(8, 78), p(10, 93), p(12, 97),
            p(13, 96), p(10, 60), p(12, 18),
            p(32, 6),
            p(52, 28), p(56, 82), p(54, 95),
            p(46, 99), p(36, 100), p(26, 99), p(17, 96), p(14, 93),
            p(26, 100), p(40, 96), p(50, 70),                     // tail leaves the ring
            p(54, 30), p(52, -8), p(44, -36), p(36, -52),         // descends through the baseline
            p(40, -56), p(48, -52), p(56, -40),                   // small hook
            p(64, -20), p(72, 4), p(76, 5),                       // up the far side to the exit
        )))
        // h — tall stem then two humps
        register('h', 112f, listOf(s(
            p(0, 0), p(7, 45), p(11, 120), p(13, 155), p(14, 158), // stem up
            p(17, 152), p(19, 110), p(19, 55), p(17, 12), p(16, 4), // stem down its right
            p(30, 45), p(40, 78), p(46, 94), p(49, 97), p(50, 96), // hump 1 up
            p(52, 75), p(56, 35), p(58, 8), p(58, 4),              // hump 1 down
            p(72, 45), p(82, 78), p(88, 94), p(91, 97), p(92, 96), // hump 2 up
            p(94, 75), p(98, 35), p(100, 8), p(101, 4),            // hump 2 down to the exit
        )))
        // i — single stem; dot drawn after the word
        register('i', 54f, listOf(s(
            p(0, 0), p(7, 45), p(12, 88), p(15, 95), p(17, 97),
            p(19, 90), p(22, 45), p(27, 10), p(29, 4),
        )), dot = p(20f, 118f))
        // j — stem descending into a hook; dot after the word
        register('j', 56f, listOf(s(
            p(0, 0), p(7, 45), p(12, 88), p(15, 95), p(17, 97),
            p(19, 90), p(22, 45), p(27, 10), p(28, -12),           // stem down
            p(25, -34), p(29, -50), p(38, -54), p(45, -44),        // hook around
            p(49, -28), p(50, -12), p(49, 0), p(48, 6),            // up the far side
        )), dot = p(20f, 118f))
        // k — tall stem, then two open arms (pen lifts between)
        register('k', 100f, listOf(
            s(p(0, 0), p(7, 45), p(11, 120), p(13, 155), p(14, 158),
                p(17, 152), p(19, 110), p(19, 60), p(18, 20), p(16, 4)),  // stem
            s(p(20, 58), p(32, 78), p(46, 93), p(52, 97), p(54, 96)),     // upper arm out and up
            s(p(22, 52), p(36, 30), p(52, 8), p(60, 2)),                  // lower arm out and down
        ))
        // l — a simple tall stem
        register('l', 58f, listOf(s(
            p(0, 0), p(7, 45), p(11, 120), p(13, 155), p(14, 158),
            p(17, 152), p(19, 110), p(21, 55), p(26, 12), p(28, 5),
            p(34, 30), p(42, 55), p(48, 65), p(52, 50), p(54, 20), p(53, 5), // exit tail
        )))
        // m — three humps
        register('m', 150f, listOf(s(
            p(0, 0), p(9, 40), p(15, 72), p(20, 90), p(23, 96), p(25, 97), p(26, 96), // hump 1
            p(29, 70), p(33, 30), p(35, 8), p(36, 4),
            p(48, 40), p(56, 74), p(61, 92), p(64, 97), p(65, 96), // hump 2
            p(68, 70), p(72, 30), p(74, 8), p(75, 4),
            p(87, 40), p(95, 74), p(100, 92), p(103, 97), p(104, 96), // hump 3
            p(107, 70), p(111, 30), p(113, 8), p(114, 4),
        )))
        // n — two humps
        register('n', 104f, listOf(s(
            p(0, 0), p(9, 40), p(15, 72), p(20, 90), p(23, 96), p(25, 97), p(26, 96), // hump 1
            p(29, 70), p(33, 30), p(35, 8), p(36, 4),
            p(48, 40), p(56, 74), p(61, 92), p(64, 97), p(65, 96), // hump 2
            p(68, 70), p(72, 30), p(74, 8), p(75, 4),
        )))
        // o — closed ring, exit over the top
        register('o', 88f, listOf(s(
            p(0, 0), p(5, 35), p(8, 78), p(10, 93), p(12, 97),
            p(13, 96), p(10, 60), p(12, 18),
            p(32, 6),
            p(52, 28), p(56, 82), p(54, 95),
            p(46, 99), p(36, 100), p(26, 99), p(17, 96), p(14, 93), // ring closed at the top-left
            p(22, 101), p(34, 104), p(48, 101), p(58, 94),         // exit arcs over the ring
            p(66, 55), p(70, 12), p(72, 5),                        // exit tail down
        )))
        // p — stem descends below the line, then a belly
        register('p', 96f, listOf(
            s(p(0, 0), p(6, 40), p(10, 96), p(12, 100),            // stem up to x-height
                p(14, 96), p(15, 55), p(14, 10), p(11, -20),       // stem down
                p(8, -42), p(10, -52), p(14, -54), p(19, -50)),    // descender hook
            s(p(19, -50), p(27, -24), p(34, 10), p(40, 60), p(43, 90), p(45, 96), // up the belly's left
                p(48, 99), p(56, 94), p(60, 70), p(58, 30), p(52, 8), p(46, 4)),  // belly over and down
            s(p(46, 4), p(58, 4), p(72, 5), p(80, 6), p(86, 5)),   // exit along the baseline
        ))
        // q — ring with a descender tail
        register('q', 100f, listOf(s(
            p(0, 0), p(5, 35), p(8, 78), p(10, 93), p(12, 97),
            p(13, 96), p(10, 60), p(12, 18),
            p(32, 6),
            p(52, 28), p(56, 82), p(54, 95),
            p(46, 99), p(36, 100), p(26, 99), p(17, 96), p(14, 93),
            p(26, 100), p(40, 96), p(52, 62),                     // tail leaves the ring
            p(58, 20), p(56, -18), p(48, -44), p(42, -56),        // straight descender
            p(50, -52), p(60, -36), p(68, -12), p(74, 8), p(78, 5), // up the far side
        )))
        // r — a short rise, a shoulder, a long exit
        register('r', 82f, listOf(s(
            p(0, 0), p(8, 45), p(15, 85), p(19, 95), p(21, 97),   // rise
            p(23, 96), p(24, 80),                                 // shoulder crest
            p(21, 55), p(16, 25), p(13, 6),                       // fold back down
            p(24, 28), p(36, 55), p(48, 78), p(56, 92),           // the long exit tail out and up
            p(62, 96), p(66, 90), p(72, 60), p(76, 25), p(78, 6), // ... over and down to the exit
        )))
        // s — two stacked rings (like an 8 open on the right); exits from the right
        register('s', 84f, listOf(s(
            p(0, 0), p(4, 26), p(7, 50), p(10, 74), p(13, 90), p(16, 97), p(19, 99), // entry rise
            p(26, 101), p(34, 100), p(42, 97), p(48, 92), p(53, 86), p(55, 79),     // top ring: dome and down
            p(54, 70), p(49, 63), p(42, 58), p(34, 56), p(27, 56),                  // ... and its bottom
            p(26, 44), p(26, 32), p(28, 20), p(32, 11), p(38, 6),                   // bottom ring: left side down
            p(46, 4), p(55, 6), p(62, 12), p(67, 20),                               // ... bottom across
            p(69, 30), p(69, 42), p(67, 52), p(63, 60),                             // ... right side up
            p(68, 50), p(74, 28), p(78, 8), p(79, 4),                               // exit drop
        )))
        // t — shorter stem with a top hook; crossbar drawn after the word
        register('t', 68f, listOf(s(
            p(0, 0), p(7, 45), p(11, 90), p(14, 128), p(17, 136), p(21, 130), // stem up, hook over
            p(23, 112), p(22, 70), p(19, 25), p(18, 5),                      // stem down
            p(26, 30), p(36, 55), p(46, 68), p(54, 60),                      // exit tail
            p(60, 40), p(64, 15), p(65, 5),                                  // ... down to the exit
        )), cross = p(22f, 88f))
        // u — stem, bowl, stem
        register('u', 102f, listOf(s(
            p(0, 0), p(8, 40), p(13, 72), p(17, 90), p(20, 96), p(22, 97), p(23, 96), // stem up
            p(26, 70), p(30, 30), p(33, 12), p(36, 10),                     // down into the bowl
            p(52, 8), p(66, 28), p(72, 60), p(78, 90), p(81, 96), p(83, 97), // bowl up the right side
            p(86, 70), p(90, 30), p(94, 8), p(97, 4),                       // final stem down to the exit
        )))
        // v — rise and long fall
        register('v', 94f, listOf(s(
            p(0, 0), p(7, 35), p(14, 70), p(20, 90), p(25, 96), p(28, 97), // rise to the crest
            p(32, 80), p(40, 45), p(50, 14), p(58, 4),                     // the long fall
            p(66, 30), p(76, 60), p(84, 80), p(90, 90),                    // exit tail up
            p(93, 92), p(94, 80), p(92, 55), p(88, 30), p(86, 8),          // ... over and down
        )))
        // w — three crests with two deep bowls (like u + v)
        register('w', 146f, listOf(s(
            p(0, 0), p(7, 35), p(13, 68), p(18, 88), p(22, 96), p(24, 97), p(25, 96), // crest 1
            p(28, 74), p(32, 40), p(35, 14), p(37, 8),                     // fall into the first bowl
            p(52, 10), p(66, 30), p(73, 62), p(79, 90), p(82, 96), p(84, 97), // up crest 2
            p(87, 74), p(91, 40), p(94, 14), p(96, 8),                     // fall into the second bowl
            p(111, 10), p(125, 30), p(132, 62), p(137, 88), p(140, 95), p(142, 96), // up crest 3
            p(144, 74), p(146, 30), p(148, 8), p(149, 5),                  // fall to the exit
        )))
        // x — two clean crossing diagonals
        register('x', 96f, listOf(
            s(p(0, 4), p(14, 38), p(28, 70), p(40, 92), p(46, 98)),        // the rise stroke
            s(p(6, 98), p(20, 68), p(36, 34), p(50, 6), p(56, 3)),         // the fall stroke
        ))
        // y — stem into a descender hook
        register('y', 96f, listOf(s(
            p(0, 0), p(8, 40), p(14, 74), p(19, 92), p(22, 97), p(24, 96), // stem up
            p(27, 70), p(31, 30), p(34, 8),                               // down
            p(50, 8), p(64, 30), p(71, 62), p(77, 90), p(80, 96), p(82, 96), // up the right side
            p(85, 70), p(88, 30), p(88, 0), p(84, -24),                    // over and down
            p(78, -46), p(82, -54), p(90, -48), p(96, -30),                // descender hook
            p(100, -8), p(102, 10), p(103, 5),                             // up to the exit
        )))
        // z — top stroke, diagonal, bottom stroke
        register('z', 78f, listOf(s(
            p(0, 0), p(5, 40), p(9, 75), p(12, 92), p(10, 96), p(7, 97),   // rise to the top-left
            p(30, 99), p(52, 97), p(60, 95),                               // top stroke across
            p(56, 60), p(42, 26), p(28, 6), p(24, 4),                      // the diagonal down
            p(44, 6), p(60, 8), p(70, 10), p(73, 12),                      // bottom stroke across
            p(75, 20), p(76, 8), p(74, 5),                                 // a small end rise
        )))
    }
    // ------------------------------------------------------------ uppercase
    // Standalone: the pen lifts between every cap. Each cap starts at its
    // own (0,0) with a short rise — the hand's flick into a capital.

    private fun uppercase() {
        register('A', 100f, listOf(
            s(p(0, 0), p(6, 40), p(16, 100), p(28, 140), p(36, 150)),   // left stroke
            s(p(36, 150), p(50, 90), p(66, 30), p(80, 4)),              // right stroke down
            s(p(6, 70), p(30, 78), p(58, 72)),                          // crossbar
        ))
        register('B', 90f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(15, 90), p(15, 60), p(15, 20), p(16, 4)),   // stem
            s(p(16, 30), p(40, 22), p(58, 30), p(62, 52), p(54, 76), p(38, 84), p(16, 84)), // bottom bowl
            s(p(16, 92), p(40, 92), p(58, 98), p(64, 118), p(56, 138), p(40, 147), p(16, 150)), // top bowl
            s(p(16, 150), p(17, 120), p(17, 60), p(18, 8), p(20, 4)),  // stem down
            s(p(20, 4), p(60, 4), p(80, 8)),                           // base
        ))
                register('C', 84f, listOf(
            s(p(0, 0), p(6, 40), p(12, 100), p(18, 138), p(24, 149), p(28, 151)), // entry rise on the left
            s(p(28, 151), p(56, 149), p(72, 140), p(78, 126), p(78, 112)),        // over the top
            s(p(78, 112), p(72, 118), p(58, 122), p(42, 122), p(26, 116), p(14, 102), p(9, 84)), // inner curve down
            s(p(9, 84), p(11, 60), p(22, 44), p(42, 38), p(60, 40), p(72, 46), p(77, 54)), // up the bottom
            s(p(77, 54), p(82, 30), p(84, 8), p(84, 4)),                            // exit drop
        ))
        register('D', 96f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(52, 148), p(76, 132), p(86, 108), p(88, 80)), // over and down the right
            s(p(88, 80), p(86, 52), p(76, 32), p(58, 24), p(36, 22), p(16, 24), p(14, 26)), // bottom
            s(p(14, 26), p(14, 70), p(14, 120), p(15, 148), p(16, 150)), // stem
            s(p(16, 150), p(17, 90), p(17, 20), p(18, 4)),             // stem down
            s(p(18, 4), p(70, 4), p(88, 8)),                           // base
        ))
        register('E', 84f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),  // left stem up
            s(p(14, 150), p(62, 148), p(70, 146)),                     // top bar
            s(p(70, 146), p(52, 140), p(30, 132), p(18, 124), p(16, 108), p(16, 96)), // back down
            s(p(16, 96), p(16, 92), p(44, 88), p(58, 86)),             // middle bar
            s(p(58, 86), p(36, 84), p(16, 82), p(15, 68), p(15, 50)),  // back down
            s(p(15, 50), p(15, 20), p(16, 6), p(18, 4), p(66, 4)),     // stem down + base
        ))
        register('F', 80f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(62, 148), p(70, 146)),
            s(p(70, 146), p(50, 140), p(28, 132), p(17, 124), p(15, 108), p(15, 96)),
            s(p(15, 96), p(15, 92), p(44, 88), p(58, 86)),
            s(p(58, 86), p(34, 84), p(15, 82), p(14, 60), p(14, 30), p(15, 6), p(16, 4)),
        ))
                register('G', 92f, listOf(
            s(p(0, 0), p(6, 40), p(12, 100), p(18, 138), p(24, 149), p(28, 151)),
            s(p(28, 151), p(56, 149), p(72, 140), p(78, 126), p(78, 112)),
            s(p(78, 112), p(72, 118), p(58, 122), p(42, 122), p(26, 116), p(14, 102), p(9, 84)),
            s(p(9, 84), p(11, 60), p(22, 44), p(42, 38), p(60, 40), p(72, 46), p(76, 56), p(76, 66)), // up the bottom
            s(p(76, 66), p(56, 62), p(36, 60), p(20, 62), p(14, 66)),  // the bar into the C-mouth
        ))
        register('H', 100f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(15, 90), p(15, 40), p(16, 4)),             // left stem
            s(p(18, 80), p(50, 76), p(80, 72)),                        // crossbar
            s(p(80, 72), p(82, 40), p(84, 10), p(86, 4)),              // right stem up from bar? no—
            s(p(90, 4), p(90, 60), p(88, 120), p(88, 148), p(90, 150)), // right stem (drawn top-down)
            s(p(90, 150), p(92, 100), p(94, 40), p(96, 4)),
        ))
                register('I', 44f, listOf(
            s(p(3, 149), p(18, 151), p(32, 149), p(35, 147)), // top bar
            s(p(19, 149), p(21, 100), p(22, 45), p(23, 8), p(24, 5)), // stem
            s(p(4, 5), p(18, 3), p(32, 5), p(36, 6)),          // bottom bar
        ))
                register('J', 84f, listOf(
            s(p(2, 148), p(20, 151), p(40, 150), p(56, 147), p(66, 140), p(72, 130), p(75, 118), // top curve
                p(76, 90), p(74, 50), p(69, 18), p(62, 5),                                      // stem
                p(55, -1), p(46, -4), p(39, -2), p(36, 5), p(40, 12), p(48, 12)),               // bottom hook
        ))
        register('K', 96f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(15, 90), p(15, 40), p(16, 4)),             // left stem
            s(p(18, 84), p(40, 108), p(64, 132), p(78, 148), p(82, 150)), // upper arm
            s(p(82, 150), p(66, 116), p(48, 84), p(36, 62)),           // fold back in
            s(p(36, 62), p(56, 36), p(78, 14), p(88, 4)),              // lower arm
            s(p(88, 4), p(84, 20), p(78, 44), p(74, 58)),              // tail
        ))
        register('L', 82f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(15, 90), p(15, 40), p(16, 10), p(18, 4)),
            s(p(18, 4), p(44, 4), p(66, 4), p(72, 4)),                 // base
            s(p(72, 4), p(70, 30), p(66, 50), p(64, 60)),              // exit flick
        ))
        register('M', 130f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(30, 100), p(46, 56), p(58, 30), p(66, 14)), // down into the valley
            s(p(66, 14), p(76, 40), p(92, 90), p(108, 128), p(116, 148), p(120, 150)), // up and over
            s(p(120, 150), p(122, 100), p(124, 40), p(126, 4)),        // right stem down
        ))
        register('N', 104f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(34, 96), p(54, 44), p(70, 12), p(82, 4)),  // diagonal down
            s(p(82, 4), p(84, 60), p(84, 110), p(84, 148), p(86, 150)), // right stem up
            s(p(86, 150), p(88, 100), p(90, 40), p(92, 4)),            // right stem down
        ))
        register('O', 96f, listOf(
            s(p(0, 0), p(6, 40), p(12, 100), p(18, 140), p(26, 150)),
            s(p(26, 150), p(12, 110), p(8, 60), p(14, 20), p(34, 6)),  // down the left
            s(p(34, 6), p(64, 8), p(80, 30), p(86, 60), p(84, 96), p(72, 130), p(56, 147), p(44, 150)), // up the right
            s(p(44, 150), p(46, 110), p(48, 60), p(52, 26), p(64, 12)), // close over the top
            s(p(64, 12), p(74, 16), p(80, 26), p(84, 40)),             // exit
        ))
                register('P', 86f, listOf(
            s(p(4, 150), p(5, 110), p(6, 60), p(7, 15), p(8, 4)),                       // stem (left side)
            s(p(5, 148), p(26, 151), p(50, 149), p(63, 141), p(69, 128), p(68, 114),    // bowl over the top
                p(62, 100), p(52, 94), p(40, 92), p(28, 93), p(16, 97), p(8, 102)),     // ... around to the stem
        ))
        register('Q', 104f, listOf(
            s(p(0, 0), p(6, 40), p(12, 100), p(18, 140), p(26, 150)),
            s(p(26, 150), p(12, 110), p(8, 60), p(14, 20), p(34, 6)),
            s(p(34, 6), p(64, 8), p(80, 30), p(86, 60), p(84, 96), p(72, 130), p(56, 147), p(44, 150)),
            s(p(44, 150), p(46, 110), p(48, 60), p(52, 26), p(64, 12)),
            s(p(64, 12), p(66, 20), p(64, 34), p(60, 44), p(56, 50)),  // exit
            s(p(60, 46), p(78, 28), p(92, 14), p(100, 4)),             // Q tail
        ))
                register('R', 92f, listOf(
            s(p(4, 150), p(5, 110), p(6, 60), p(7, 15), p(8, 4)),                       // stem
            s(p(5, 148), p(26, 151), p(50, 149), p(63, 141), p(69, 128), p(68, 114),
                p(62, 100), p(52, 94), p(40, 92), p(28, 93), p(16, 97), p(8, 102)),     // bowl
            s(p(10, 96), p(26, 64), p(46, 34), p(66, 12), p(82, 4)),                    // the leg out
        ))
        register('S', 84f, listOf(
            s(p(0, 0), p(5, 35), p(11, 100), p(18, 140), p(26, 150)),
            s(p(26, 150), p(18, 124), p(12, 96), p(10, 78), p(12, 60), p(22, 48), p(40, 44)), // top swell, waist
            s(p(40, 44), p(58, 40), p(70, 46), p(76, 60), p(74, 76), p(64, 90), p(52, 98), p(44, 102)), // bottom swell
            s(p(44, 102), p(58, 100), p(70, 92), p(78, 76)),           // inner up
            s(p(78, 76), p(76, 40), p(68, 12), p(62, 4), p(76, 4)),    // tail down + base flick
        ))
                register('T', 96f, listOf(
            s(p(2, 149), p(28, 151), p(52, 150), p(70, 148), p(74, 147)), // top bar
            s(p(40, 150), p(42, 110), p(44, 55), p(46, 12), p(47, 4)),    // stem
        ))
        register('U', 100f, listOf(
            s(p(0, 0), p(5, 40), p(8, 100), p(10, 148), p(12, 150)),
            s(p(12, 150), p(14, 100), p(16, 50), p(20, 16), p(38, 4)), // down into the bowl
            s(p(38, 4), p(64, 4), p(80, 24), p(88, 60), p(90, 110)),  // bowl up the right
            s(p(90, 110), p(91, 148), p(93, 150)),                    // to the top
            s(p(93, 150), p(95, 100), p(97, 40), p(100, 4)),          // right stem down
        ))
        register('V', 100f, listOf(
            s(p(0, 0), p(6, 40), p(14, 100), p(24, 140), p(30, 150)),
            s(p(30, 150), p(44, 90), p(58, 30), p(66, 4)),            // down into the V
            s(p(66, 4), p(76, 60), p(84, 120), p(88, 148), p(92, 150)), // up the other side
            s(p(92, 150), p(96, 100), p(100, 40), p(102, 4)),         // and down
        ))
        register('W', 160f, listOf(
            s(p(0, 0), p(6, 40), p(13, 100), p(22, 140), p(28, 150)),
            s(p(28, 150), p(42, 90), p(54, 30), p(60, 4)),
            s(p(60, 4), p(70, 60), p(80, 120), p(86, 148), p(90, 150)),
            s(p(90, 150), p(104, 90), p(116, 30), p(122, 4)),
            s(p(122, 4), p(132, 60), p(142, 120), p(148, 148), p(152, 150)),
            s(p(152, 150), p(156, 100), p(160, 40), p(162, 4)),
        ))
        register('X', 100f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(36, 90), p(58, 34), p(72, 6)),            // fall stroke
            s(p(72, 6), p(78, 50), p(82, 110), p(84, 148), p(88, 150)), // rise stroke
            s(p(88, 150), p(64, 90), p(40, 34), p(26, 4)),            // fall back
        ))
        register('Y', 100f, listOf(
            s(p(0, 0), p(6, 40), p(10, 120), p(12, 148), p(14, 150)),
            s(p(14, 150), p(34, 100), p(52, 50), p(60, 20), p(64, 4)), // down into the fork
            s(p(64, 4), p(74, 60), p(82, 120), p(86, 148), p(90, 150)), // up the right arm
            s(p(90, 150), p(94, 100), p(98, 40), p(100, 4)),          // and down
        ))
        register('Z', 96f, listOf(
            s(p(0, 0), p(5, 40), p(9, 120), p(11, 148), p(14, 150)),
            s(p(14, 150), p(56, 148), p(72, 146)),                    // top bar
            s(p(72, 146), p(48, 100), p(30, 60), p(18, 30), p(12, 10), p(10, 4)), // diagonal
            s(p(10, 4), p(40, 4), p(70, 4), p(78, 4)),                // bottom bar
            s(p(78, 4), p(76, 30), p(72, 50), p(70, 60)),             // exit flick
        ))
    }

    // ------------------------------------------------------------ digits

    private fun digits() {
        register('0', 74f, listOf(
            s(p(0, 0), p(5, 40), p(9, 100), p(14, 140), p(22, 150)),
            s(p(22, 150), p(12, 110), p(8, 60), p(14, 20), p(34, 6)),
            s(p(34, 6), p(58, 8), p(70, 34), p(74, 70), p(70, 110), p(60, 138), p(46, 150), p(34, 150)),
            s(p(34, 150), p(40, 100), p(46, 50), p(52, 14), p(60, 6)),
        ))
        register('1', 52f, listOf(
            s(p(0, 0), p(3, 40), p(5, 120), p(7, 148), p(10, 150)),
            s(p(10, 150), p(12, 100), p(16, 40), p(22, 8)),
            s(p(22, 8), p(24, 40), p(26, 80), p(28, 110), p(30, 130), p(32, 120), p(34, 90), p(36, 60), p(38, 20), p(40, 6)),
        ))
        register('2', 74f, listOf(
            s(p(0, 0), p(6, 40), p(13, 100), p(20, 140), p(28, 150)),
            s(p(28, 150), p(54, 146), p(68, 132), p(72, 114)),
            s(p(72, 114), p(68, 96), p(56, 82), p(40, 68), p(26, 52), p(14, 32), p(8, 12)),
            s(p(8, 12), p(10, 6), p(30, 4), p(56, 4), p(72, 4)),
            s(p(72, 4), p(70, 30), p(66, 55), p(64, 70)),
        ))
        register('3', 74f, listOf(
            s(p(0, 0), p(6, 40), p(13, 100), p(20, 140), p(28, 150)),
            s(p(28, 150), p(54, 146), p(68, 132), p(72, 114)),
            s(p(72, 114), p(68, 98), p(56, 86), p(42, 82), p(58, 78), p(68, 66), p(72, 52)),
            s(p(72, 52), p(66, 36), p(52, 26), p(36, 24)),
            s(p(36, 24), p(56, 20), p(68, 12), p(72, 4)),
        ))
        register('4', 74f, listOf(
            s(p(0, 0), p(2, 40), p(4, 120), p(6, 148), p(9, 150)),
            s(p(9, 150), p(50, 146), p(62, 140)),
            s(p(62, 140), p(58, 90), p(54, 30), p(52, 4)),
            s(p(52, 4), p(50, 60), p(48, 120), p(46, 146), p(44, 150)),
            s(p(12, 86), p(34, 82), p(52, 78)),
        ))
        register('5', 74f, listOf(
            s(p(0, 0), p(4, 40), p(8, 120), p(10, 148), p(14, 150)),
            s(p(14, 150), p(50, 146), p(62, 142)),
            s(p(62, 142), p(58, 90), p(54, 30), p(52, 4)),
            s(p(52, 4), p(50, 60), p(48, 120), p(46, 146), p(44, 150)),
            s(p(16, 96), p(36, 92), p(54, 88)),
        ))
        register('6', 74f, listOf(
            s(p(0, 0), p(5, 40), p(9, 100), p(14, 140), p(22, 150)),
            s(p(22, 150), p(12, 110), p(8, 60), p(14, 20), p(34, 6)),
            s(p(34, 6), p(58, 8), p(70, 34), p(74, 70), p(70, 110), p(60, 138), p(46, 150), p(34, 150)),
            s(p(34, 150), p(36, 110), p(38, 70), p(40, 30), p(38, 6)),
        ))
        register('7', 74f, listOf(
            s(p(0, 0), p(5, 40), p(9, 120), p(11, 148), p(14, 150)),
            s(p(14, 150), p(50, 148), p(66, 146)),
            s(p(66, 146), p(48, 100), p(32, 60), p(22, 30), p(18, 10)),
            s(p(18, 10), p(34, 8), p(56, 6), p(70, 4)),
            s(p(70, 4), p(66, 40), p(60, 70), p(56, 85)),
        ))
        register('8', 74f, listOf(
            s(p(0, 0), p(5, 40), p(9, 100), p(14, 140), p(22, 150)),
            s(p(22, 150), p(12, 110), p(8, 60), p(14, 20), p(34, 6)),
            s(p(34, 6), p(58, 8), p(70, 34), p(74, 70), p(70, 110), p(60, 138), p(46, 150), p(34, 150)),
            s(p(34, 150), p(30, 120), p(26, 90), p(28, 60), p(38, 40), p(54, 30), p(66, 20), p(70, 8)),
        ))
        register('9', 74f, listOf(
            s(p(0, 0), p(5, 40), p(9, 100), p(14, 140), p(22, 150)),
            s(p(22, 150), p(12, 110), p(8, 60), p(14, 20), p(34, 6)),
            s(p(34, 6), p(58, 8), p(70, 34), p(74, 70), p(70, 110), p(60, 138), p(46, 150), p(34, 150)),
            s(p(34, 150), p(36, 110), p(38, 70), p(40, 30), p(44, 4)),
        ))
    }

    // ------------------------------------------------------------ punctuation

    private fun punctuation() {
        register('.', 30f, listOf(s(
            p(0, 0), p(4, 6), p(10, 4), p(14, 0), p(16, -4),
        )))
        register(',', 32f, listOf(s(
            p(0, 0), p(3, -2), p(6, -4), p(9, -2), p(10, 4), p(8, 8), p(4, 6),
        )))
        register('!', 40f, listOf(
            s(p(0, 0), p(3, 40), p(6, 110), p(8, 146), p(10, 150)),
            s(p(10, 150), p(12, 100), p(16, 40), p(20, 8), p(22, 6)),
            s(p(13, -8), p(18, -6), p(23, -8)),
        ))
        register('?', 62f, listOf(
            s(p(0, 0), p(4, 40), p(9, 100), p(14, 140), p(22, 150)),
            s(p(22, 150), p(42, 147), p(54, 136), p(58, 120)),
            s(p(58, 120), p(56, 106), p(48, 96), p(38, 90)),
            s(p(38, 90), p(39, 72), p(42, 56), p(46, 44)),
            s(p(46, 44), p(50, 42), p(54, 44), p(52, 38), p(48, 34)),
            s(p(46, -8), p(52, -6), p(58, -8)),
        ))
        register('\'', 26f, listOf(s(
            p(0, 0), p(4, 20), p(6, 40), p(5, 52), p(2, 60),
        )))
        register('"', 48f, listOf(
            s(p(0, 0), p(4, 20), p(6, 40), p(5, 52), p(2, 60)),
            s(p(22, 0), p(26, 20), p(28, 40), p(27, 52), p(24, 60)),
        ))
        register('-', 46f, listOf(s(
            p(0, 0), p(2, 58), p(8, 64), p(20, 66), p(34, 64), p(44, 60), p(46, 56),
        )))
        register('–', 62f, listOf(s(
            p(0, 0), p(2, 58), p(10, 64), p(28, 66), p(48, 64), p(60, 60), p(62, 56),
        )))
        register('—', 90f, listOf(s(
            p(0, 0), p(2, 58), p(16, 64), p(44, 66), p(74, 64), p(88, 60), p(90, 56),
        )))
        register(':', 30f, listOf(
            s(p(0, 0), p(4, 40), p(8, 48), p(12, 42), p(14, 34)),
            s(p(4, -12), p(9, -8), p(14, -12)),
        ))
        register(';', 32f, listOf(
            s(p(0, 0), p(4, 40), p(8, 48), p(12, 42), p(14, 34)),
            s(p(4, -4), p(8, 0), p(11, 6), p(12, 12), p(10, 16), p(6, 12)),
        ))
        register('(', 34f, listOf(s(
            p(0, 0), p(6, 50), p(12, 110), p(14, 146), p(10, 150),
            p(8, 120), p(8, 70), p(12, 20), p(18, -20), p(26, -46),
        )))
        register(')', 34f, listOf(s(
            p(0, 0), p(12, -46), p(22, -20), p(28, 20), p(28, 70),
            p(26, 120), p(24, 150), p(20, 146), p(22, 110), p(28, 50),
        )))
        register('/', 56f, listOf(s(
            p(0, 0), p(10, 40), p(24, 110), p(32, 146), p(38, 150),
        )))
        register('\\', 56f, listOf(s(
            p(0, 0), p(14, 150), p(20, 146), p(30, 60), p(40, 4),
        )))
        register('=', 46f, listOf(
            s(p(0, 0), p(4, 66), p(20, 70), p(44, 68), p(46, 64)),
            s(p(2, 44), p(6, 46), p(22, 48), p(44, 46), p(46, 42)),
        ))
        register('+', 46f, listOf(
            s(p(0, 0), p(4, 40), p(8, 58), p(14, 62), p(24, 62), p(44, 60), p(46, 56)),
            s(p(22, 22), p(23, 40), p(24, 62), p(23, 84), p(22, 96)),
        ))
        register('*', 48f, listOf(
            s(p(0, 0), p(2, 30), p(4, 58), p(6, 68), p(8, 58), p(10, 30), p(12, 20)),
            s(p(4, 48), p(16, 54), p(22, 60)),
            s(p(18, 46), p(12, 40), p(4, 38)),
        ))
    }

    init {
        lowercase()
        uppercase()
        digits()
        punctuation()
    }
}
