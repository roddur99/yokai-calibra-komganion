package yokai.data.calibre

import android.app.Application
import java.security.MessageDigest
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

class CalibreReadingProgressStore(app: Application) {
    private val preferences = app.getSharedPreferences("calibre_reading_progress", 0)

    fun get(bookId: String): Locator? =
        preferences.getString(key(bookId), null)
            ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }

    fun save(bookId: String, locator: Locator) {
        preferences.edit()
            .putString(key(bookId), locator.toJSON().toString())
            .apply()
    }

    fun remove(bookId: String) {
        preferences.edit().remove(key(bookId)).apply()
    }

    private fun key(bookId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(bookId.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
