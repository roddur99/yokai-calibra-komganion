package yokai.data.calibre

import android.app.Application
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.Theme

class CalibreReaderPreferencesStore(app: Application) {
    private val preferences = app.getSharedPreferences("calibre_reader_preferences", 0)

    fun get(): Values = Values(
        scroll = preferences.getBoolean(KEY_SCROLL, false),
        theme = ThemeValue.valueOf(preferences.getString(KEY_THEME, ThemeValue.LIGHT.name)!!),
        fontSize = preferences.getInt(KEY_FONT_SIZE, 100),
        margins = preferences.getInt(KEY_MARGINS, 100),
        lineHeight = preferences.getInt(KEY_LINE_HEIGHT, 140),
    )

    fun save(values: Values) {
        preferences.edit()
            .putBoolean(KEY_SCROLL, values.scroll)
            .putString(KEY_THEME, values.theme.name)
            .putInt(KEY_FONT_SIZE, values.fontSize)
            .putInt(KEY_MARGINS, values.margins)
            .putInt(KEY_LINE_HEIGHT, values.lineHeight)
            .apply()
    }

    data class Values(
        val scroll: Boolean,
        val theme: ThemeValue,
        val fontSize: Int,
        val margins: Int,
        val lineHeight: Int,
    ) {
        fun toReadium() = EpubPreferences(
            scroll = scroll,
            theme = when (theme) {
                ThemeValue.LIGHT -> Theme.LIGHT
                ThemeValue.SEPIA -> Theme.SEPIA
                ThemeValue.DARK -> Theme.DARK
            },
            fontSize = fontSize / 100.0,
            pageMargins = margins / 100.0,
            lineHeight = lineHeight / 100.0,
            columnCount = ColumnCount.ONE,
            spread = Spread.NEVER,
            publisherStyles = false,
        )
    }

    enum class ThemeValue { LIGHT, SEPIA, DARK }

    private companion object {
        const val KEY_SCROLL = "scroll"
        const val KEY_THEME = "theme"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_MARGINS = "margins"
        const val KEY_LINE_HEIGHT = "line_height"
    }
}
