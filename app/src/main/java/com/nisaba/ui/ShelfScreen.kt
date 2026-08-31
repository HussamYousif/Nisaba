package com.nisaba.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
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
    )

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

        findViewById<TextView>(R.id.blankTome).apply {
            typeface = Fonts.kalam
            setOnClickListener { listener(ShelfAction.OpenBlank) }
        }
        findViewById<TextView>(R.id.pdfTome).apply {
            typeface = Fonts.kalam
            setOnClickListener { listener(ShelfAction.OpenTome) }
        }

        listView = findViewById(R.id.shelfList)
        emptyView = findViewById(R.id.shelfEmpty)
        listView.layoutManager = GridLayoutManager(context, 2)
        listView.adapter = adapter

        refresh()
    }

    /** Reloads the shelf from disk. */
    fun refresh() {
        val items = store?.list().orEmpty()
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private class ShelfAdapter(
        private val onOpen: (ShelfAction) -> Unit,
        private val onDelete: (NotebookSummary) -> Unit,
    ) : RecyclerView.Adapter<ShelfAdapter.Holder>() {

        private var items: List<NotebookSummary> = emptyList()

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

            val thumbFile = File(holder.itemView.context.filesDir, "notebooks/${item.id}/thumb.png")
            val bmp = if (thumbFile.exists()) {
                BitmapFactory.decodeFile(thumbFile.absolutePath)
            } else null
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
                holder.thumb.background = ColorDrawable(MagicTheme.PARCHMENT_DEEP)
            }

            holder.itemView.setOnClickListener { onOpen(ShelfAction.Open(item.id)) }
            holder.itemView.setOnLongClickListener {
                confirmDelete(holder.itemView.context, item)
                true
            }
        }

        private fun confirmDelete(context: Context, item: NotebookSummary) {
            AlertDialog.Builder(context)
                .setTitle(R.string.delete_title)
                .setMessage(context.getString(R.string.delete_message, item.title))
                .setPositiveButton(R.string.delete_confirm) { _, _ ->
                    onDelete(item)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun formatDate(iso: String): String = try {
            DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(iso))
        } catch (_: Exception) {
            ""
        }
    }
}
