package yokai.domain.connection

enum class KomgaAuthMethod {
    API_KEY,
    BASIC,
}

sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult

    data class Failure(
        val message: String,
        val statusCode: Int? = null,
    ) : ConnectionTestResult
}
