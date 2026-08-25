package yokai.domain.connection

import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.preference.getEnum

class ConnectionPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun komgaBaseUrl() = preferenceStore.getString("komganion_komga_base_url")

    fun komgaAuthMethod() = preferenceStore.getEnum(
        "komganion_komga_auth_method",
        KomgaAuthMethod.API_KEY,
    )

    fun komgaUsername() = preferenceStore.getString("komganion_komga_username")

    fun galleryBaseUrl() = preferenceStore.getString("komganion_gallery_base_url")
}
