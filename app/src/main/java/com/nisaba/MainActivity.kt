package com.nisaba

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nisaba.magic.Fonts
import com.nisaba.notebook.NotebookStore
import com.nisaba.ui.NotebookScreen
import com.nisaba.ui.ShelfScreen

/**
 * Single-activity host (docs/03 §2): swaps the shelf and the notebook screen
 * inside [R.id.container].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: NotebookStore
    private lateinit var container: FrameLayout
    private var notebookScreen: NotebookScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NotebookStore(this)
        Fonts.load(assets)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        hideSystemBars()
        showShelf()
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
                    ShelfScreen.ShelfAction.OpenTome -> Unit // handled inside the shelf
                    is ShelfScreen.ShelfAction.Open -> openNotebook(action.id)
                }
            }
        }
        container.addView(shelf)
    }

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
