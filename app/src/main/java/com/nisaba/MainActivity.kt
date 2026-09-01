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
import com.nisaba.llm.AppSettings
import com.nisaba.magic.Fonts
import com.nisaba.notebook.NotebookStore
import com.nisaba.pdf.PdfImporter
import com.nisaba.ui.NotebookScreen
import com.nisaba.ui.SettingsScreen
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
    private lateinit var settings: AppSettings
    private lateinit var container: FrameLayout
    private var notebookScreen: NotebookScreen? = null
    private var settingsScreen: SettingsScreen? = null
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NotebookStore(this)
        settings = AppSettings(this)
        Fonts.load(assets)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        hideSystemBars()
        // Audit P4-7: onBackPressed() is deprecated on targetSdk 35.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (notebookScreen != null || settingsScreen != null) {
                    showShelf()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        showShelf()
    }

    override fun onDestroy() {
        // Without this, killing the activity with a notebook open leaves the
        // screen scopes, the in-flight HTTP call, the nisaba-bg executor and
        // the whole Notebook retained (audit P1-2). onClose() is idempotent.
        notebookScreen?.onClose()
        notebookScreen = null
        settingsScreen?.onClose()
        settingsScreen = null
        super.onDestroy()
        importScope.cancel()
    }

    override fun onPause() {
        super.onPause()
        notebookScreen?.saveNow()
    }

    // ------------------------------------------------------------ screens

    private fun showShelf() {
        notebookScreen?.onClose()
        notebookScreen = null
        settingsScreen?.onClose()
        settingsScreen = null
        container.removeAllViews()
        val shelf = ShelfScreen(this).apply {
            bind(store) { action ->
                when (action) {
                    ShelfScreen.ShelfAction.OpenBlank -> openNotebook(store.createBlank().id)
                    ShelfScreen.ShelfAction.OpenTome ->
                        pdfPicker.launch(arrayOf("application/pdf"))
                    ShelfScreen.ShelfAction.OpenSettings -> showSettings()
                    is ShelfScreen.ShelfAction.Open -> openNotebook(action.id)
                }
            }
        }
        container.addView(shelf)
    }

    private fun showSettings() {
        notebookScreen?.onClose()
        notebookScreen = null
        container.removeAllViews()
        val screen = SettingsScreen(this).apply {
            bind(settings) { showShelf() }
        }
        settingsScreen = screen
        container.addView(screen)
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
            var importError: Exception? = null
            val result = withContext(Dispatchers.IO) {
                // Explicit try/catch, not runCatching: a cancelled import must
                // propagate (audit P1-11), or the code below keeps running
                // against a destroyed activity.
                try {
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    importError = e
                    null
                }
            }
            dialog.dismiss()
            if (result != null) {
                openNotebook(result.notebook.id)
            } else if (importError !is PdfImporter.ImportCancelled) {
                Toast.makeText(this@MainActivity, R.string.tome_import_failed, Toast.LENGTH_LONG).show()
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
        settingsScreen?.onClose()
        settingsScreen = null
        val notebook = try {
            store.load(id)
        } catch (e: Exception) {
            // Corrupt file or a newer schema — never crash the app on open
            // (audit P2-5); the user stays on the shelf with a clear word.
            Toast.makeText(this, R.string.tome_open_failed, Toast.LENGTH_LONG).show()
            return
        }
        val screen = NotebookScreen(this).apply {
            bind(
                notebook = notebook,
                settings = this@MainActivity.settings,
                onBack = {
                    showShelf()
                },
                onSave = { nb, thumbnail -> store.save(nb, thumbnail = thumbnail) },
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
