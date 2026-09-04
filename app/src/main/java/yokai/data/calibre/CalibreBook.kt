package yokai.data.calibre

data class CalibreBook(
    val id: String,
    val title: String,
    val authors: List<String>,
    val series: String?,
    val seriesIndex: Double?,
    val tags: Set<String>,
    val coverUrl: String?,
    val thumbnailUrl: String?,
    val epubUrl: String?,
    val updatedAt: String?,
)
