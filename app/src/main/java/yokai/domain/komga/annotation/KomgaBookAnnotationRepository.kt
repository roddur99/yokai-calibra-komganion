package yokai.domain.komga.annotation

import kotlinx.coroutines.flow.Flow
import yokai.domain.komga.annotation.model.KomgaBookAnnotation

interface KomgaBookAnnotationRepository {
    fun subscribeAll(): Flow<List<KomgaBookAnnotation>>

    suspend fun get(bookId: String): KomgaBookAnnotation?

    suspend fun getAll(): List<KomgaBookAnnotation>

    suspend fun upsert(annotation: KomgaBookAnnotation)

    suspend fun delete(bookId: String)

    suspend fun deleteAll()
}
