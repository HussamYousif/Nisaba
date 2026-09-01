package com.nisaba.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nisaba.R
import com.nisaba.magic.Fonts
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.NotebookStore
import com.nisaba.notebook.NotebookSummary
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * The shelf of tomes (docs/09 §6). Two creation actions plus the notebook list.
 */
class ShelfScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    sealed interface ShelfAction {
        data object OpenBlank : ShelfAction
        data object OpenTome : ShelfAction
        data object OpenSettings : ShelfAction
        data class Open(val id: String) : ShelfAction
    }

    private var store: NotebookStore? = null
    private var listener: ((ShelfAction) -> Unit)? = null
    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = ShelfAdapter(
        onOpen = { action -> listener?.invoke(action) },
        onDelete = { item ->
            store?.delete(item.id)
            refresh()
        },
        onRename = { id, title ->
            store?.rename(id, title)
            refresh()
        },
    )
    // Shelf listing does disk I/O (summary decode of every tome); never on
    // the main thread (audit P1-4).
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nisaba-shelf").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        orientation = VERTICAL
    }

    fun bind(store: NotebookStore, listener: (ShelfAction) -> Unit) {
        this.store = store
        this.listener = listener
        LayoutInflater.from(context).inflate(R.layout.shelf_screen, this, true)

        findViewById<TextView>(R.id.shelfTitle).apply {
            typeface = Fonts.dancingScript
        }
        findViewById<TextView>(R.id.shelfSettingsBtn).apply {
            typeface = Fonts.kalam
            setOnClickListener { listener(ShelfAction.OpenSettings) }
        }

        findViewById<TextView>(R.id.shelfTagline).apply { typeface = Fonts.kalam }
        findViewById<TextView>(R.id.blankGlyph).apply { typeface = Fonts.greatVibes }
        findViewById<TextView>(R.id.blankTitle).apply { typeface = Fonts.kalamBold }
        findViewById<TextView>(R.id.blankDesc).apply { typeface = Fonts.kalam }
        findViewById<TextView>(R.id.pdfGlyph).apply { typeface = Fonts.greatVibes }
        findViewById<TextView>(R.id.pdfTitle).apply { typeface = Fonts.kalamBold }
        findViewById<TextView>(R.id.pdfDesc).apply { typeface = Fonts.kalam }
        findViewById<View>(R.id.blankTome).setOnClickListener { listener(ShelfAction.OpenBlank) }
        findViewById<View>(R.id.pdfTome).setOnClickListener { listener(ShelfAction.OpenTome) }

        listView = findViewById(R.id.shelfList)
        emptyView = findViewById(R.id.shelfEmpty)
        listView.layoutManager = GridLayoutManager(context, 2)
        listView.adapter = adapter

        refresh()
    }

    /** Reloads the shelf from disk (off the main thread, P1-4). */
    fun refresh() {
        val store = this.store ?: return
        io.execute {
            val items = store.list()
            mainHandler.post {
                // The screen may have been rebound to another store meanwhile.
                if (this.store === store) {
                    adapter.submit(items)
                    emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private class ShelfAdapter(
        private val onOpen: (ShelfAction) -> Unit,
        private val onDelete: (NotebookSummary) -> Unit,
        private val onRename: (id: String, title: String) -> Unit,
    ) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

        private var items: List<NotebookSummary> = emptyList()

        // Small decode cache: refresh() → notifyDataSetChanged() would
        // otherwise re-decode every visible thumbnail each time (audit P1-4).
        private val thumbs = LruCache<String, Bitmap>(THUMB_CACHE_BYTES)

        fun submit(newItems: List<NotebookSummary>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.tomeThumb)
            val title: TextView = view.findViewById(R.id.tomeTitle)
            val date: TextView = view.findViewById(R.id.tomeDate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tome, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.title.typeface = Fonts.kalamBold
            holder.date.text = formatDate(item.updatedAt)
            holder.date.typeface = Fonts.kalam

            val bmp = thumbOf(holder.itemView, item.id)
            if (bmp != null) {
                holder.thumb.setImageBitmap(bmp)
            } else {
                holder.thumb.setImageDrawable(
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(MagicTheme.PARCHMENT)
                        cornerRadius = 8f
                    }
                )
                holder.thumb.background = ColorDrawable(MagicTheme.PARCHMENT)
            }

            holder.itemView.setOnClickListener { onOpen(ShelfAction.Open(item.id)) }
            holder.itemView.setOnLongClickListener {
                confirmActions(holder.itemView.context, item)
                true
            }
        }

        /** Cached, downsized thumbnail decode (audit P1-4). */
        private fun thumbOf(view: View, id: String): Bitmap? {
            thumbs.get(id)?.let { return it }
            val f = File(view.context.filesDir, "notebooks/$id/thumb.png")
            if (!f.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= THUMB_W ||
                bounds.outHeight / (sample * 2) >= THUMB_H
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
            thumbs.put(id, bmp)
            return bmp
        }

        /** Long-press: rename (F1.5, M5) or smudge away (F1.4). */
        private fun confirmActions(context: Context, item: NotebookSummary) {
            val options = arrayOf(
                context.getString(R.string.rename_title),
                context.getString(R.string.delete_confirm),
            )
            AlertDialog.Builder(context)
                .setTitle(item.title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> promptRename(context, item)
                        1 -> onDelete(item)
                    }
                }
                .setNegativeButton(R.string.dismiss, null)
                .show()
        }

        private fun promptRename(context: Context, item: NotebookSummary) {
            val input = android.widget.EditText(context).apply {
                setText(item.title)
                selectAll()
            }
            AlertDialog.Builder(context)
                .setTitle(R.string.rename_title)
                .setView(input)
                .setPositiveButton(R.string.rename_confirm) { _, _ ->
                    onRename(item.id, input.text.toString())
                }
                .setNegativeButton(R.string.dismiss, null)
                .show()
        }

        private fun formatDate(iso: String): String = try {
            DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(iso))
        } catch (_: Exception) {
            ""
        }

        companion object {
            private const val THUMB_W = 480
            private const val THUMB_H = 360
            private const val THUMB_CACHE_BYTES = 4 * 1024 * 1024 // ~4 MB
        }
    }
}
