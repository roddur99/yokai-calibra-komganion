package yokai.data.komga.annotation

import kotlinx.coroutines.flow.Flow
import yokai.data.DatabaseHandler
import yokai.domain.komga.annotation.KomgaBookAnnotationRepository
import yokai.domain.komga.annotation.model.KomgaBookAnnotation

class KomgaBookAnnotationRepositoryImpl(
    private val handler: DatabaseHandler,
) : KomgaBookAnnotationRepository {

    override fun subscribeAll(): Flow<List<KomgaBookAnnotation>> =
        handler.subscribeToList {
            komga_book_annotationsQueries.findAll(::mapAnnotation)
        }

    override suspend fun get(bookId: String): KomgaBookAnnotation? =
        handler.awaitOneOrNull {
            komga_book_annotationsQueries.findByBookId(bookId, ::mapAnnotation)
        }

    override suspend fun getAll(): List<KomgaBookAnnotation> =
        handler.awaitList {
            komga_book_annotationsQueries.findAll(::mapAnnotation)
        }

    override suspend fun upsert(annotation: KomgaBookAnnotation) {
        handler.await {
            komga_book_annotationsQueries.upsert(
                bookId = annotation.bookId,
                score = annotation.score?.toLong(),
                notes = annotation.notes,
                bookTitle = annotation.bookTitle,
                seriesTitle = annotation.seriesTitle,
                createdAt = annotation.createdAt,
                updatedAt = annotation.updatedAt,
            )
        }
    }

    override suspend fun delete(bookId: String) {
        handler.await {
            komga_book_annotationsQueries.deleteByBookId(bookId)
        }
    }

    override suspend fun deleteAll() {
        handler.await {
            komga_book_annotationsQueries.deleteAll()
        }
    }

    private fun mapAnnotation(
        bookId: String,
        score: Long?,
        notes: String,
        bookTitle: String,
        seriesTitle: String,
        createdAt: Long,
        updatedAt: Long,
    ): KomgaBookAnnotation = KomgaBookAnnotation(
        bookId = bookId,
        score = score?.toInt(),
        notes = notes,
        bookTitle = bookTitle,
        seriesTitle = seriesTitle,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
