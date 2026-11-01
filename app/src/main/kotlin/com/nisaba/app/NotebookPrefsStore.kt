package com.nisaba.app

import android.content.Context
import com.nisaba.domain.conversation.Utterance
import com.nisaba.domain.shared.NotebookId
import com.nisaba.domain.shared.PageId
import com.nisaba.domain.shared.PagePoint
import com.nisaba.domain.shared.PageRect
import com.nisaba.domain.shared.StrokeId

/**
 * What the app remembers about notebooks outside the database (small, per device): where each endless canvas was
 * left, the page each notebook was left on, and each page's unanswered question.
 */
class NotebookPrefsStore(context: Context) {
    private val viewPrefs = context.getSharedPreferences("views", Context.MODE_PRIVATE)
    private val lastPagePrefs = context.getSharedPreferences("last_page", Context.MODE_PRIVATE)
    private val pendingPrefs = context.getSharedPreferences("pending_questions", Context.MODE_PRIVATE)
    private val dailyPrefs = context.getSharedPreferences("daily_page", Context.MODE_PRIVATE)

    fun lastDaily(nb: NotebookId): String? = dailyPrefs.getString(nb.value, null)

    fun saveLastDaily(nb: NotebookId, day: String) = dailyPrefs.edit().putString(nb.value, day).apply()

    /** "x,y" or "x,y,zoom": where an endless canvas's view was left. */
    private fun view(page: PageId): List<Float>? =
        viewPrefs.getString(page.value, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size >= 2 }

    fun loadView(page: PageId): PagePoint? = view(page)?.let { PagePoint(it[0], it[1]) }

    fun loadZoom(page: PageId): Float = view(page)?.getOrNull(2)?.takeIf { it > 0f } ?: 1f

    fun saveView(page: PageId, at: PagePoint, zoom: Float) = viewPrefs.edit().putString(page.value, "${at.x},${at.y},$zoom").apply()

    fun lastPage(nb: NotebookId): PageId? = lastPagePrefs.getString(nb.value, null)?.let(::PageId)

    fun saveLastPage(nb: NotebookId, page: PageId) = lastPagePrefs.edit().putString(nb.value, page.value).apply()

    fun loadPending(page: PageId): Utterance? = pendingPrefs.getString(page.value, null)?.let { decodePending(page, it) }

    fun savePending(page: PageId, utt: Utterance?) = pendingPrefs.edit().apply {
        if (utt == null) remove(page.value) else putString(page.value, encodePending(utt))
    }.apply()

    /** "l,t,r,b|strokeId;strokeId": a page's unanswered question (its strokes are on the page). */
    private fun encodePending(u: Utterance) =
        "${u.bounds.left},${u.bounds.top},${u.bounds.right},${u.bounds.bottom}|" + u.strokeIds.joinToString(";") { it.value }

    private fun decodePending(page: PageId, s: String): Utterance? = runCatching {
        val (box, ids) = s.split('|', limit = 2)
        val n = box.split(',').map { it.toFloat() }
        Utterance(page, ids.split(';').filter { it.isNotBlank() }.mapTo(HashSet()) { StrokeId(it) }, PageRect(n[0], n[1], n[2], n[3]))
    }.getOrNull()
}
