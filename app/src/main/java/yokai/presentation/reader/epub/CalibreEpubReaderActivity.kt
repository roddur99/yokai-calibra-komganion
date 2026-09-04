package yokai.presentation.reader.epub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.commitNow
import com.google.android.material.appbar.MaterialToolbar
import eu.kanade.tachiyomi.R
import java.io.File
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.data.calibre.CalibreReadingProgressStore

class CalibreEpubReaderActivity : AppCompatActivity() {

    private val progressStore: CalibreReadingProgressStore = Injekt.get()
    private var publication: Publication? = null
    private val containerId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The publication-backed FragmentFactory is created asynchronously. Rebuild the
        // navigator after recreation instead of asking FragmentManager to restore it first.
        super.onCreate(null)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val toolbar = MaterialToolbar(this).apply {
            this.title = title
            navigationIcon = ContextCompat.getDrawable(context, R.drawable.ic_arrow_back_24dp)
            setNavigationOnClickListener { finish() }
        }
        val readerContainer = FrameLayout(this).apply {
            id = containerId
            addView(
                ProgressBar(context),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                toolbar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (56 * resources.displayMetrics.density).toInt(),
                ),
            )
            addView(
                readerContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) { finish() }

        val path = intent.getStringExtra(EXTRA_PATH)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        if (path.isNullOrBlank() || bookId.isNullOrBlank()) {
            fail("Downloaded EPUB information is missing")
            return
        }

        lifecycleScope.launch {
            openPublication(File(path), bookId)
        }
    }

    private suspend fun openPublication(file: File, bookId: String) {
        if (!file.isFile) {
            fail("Downloaded EPUB was not found")
            return
        }

        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(contentResolver, httpClient)
        val parser = DefaultPublicationParser(this, httpClient, assetRetriever, null)
        val opener = PublicationOpener(parser)
        val asset = assetRetriever.retrieve(file.toUrl(isDirectory = false))
            .getOrElse {
                fail("Readium could not read this EPUB")
                return
            }
        val opened = opener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                fail("Readium could not open this EPUB")
                return
            }
        publication = opened

        val navigatorFactory = EpubNavigatorFactory(opened)
        supportFragmentManager.fragmentFactory = navigatorFactory.createFragmentFactory(
            initialLocator = progressStore.get(bookId),
        )
        supportFragmentManager.commitNow {
            replace(containerId, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
        }
        val navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)
            as EpubNavigatorFragment
        lifecycleScope.launch {
            navigator.currentLocator
                .drop(1)
                .collect { locator -> progressStore.save(bookId, locator) }
        }
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        publication?.close()
        publication = null
    }

    companion object {
        private const val EXTRA_PATH = "epub_path"
        private const val EXTRA_TITLE = "book_title"
        private const val EXTRA_BOOK_ID = "book_id"
        private const val NAVIGATOR_TAG = "calibre_epub_navigator"

        fun intent(context: Context, file: File, title: String, bookId: String): Intent =
            Intent(context, CalibreEpubReaderActivity::class.java)
                .putExtra(EXTRA_PATH, file.absolutePath)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BOOK_ID, bookId)
    }
}
