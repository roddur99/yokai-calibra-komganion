package yokai.data.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var komgaApiKey: String?
        get() = read(KOMGA_API_KEY)
        set(value) = write(KOMGA_API_KEY, value)

    var komgaPassword: String?
        get() = read(KOMGA_PASSWORD)
        set(value) = write(KOMGA_PASSWORD, value)

    var galleryApiToken: String?
        get() = read(GALLERY_API_TOKEN)
        set(value) = write(GALLERY_API_TOKEN, value)

    var calibrePassword: String?
        get() = read(CALIBRE_PASSWORD)
        set(value) = write(CALIBRE_PASSWORD, value)

    fun clearKomgaCredentials() {
        preferences.edit()
            .remove(KOMGA_API_KEY)
            .remove(KOMGA_PASSWORD)
            .apply()
    }

    fun clearGalleryCredentials() {
        preferences.edit().remove(GALLERY_API_TOKEN).apply()
    }

    fun clearCalibreCredentials() {
        preferences.edit().remove(CALIBRE_PASSWORD).apply()
    }

    private fun write(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            preferences.edit().remove(name).apply()
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        preferences.edit().putString(name, payload).apply()
    }

    private fun read(name: String): String? {
        val payload = preferences.getString(name, null) ?: return null

        return runCatching {
            val decoded = Base64.decode(payload, Base64.NO_WRAP)
            require(decoded.size > IV_SIZE)

            val iv = decoded.copyOfRange(0, IV_SIZE)
            val encrypted = decoded.copyOfRange(IV_SIZE, decoded.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_SIZE_BITS, iv),
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrElse {
            preferences.edit().remove(name).apply()
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }

        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
            return it
        }

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "komganion_credentials"
        const val KEY_ALIAS = "yokai_komganion_credentials"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_SIZE_BITS = 128

        const val KOMGA_API_KEY = "komga_api_key"
        const val KOMGA_PASSWORD = "komga_password"
        const val GALLERY_API_TOKEN = "gallery_api_token"
        const val CALIBRE_PASSWORD = "calibre_password"
    }
}
