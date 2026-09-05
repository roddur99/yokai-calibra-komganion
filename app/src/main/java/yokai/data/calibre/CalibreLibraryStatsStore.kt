package yokai.data.calibre

import android.app.Application

class CalibreLibraryStatsStore(app: Application) {
    private val preferences = app.getSharedPreferences("calibre_library_stats", 0)

    fun availableCount(): Int = preferences.getInt(KEY_AVAILABLE_COUNT, 0)

    fun updateAvailableCount(count: Int) {
        preferences.edit().putInt(KEY_AVAILABLE_COUNT, count.coerceAtLeast(0)).apply()
    }

    private companion object {
        const val KEY_AVAILABLE_COUNT = "available_count"
    }
}
