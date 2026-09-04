package yokai.source.komga

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import yokai.data.connection.CredentialStore
import yokai.domain.connection.ConnectionPreferences
import yokai.domain.connection.KomgaAuthMethod

class KomgaSource : HttpSource() {
    private val connectionPreferences: ConnectionPreferences by injectLazy()
    private val credentialStore: CredentialStore by injectLazy()
    private val json: Json by injectLazy()

    override val id: Long = ID
    override val name: String = "Komga"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    override val baseUrl: String
        get() = connectionPreferences.komgaBaseUrl().get().trimEnd('/')

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .dns(Dns.SYSTEM)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder().apply {
        when (connectionPreferences.komgaAuthMethod().get()) {
            KomgaAuthMethod.API_KEY -> {
                credentialStore.komgaApiKey
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add("X-API-Key", it) }
            }
            KomgaAuthMethod.BASIC -> {
                val username = connectionPreferences.komgaUsername().get()
                val password = credentialStore.komgaPassword
                if (username.isNotBlank() && !password.isNullOrEmpty()) {
                    add("Authorization", Credentials.basic(username, password))
                }
            }
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        return getSeriesPage(page, query = "", sort = "metadata.titleSort,asc")
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return getSeriesPage(page, query = "", sort = "lastModified,desc")
    }

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        return getSeriesPage(page, query = query, sort = "metadata.titleSort,asc")
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        return get<SeriesDto>(manga.url).toSManga()
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val seriesId = manga.url.substringAfterLast('/')
        val body = buildJsonObject {
            put(
                "condition",
                buildJsonObject {
                    put(
                        "seriesId",
                        buildJsonObject {
                            put("operator", "is")
                            put("value", seriesId)
                        },
                    )
                },
            )
        }.toString()

        val url = endpoint("/api/v1/books/list")
            .newBuilder()
            .addQueryParameter("unpaged", "true")
            .addQueryParameter("sort", "metadata.numberSort,asc")
            .build()

        val response = execute<PageBookDto>(
            Request.Builder()
                .url(url)
                .headers(headers)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )

        return response.content
            .filterNot { it.deleted }
            .map { it.toSChapter() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val bookId = chapter.url.substringAfterLast('/')
        val pages = get<List<KomgaPageDto>>("/api/v1/books/$bookId/pages")

        return pages.map { page ->
            val imageUrl = absoluteUrl(
                "/api/v1/books/$bookId/pages/${page.number}",
            )
            Page(
                index = (page.number - 1).coerceAtLeast(0),
                url = imageUrl,
                imageUrl = imageUrl,
            )
        }
    }

    fun getBookThumbnailUrl(bookId: String): String =
        absoluteUrl("/api/v1/books/$bookId/thumbnail")

    suspend fun deleteBook(bookId: String) {
        val request = Request.Builder()
            .url(endpoint("/api/v1/books/$bookId/file"))
            .headers(headers)
            .delete()
            .build()

        client.newCall(request).awaitSuccess().close()
    }

    private suspend fun getSeriesPage(
        page: Int,
        query: String,
        sort: String,
    ): MangasPage {
        val pageIndex = (page - 1).coerceAtLeast(0)
        val body = buildJsonObject {
            if (query.isNotBlank()) {
                put("fullTextSearch", query)
            }
        }.toString()

        val url = endpoint("/api/v1/series/list")
            .newBuilder()
            .addQueryParameter("page", pageIndex.toString())
            .addQueryParameter("size", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)
            .build()

        val response = execute<PageSeriesDto>(
            Request.Builder()
                .url(url)
                .headers(headers)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )

        return MangasPage(
            mangas = response.content
                .filterNot { it.deleted }
                .map { it.toSManga() },
            hasNextPage = !response.last,
        )
    }

    private suspend inline fun <reified T> get(path: String): T {
        val request = Request.Builder()
            .url(endpoint(path))
            .headers(headers)
            .get()
            .build()
        return execute(request)
    }

    private suspend inline fun <reified T> execute(request: Request): T {
        return client.newCall(request).awaitSuccess().use { response ->
            json.decodeFromString(response.body.string())
        }
    }

    private fun endpoint(path: String) = absoluteUrl(path).toHttpUrl()

    private fun absoluteUrl(path: String): String {
        check(baseUrl.isNotBlank()) {
            "Komga is not configured"
        }
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
        }
    }

    private fun SeriesDto.toSManga(): SManga {
        return SManga.create().apply {
            url = "/api/v1/series/$id"
            title = metadata.title.ifBlank { name }
            thumbnail_url = absoluteUrl("/api/v1/series/$id/thumbnail")
            author = null
            artist = null
            description = metadata.summary.takeIf { it.isNotBlank() }
            genre = metadata.genres.joinToString(", ").takeIf { it.isNotBlank() }
            status = metadata.status.toMangaStatus()
            initialized = true
        }
    }

    private fun BookDto.toSChapter(): SChapter {
        return SChapter.create().apply {
            url = "/api/v1/books/$id"
            name = metadata.title.ifBlank { this@toSChapter.name }
            chapter_number = metadata.numberSort
            date_upload = metadata.releaseDate.toEpochMilliseconds()
                .takeIf { it != 0L }
                ?: created.toInstantMilliseconds()
        }
    }

    private fun String.toMangaStatus(): Int {
        return when (uppercase()) {
            "ENDED" -> SManga.COMPLETED
            "ABANDONED", "CANCELLED" -> SManga.CANCELLED
            "HIATUS" -> SManga.ON_HIATUS
            "ONGOING" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun String?.toEpochMilliseconds(): Long {
        if (this.isNullOrBlank()) return 0L
        return runCatching {
            LocalDate.parse(this)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun String.toInstantMilliseconds(): Long {
        return runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0L)
    }

    override fun popularMangaRequest(page: Int): Request = unsupported()
    override fun popularMangaParse(response: Response): MangasPage = unsupported()
    override fun latestUpdatesRequest(page: Int): Request = unsupported()
    override fun latestUpdatesParse(response: Response): MangasPage = unsupported()

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = unsupported()

    override fun searchMangaParse(response: Response): MangasPage = unsupported()
    override fun mangaDetailsParse(response: Response): SManga = unsupported()
    override fun chapterListParse(response: Response): List<SChapter> = unsupported()
    override fun chapterPageParse(response: Response): SChapter = unsupported()
    override fun pageListParse(response: Response): List<Page> = unsupported()
    override fun imageUrlParse(response: Response): String = unsupported()

    private fun <T> unsupported(): T {
        throw UnsupportedOperationException("Komga source uses the suspend API")
    }

    companion object {
        const val ID: Long = 0x4B4F4D4741L
        private const val PAGE_SIZE = 50
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
private data class PageSeriesDto(
    val content: List<SeriesDto> = emptyList(),
    val last: Boolean = true,
)

@Serializable
private data class SeriesDto(
    val id: String,
    val name: String,
    val deleted: Boolean = false,
    val metadata: SeriesMetadataDto,
)

@Serializable
private data class SeriesMetadataDto(
    val title: String = "",
    val titleSort: String = "",
    val summary: String = "",
    val status: String = "",
    val genres: List<String> = emptyList(),
)

@Serializable
private data class PageBookDto(
    val content: List<BookDto> = emptyList(),
)

@Serializable
private data class BookDto(
    val id: String,
    val name: String,
    val created: String,
    val deleted: Boolean = false,
    val metadata: BookMetadataDto,
)

@Serializable
private data class BookMetadataDto(
    val title: String = "",
    val number: String = "",
    val numberSort: Float = -1f,
    val releaseDate: String? = null,
)

@Serializable
private data class KomgaPageDto(
    val number: Int,
    val fileName: String,
    val mediaType: String,
)
