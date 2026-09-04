package yokai.domain.komga.annotation.model

data class KomgaBookAnnotation(
    val bookId: String,
    val score: Int?,
    val notes: String,
    val bookTitle: String,
    val seriesTitle: String,
    val createdAt: Long,
    val updatedAt: Long,
)
