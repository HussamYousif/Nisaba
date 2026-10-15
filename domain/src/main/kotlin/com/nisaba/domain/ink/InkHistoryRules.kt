package com.nisaba.domain.ink

const val MAX_UNDO = 200

fun applyCommand(strokes: List<Stroke>, cmd: InkCommand): List<Stroke> = when (cmd) {
    is InkCommand.Add -> {
        val have = strokes.mapTo(HashSet()) { it.id }
        strokes + cmd.strokes.filter { it.id !in have }
    }
    is InkCommand.Remove -> {
        val gone = cmd.strokes.mapTo(HashSet()) { it.id }
        strokes.filter { it.id !in gone }
    }
    is InkCommand.Replace -> applyCommand(applyCommand(strokes, InkCommand.Remove(cmd.removed)), InkCommand.Add(cmd.added))
}

fun invert(cmd: InkCommand): InkCommand = when (cmd) {
    is InkCommand.Add -> InkCommand.Remove(cmd.strokes)
    is InkCommand.Remove -> InkCommand.Add(cmd.strokes)
    is InkCommand.Replace -> InkCommand.Replace(removed = cmd.added, added = cmd.removed)
}

/**
 * Pushes [cmd]. An answer is committed block by block as the hand writes it; consecutive additions of the same answer
 * merge into one step, so undo still takes the whole answer at once.
 */
fun push(history: InkHistory, cmd: InkCommand): InkHistory {
    val last = history.undo.lastOrNull()
    val undo = if (last is InkCommand.Add && cmd is InkCommand.Add && sameAnswer(last.strokes, cmd.strokes)) {
        history.undo.dropLast(1) + InkCommand.Add(last.strokes + cmd.strokes)
    } else {
        history.undo + cmd
    }
    return InkHistory(undo = undo.takeLast(MAX_UNDO), redo = emptyList())
}

private fun answerOf(strokes: List<Stroke>): com.nisaba.domain.shared.ExchangeId? {
    val id = (strokes.firstOrNull()?.origin as? StrokeOrigin.Oracle)?.exchangeId ?: return null
    return id.takeIf { strokes.all { s -> (s.origin as? StrokeOrigin.Oracle)?.exchangeId == id } }
}

private fun sameAnswer(a: List<Stroke>, b: List<Stroke>) = answerOf(a)?.let { it == answerOf(b) } ?: false

/** Pops the last command; returns the new history and the command to APPLY (the inverse). */
fun undo(history: InkHistory): Pair<InkHistory, InkCommand>? {
    val last = history.undo.lastOrNull() ?: return null
    return InkHistory(history.undo.dropLast(1), history.redo + last) to invert(last)
}

fun redo(history: InkHistory): Pair<InkHistory, InkCommand>? {
    val last = history.redo.lastOrNull() ?: return null
    return InkHistory(history.undo + last, history.redo.dropLast(1)) to last
}
