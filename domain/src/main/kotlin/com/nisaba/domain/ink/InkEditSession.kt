package com.nisaba.domain.ink

import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The ink editing actions a notebook page needs. */
class InkUseCases(
    val load: LoadPageInkUseCase,
    val commit: CommitStrokeUseCase,
    val commitMany: CommitStrokesUseCase,
    val erase: EraseUseCase,
    val undo: UndoUseCase,
    val redo: RedoUseCase,
    val eraseAnswer: EraseAnswerUseCase,
    val replace: ReplaceStrokesUseCase,
    val remove: RemoveStrokesUseCase,
)

/**
 * The open page's ink and its neighbours', each with its undo history, changed one edit at a time. An edit made for a
 * page that is no longer open (the person turned while it was in flight) lands on that page, never on the open one;
 * turning back finds a page as it was left, undo included.
 */
class InkEditSession(private val ink: InkUseCases) {
    private val lock = Mutex()
    private val nearby = HashMap<PageId, InkEditState>() // guarded by [lock]

    /** The open page (an immutable snapshot; safe to read any time). */
    @Volatile var open: InkEditState? = null
        private set

    /** Opens [pageId]; the page left behind stays at hand. */
    suspend fun open(pageId: PageId): InkEditState = lock.withLock {
        open?.let { nearby[it.ink.pageId] = it }
        (nearby.remove(pageId) ?: ink.load(pageId)).also { open = it }
    }

    /** Keeps only [keep] at hand, loading what's missing, as long as [stillWanted] (the reader may have moved on). */
    suspend fun preload(keep: Set<PageId>, stillWanted: () -> Boolean) {
        lock.withLock { nearby.keys.retainAll(keep) }
        for (id in keep) {
            if (lock.withLock { id in nearby || open?.ink?.pageId == id }) continue
            val loaded = ink.load(id)
            lock.withLock { if (id !in nearby && open?.ink?.pageId != id && stillWanted()) nearby[id] = loaded }
        }
    }

    /** Applies [change] to [pageId], open or not. True when it was the open page (what's on screen changed). */
    suspend fun editAny(pageId: PageId, change: suspend (InkEditState) -> InkEditState): Boolean = lock.withLock {
        val cur = open
        if (cur != null && cur.ink.pageId == pageId) {
            open = change(cur); true
        } else {
            nearby[pageId] = change(nearby[pageId] ?: ink.load(pageId)); false
        }
    }

    /** Applies [change] only while [pageId] is open (undo, redo, erase); null = nothing to do. True if it changed. */
    suspend fun editOpen(pageId: PageId, change: suspend (InkEditState) -> InkEditState?): Boolean = lock.withLock {
        val cur = open?.takeIf { it.ink.pageId == pageId } ?: return@withLock false
        val next = change(cur)?.takeIf { it !== cur } ?: return@withLock false
        open = next
        true
    }
}
