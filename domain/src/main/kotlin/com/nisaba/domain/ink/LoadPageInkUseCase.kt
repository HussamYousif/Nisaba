package com.nisaba.domain.ink

import com.nisaba.domain.shared.PageId

class LoadPageInkUseCase(private val store: InkStore) {
    suspend operator fun invoke(pageId: PageId): InkEditState = InkEditState(store.load(pageId))
}
