package yokai.data.connection

import eu.kanade.tachiyomi.network.NetworkHelper
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import yokai.domain.connection.ConnectionTestResult
import yokai.domain.connection.KomgaAuthMethod

class ConnectionTester(
    networkHelper: NetworkHelper,
) {
    private val client = networkHelper.client.newBuilder()
        .dns(Dns.SYSTEM)
        .build()

    suspend fun testKomga(
        baseUrl: String,
        authMethod: KomgaAuthMethod,
        apiKey: String? = null,
        username: String? = null,
        password: String? = null,
    ): ConnectionTestResult {
        val url = endpoint(baseUrl, "api/v2/users/me")
            ?: return invalidUrl()

        val request = Request.Builder()
            .url(url)
            .apply {
                when (authMethod) {
                    KomgaAuthMethod.API_KEY -> {
                        if (apiKey.isNullOrBlank()) {
                            return ConnectionTestResult.Failure("Komga API key is required")
                        }
                        header("X-API-Key", apiKey)
                    }
                    KomgaAuthMethod.BASIC -> {
                        if (username.isNullOrBlank() || password.isNullOrEmpty()) {
                            return ConnectionTestResult.Failure(
                                "Komga username and password are required",
                            )
                        }
                        header("Authorization", Credentials.basic(username, password))
                    }
                }
            }
            .get()
            .build()

        return execute(request, "Komga")
    }

    suspend fun testGallery(
        baseUrl: String,
        apiToken: String?,
    ): ConnectionTestResult {
        if (apiToken.isNullOrBlank()) {
            return ConnectionTestResult.Failure("Gallery API token is required")
        }

        val url = endpoint(baseUrl, "api/v1/galleries")
            ?.newBuilder()
            ?.addQueryParameter("limit", "1")
            ?.build()
            ?: return invalidUrl()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiToken")
            .get()
            .build()

        return execute(request, "Gallery Komganion")
    }

    suspend fun testCalibre(
        baseUrl: String,
        username: String,
        password: String,
    ): ConnectionTestResult {
        if (username.isBlank() || password.isEmpty()) {
            return ConnectionTestResult.Failure("Calibre username and password are required")
        }

        val url = endpoint(baseUrl, "opds") ?: return invalidUrl()
        val request = Request.Builder()
            .url(url)
            .header("Accept", OPDS_CONTENT_TYPE)
            .get()
            .build()

        return executeCalibre(request, username, password)
    }

    private suspend fun executeCalibre(
        request: Request,
        username: String,
        password: String,
    ): ConnectionTestResult =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return@use validateCalibreResponse(response)
                    if (response.code != 401) return@use response.toResult("Calibre")

                    val challenge = response.header("WWW-Authenticate").orEmpty()
                    val authorization = when {
                        challenge.startsWith("Basic", ignoreCase = true) ->
                            Credentials.basic(username, password)
                        challenge.startsWith("Digest", ignoreCase = true) ->
                            digestAuthorization(request, challenge, username, password)
                        else -> null
                    } ?: return@use ConnectionTestResult.Failure(
                        "Calibre requested an unsupported authentication method",
                        statusCode = 401,
                    )

                    val authenticated = request.newBuilder()
                        .header("Authorization", authorization)
                        .build()
                    client.newCall(authenticated).execute().use { authenticatedResponse ->
                        if (authenticatedResponse.isSuccessful) {
                            validateCalibreResponse(authenticatedResponse)
                        } else {
                            authenticatedResponse.toResult("Calibre")
                        }
                    }
                }
            } catch (error: IOException) {
                ConnectionTestResult.Failure(error.message ?: "Could not connect to Calibre")
            }
        }

    private fun validateCalibreResponse(response: Response): ConnectionTestResult {
        val body = response.body.string()
        return if (body.contains("<feed") && body.contains("urn:calibre:main")) {
            ConnectionTestResult.Success
        } else {
            ConnectionTestResult.Failure(
                "The server responded, but it was not a Calibre OPDS catalog",
            )
        }
    }

    private fun digestAuthorization(
        request: Request,
        challenge: String,
        username: String,
        password: String,
    ): String? {
        val values = DIGEST_PARAMETER.findAll(challenge.removePrefix("Digest").trim())
            .associate { match ->
                match.groupValues[1].lowercase() to
                    (match.groupValues[2].ifEmpty { match.groupValues[3] })
            }
        val realm = values["realm"] ?: return null
        val nonce = values["nonce"] ?: return null
        val opaque = values["opaque"]
        val algorithm = values["algorithm"]?.uppercase() ?: "MD5"
        if (algorithm != "MD5" && algorithm != "MD5-SESS") return null

        val qop = values["qop"]?.split(',')?.map(String::trim)?.firstOrNull { it == "auth" }
        val uri = request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()
        val cnonce = UUID.randomUUID().toString().replace("-", "")
        val nonceCount = "00000001"
        val initialHa1 = md5("$username:$realm:$password")
        val ha1 = if (algorithm == "MD5-SESS") md5("$initialHa1:$nonce:$cnonce") else initialHa1
        val ha2 = md5("${request.method}:$uri")
        val digest = if (qop != null) {
            md5("$ha1:$nonce:$nonceCount:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        return buildString {
            append("Digest username=\"").append(username).append("\"")
            append(", realm=\"").append(realm).append("\"")
            append(", nonce=\"").append(nonce).append("\"")
            append(", uri=\"").append(uri).append("\"")
            append(", response=\"").append(digest).append("\"")
            append(", algorithm=").append(algorithm)
            if (opaque != null) append(", opaque=\"").append(opaque).append("\"")
            if (qop != null) {
                append(", qop=").append(qop)
                append(", nc=").append(nonceCount)
                append(", cnonce=\"").append(cnonce).append("\"")
            }
        }
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.ISO_8859_1))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun execute(
        request: Request,
        serviceName: String,
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                response.toResult(serviceName)
            }
        } catch (error: IOException) {
            ConnectionTestResult.Failure(
                error.message ?: "Could not connect to $serviceName",
            )
        }
    }

    private fun Response.toResult(serviceName: String): ConnectionTestResult {
        return if (isSuccessful) {
            ConnectionTestResult.Success
        } else {
            ConnectionTestResult.Failure(
                message = when (code) {
                    401, 403 -> "$serviceName rejected the credentials"
                    404 -> "$serviceName endpoint was not found; check the server URL"
                    else -> "$serviceName returned HTTP $code"
                },
                statusCode = code,
            )
        }
    }

    private fun endpoint(baseUrl: String, path: String): HttpUrl? {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isEmpty()) return null

        val base = "$normalized/".toHttpUrlOrNull() ?: return null
        if (base.scheme != "http" && base.scheme != "https") return null

        return base.newBuilder()
            .addPathSegments(path)
            .build()
    }

    private fun invalidUrl() = ConnectionTestResult.Failure(
        "Enter a valid http:// or https:// server URL",
    )

    private companion object {
        const val OPDS_CONTENT_TYPE = "application/atom+xml"
        val DIGEST_PARAMETER = Regex("""([A-Za-z]+)=(?:\"([^\"]*)\"|([^,\\s]+))""")
    }
}
