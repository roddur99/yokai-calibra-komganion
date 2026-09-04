package yokai.data.calibre

import android.app.Application
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CalibreEpubStore(
    app: Application,
    private val catalogClient: CalibreCatalogClient,
) {
    private val directory = File(app.filesDir, "calibre/epubs")

    fun isDownloaded(bookId: String): Boolean = fileFor(bookId).isFile

    fun downloadedCount(): Int =
        directory.listFiles { file -> file.isFile && file.extension.equals("epub", ignoreCase = true) }
            ?.size
            ?: 0

    suspend fun download(book: CalibreBook): File = withContext(Dispatchers.IO) {
        val url = book.epubUrl ?: error("This Calibre book has no EPUB format")
        directory.mkdirs()
        val destination = fileFor(book.id)
        val temporary = File(directory, destination.name + ".part")
        temporary.delete()
        try {
            catalogClient.downloadTo(url, temporary)
            check(temporary.length() > 0L) { "Calibre returned an empty EPUB" }
            if (destination.exists() && !destination.delete()) {
                error("Unable to replace the existing EPUB")
            }
            check(temporary.renameTo(destination)) { "Unable to finish the EPUB download" }
            destination
        } finally {
            temporary.delete()
        }
    }

    suspend fun delete(bookId: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(bookId)
        !file.exists() || file.delete()
    }

    fun fileFor(bookId: String): File =
        File(directory, sha256(bookId) + ".epub")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
