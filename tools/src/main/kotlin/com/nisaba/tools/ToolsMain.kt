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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
        "eval" -> eval(opts)
        "book" -> book(opts)
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

/**
 * `book --pdf book.pdf [--walk 2] [--answer "their answer to the last page's question"] [--persona teacher] [--model m]`:
 * reads the book into its map as the app does (text from `pdftotext`, one page per form feed), then walks the first
 * pages of it; see BookRunUseCase.
 */
private fun book(o: Map<String, String>) {
    val pdf = File(o["pdf"] ?: error("--pdf required"))
    val text = ProcessBuilder("pdftotext", "-enc", "UTF-8", pdf.path, "-").redirectErrorStream(false).start()
        .inputStream.bufferedReader().readText()
    val pages = text.split('\u000C').let { if (it.lastOrNull()?.isBlank() == true) it.dropLast(1) else it }
    val settings = com.nisaba.domain.settings.Settings(model = o["model"] ?: "deepseek-flash")
    val run = BookRunUseCase(EvalOracleClient(deepSeekKey()), EvalPageRenderer(loadHand("casual")), settings)
    run(pdf.nameWithoutExtension, pages, com.nisaba.domain.persona.PersonaRegistry.of(o["persona"] ?: "teacher"),
        (o["walk"] ?: "0").toInt(), o["answer"])
}

private fun deepSeekKey(): String =
    File(".env").readLines().firstOrNull { it.startsWith("DEEPSEEK_API_KEY=") }?.substringAfter('=')?.trim('"', ' ')
        ?: error("DEEPSEEK_API_KEY missing from .env")

/**
 * `eval [--persona id,id|all] [--case id,id] [--temperature-scale 1.0] [--out captures/eval.json]`: the real request
 * builder and parser against DeepSeek (key from .env), every voice × case scored; see EvalCaseModel.
 */
private fun eval(o: Map<String, String>) {
    val key = deepSeekKey()
    val personas = o["persona"]?.takeIf { it != "all" }?.split(',')?.map { com.nisaba.domain.persona.PersonaRegistry.of(it) }
        ?: com.nisaba.domain.persona.PersonaRegistry.all
    val cases = o["case"]?.split(',')?.let { ids -> EVAL_CASES.filter { it.id in ids } } ?: EVAL_CASES
    val scale = (o["temperature-scale"] ?: "1.0").toDouble()
    val run = EvalRunUseCase(EvalPageRenderer(loadHand("casual")), EvalOracleClient(key))
    val results = run(personas, cases, scale) { r ->
        val head = "${if (r.ok) "ok " else "BAD"} ${r.persona.padEnd(PAD_VOICE)} ${r.case.padEnd(PAD_CASE)}"
        println("$head ${r.words}w ${r.millis}ms ${r.problems.joinToString("; ")}")
        if (!r.ok || r.case == "grief") println("      " + r.reply.replace("\n", " / ").take(SHOWN))
    }
    val tokens = "tokens in ${results.sumOf { it.inputTokens }} out ${results.sumOf { it.outputTokens }}"
    println("eval: ${results.count { it.ok }}/${results.size} ok at temperature ×$scale · $tokens")
    val out = File(o["out"] ?: "captures/eval-${System.currentTimeMillis() / 1000}.json")
    out.parentFile?.mkdirs()
    out.writeText(JsonArray(results.map(::evalJson)).toString())
    println("-> $out")
}

private fun evalJson(r: EvalResult) = buildJsonObject {
    put("persona", JsonPrimitive(r.persona)); put("case", JsonPrimitive(r.case)); put("ok", JsonPrimitive(r.ok))
    put("problems", JsonPrimitive(r.problems.joinToString("; ")))
    put("words", JsonPrimitive(r.words)); put("reply", JsonPrimitive(r.reply))
}

private const val PAD_VOICE = 9
private const val PAD_CASE = 7
private const val SHOWN = 300
