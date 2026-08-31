package com.nisaba.render

/**
 * Pure SVG path-data tokenizer (docs/08 §4): "d" attribute → command list.
 * No android dependencies — JVM-testable. Supports M/L/C/Q/Z (upper and
 * lower case; lowercase = relative) plus H/V for robustness, and implicit
 * argument repetition per the SVG spec.
 */
object SvgPathParser {

    data class Cmd(val op: Char, val args: FloatArray)

    /** Returns null when the path data is malformed. */
    fun parse(d: String): List<Cmd>? {
        val cmds = ArrayList<Cmd>()
        var i = 0
        var op: Char? = null
        val args = ArrayList<Float>()

        fun expectedArgs(c: Char): Int = when (c.uppercaseChar()) {
            'M', 'L' -> 2
            'H', 'V' -> 1
            'C' -> 6
            'Q' -> 4
            'Z' -> 0
            else -> -1
        }

        fun flush(): Boolean {
            val o = op ?: return true
            val need = expectedArgs(o)
            if (need < 0) return false // unknown command
            if (args.isEmpty()) return true // op set, args still coming
            if (args.size != need) return false // malformed arg count
            cmds.add(Cmd(o, args.toFloatArray()))
            args.clear()
            return true
        }

        while (i < d.length) {
            val c = d[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c.isLetter() -> {
                    if (!flush()) return null
                    if (expectedArgs(c) < 0) return null // unknown command
                    op = c
                    if (expectedArgs(c) == 0) { // Z: no args, emit immediately
                        cmds.add(Cmd(c, FloatArray(0)))
                        op = null
                    }
                    i++
                }
                c == '-' || c == '+' || c.isDigit() || c == '.' -> {
                    if (op == null) return null // number without a command
                    val start = i
                    while (i < d.length && (d[i].isDigit() || d[i] in "+-.eE")) i++
                    val token = d.substring(start, i)
                    val v = token.toFloatOrNull() ?: return null
                    args.add(v)
                    // Implicit repetition: M→L, and C/Q continue with full arg sets.
                    if (op.uppercaseChar() == 'M' && args.size == 2) {
                        if (!flush()) return null
                        op = if (op == 'M') 'L' else 'l'
                    } else if (args.size == expectedArgs(op)) {
                        if (!flush()) return null
                    }
                }
                else -> return null
            }
        }
        if (!flush()) return null
        // SVG path data must start with a moveto.
        if (cmds.firstOrNull()?.op?.uppercaseChar() != 'M') return null
        return cmds.ifEmpty { null }
    }
}
