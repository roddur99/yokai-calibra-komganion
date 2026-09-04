package yokai.presentation.books

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.main.BottomNavBarInterface
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.data.calibre.CalibreBook
import yokai.data.calibre.CalibreCatalogClient
import yokai.data.calibre.CalibreEpubStore
import yokai.presentation.reader.epub.CalibreEpubReaderActivity

class BooksController(
    private val catalogClient: CalibreCatalogClient = Injekt.get(),
    private val epubStore: CalibreEpubStore = Injekt.get(),
) : BaseComposeController(), BottomNavBarInterface {

    private var state by mutableStateOf<BooksState>(BooksState.Loading)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        refresh()
    }

    @Composable
    override fun ScreenContent() {
        BooksScreen(
            state = state,
            coverLoader = catalogClient::getBytes,
            onRetry = ::refresh,
            onDownload = ::download,
            onDeleteDownload = ::deleteDownload,
            onOpen = ::open,
        )
    }

    private fun refresh() {
        state = BooksState.Loading
        viewScope.launch {
            state = runCatching { catalogClient.getLightNovels() }
                .fold(
                    onSuccess = { books ->
                        BooksState.Content(
                            books = books,
                            downloadedIds = books.filter { epubStore.isDownloaded(it.id) }
                                .mapTo(mutableSetOf()) { it.id },
                        )
                    },
                    onFailure = { BooksState.Error(it.message ?: "Unable to load Calibre") },
                )
        }
    }

    private fun download(book: CalibreBook) {
        updateContent { it.copy(busyIds = it.busyIds + book.id, operationError = null) }
        viewScope.launch {
            runCatching { epubStore.download(book) }
                .onSuccess {
                    updateContent {
                        it.copy(
                            downloadedIds = it.downloadedIds + book.id,
                            busyIds = it.busyIds - book.id,
                        )
                    }
                }
                .onFailure { error ->
                    updateContent {
                        it.copy(
                            busyIds = it.busyIds - book.id,
                            operationError = error.message ?: "Unable to download EPUB",
                        )
                    }
                }
        }
    }

    private fun open(book: CalibreBook) {
        val context = activity ?: return
        val file = epubStore.fileFor(book.id)
        if (!file.isFile) return
        context.startActivity(CalibreEpubReaderActivity.intent(context, file, book.title))
    }

    private fun deleteDownload(book: CalibreBook) {
        updateContent { it.copy(busyIds = it.busyIds + book.id, operationError = null) }
        viewScope.launch {
            runCatching { check(epubStore.delete(book.id)) { "Unable to delete EPUB" } }
                .onSuccess {
                    updateContent {
                        it.copy(
                            downloadedIds = it.downloadedIds - book.id,
                            busyIds = it.busyIds - book.id,
                        )
                    }
                }
                .onFailure { error ->
                    updateContent {
                        it.copy(
                            busyIds = it.busyIds - book.id,
                            operationError = error.message ?: "Unable to delete EPUB",
                        )
                    }
                }
        }
    }

    private fun updateContent(transform: (BooksState.Content) -> BooksState.Content) {
        val content = state as? BooksState.Content ?: return
        state = transform(content)
    }

    override fun canChangeTabs(block: () -> Unit) = true
}

sealed interface BooksState {
    data object Loading : BooksState

    data class Content(
        val books: List<CalibreBook>,
        val downloadedIds: Set<String> = emptySet(),
        val busyIds: Set<String> = emptySet(),
        val operationError: String? = null,
    ) : BooksState

    data class Error(val message: String) : BooksState
}
