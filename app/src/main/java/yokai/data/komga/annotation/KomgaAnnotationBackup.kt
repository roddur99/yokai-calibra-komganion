package yokai.data.komga.annotation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import yokai.domain.komga.annotation.KomgaBookAnnotationRepository
import yokai.domain.komga.annotation.model.KomgaBookAnnotation

class KomgaAnnotationBackup(
    private val repository: KomgaBookAnnotationRepository,
    private val json: Json,
) {
    suspend fun export(): String {
        val backup = BackupFile(
            version = CURRENT_VERSION,
            exportedAt = System.currentTimeMillis(),
            annotations = repository.getAll().map { it.toBackupEntry() },
        )
        return json.encodeToString(backup)
    }

    suspend fun import(content: String): ImportResult {
        val backup = json.decodeFromString<BackupFile>(content)
        require(backup.version == CURRENT_VERSION) {
            "Unsupported Komga annotation backup version: ${backup.version}"
        }

        val existing = repository.getAll().associateBy { it.bookId }.toMutableMap()
        var imported = 0
        var updated = 0
        var unchanged = 0
        var invalid = 0

        backup.annotations.forEach { entry ->
            if (!entry.isValid()) {
                invalid++
                return@forEach
            }

            val current = existing[entry.bookId]
            if (current != null && current.updatedAt >= entry.updatedAt) {
                unchanged++
                return@forEach
            }

            val annotation = entry.toAnnotation()
            repository.upsert(annotation)
            existing[entry.bookId] = annotation
            if (current == null) {
                imported++
            } else {
                updated++
            }
        }

        return ImportResult(
            imported = imported,
            updated = updated,
            unchanged = unchanged,
            invalid = invalid,
        )
    }

    @Serializable
    private data class BackupFile(
        val version: Int,
        val exportedAt: Long,
        val annotations: List<BackupEntry>,
    )

    @Serializable
    private data class BackupEntry(
        val bookId: String,
        val score: Int? = null,
        val notes: String = "",
        val bookTitle: String,
        val seriesTitle: String,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        fun isValid(): Boolean =
            bookId.isNotBlank() &&
                (score == null || score in 1..10) &&
                createdAt >= 0 &&
                updatedAt >= createdAt

        fun toAnnotation() = KomgaBookAnnotation(
            bookId = bookId,
            score = score,
            notes = notes,
            bookTitle = bookTitle,
            seriesTitle = seriesTitle,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun KomgaBookAnnotation.toBackupEntry() = BackupEntry(
        bookId = bookId,
        score = score,
        notes = notes,
        bookTitle = bookTitle,
        seriesTitle = seriesTitle,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    data class ImportResult(
        val imported: Int,
        val updated: Int,
        val unchanged: Int,
        val invalid: Int,
    ) {
        fun summary(): String =
            "Imported: $imported, updated: $updated, unchanged: $unchanged, invalid: $invalid"
    }

    private companion object {
        const val CURRENT_VERSION = 1
    }
}
