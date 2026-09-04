package eu.kanade.tachiyomi.ui.reader.chapter

import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import coil3.request.Disposable
import coil3.request.error
import coil3.request.placeholder
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.ReaderChapterItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import uy.kohesive.injekt.injectLazy
import yokai.domain.manga.models.MangaCover
import yokai.source.komga.KomgaSource
import yokai.util.coil.loadManga

class ReaderChapterItem(val chapter: Chapter, val manga: Manga, val isCurrent: Boolean) :
    AbstractItem<ReaderChapterItem.ViewHolder>(),
    Chapter by chapter {

    val preferences: PreferencesHelper by injectLazy()
    val sourceManager: SourceManager by injectLazy()

    /** defines the type defining this item. must be unique. preferably an id */
    override val type: Int = R.id.reader_chapter_layout

    /** defines the layout which will be used for this item in the list */
    override val layoutRes: Int = R.layout.reader_chapter_item

    override var identifier: Long = chapter.id!!

    override fun getViewHolder(v: View): ViewHolder {
        return ViewHolder(v)
    }

    class ViewHolder(view: View) : FastAdapter.ViewHolder<ReaderChapterItem>(view) {
        val binding = ReaderChapterItemBinding.bind(view)
        private var coverRequest: Disposable? = null

        override fun bindView(item: ReaderChapterItem, payloads: List<Any>) {
            val manga = item.manga
            val komgaSource = if (manga.source == KomgaSource.ID) {
                item.sourceManager.get(KomgaSource.ID) as? KomgaSource
            } else {
                null
            }
            val bookId = item.chapter.url.substringAfterLast('/').takeIf { it.isNotBlank() }

            coverRequest?.dispose()
            coverRequest = null
            binding.komgaBookCover.isVisible = komgaSource != null && bookId != null
            if (komgaSource != null && bookId != null) {
                coverRequest = binding.komgaBookCover.loadManga(
                    MangaCover(
                        mangaId = item.chapter.id ?: bookId.hashCode().toLong(),
                        sourceId = KomgaSource.ID,
                        url = komgaSource.getBookThumbnailUrl(bookId),
                        lastModified = 0L,
                        inLibrary = false,
                    ),
                ) {
                    placeholder(R.drawable.ic_book_24dp)
                    error(R.drawable.ic_broken_image_24dp)
                }
            } else {
                binding.komgaBookCover.setImageDrawable(null)
            }

            val chapterColor = ChapterUtil.chapterColor(itemView.context, item.chapter)

            binding.chapterTitle.text =
                item.preferredChapterName(itemView.context, manga, item.preferences)

            val statuses = mutableListOf<String>()
            ChapterUtil.relativeDate(item)?.let { statuses.add(it) }
            item.scanlator?.takeIf { it.isNotBlank() }?.let { statuses.add(item.scanlator ?: "") }

            if (item.isCurrent) {
                binding.chapterTitle.setTypeface(null, Typeface.BOLD_ITALIC)
                binding.chapterSubtitle.setTypeface(null, Typeface.BOLD_ITALIC)
            } else {
                binding.chapterTitle.setTypeface(null, Typeface.NORMAL)
                binding.chapterSubtitle.setTypeface(null, Typeface.NORMAL)
            }

            // match color of the chapter title
            binding.chapterTitle.setTextColor(chapterColor)
            binding.chapterSubtitle.setTextColor(chapterColor)

            binding.bookmarkImage.setImageResource(
                if (item.bookmark) {
                    R.drawable.ic_bookmark_24dp
                } else {
                    R.drawable.ic_bookmark_border_24dp
                },
            )

            val drawableColor = ChapterUtil.bookmarkColor(itemView.context, item)

            DrawableCompat.setTint(binding.bookmarkImage.drawable, drawableColor)

            binding.chapterSubtitle.text = statuses.joinToString(" • ")
        }

        override fun unbindView(item: ReaderChapterItem) {
            coverRequest?.dispose()
            coverRequest = null
            binding.komgaBookCover.setImageDrawable(null)
            binding.komgaBookCover.isVisible = false
            binding.chapterTitle.text = null
            binding.chapterSubtitle.text = null
        }
    }
}
