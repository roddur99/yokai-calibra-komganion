package yokai.data.calibre

import android.util.Xml
import eu.kanade.tachiyomi.network.NetworkHelper
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import yokai.data.connection.CredentialStore
import yokai.domain.connection.ConnectionPreferences

class CalibreCatalogClient(
    networkHelper: NetworkHelper,
    private val preferences: ConnectionPreferences,
    private val credentialStore: CredentialStore,
) {
    private val client = networkHelper.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun getLightNovels(): List<CalibreBook> = withContext(Dispatchers.IO) {
        val baseUrl = preferences.calibreBaseUrl().get().trim().trimEnd('/')
        val libraryId = preferences.calibreLibraryId().get().trim()
        val requiredTag = preferences.calibreLightNovelTag().get().trim()
        require(baseUrl.isNotEmpty()) { "Configure the Calibre server in Data and storage" }

        val base = "$baseUrl/".toHttpUrlOrNull() ?: error("Invalid Calibre server URL")
        val search = "tags:\"=$requiredTag\""
        var nextUrl: HttpUrl? = base.newBuilder()
            .addPathSegments("opds/search")
            .addPathSegment(search)
            .addQueryParameter("library_id", libraryId)
            .build()
        val books = linkedMapOf<String, CalibreBook>()
        var pageCount = 0

        while (nextUrl != null && pageCount++ < MAX_PAGES) {
            val pageUrl = nextUrl
            val xml = getText(pageUrl)
            val page = parseFeed(xml, pageUrl)
            // Calibre applies the exact tag query server-side. Acquisition entries do not
            // expose tags as Atom categories, so filtering parsed categories here would
            // incorrectly discard every matching book.
            page.books.forEach { books[it.id] = it }
            nextUrl = page.nextUrl?.toHttpUrlOrNull()
        }
        enrichTags(books.values.toList(), base, libraryId)
    }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        executeAuthenticated(url.toHttpUrlOrNull() ?: error("Invalid Calibre resource URL")).use { response ->
            if (!response.isSuccessful) error("Calibre returned HTTP ${response.code}")
            response.body.bytes()
        }
    }

    suspend fun downloadTo(url: String, destination: File) = withContext(Dispatchers.IO) {
        executeAuthenticated(url.toHttpUrlOrNull() ?: error("Invalid Calibre resource URL")).use { response ->
            if (!response.isSuccessful) error("Calibre returned HTTP ${response.code}")
            destination.outputStream().buffered().use { output ->
                response.body.byteStream().use { input -> input.copyTo(output) }
            }
        }
    }

    private fun getText(url: HttpUrl): String = executeAuthenticated(url).use { response ->
        if (!response.isSuccessful) error("Calibre returned HTTP ${response.code}")
        response.body.string()
    }

    private fun executeAuthenticated(
        url: HttpUrl,
        accept: String = "application/atom+xml",
    ): Response {
        val configuredOrigin = configuredOrigin()
        val trustedOrigin = url.scheme == configuredOrigin.scheme &&
            url.host == configuredOrigin.host &&
            url.port == configuredOrigin.port
        require(trustedOrigin) {
            "Refusing to send Calibre credentials to an untrusted host"
        }
        val username = preferences.calibreUsername().get()
        val password = credentialStore.calibrePassword.orEmpty()
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .get()
            .build()
        val first = client.newCall(request).execute()
        if (first.code != 401) return first

        val challenge = first.header("WWW-Authenticate").orEmpty()
        first.close()
        val authorization = when {
            challenge.startsWith("Basic", ignoreCase = true) -> Credentials.basic(username, password)
            challenge.startsWith("Digest", ignoreCase = true) ->
                digestAuthorization(request, challenge, username, password)
            else -> null
        } ?: throw IOException("Unsupported Calibre authentication method")

        return client.newCall(request.newBuilder().header("Authorization", authorization).build()).execute()
    }

    private fun configuredOrigin(): HttpUrl {
        val baseUrl = preferences.calibreBaseUrl().get().trim().trimEnd('/')
        return "$baseUrl/".toHttpUrlOrNull() ?: error("Invalid Calibre server URL")
    }

    private fun parseFeed(xml: String, feedUrl: HttpUrl): FeedPage {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xml))
        }
        val books = mutableListOf<CalibreBook>()
        var nextUrl: String? = null
        var builder: BookBuilder? = null
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> builder = BookBuilder()
                    "title" -> builder?.title = parser.nextText()
                    "id" -> builder?.id = parser.nextText()
                    "updated" -> builder?.updatedAt = parser.nextText()
                    "name", "creator" -> builder?.authors?.add(parser.nextText())
                    "series" -> builder?.series = parser.nextText()
                    "series_index" -> builder?.seriesIndex = parser.nextText().toDoubleOrNull()
                    "category" -> builder?.tags?.add(parser.getAttributeValue(null, "term").orEmpty())
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel").orEmpty()
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val href = parser.getAttributeValue(null, "href")
                        val resolved = href?.let(feedUrl::resolve)?.toString()
                        if (builder == null && rel == "next") nextUrl = resolved
                        if (builder != null && resolved != null) {
                            when {
                                rel.contains("thumbnail") -> builder?.thumbnailUrl = resolved
                                rel.contains("image") -> builder?.coverUrl = resolved
                                rel.contains("acquisition") && type.contains("epub") -> builder?.epubUrl = resolved
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "entry") {
                    builder?.build()?.let(books::add)
                    builder = null
                }
            }
            event = parser.next()
        }
        return FeedPage(books, nextUrl)
    }

    private fun enrichTags(
        books: List<CalibreBook>,
        base: HttpUrl,
        libraryId: String,
    ): List<CalibreBook> {
        val bookByUuid = books.mapNotNull { book ->
            normalizedUuid(book.id)?.let { it to book }
        }.toMap()
        if (bookByUuid.isEmpty()) return books

        val tagsByUuid = mutableMapOf<String, Set<String>>()
        bookByUuid.keys.chunked(METADATA_BATCH_SIZE).forEach { uuids ->
            val builder = base.newBuilder().addPathSegments("ajax/books")
            if (libraryId.isNotBlank()) builder.addPathSegment(libraryId)
            val url = builder
                .addQueryParameter("ids", uuids.joinToString(","))
                .addQueryParameter("id_is_uuid", "true")
                .addQueryParameter("category_urls", "false")
                .build()
            val response = executeAuthenticated(url, accept = "application/json").use { result ->
                if (!result.isSuccessful) error("Calibre metadata returned HTTP ${result.code}")
                JSONObject(result.body.string())
            }
            response.keys().forEach { key ->
                val metadata = response.optJSONObject(key) ?: return@forEach
                val uuid = normalizedUuid(metadata.optString("uuid")) ?: return@forEach
                val tags = metadata.optJSONArray("tags")
                tagsByUuid[uuid] = buildSet {
                    if (tags != null) {
                        for (index in 0 until tags.length()) {
                            tags.optString(index).trim().takeIf(String::isNotEmpty)?.let { add(it) }
                        }
                    }
                }
            }
        }
        return books.map { book ->
            val tags = normalizedUuid(book.id)?.let(tagsByUuid::get)
            if (tags == null) book else book.copy(tags = tags)
        }
    }

    private fun normalizedUuid(value: String): String? {
        val candidate = value.substringAfterLast(':').trim()
        return runCatching { UUID.fromString(candidate).toString() }.getOrNull()
    }

    private fun digestAuthorization(
        request: Request,
        challenge: String,
        username: String,
        password: String,
    ): String? {
        val values = DIGEST_PARAMETER.findAll(challenge.substringAfter(' '))
            .associate { match ->
                match.groupValues[1].lowercase() to
                    match.groupValues[2].ifEmpty { match.groupValues[3] }
            }
        val realm = values["realm"] ?: return null
        val nonce = values["nonce"] ?: return null
        val opaque = values["opaque"]
        val qop = values["qop"]?.split(',')?.map(String::trim)?.firstOrNull { it == "auth" }
        val uri = request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()
        val cnonce = UUID.randomUUID().toString().replace("-", "")
        val nc = "00000001"
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("${request.method}:$uri")
        val response = if (qop == null) md5("$ha1:$nonce:$ha2") else md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        return buildString {
            append("Digest username=\"").append(username).append("\", realm=\"").append(realm)
            append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri)
            append("\", response=\"").append(response).append("\", algorithm=MD5")
            if (opaque != null) append(", opaque=\"").append(opaque).append('"')
            if (qop != null) append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
        }
    }

    private fun md5(value: String) = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.ISO_8859_1))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class FeedPage(val books: List<CalibreBook>, val nextUrl: String?)

    private class BookBuilder {
        var id = ""
        var title = ""
        val authors = mutableListOf<String>()
        var series: String? = null
        var seriesIndex: Double? = null
        val tags = mutableSetOf<String>()
        var coverUrl: String? = null
        var thumbnailUrl: String? = null
        var epubUrl: String? = null
        var updatedAt: String? = null

        fun build() = if (id.isBlank() || title.isBlank()) null else CalibreBook(
            id, title, authors.distinct(), series, seriesIndex, tags.filter(String::isNotBlank).toSet(),
            coverUrl, thumbnailUrl, epubUrl, updatedAt,
        )
    }

    private companion object {
        const val MAX_PAGES = 100
        const val METADATA_BATCH_SIZE = 100
        val DIGEST_PARAMETER = Regex("""([A-Za-z]+)=(?:\"([^\"]*)\"|([^,\\s]+))""")
    }
}
