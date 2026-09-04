package yokai.domain.activity.model

data class ReadingActivity(
    val id: Long,
    val sourceId: Long,
    val mangaId: Long?,
    val chapterId: Long?,
    val itemKey: String,
    val seriesTitle: String,
    val itemTitle: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val pagesViewed: Int,
    val completed: Boolean,
)
