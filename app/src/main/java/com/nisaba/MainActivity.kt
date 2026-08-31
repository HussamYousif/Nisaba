package com.nisaba

import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nisaba.magic.Fonts
import com.nisaba.notebook.NotebookStore
import com.nisaba.pdf.PdfImporter
import com.nisaba.ui.NotebookScreen
import com.nisaba.ui.ShelfScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-activity host (docs/03 §2): swaps the shelf and the notebook screen
 * inside [R.id.container]; owns the PDF picker + import flow.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: NotebookStore
    private lateinit var container: FrameLayout
    private var notebookScreen: NotebookScreen? = null
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NotebookStore(this)
        Fonts.load(assets)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        hideSystemBars()
        showShelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        importScope.cancel()
    }

    override fun onPause() {
        super.onPause()
        notebookScreen?.saveNow()
    }

    override fun onBackPressed() {
        if (notebookScreen != null) {
            showShelf()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------ screens

    private fun showShelf() {
        notebookScreen?.onClose()
        notebookScreen = null
        container.removeAllViews()
        val shelf = ShelfScreen(this).apply {
            bind(store) { action ->
                when (action) {
                    ShelfScreen.ShelfAction.OpenBlank -> openNotebook(store.createBlank().id)
                    ShelfScreen.ShelfAction.OpenTome ->
                        pdfPicker.launch(arrayOf("application/pdf"))
                    is ShelfScreen.ShelfAction.Open -> openNotebook(action.id)
                }
            }
        }
        container.addView(shelf)
    }

    // ------------------------------------------------------------ PDF import

    /** Copies + renders the picked PDF into a new notebook (docs/06). */
    private fun importPdf(uri: Uri) {
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tome_import_title)
            .setMessage(getString(R.string.tome_import_progress, 0, 0))
            .setNegativeButton(R.string.tome_import_cancel) { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .create()
        dialog.show()

        importScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openInputStream(uri)
                        ?: throw IOException("cannot open picked PDF")
                    try {
                        PdfImporter(store).importFrom(
                            stream = stream,
                            displayName = queryDisplayName(uri),
                            progress = { done, total ->
                                runOnUiThread {
                                    dialog.setMessage(getString(R.string.tome_import_progress, done, total))
                                }
                            },
                            cancelled = { cancelled.get() },
                        )
                    } finally {
                        stream.close()
                    }
                }
            }
            dialog.dismiss()
            result
                .onSuccess { r -> openNotebook(r.notebook.id) }
                .onFailure { e ->
                    if (e !is PdfImporter.ImportCancelled) {
                        Toast.makeText(this@MainActivity, R.string.tome_import_failed, Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    /** Best-effort original file name from the SAF Uri. */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(
            android.provider.OpenableColumns.DISPLAY_NAME
        ), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun openNotebook(id: String) {
        val notebook = store.load(id)
        val screen = NotebookScreen(this).apply {
            bind(
                notebook = notebook,
                onBack = {
                    showShelf()
                },
                onSave = { nb -> store.save(nb) },
            )
        }
        notebookScreen = screen
        container.removeAllViews()
        container.addView(screen)
    }

    // ------------------------------------------------------------ chrome

    /** Immersive: the whole e-ink panel belongs to the page. */
    private fun hideSystemBars() {
        val window = window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
