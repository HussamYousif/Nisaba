package com.nisaba.domain.document

import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.PageSize

/** What an import learned about a document; page sizes are in page units (1860 wide, height by aspect). */
data class DocumentInfo(
    val id: DocumentId,
    val title: String,
    val pageSizes: List<PageSize>,
    val hasText: Boolean,
) {
    val pageCount: Int get() = pageSizes.size
}
