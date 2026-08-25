package yokai.data.connection

import eu.kanade.tachiyomi.network.NetworkHelper
import java.io.IOException
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
}
