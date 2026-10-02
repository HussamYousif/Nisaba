package com.nisaba.tools

import com.nisaba.domain.answer.AnswerParser
import com.nisaba.domain.answer.ParseEvent
import com.nisaba.domain.settings.HandSpeed
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.GlyphSetParser
import com.nisaba.scribe.hand.HumanParams
import com.nisaba.scribe.layout.LayoutRegion
import com.nisaba.scribe.layout.OccupancyLookup
import com.nisaba.scribe.motion.ScribePlanUseCase
import com.nisaba.scribe.motion.speedProfile
import java.io.File

private fun loadHand(name: String) =
    GlyphSetParser.parse(object {}.javaClass.getResource("/hands/$name.json")!!.readText())

/**
 * `preview --in answer.md --out out.png [--hand casual|formal|print] [--seed 42] [--xheight 44] [--perfect]`
 * Feeds the file through the real parser → layout → humanizer → motion pipeline and renders page 0 (and spill pages).
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 1).filter { it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }
    when (args.firstOrNull()) {
        "preview" -> preview(opts, args.contains("--perfect"), args.contains("--nojoin"))
        else -> println("usage: preview --in file.md --out out.png [--hand casual] [--seed 42] [--xheight 44] [--perfect]")
    }
}

private fun preview(o: Map<String, String>, perfect: Boolean, noJoin: Boolean) {
    val text = File(o["in"] ?: error("--in required")).readText()
    val hand = loadHand(o["hand"] ?: "casual")
    val seed = (o["seed"] ?: "42").toLong()
    val xh = (o["xheight"] ?: "44").toFloat()
    val page = PageSize.PORTRAIT
    val region = LayoutRegion.below(page, LayoutRegion.MARGIN, 200f, xh)
    val base = if (perfect) HumanParams.PERFECT else if (o["hand"] == "formal") HumanParams.FORMAL else HumanParams.CASUAL
    val human = if (noJoin) base.copy(joinLetters = false) else base
    val plan = ScribePlanUseCase(hand, region, OccupancyLookup { null }, seed, human, speedProfile(HandSpeed.Natural))
    val parser = AnswerParser()
    val events = parser.feed(text) + parser.finish()
    var state = plan.start()
    val strokes = ArrayList<com.nisaba.scribe.motion.TimedStroke>()
    for (e in events.filterIsInstance<ParseEvent.BlockReady>()) {
        val step = plan(state, e.block)
        strokes += step.strokes
        state = step.state
    }
    val out = File(o["out"] ?: "captures/preview.png")
    val r = ScribePreviewRenderer(page.w.toInt(), page.h.toInt(), 0.5f)
    val pages = strokes.maxOfOrNull { it.page } ?: 0
    for (p in 0..pages) {
        val bottom = strokes.filter { it.page == p }.maxOfOrNull { it.points.ys.max() }?.plus(120f)
        val file = if (p == 0) out else File(out.path.replace(".png", "-p$p.png"))
        r.save(r.render(strokes, p, cropBottom = bottom?.coerceAtMost(page.h)), file)
    }
    val ms = state.motion.timeMs
    val seconds = "%.1f".format(ms / 1000f)
    val summary = "blocks=${state.blocks} strokes=${strokes.size} pages=${pages + 1}"
    println("$summary writingTime=${ms}ms (${seconds}s) chars=${state.motion.chars} -> $out")
}
