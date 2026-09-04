package yokai.presentation.reader.epub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.commit
import java.io.File
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

class CalibreEpubReaderActivity : AppCompatActivity() {

    private var publication: Publication? = null
    private val containerId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The publication-backed FragmentFactory is created asynchronously. Rebuild the
        // navigator after recreation instead of asking FragmentManager to restore it first.
        super.onCreate(null)

        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val container = FrameLayout(this).apply {
            id = containerId
            addView(
                ProgressBar(context),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER,
                ),
            )
        }
        setContentView(container)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            fail("Downloaded EPUB path is missing")
            return
        }

        lifecycleScope.launch {
            openPublication(File(path))
        }
    }

    private suspend fun openPublication(file: File) {
        if (!file.isFile) {
            fail("Downloaded EPUB was not found")
            return
        }

        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(contentResolver, httpClient)
        val parser = DefaultPublicationParser(this, httpClient, assetRetriever, null)
        val opener = PublicationOpener(parser)
        val asset = assetRetriever.retrieve(file.toUrl())
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
            initialLocator = null,
        )
        supportFragmentManager.commit {
            replace(containerId, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
        }
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        publication?.close()
        publication = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "epub_path"
        private const val EXTRA_TITLE = "book_title"
        private const val NAVIGATOR_TAG = "calibre_epub_navigator"

        fun intent(context: Context, file: File, title: String): Intent =
            Intent(context, CalibreEpubReaderActivity::class.java)
                .putExtra(EXTRA_PATH, file.absolutePath)
                .putExtra(EXTRA_TITLE, title)
    }
}
