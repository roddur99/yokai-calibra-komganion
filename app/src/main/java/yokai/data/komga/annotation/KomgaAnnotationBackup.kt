package yokai.data.komga.annotation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import yokai.data.calibre.CalibreReadingProgressStore
import yokai.domain.activity.ReadingActivityRepository
import yokai.domain.activity.model.ReadingActivity
import yokai.domain.komga.annotation.KomgaBookAnnotationRepository
import yokai.domain.komga.annotation.model.KomgaBookAnnotation

class KomgaAnnotationBackup(
    private val repository: KomgaBookAnnotationRepository,
    private val activityRepository: ReadingActivityRepository,
    private val calibreProgressStore: CalibreReadingProgressStore,
    private val json: Json,
) {
    suspend fun export(): String {
        val backup = BackupFile(
            version = CURRENT_VERSION,
            exportedAt = System.currentTimeMillis(),
            annotations = repository.getAll().map { it.toBackupEntry() },
            activities = activityRepository.getAll().map { it.toActivityEntry() },
            calibreProgress = calibreProgressStore.exportEntries().map { it.toProgressEntry() },
        )
        return json.encodeToString(backup)
    }

    suspend fun restore(content: String): ImportResult {
        val backup = json.decodeFromString<BackupFile>(content)
        require(backup.version in 1..CURRENT_VERSION) {
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

        val existingActivities = activityRepository.getAll().map { it.fingerprint() }.toMutableSet()
        var activitiesImported = 0
        var activitiesUnchanged = 0
        var activitiesInvalid = 0
        backup.activities.forEach { entry ->
            if (!entry.isValid()) {
                activitiesInvalid++
                return@forEach
            }
            val activity = entry.toActivity()
            if (!existingActivities.add(activity.fingerprint())) {
                activitiesUnchanged++
                return@forEach
            }
            activityRepository.insert(activity)
            activitiesImported++
        }

        var progressImported = 0
        var progressUpdated = 0
        var progressUnchanged = 0
        var progressInvalid = 0
        backup.calibreProgress.forEach { entry ->
            when (calibreProgressStore.restore(entry.toStoreEntry())) {
                CalibreReadingProgressStore.RestoreOutcome.IMPORTED -> progressImported++
                CalibreReadingProgressStore.RestoreOutcome.UPDATED -> progressUpdated++
                CalibreReadingProgressStore.RestoreOutcome.UNCHANGED -> progressUnchanged++
                CalibreReadingProgressStore.RestoreOutcome.INVALID -> progressInvalid++
            }
        }

        return ImportResult(
            imported = imported,
            updated = updated,
            unchanged = unchanged,
            invalid = invalid,
            activitiesImported = activitiesImported,
            activitiesUnchanged = activitiesUnchanged,
            activitiesInvalid = activitiesInvalid,
            progressImported = progressImported,
            progressUpdated = progressUpdated,
            progressUnchanged = progressUnchanged,
            progressInvalid = progressInvalid,
        )
    }

    @Serializable
    private data class BackupFile(
        val version: Int,
        val exportedAt: Long,
        val annotations: List<BackupEntry>,
        val activities: List<ActivityEntry> = emptyList(),
        val calibreProgress: List<CalibreProgressEntry> = emptyList(),
    )

    @Serializable
    private data class CalibreProgressEntry(
        val storageKey: String,
        val locatorJson: String,
        val updatedAt: Long,
    ) {
        fun toStoreEntry() = CalibreReadingProgressStore.ExportEntry(
            storageKey = storageKey,
            locatorJson = locatorJson,
            updatedAt = updatedAt,
        )
    }

    @Serializable
    private data class ActivityEntry(
        val sourceId: Long,
        val mangaId: Long? = null,
        val chapterId: Long? = null,
        val itemKey: String,
        val seriesTitle: String,
        val itemTitle: String,
        val startedAt: Long,
        val endedAt: Long,
        val durationMs: Long,
        val pagesViewed: Int,
        val completed: Boolean,
    ) {
        fun isValid() = itemKey.isNotBlank() && startedAt >= 0 && endedAt >= startedAt &&
            durationMs >= 0 && pagesViewed >= 0

        fun toActivity() = ReadingActivity(
            id = 0,
            sourceId = sourceId,
            mangaId = mangaId,
            chapterId = chapterId,
            itemKey = itemKey,
            seriesTitle = seriesTitle,
            itemTitle = itemTitle,
            startedAt = startedAt,
            endedAt = endedAt,
            durationMs = durationMs,
            pagesViewed = pagesViewed,
            completed = completed,
        )
    }

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

    private fun CalibreReadingProgressStore.ExportEntry.toProgressEntry() =
        CalibreProgressEntry(storageKey, locatorJson, updatedAt)

    private fun ReadingActivity.toActivityEntry() = ActivityEntry(
        sourceId, mangaId, chapterId, itemKey, seriesTitle, itemTitle, startedAt, endedAt,
        durationMs, pagesViewed, completed,
    )

    private fun ReadingActivity.fingerprint() =
        "$sourceId|$itemKey|$startedAt|$endedAt|$durationMs|$pagesViewed|$completed"

    data class ImportResult(
        val imported: Int,
        val updated: Int,
        val unchanged: Int,
        val invalid: Int,
        val activitiesImported: Int,
        val activitiesUnchanged: Int,
        val activitiesInvalid: Int,
        val progressImported: Int,
        val progressUpdated: Int,
        val progressUnchanged: Int,
        val progressInvalid: Int,
    ) {
        fun summary(): String =
            "Annotations — imported: $imported, updated: $updated, unchanged: $unchanged, invalid: $invalid. " +
                "Activity — imported: $activitiesImported, unchanged: $activitiesUnchanged, invalid: $activitiesInvalid. " +
                "EPUB progress — imported: $progressImported, updated: $progressUpdated, " +
                "unchanged: $progressUnchanged, invalid: $progressInvalid"
    }

    private companion object {
        const val CURRENT_VERSION = 3
    }
}
