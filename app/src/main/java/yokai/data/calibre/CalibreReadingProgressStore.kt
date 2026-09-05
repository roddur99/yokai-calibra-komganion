package yokai.data.calibre

import android.app.Application
import java.security.MessageDigest
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

class CalibreReadingProgressStore(app: Application) {
    private val preferences = app.getSharedPreferences("calibre_reading_progress", 0)

    fun get(bookId: String): Locator? =
        preferences.getString(key(bookId), null)?.let(::decode)?.locator

    fun percentage(bookId: String): Int? =
        get(bookId)?.locations?.totalProgression
            ?.coerceIn(0.0, 1.0)
            ?.times(100)
            ?.toInt()

    fun startedCount(): Int = allStored().count { it.locator.percentage() > 0 }

    fun completedCount(): Int = allStored().count { it.locator.percentage() >= 99 }

    fun exportEntries(): List<ExportEntry> =
        preferences.all.mapNotNull { (storageKey, value) ->
            val stored = (value as? String)?.let(::decode) ?: return@mapNotNull null
            ExportEntry(
                storageKey = storageKey,
                locatorJson = stored.locator.toJSON().toString(),
                updatedAt = stored.updatedAt,
            )
        }

    fun restore(entry: ExportEntry): RestoreOutcome {
        if (!STORAGE_KEY.matches(entry.storageKey) || entry.updatedAt < 0) {
            return RestoreOutcome.INVALID
        }
        val locator = runCatching {
            Locator.fromJSON(JSONObject(entry.locatorJson))
        }.getOrNull() ?: return RestoreOutcome.INVALID

        val current = preferences.getString(entry.storageKey, null)?.let(::decode)
        if (current != null && current.updatedAt >= entry.updatedAt) {
            return RestoreOutcome.UNCHANGED
        }

        preferences.edit()
            .putString(entry.storageKey, encode(locator, entry.updatedAt))
            .apply()
        return if (current == null) RestoreOutcome.IMPORTED else RestoreOutcome.UPDATED
    }

    fun save(bookId: String, locator: Locator) {
        preferences.edit()
            .putString(key(bookId), encode(locator, System.currentTimeMillis()))
            .apply()
    }

    fun remove(bookId: String) {
        preferences.edit().remove(key(bookId)).apply()
    }

    private fun allStored(): List<StoredLocator> =
        preferences.all.values.mapNotNull { value -> (value as? String)?.let(::decode) }

    private fun decode(raw: String): StoredLocator? = runCatching {
        val json = JSONObject(raw)
        val wrappedLocator = json.optJSONObject(KEY_LOCATOR)
        if (wrappedLocator != null) {
            StoredLocator(
                locator = Locator.fromJSON(wrappedLocator) ?: return@runCatching null,
                updatedAt = json.optLong(KEY_UPDATED_AT, 0L).coerceAtLeast(0L),
            )
        } else {
            // Backward compatibility for locators saved before backup version 3.
            StoredLocator(
                locator = Locator.fromJSON(json) ?: return@runCatching null,
                updatedAt = 0L,
            )
        }
    }.getOrNull()

    private fun encode(locator: Locator, updatedAt: Long): String =
        JSONObject()
            .put(KEY_LOCATOR, locator.toJSON())
            .put(KEY_UPDATED_AT, updatedAt)
            .toString()

    private fun Locator.percentage(): Int =
        locations.totalProgression
            ?.coerceIn(0.0, 1.0)
            ?.times(100)
            ?.toInt()
            ?: 0

    private fun key(bookId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(bookId.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    data class ExportEntry(
        val storageKey: String,
        val locatorJson: String,
        val updatedAt: Long,
    )

    enum class RestoreOutcome {
        IMPORTED,
        UPDATED,
        UNCHANGED,
        INVALID,
    }

    private data class StoredLocator(
        val locator: Locator,
        val updatedAt: Long,
    )

    private companion object {
        const val KEY_LOCATOR = "locator"
        const val KEY_UPDATED_AT = "updatedAt"
        val STORAGE_KEY = Regex("[0-9a-f]{64}")
    }
}
