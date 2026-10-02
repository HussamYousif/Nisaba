package com.nisaba.domain.ink

import com.nisaba.domain.shared.PageId

interface InkStore {
    suspend fun load(pageId: PageId): PageInk
    suspend fun save(pageId: PageId, strokes: List<Stroke>)
    suspend fun deletePages(ids: List<PageId>)
}
