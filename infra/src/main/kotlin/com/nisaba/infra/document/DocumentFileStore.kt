package com.nisaba.infra.document

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.nisaba.domain.document.DocumentImporter
import com.nisaba.domain.document.DocumentInfo
import com.nisaba.domain.document.DocumentStore
import com.nisaba.domain.document.DocumentTextSource
import com.nisaba.domain.shared.DocumentId
import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.IdGen
import com.nisaba.domain.shared.Outcome
import com.nisaba.domain.shared.PageSize
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Imported documents live in `<docsDir>/<id>.pdf` with their text in `<id>.text.json`; page pictures are cached
 * separately by [DocumentRasterCache]. Implements importing, reading text back, and deleting.
 */
class FileDocumentStore(
    private val docsDir: File,
    private val rasterDir: File,
    private val resolver: ContentResolver,
    private val idGen: IdGen,
) : DocumentStore, DocumentImporter, DocumentTextSource {
    private val json = Json
    private val textCache = HashMap<String, List<String>>()

    override suspend fun delete(id: DocumentId) {
        docsDir.listFiles { f -> f.name.startsWith("${id.value}.") }?.forEach { it.delete() }
        File(rasterDir, id.value).deleteRecursively()
        synchronized(textCache) { textCache.remove(id.value) }
    }

    override suspend fun pages(id: DocumentId): List<String> = withContext(Dispatchers.IO) {
        synchronized(textCache) { textCache[id.value] }?.let { return@withContext it }
        val f = File(docsDir, "${id.value}.text.json")
        val pages = if (f.exists()) readPages(f) else emptyList()
        synchronized(textCache) { textCache[id.value] = pages }
        pages
    }

    private fun readPages(f: File): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), f.readText()) }.getOrDefault(emptyList())

    override suspend fun import(source: String, onProgress: (Float) -> Unit): Outcome<DocumentInfo> = withContext(Dispatchers.IO) {
        val id = DocumentId(idGen.next())
        docsDir.mkdirs()
        val target = File(docsDir, "${id.value}.pdf")
        try {
            copy(Uri.parse(source), target)
            val info = analyse(id, titleOf(Uri.parse(source)), target, onProgress)
            Outcome.Ok(info)
        } catch (@Suppress("SwallowedException") e: InvalidPasswordException) { // reported to the user as a plain message
            cleanup(id)
            Outcome.Err(DomainError.Invalid("password-protected PDFs aren't supported yet"))
        } catch (e: IOException) {
            cleanup(id)
            Outcome.Err(DomainError.Storage(e.message ?: "could not read the PDF"))
        }
    }

    private fun cleanup(id: DocumentId) { docsDir.listFiles { f -> f.name.startsWith("${id.value}.") }?.forEach { it.delete() } }

    private fun copy(uri: Uri, target: File) {
        val input = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
    }

    private fun titleOf(uri: Uri): String {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        return name.substringBeforeLast('.').ifBlank { "Untitled PDF" }
    }

    /** Page sizes (scaled to the page width) and per-page text, with progress after each page. */
    private fun analyse(id: DocumentId, title: String, file: File, onProgress: (Float) -> Unit): DocumentInfo =
        PDDocument.load(file).use { doc ->
            val count = doc.numberOfPages
            if (count == 0) throw IOException("the PDF has no pages")
            val sizes = List(count) { i -> pageSizeOf(doc, i) }
            val stripper = PDFTextStripper()
            val texts = ArrayList<String>(count)
            for (i in 0 until count) {
                stripper.startPage = i + 1; stripper.endPage = i + 1
                texts += runCatching { stripper.getText(doc).trim() }.getOrDefault("")
                onProgress((i + 1) / count.toFloat())
            }
            File(docsDir, "${id.value}.text.json").writeText(json.encodeToString(ListSerializer(String.serializer()), texts))
            synchronized(textCache) { textCache[id.value] = texts }
            DocumentInfo(id, title, sizes, hasText = texts.sumOf { it.length } > MIN_CHARS_PER_PAGE * count)
        }

    private fun pageSizeOf(doc: PDDocument, i: Int): PageSize {
        val page = doc.getPage(i)
        val box = page.cropBox ?: page.mediaBox
        val rotated = page.rotation % HALF_TURN != 0
        val w = if (rotated) box.height else box.width
        val h = if (rotated) box.width else box.height
        return PageSize(PageSize.PORTRAIT.w, PageSize.PORTRAIT.w * h / w)
    }

    private companion object {
        const val MIN_CHARS_PER_PAGE = 20
        const val HALF_TURN = 180
    }
}
