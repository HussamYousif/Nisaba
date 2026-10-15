package com.nisaba.infra.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nisaba.domain.notebook.Notebook
import com.nisaba.domain.shared.NotebookId
import com.nisaba.infra.db.NisabaDatabase
import com.nisaba.infra.db.RoomNotebookStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aBackupBringsBackNotebooksAndInkAndRefusesWhatIsNotOne() = runBlocking {
        val store = RoomNotebookStore(NisabaDatabase.shared(context).notebooks())
        val id = NotebookId("backup-test")
        store.create(Notebook(id, "Kept", 1, 1, null, true, 7), emptyList())
        val ink = File(context.filesDir, "ink/backup-test.ink").apply { parentFile?.mkdirs(); writeText("ink") }
        val zip = ByteArrayOutputStream().also { BackupStore(context).backup(it) }.toByteArray()

        store.delete(id); ink.delete()
        assertNull(store.get(id))
        assertTrue(BackupStore(context).restore(ByteArrayInputStream(zip)))
        val again = RoomNotebookStore(NisabaDatabase.shared(context).notebooks()) // the restore closed the old one
        assertEquals("Kept", again.get(id)?.notebook?.name)
        assertEquals("ink", File(context.filesDir, "ink/backup-test.ink").readText())

        assertFalse(BackupStore(context).restore(ByteArrayInputStream("not a zip".toByteArray())))
        assertNotNull(again.get(id)) // nothing was touched
        again.delete(id)
    }
}
