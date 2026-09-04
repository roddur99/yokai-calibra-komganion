package yokai.presentation.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import yokai.data.calibre.CalibreBook

private enum class BookSort { SERIES, TITLE, AUTHOR, NEWEST }

private enum class BookFilter { ALL, DOWNLOADED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(
    state: BooksState,
    coverLoader: suspend (String) -> ByteArray,
    progressLoader: (String) -> Int?,
    onRetry: () -> Unit,
    onDownload: (CalibreBook) -> Unit,
    onDeleteDownload: (CalibreBook) -> Unit,
    onOpen: (CalibreBook) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(BookSort.SERIES) }
    var filter by remember { mutableStateOf(BookFilter.ALL) }
    var selected by remember { mutableStateOf<CalibreBook?>(null) }
    val gridState = rememberLazyGridState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var progressRefreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) progressRefreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Light Novels") },
                scrollBehavior = topBarScrollBehavior,
            )
        },
    ) { padding ->
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
                val progressById = remember(state.books, progressRefreshKey) {
                    state.books.associate { it.id to progressLoader(it.id) }
                }
                val shown = remember(state.books, state.downloadedIds, query, sort, filter) {
                    state.books
                        .filter { book ->
                            filter == BookFilter.ALL || book.id in state.downloadedIds
                        }
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
                    state.operationError?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = filter == BookFilter.ALL,
                            onClick = { filter = BookFilter.ALL },
                            label = { Text("All") },
                        )
                        FilterChip(
                            selected = filter == BookFilter.DOWNLOADED,
                            onClick = { filter = BookFilter.DOWNLOADED },
                            label = { Text("Downloaded (${state.downloadedIds.size})") },
                        )
                    }
                    Text(
                        "${shown.size} books",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        state = gridState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(shown, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                coverLoader = coverLoader,
                                isDownloaded = book.id in state.downloadedIds,
                                progress = progressById[book.id],
                                onClick = { selected = book },
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { book ->
        val content = state as? BooksState.Content
        val isDownloaded = content?.downloadedIds?.contains(book.id) == true
        val isBusy = content?.busyIds?.contains(book.id) == true
        val progress = progressLoader(book.id)
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
                        progress?.let {
                            add(if (it >= 99) "Completed" else "$it% read")
                        }
                    }.joinToString("\n"),
                )
            },
            confirmButton = {
                if (isDownloaded) {
                    TextButton(
                        onClick = {
                            selected = null
                            onOpen(book)
                        },
                    ) {
                        Text("Open")
                    }
                } else {
                    TextButton(onClick = { selected = null }) {
                        Text("Close")
                    }
                }
            },
            dismissButton = {
                when {
                    isBusy -> CircularProgressIndicator()
                    isDownloaded -> TextButton(onClick = { onDeleteDownload(book) }) {
                        Text("Delete download")
                    }
                    book.epubUrl != null -> TextButton(onClick = { onDownload(book) }) {
                        Text("Download EPUB")
                    }
                }
            },
        )
    }
}

@Composable
private fun BookCard(
    book: CalibreBook,
    coverLoader: suspend (String) -> ByteArray,
    isDownloaded: Boolean,
    progress: Int?,
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
        if (isDownloaded) {
            Text(
                text = "Downloaded",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        progress?.let { percentage ->
            LinearProgressIndicator(
                progress = { percentage / 100f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            )
            Text(
                text = if (percentage >= 99) "Completed" else "$percentage% read",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
