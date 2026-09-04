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

class BooksController(
    private val catalogClient: CalibreCatalogClient = Injekt.get(),
) : BaseComposeController(), BottomNavBarInterface {

    private var state by mutableStateOf<BooksState>(BooksState.Loading)

    override fun getTitle() = "Books"

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
        )
    }

    private fun refresh() {
        state = BooksState.Loading
        viewScope.launch {
            state = runCatching { catalogClient.getLightNovels() }
                .fold(
                    onSuccess = { BooksState.Content(it) },
                    onFailure = { BooksState.Error(it.message ?: "Unable to load Calibre") },
                )
        }
    }

    override fun canChangeTabs(block: () -> Unit) = true
}

sealed interface BooksState {
    data object Loading : BooksState
    data class Content(val books: List<CalibreBook>) : BooksState
    data class Error(val message: String) : BooksState
}
