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
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.data.DatabaseHandler
import yokai.data.calibre.CalibreEpubStore
import yokai.data.calibre.CalibreLibraryStatsStore
import yokai.data.calibre.CalibreReadingProgressStore
import yokai.data.calibre.CalibreReadingSource
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
    private val calibreEpubStore: CalibreEpubStore by injectLazy()
    private val calibreLibraryStatsStore: CalibreLibraryStatsStore by injectLazy()
    private val calibreReadingProgressStore: CalibreReadingProgressStore by injectLazy()

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

    fun getRatedBookCount(): Int = getAnnotationScores().size

    fun getRecordedSessionCount(): Int = getActivitySessions().size

    fun getCalibreAvailableCount(): Int = calibreLibraryStatsStore.availableCount()

    fun getCalibreDownloadedCount(): Int = calibreEpubStore.downloadedCount()

    fun getCalibreStartedCount(): Int = calibreReadingProgressStore.startedCount()

    fun getCalibreCompletedCount(): Int = calibreReadingProgressStore.completedCount()

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
        val books = sessions.count { it.sourceId == CalibreReadingSource.ID }
        return "Komga $komga · Galleries $galleries · Books $books"
    }

    fun getRecentCompletions(): String =
        getActivitySessions().asSequence()
            .filter { it.completed }
            .distinctBy { it.itemKey }
            .take(5)
            .joinToString("\n") { "${it.itemTitle} — ${it.seriesTitle}" }
            .ifBlank { "No completions recorded yet." }

    fun getCompletionCalendar(): String {
        val zone = ZoneId.systemDefault()
        val currentWeekStart = LocalDate.now(zone).with(DayOfWeek.MONDAY)
        val completedDays = getActivitySessions().asSequence()
            .filter { it.completed }
            .map { java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .toSet()
        val firstDay = currentWeekStart.minusWeeks(3)
        val days = (0..27).map { firstDay.plusDays(it.toLong()) }

        return buildString {
            append("M  T  W  T  F  S  S\n")
            days.chunked(7).forEachIndexed { row, week ->
                append(week.joinToString("  ") { if (it in completedDays) "●" else "·" })
                if (row < 3) append('\n')
            }
        }
    }

    fun getSeriesScoreAverages(): String = runBlocking {
        komgaBookAnnotationRepository.getAll().asSequence()
            .filter { it.score != null }
            .groupBy { it.seriesTitle.ifBlank { "Unknown series" } }
            .map { (series, annotations) -> series to annotations.mapNotNull { it.score }.average() }
            .sortedByDescending { it.second }
            .take(5)
            .joinToString("\n") { (series, average) ->
                "${average.roundToOneDecimal()} ★  $series"
            }
            .ifBlank { "No rated series yet." }
    }

    fun getMostReadSeries(): String =
        getActivitySessions().groupBy { it.seriesTitle.ifBlank { "Unknown series" } }
            .map { (series, sessions) ->
                Triple(series, sessions.sumOf { it.durationMs }, sessions.sumOf { it.pagesViewed })
            }
            .sortedWith(compareByDescending<Triple<String, Long, Int>> { it.second }.thenByDescending { it.third })
            .take(5)
            .mapIndexed { index, (series, duration, pages) ->
                val pageSummary = if (pages > 0) ", $pages pages" else ""
                "${index + 1}. $series — ${duration.getReadDuration("0m")}$pageSummary"
            }
            .joinToString("\n")
            .ifBlank { "No reading activity yet." }

    private fun Double.roundToOneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString()

    data class ReadingTimeBucket(
        val label: String,
        val durationMs: Long,
    )

    fun getDailyReadingTime(dayCount: Int = 7): List<ReadingTimeBucket> {
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ofPattern("EEE")
        val today = LocalDate.now(zone)
        val sessionsByDay = getActivitySessions().groupBy {
            java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
        }

        return (dayCount - 1 downTo 0).map { daysAgo ->
            val day = today.minusDays(daysAgo.toLong())
            ReadingTimeBucket(day.format(formatter), sessionsByDay[day].orEmpty().sumOf { it.durationMs })
        }
    }

    fun getWeeklyReadingTime(weekCount: Int = 8): List<ReadingTimeBucket> {
        val zone = ZoneId.systemDefault()
        val currentWeek = LocalDate.now(zone).with(DayOfWeek.MONDAY)
        val sessionsByWeek = getActivitySessions().groupBy {
            java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate().with(DayOfWeek.MONDAY)
        }

        return (weekCount - 1 downTo 0).map { weeksAgo ->
            val week = currentWeek.minusWeeks(weeksAgo.toLong())
            ReadingTimeBucket(
                label = "${week.monthValue}/${week.dayOfMonth}",
                durationMs = sessionsByWeek[week].orEmpty().sumOf { it.durationMs },
            )
        }
    }

    fun getMonthlyReadingTime(monthCount: Int = 6): List<ReadingTimeBucket> {
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ofPattern("MMM")
        val currentMonth = YearMonth.now(zone)
        val sessionsByMonth = getActivitySessions().groupBy {
            YearMonth.from(java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone))
        }

        return (monthCount - 1 downTo 0).map { monthsAgo ->
            val month = currentMonth.minusMonths(monthsAgo.toLong())
            ReadingTimeBucket(
                label = month.format(formatter),
                durationMs = sessionsByMonth[month].orEmpty().sumOf { it.durationMs },
            )
        }
    }

    fun getReadDuration(): String {
        val chaptersTime = runBlocking {
            handler.awaitOneOrNull { historyQueries.getTotalReadDuration() }?.sum?.toLong()
        }
        return chaptersTime.getReadDuration(prefs.context.getString(MR.strings.none))
    }
}
