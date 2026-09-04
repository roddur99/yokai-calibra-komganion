package yokai.data.calibre

/**
 * Stable source identity used by the local reading-activity dashboard.
 *
 * This is intentionally separate from Tachiyomi source IDs because Calibre
 * books are not represented as manga records.
 */
object CalibreReadingSource {
    const val ID: Long = 0x43414C49425245L
}
