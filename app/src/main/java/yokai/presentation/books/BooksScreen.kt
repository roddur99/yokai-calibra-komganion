package yokai.presentation.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import yokai.data.calibre.CalibreBook

private enum class BookSort { SERIES, TITLE, AUTHOR, NEWEST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(
    state: BooksState,
    coverLoader: suspend (String) -> ByteArray,
    onRetry: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(BookSort.SERIES) }
    var selected by remember { mutableStateOf<CalibreBook?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Light Novels") }) }) { padding ->
        when (state) {
            BooksState.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is BooksState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Retry")
                }
            }
            is BooksState.Content -> {
                val shown = remember(state.books, query, sort) {
                    state.books
                        .filter {
                            query.isBlank() ||
                                listOf(it.title, it.authors.joinToString(), it.series.orEmpty())
                                    .any { value -> value.contains(query, ignoreCase = true) }
                        }
                        .sortedWith(
                            when (sort) {
                                BookSort.SERIES -> compareBy<CalibreBook>(
                                    { it.series ?: it.title },
                                    { it.seriesIndex ?: 0.0 },
                                    { it.title },
                                )
                                BookSort.TITLE -> compareBy { it.title }
                                BookSort.AUTHOR -> compareBy(
                                    { it.authors.firstOrNull().orEmpty() },
                                    { it.title },
                                )
                                BookSort.NEWEST -> compareByDescending { it.updatedAt.orEmpty() }
                            },
                        )
                }
                Column(Modifier.fillMaxSize().padding(padding)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search title, author, or series") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    ) {
                        BookSort.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = sort == option,
                                onClick = { sort = option },
                                shape = SegmentedButtonDefaults.itemShape(index, BookSort.entries.size),
                            ) {
                                Text(option.name.lowercase().replaceFirstChar(Char::uppercase))
                            }
                        }
                    }
                    Text(
                        "${shown.size} books",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(shown, key = { it.id }) { book ->
                            BookCard(book, coverLoader) { selected = book }
                        }
                    }
                }
            }
        }
    }

    selected?.let { book ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(book.title) },
            text = {
                Text(
                    buildList {
                        if (book.authors.isNotEmpty()) add(book.authors.joinToString())
                        book.series?.let {
                            val index = book.seriesIndex
                                ?.toString()
                                ?.removeSuffix(".0")
                                ?.let { value -> " #$value" }
                                .orEmpty()
                            add(it + index)
                        }
                        if (book.epubUrl != null) add("EPUB available")
                    }.joinToString("\n"),
                )
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun BookCard(
    book: CalibreBook,
    coverLoader: suspend (String) -> ByteArray,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        val coverUrl = book.coverUrl ?: book.thumbnailUrl
        val cover by produceState<ByteArray?>(initialValue = null, coverUrl) {
            value = coverUrl?.let { runCatching { coverLoader(it) }.getOrNull() }
        }
        AsyncImage(
            model = cover,
            contentDescription = "Cover for ${book.title}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .padding(bottom = 6.dp),
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
