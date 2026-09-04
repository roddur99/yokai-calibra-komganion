package yokai.data.activity

import kotlinx.coroutines.flow.Flow
import yokai.data.DatabaseHandler
import yokai.domain.activity.ReadingActivityRepository
import yokai.domain.activity.model.ReadingActivity

class ReadingActivityRepositoryImpl(
    private val handler: DatabaseHandler,
) : ReadingActivityRepository {

    override fun subscribeAll(): Flow<List<ReadingActivity>> =
        handler.subscribeToList {
            reading_activityQueries.findAll(::mapActivity)
        }

    override suspend fun getAll(): List<ReadingActivity> =
        handler.awaitList {
            reading_activityQueries.findAll(::mapActivity)
        }

    override suspend fun getSince(startDate: Long): List<ReadingActivity> =
        handler.awaitList {
            reading_activityQueries.findSince(startDate, ::mapActivity)
        }

    override suspend fun insert(activity: ReadingActivity) {
        handler.await {
            reading_activityQueries.insert(
                sourceId = activity.sourceId,
                mangaId = activity.mangaId,
                chapterId = activity.chapterId,
                itemKey = activity.itemKey,
                seriesTitle = activity.seriesTitle,
                itemTitle = activity.itemTitle,
                startedAt = activity.startedAt,
                endedAt = activity.endedAt,
                durationMs = activity.durationMs,
                pagesViewed = activity.pagesViewed.toLong(),
                completed = activity.completed,
            )
        }
    }

    override suspend fun deleteAll() {
        handler.await {
            reading_activityQueries.deleteAll()
        }
    }

    private fun mapActivity(
        activityId: Long,
        sourceId: Long,
        mangaId: Long?,
        chapterId: Long?,
        itemKey: String,
        seriesTitle: String,
        itemTitle: String,
        startedAt: Long,
        endedAt: Long,
        durationMs: Long,
        pagesViewed: Long,
        completed: Boolean,
    ): ReadingActivity = ReadingActivity(
        id = activityId,
        sourceId = sourceId,
        mangaId = mangaId,
        chapterId = chapterId,
        itemKey = itemKey,
        seriesTitle = seriesTitle,
        itemTitle = itemTitle,
        startedAt = startedAt,
        endedAt = endedAt,
        durationMs = durationMs,
        pagesViewed = pagesViewed.toInt(),
        completed = completed,
    )
}
