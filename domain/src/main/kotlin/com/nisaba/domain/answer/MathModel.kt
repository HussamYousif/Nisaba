package com.nisaba.domain.answer

/** A formula as a tree the scribe can lay out. Symbols are single characters or names like "alpha", "sum", "le". */
sealed interface MathNode {
    data class Row(val items: List<MathNode>) : MathNode
    data class Sym(val name: String) : MathNode
    data class Frac(val num: MathNode, val den: MathNode) : MathNode
    data class Sup(val base: MathNode, val sup: MathNode) : MathNode
    data class Sub(val base: MathNode, val sub: MathNode) : MathNode
    data class SubSup(val base: MathNode, val sub: MathNode, val sup: MathNode) : MathNode
    data class Sqrt(val index: MathNode?, val body: MathNode) : MathNode

    /** Sum, product, integral or limit with optional bounds. [op] is "sum", "prod", "int" or "lim". */
    data class BigOp(val op: String, val lower: MathNode?, val upper: MathNode?) : MathNode
    data class Fenced(val left: String, val body: MathNode, val right: String) : MathNode
    data class Matrix(val rows: List<List<MathNode>>, val open: String, val close: String) : MathNode
    data class Text(val s: String) : MathNode

    companion object {
        val EMPTY: MathNode = Row(emptyList())
    }
}
