package yokai.source.gallery

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import yokai.data.connection.CredentialStore
import yokai.domain.connection.ConnectionPreferences

class GalleryKomganionSource : HttpSource() {
    private val connectionPreferences: ConnectionPreferences by injectLazy()
    private val credentialStore: CredentialStore by injectLazy()
    private val json: Json by injectLazy()

    override val id: Long = ID
    override val name: String = "Gallery Komganion"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    override val baseUrl: String
        get() = connectionPreferences.galleryBaseUrl().get().trimEnd('/')

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .dns(Dns.SYSTEM)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder().apply {
        credentialStore.galleryApiToken
            ?.takeIf { it.isNotBlank() }
            ?.let { add("Authorization", "Bearer $it") }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        return getGalleryPage(page, sort = "title", direction = "asc")
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return getGalleryPage(page, sort = "recentlyDetected", direction = "desc")
    }

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        return getGalleryPage(
            page = page,
            sort = "title",
            direction = "asc",
            query = query,
        )
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val gallery = get<GalleryDto>(manga.url)
        return gallery.toSManga()
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val gallery = get<GalleryDto>(manga.url)
        return listOf(
            SChapter.create().apply {
                url = "/api/v1/galleries/${gallery.id}/pages"
                name = gallery.title
                chapter_number = 1f
                date_upload = gallery.modifiedAt.toEpochMilliseconds()
            },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = get<PageListResponse>(chapter.url)
        return response.items.map { page ->
            GalleryPage(
                index = page.pageIndex,
                url = absoluteUrl(page.imageUrl),
                imageUrl = absoluteUrl(page.imageUrl),
                filename = page.filename,
                modifiedAt = page.modifiedAt,
                sizeBytes = page.sizeBytes,
                width = page.width,
                height = page.height,
            )
        }
    }

    private suspend fun getGalleryPage(
        page: Int,
        sort: String,
        direction: String,
        query: String? = null,
    ): MangasPage {
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * PAGE_SIZE
        val url = endpoint("/api/v1/galleries")
            .newBuilder()
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("sort", sort)
            .addQueryParameter("direction", direction)
            .apply {
                query?.takeIf { it.isNotBlank() }?.let {
                    addQueryParameter("query", it)
                }
            }
            .build()

        val response = execute<GalleryListResponse>(
            Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build(),
        )

        return MangasPage(
            mangas = response.items.map { it.toSManga() },
            hasNextPage = offset + response.items.size < response.total,
        )
    }

    suspend fun trashPage(
        galleryId: String,
        pageIndex: Int,
    ): TrashedPageResponse {
        val request = Request.Builder()
            .url(endpoint("/api/v1/galleries/$galleryId/pages/$pageIndex"))
            .headers(headers)
            .delete()
            .build()
        return execute(request)
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
            val body = response.body.string()
            json.decodeFromString(body)
        }
    }

    private fun endpoint(path: String) = absoluteUrl(path).toHttpUrl()

    private fun absoluteUrl(path: String): String {
        check(baseUrl.isNotBlank()) {
            "Gallery Komganion is not configured"
        }
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
        }
    }

    private fun String.toEpochMilliseconds(): Long {
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
        throw UnsupportedOperationException("Gallery source uses the suspend API")
    }

    private fun GalleryDto.toSManga(): SManga {
        return SManga.create().apply {
            url = "/api/v1/galleries/$id"
            title = this@toSManga.title
            thumbnail_url = coverUrl?.let(::absoluteUrl)
            author = null
            artist = null
            description = relativePath
            genre = categoryPath.joinToString(", ").takeIf { it.isNotBlank() }
            status = SManga.COMPLETED
            initialized = true
        }
    }

    companion object {
        const val ID: Long = 0x47414C4C455259L
        private const val PAGE_SIZE = 50
    }
}

@Serializable
private data class GalleryListResponse(
    val items: List<GalleryDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
private data class GalleryDto(
    val id: String,
    val title: String,
    val relativePath: String,
    val categoryPath: List<String>,
    val pageCount: Int,
    val modifiedAt: String,
    val detectedAt: String,
    val status: String,
    val canDelete: Boolean,
    val coverUrl: String? = null,
    val lastScannedAt: String? = null,
)

@Serializable
private data class PageListResponse(
    val galleryId: String,
    val items: List<PageDto>,
)

@Serializable
private data class PageDto(
    val pageIndex: Int,
    val filename: String,
    val sizeBytes: Long,
    val modifiedAt: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val imageUrl: String,
    val thumbnailUrl: String,
)

class GalleryPage(
    index: Int,
    url: String,
    imageUrl: String,
    val filename: String,
    val modifiedAt: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
) : Page(index, url, imageUrl)

@Serializable
data class TrashedPageResponse(
    val galleryId: String,
    val filename: String,
    val trashRelativePath: String,
    val remainingPages: Int,
    val nextPageIndex: Int?,
)

