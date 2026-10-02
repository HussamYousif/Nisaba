package com.nisaba.domain.document

import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.Outcome

interface DocumentStore {
    suspend fun delete(id: DocumentId)
}

/** Brings a file the user picked into the app: copy, measure, extract text. [source] is an opaque location (a URI). */
interface DocumentImporter {
    suspend fun import(source: String, onProgress: (Float) -> Unit): Outcome<DocumentInfo>
}

/** Text of an imported document, one string per page (empty strings for scanned pages). */
interface DocumentTextSource {
    suspend fun pages(id: DocumentId): List<String>
}
