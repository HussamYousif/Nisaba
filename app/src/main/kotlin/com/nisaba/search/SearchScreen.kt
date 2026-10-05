package com.nisaba.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nisaba.domain.search.SearchHit
import com.nisaba.ui.theme.Nisaba
import com.nisaba.ui.theme.NisabaButton
import com.nisaba.ui.theme.NisabaTextField

private const val RESULT_LIMIT = 10 // what SearchUseCase returns at most

/** Every question asked and answer given, across notebooks; tap one to open its page. */
@Composable
fun SearchScreen(state: SearchUiState, vm: SearchViewModel, onBack: () -> Unit, onOpen: (SearchHit) -> Unit) {
    val c = Nisaba.colors
    Column(Modifier.fillMaxSize().background(c.paper).padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            NisabaButton("Done", onBack, filled = true)
        }
        NisabaTextField(state.query, vm::onQuery)
        Text(
            when {
                state.query.isBlank() -> "Words from your questions or Nisaba's answers, in any notebook."
                state.searched && state.hits.isEmpty() -> "Nothing found."
                state.searched && state.hits.size == 1 -> "1 match."
                state.searched && state.hits.size >= RESULT_LIMIT -> "The $RESULT_LIMIT newest matches."
                state.searched -> "${state.hits.size} matches."
                else -> ""
            },
            style = MaterialTheme.typography.bodyLarge, color = c.faint,
        )
        for (hit in state.hits) {
            Column(
                Modifier.fillMaxWidth().border(BorderStroke(1.dp, c.ink)).clickable { onOpen(hit) }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("${hit.notebookName} · page ${hit.pageNumber}", style = MaterialTheme.typography.labelLarge, color = c.faint)
                if (hit.question.isNotBlank()) {
                    Text(hit.question, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(hit.answer, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
