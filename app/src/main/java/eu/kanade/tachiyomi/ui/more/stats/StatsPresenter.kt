package eu.kanade.tachiyomi.ui.more.stats

import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.MANGA_HAS_UNREAD
import eu.kanade.tachiyomi.data.preference.MANGA_NON_COMPLETED
import eu.kanade.tachiyomi.data.preference.MANGA_NON_READ
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.ui.more.stats.StatsHelper.getReadDuration
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.data.DatabaseHandler
import yokai.domain.activity.ReadingActivityRepository
import yokai.domain.activity.model.ReadingActivity
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.komga.annotation.KomgaBookAnnotationRepository
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.GetLibraryManga
import yokai.domain.track.interactor.GetTrack
import yokai.i18n.MR
import yokai.source.gallery.GalleryKomganionSource
import yokai.source.komga.KomgaSource
import yokai.util.lang.getString

/**
 * Presenter of [StatsController].
 */
class StatsPresenter(
    private val prefs: PreferencesHelper = Injekt.get(),
    private val trackManager: TrackManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
): BaseCoroutinePresenter<StatsController>() {
    private val handler: DatabaseHandler by injectLazy()
    private val getLibraryManga: GetLibraryManga by injectLazy()
    private val getTrack: GetTrack by injectLazy()
    private val readingActivityRepository: ReadingActivityRepository by injectLazy()
    private val komgaBookAnnotationRepository: KomgaBookAnnotationRepository by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val getChapter: GetChapter by injectLazy()

    private val focusedMangas = runBlocking { getManga.awaitAll() }
        .filter { it.source == KomgaSource.ID || it.source == GalleryKomganionSource.ID }
    private val focusedChapters = runBlocking {
        focusedMangas.flatMap { manga ->
            manga.id?.let { getChapter.awaitAll(it, false) }.orEmpty()
        }
    }
    private val libraryMangas = getLibrary()
    val mangaDistinct = libraryMangas.distinct()

    private fun getLibrary(): MutableList<LibraryManga> {
        return runBlocking { getLibraryManga.await() }.toMutableList()
    }

    fun getFocusedTitleCount(): Int = focusedMangas.size

    fun getFocusedChapterCount(): Int = focusedChapters.size

    fun getFocusedReadChapterCount(): Int = focusedChapters.count { it.read }

    fun getFocusedGalleryCount(): Int =
        focusedMangas.count { it.source == GalleryKomganionSource.ID }

    fun getAnnotationScores(): List<Double> = runBlocking {
        komgaBookAnnotationRepository.getAll()
            .mapNotNull { it.score?.toDouble() }
    }

    fun getTracks(manga: Manga): MutableList<Track> {
        return runBlocking { getTrack.awaitAllByMangaId(manga.id) }.toMutableList()
    }

    fun getLoggedTrackers(): List<TrackService> {
        return trackManager.services.filter { it.isLogged }
    }

    fun getSources(): List<CatalogueSource> {
        val languages = prefs.enabledLanguages().get()
        val hiddenCatalogues = prefs.hiddenSources().get()
        return sourceManager.getCatalogueSources()
            .filter { it.lang in languages }
            .filterNot { it.id.toString() in hiddenCatalogues }
    }

    fun getGlobalUpdateManga(): Map<Long?, List<LibraryManga>> {
        val includedCategories = prefs.libraryUpdateCategories().get().map(String::toInt)
        val excludedCategories = prefs.libraryUpdateCategoriesExclude().get().map(String::toInt)
        val restrictions = prefs.libraryUpdateMangaRestriction().get()
        return libraryMangas.groupBy { it.manga.id }
            .filterNot { it.value.any { manga -> manga.category in excludedCategories } }
            .filter { includedCategories.isEmpty() || it.value.any { manga -> manga.category in includedCategories } }
            .filterNot {
                val manga = it.value.first()
                (MANGA_NON_COMPLETED in restrictions && manga.manga.status == SManga.COMPLETED) ||
                    (MANGA_HAS_UNREAD in restrictions && manga.unread != 0) ||
                    (MANGA_NON_READ in restrictions && manga.totalChapters > 0 && !manga.hasRead)
            }
    }

    fun getDownloadCount(manga: LibraryManga): Int {
        return downloadManager.getDownloadCount(manga.manga)
    }

    fun get10PointScore(track: Track): Float? {
        val service = trackManager.getService(track.sync_id)
        return service?.get10PointScore(track.score)
    }

    private fun startOfCurrentWeek(): Long =
        LocalDate.now()
            .with(DayOfWeek.MONDAY)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun getActivitySessions(): List<ReadingActivity> =
        runBlocking { readingActivityRepository.getAll() }

    fun getWeeklyActivity(): List<ReadingActivity> =
        getActivitySessions().filter { it.startedAt >= startOfCurrentWeek() }

    fun getWeeklyReadDuration(): String =
        getWeeklyActivity().sumOf { it.durationMs }
            .getReadDuration(prefs.context.getString(MR.strings.none))

    fun getWeeklyPagesViewed(): Int = getWeeklyActivity().sumOf { it.pagesViewed }

    fun getWeeklyCompleted(): Int = getWeeklyActivity().count { it.completed }

    fun getWeeklySourceUsage(): String {
        val sessions = getWeeklyActivity()
        val komga = sessions.count { it.sourceId == KomgaSource.ID }
        val galleries = sessions.count { it.sourceId == GalleryKomganionSource.ID }
        return "Komga $komga · Galleries $galleries"
    }

    fun getRecentCompletions(): String =
        getActivitySessions().asSequence()
            .filter { it.completed }
            .distinctBy { it.itemKey }
            .take(5)
            .joinToString("\n") { "${it.itemTitle} — ${it.seriesTitle}" }
            .ifBlank { "No completions recorded yet." }

    fun getReadDuration(): String {
        val chaptersTime = runBlocking {
            handler.awaitOneOrNull { historyQueries.getTotalReadDuration() }?.sum?.toLong()
        }
        return chaptersTime.getReadDuration(prefs.context.getString(MR.strings.none))
    }
}
