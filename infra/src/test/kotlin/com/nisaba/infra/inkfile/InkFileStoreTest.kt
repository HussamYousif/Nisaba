package com.nisaba.infra.inkfile

import com.nisaba.domain.shared.PageId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class InkFileStoreTest {
    private val dir: File = Files.createTempDirectory("ink").toFile()
    private val page = PageId("p1")
    private val strokes = listOf(sampleStroke("a", 1), sampleStroke("b", 2, oracle = true))

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test fun roundTripsAcrossInstances() = runTest {
        InkFileStore(dir, backgroundScope).apply { save(page, strokes); flush() }
        val loaded = InkFileStore(dir, backgroundScope).load(page)
        assertEquals(strokes, loaded.strokes)
    }

    @Test fun writesAreDebouncedButReadsSeePendingData() = runTest {
        val store = InkFileStore(dir, backgroundScope, debounceMillis = 500)
        store.save(page, strokes)
        assertFalse(File(dir, "p1.ink").exists())
        assertEquals(strokes, store.load(page).strokes)
        advanceTimeBy(600)
        assertTrue(File(dir, "p1.ink").exists())
    }

    @Test fun rapidSavesCollapseIntoTheLastOne() = runTest {
        val store = InkFileStore(dir, backgroundScope, debounceMillis = 500)
        store.save(page, listOf(strokes[0])); advanceTimeBy(200)
        store.save(page, strokes); advanceTimeBy(600)
        assertEquals(strokes, InkFileStore(dir, backgroundScope).load(page).strokes)
    }

    @Test fun truncatedMainFallsBackToBackup() = runTest {
        val store = InkFileStore(dir, backgroundScope)
        store.save(page, listOf(strokes[0])); store.flush()
        store.save(page, strokes); store.flush()
        File(dir, "p1.ink").writeBytes(File(dir, "p1.ink").readBytes().copyOf(7))
        assertEquals(listOf(strokes[0]), InkFileStore(dir, backgroundScope).load(page).strokes)
    }

    @Test fun garbageEverywhereYieldsEmptyPage() = runTest {
        File(dir, "p1.ink").writeBytes(ByteArray(64) { 0x7F })
        File(dir, "p1.ink.bak").writeBytes(ByteArray(3) { 1 })
        assertTrue(InkFileStore(dir, backgroundScope).load(page).strokes.isEmpty())
    }

    @Test fun missingFileIsAnEmptyPage() = runTest {
        assertTrue(InkFileStore(dir, backgroundScope).load(PageId("never")).strokes.isEmpty())
    }

    @Test fun newerFileVersionIsNotMisread() = runTest {
        val future = InkFileDto(version = INK_FILE_VERSION + 1, strokes = strokes.map { it.toDto() })
        File(dir, "p1.ink").writeBytes(ProtoBuf.encodeToByteArray(future))
        assertTrue(InkFileStore(dir, backgroundScope).load(page).strokes.isEmpty())
    }

    @Test fun deleteRemovesAllFilesAndPendingWrites() = runTest {
        val store = InkFileStore(dir, backgroundScope)
        store.save(page, strokes); store.flush(); store.save(page, strokes); store.flush()
        store.save(page, strokes)
        store.deletePages(listOf(page))
        advanceTimeBy(1000)
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("p1") })
        assertTrue(store.load(page).strokes.isEmpty())
    }
}
