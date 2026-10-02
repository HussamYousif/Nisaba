package com.nisaba.domain.answer

sealed interface Inline {
    data class Text(val s: String, val bold: Boolean = false) : Inline
    data class Math(val latex: String) : Inline
}

typealias Cell = List<Inline>

data class DiagramLabel(val text: String, val x: Int, val y: Int)

sealed interface Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(val inlines: List<Inline>) : Block
    data class ListItem(val ordered: Boolean, val marker: String, val depth: Int, val inlines: List<Inline>) : Block
    data class MathBlock(val latex: String) : Block
    data class Table(val header: List<Cell>, val rows: List<List<Cell>>) : Block
    data class Diagram(val svg: String, val labels: List<DiagramLabel>) : Block
    data object Rule : Block
}

data class AnswerDocument(val blocks: List<Block>)

/** What the streaming parser reports, in order. */
sealed interface ParseEvent {
    data object Silent : ParseEvent
    data class BlockReady(val block: Block) : ParseEvent
    data class Heard(val text: String) : ParseEvent
}
