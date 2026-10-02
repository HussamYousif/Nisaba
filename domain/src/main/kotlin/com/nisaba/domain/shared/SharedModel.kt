package com.nisaba.domain.shared

@JvmInline value class NotebookId(val value: String)
@JvmInline value class PageId(val value: String)
@JvmInline value class StrokeId(val value: String)
@JvmInline value class ExchangeId(val value: String)
@JvmInline value class DocumentId(val value: String)

data class PagePoint(val x: Float, val y: Float)

data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Page space: 1 unit = 1 px of the Air 5C panel in portrait. */
data class PageSize(val w: Float, val h: Float) {
    /** An endless canvas is one huge page; its writing starts at [ENDLESS_ORIGIN] so every direction has room. */
    val isEndless: Boolean get() = w >= ENDLESS_MIN

    companion object {
        val PORTRAIT = PageSize(1860f, 2480f)
        val ENDLESS = PageSize(200_000f, 200_000f)
        const val ENDLESS_ORIGIN = 100_000f
        private const val ENDLESS_MIN = 100_000f
    }
}

/** Uniform scale + translation between page space and view space. */
data class Affine(val scale: Float, val dx: Float, val dy: Float)

enum class NetworkKind { Offline, RateLimited, Server, Timeout }

sealed interface DomainError {
    data object NotFound : DomainError
    data class Storage(val message: String) : DomainError
    data class Network(val kind: NetworkKind) : DomainError
    data object Auth : DomainError
    data object Quota : DomainError
    data class Invalid(val message: String) : DomainError
}

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: DomainError) : Outcome<Nothing>
}
