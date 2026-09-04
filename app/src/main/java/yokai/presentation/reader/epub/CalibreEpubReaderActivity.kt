package yokai.presentation.reader.epub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.commitNow
import com.google.android.material.appbar.MaterialToolbar
import eu.kanade.tachiyomi.R
import java.io.File
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.readium.r2.navigator.SelectableNavigator
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
import yokai.data.calibre.CalibreReaderPreferencesStore
import yokai.data.calibre.CalibreReadingProgressStore

class CalibreEpubReaderActivity : AppCompatActivity() {

    private val progressStore: CalibreReadingProgressStore = Injekt.get()
    private val readerPreferencesStore: CalibreReaderPreferencesStore = Injekt.get()
    private var readerPreferences = readerPreferencesStore.get()
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
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
            menu.add(Menu.NONE, ACTION_TABLE_OF_CONTENTS, 0, "Contents")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_READER_SETTINGS, 1, "Aa")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ACTION_TABLE_OF_CONTENTS -> {
                        showTableOfContents()
                        true
                    }
                    ACTION_READER_SETTINGS -> {
                        showReaderSettings()
                        true
                    }
                    else -> false
                }
            }
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
            initialPreferences = readerPreferences.toReadium(),
            configuration = EpubNavigatorFragment.Configuration(
                selectionActionModeCallback = selectionActionModeCallback,
            ),
        )
        supportFragmentManager.commitNow {
            replace(containerId, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
        }
        val navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)
            as EpubNavigatorFragment
        this.navigator = navigator
        lifecycleScope.launch {
            navigator.currentLocator
                .drop(1)
                .collect { locator -> progressStore.save(bookId, locator) }
        }
    }

    private fun showTableOfContents() {
        val entries = publication?.tableOfContents.orEmpty().flattenTableOfContents()
        if (entries.isEmpty()) {
            Toast.makeText(this, "This book does not provide a table of contents", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = entries.map { entry ->
            val indentation = "\u00a0\u00a0".repeat(entry.depth)
            indentation + entry.link.title.orEmpty().ifBlank { "Untitled section" }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Table of contents")
            .setItems(labels) { _, index ->
                val moved = navigator?.go(entries[index].link, animated = true) == true
                if (!moved) {
                    Toast.makeText(this, "Unable to open this section", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun List<Link>.flattenTableOfContents(depth: Int = 0): List<TableOfContentsEntry> =
        flatMap { link ->
            listOf(TableOfContentsEntry(link, depth)) +
                link.children.flattenTableOfContents(depth + 1)
        }

    private fun showReaderSettings() {
        val mode = if (readerPreferences.scroll) "Scrolling" else "Paginated"
        val labels = arrayOf(
            "Reading mode: $mode",
            "Theme: ${readerPreferences.theme.name.lowercase().replaceFirstChar(Char::uppercase)}",
            "Font size: ${readerPreferences.fontSize}%",
            "Margins: ${readerPreferences.margins}%",
            "Line spacing: ${readerPreferences.lineHeight}%",
        )
        AlertDialog.Builder(this)
            .setTitle("Reader settings")
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> showReadingModeDialog()
                    1 -> showThemeDialog()
                    2 -> showSlider("Font size", 50, 250, readerPreferences.fontSize, "%") {
                        updateReaderPreferences(readerPreferences.copy(fontSize = it))
                    }
                    3 -> showSlider("Margins", 0, 300, readerPreferences.margins, "%") {
                        updateReaderPreferences(readerPreferences.copy(margins = it))
                    }
                    4 -> showSlider("Line spacing", 100, 250, readerPreferences.lineHeight, "%") {
                        updateReaderPreferences(readerPreferences.copy(lineHeight = it))
                    }
                }
            }
            .show()
    }

    private fun showReadingModeDialog() {
        val values = arrayOf("Paginated", "Scrolling")
        AlertDialog.Builder(this)
            .setTitle("Reading mode")
            .setSingleChoiceItems(values, if (readerPreferences.scroll) 1 else 0) { dialog, which ->
                updateReaderPreferences(readerPreferences.copy(scroll = which == 1))
                dialog.dismiss()
            }
            .show()
    }

    private fun showThemeDialog() {
        val themes = CalibreReaderPreferencesStore.ThemeValue.entries
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(
                themes.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }.toTypedArray(),
                themes.indexOf(readerPreferences.theme),
            ) { dialog, which ->
                updateReaderPreferences(readerPreferences.copy(theme = themes[which]))
                dialog.dismiss()
            }
            .show()
    }

    private fun showSlider(
        title: String,
        minimum: Int,
        maximum: Int,
        current: Int,
        suffix: String,
        onSave: (Int) -> Unit,
    ) {
        val label = TextView(this)
        val slider = SeekBar(this).apply {
            max = maximum - minimum
            progress = current - minimum
        }
        fun updateLabel(value: Int) {
            label.text = "$title: $value$suffix"
        }
        updateLabel(current)
        slider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    updateLabel(progress + minimum)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            },
        )
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(label)
            addView(slider)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> onSave(slider.progress + minimum) }
            .show()
    }

    private fun updateReaderPreferences(values: CalibreReaderPreferencesStore.Values) {
        readerPreferences = values
        readerPreferencesStore.save(values)
        navigator?.submitPreferences(values.toReadium())
    }

    private val selectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, ACTION_DEFINE, 0, "Define").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_GOOGLE, 1, "Google").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_TRANSLATE, 2, "Translate").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_COPY, 3, "Copy")
            menu.add(Menu.NONE, ACTION_SHARE, 4, "Share")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = item.itemId
            if (action !in LOOKUP_ACTIONS) return false
            lifecycleScope.launch {
                val text = (navigator as? SelectableNavigator)
                    ?.currentSelection()
                    ?.locator
                    ?.text
                    ?.highlight
                    .orEmpty()
                if (text.isBlank()) {
                    Toast.makeText(
                        this@CalibreEpubReaderActivity,
                        "No text selected",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                val launched = when (action) {
                    ACTION_DEFINE -> TextLookupLauncher.define(this@CalibreEpubReaderActivity, text)
                    ACTION_GOOGLE -> TextLookupLauncher.google(this@CalibreEpubReaderActivity, text)
                    ACTION_TRANSLATE -> TextLookupLauncher.translate(this@CalibreEpubReaderActivity, text)
                    ACTION_COPY -> TextLookupLauncher.copy(this@CalibreEpubReaderActivity, text)
                    ACTION_SHARE -> TextLookupLauncher.share(this@CalibreEpubReaderActivity, text)
                    else -> false
                }
                if (!launched) {
                    Toast.makeText(
                        this@CalibreEpubReaderActivity,
                        "No compatible app is available",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                navigator?.clearSelection()
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
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

    private data class TableOfContentsEntry(val link: Link, val depth: Int)

    companion object {
        private const val EXTRA_PATH = "epub_path"
        private const val EXTRA_TITLE = "book_title"
        private const val EXTRA_BOOK_ID = "book_id"
        private const val NAVIGATOR_TAG = "calibre_epub_navigator"

        private const val ACTION_READER_SETTINGS = 0x7100
        private const val ACTION_TABLE_OF_CONTENTS = 0x7106
        private const val ACTION_DEFINE = 0x7101
        private const val ACTION_GOOGLE = 0x7102
        private const val ACTION_TRANSLATE = 0x7103
        private const val ACTION_COPY = 0x7104
        private const val ACTION_SHARE = 0x7105
        private val LOOKUP_ACTIONS = setOf(
            ACTION_DEFINE,
            ACTION_GOOGLE,
            ACTION_TRANSLATE,
            ACTION_COPY,
            ACTION_SHARE,
        )

        fun intent(context: Context, file: File, title: String, bookId: String): Intent =
            Intent(context, CalibreEpubReaderActivity::class.java)
                .putExtra(EXTRA_PATH, file.absolutePath)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BOOK_ID, bookId)
    }
}
