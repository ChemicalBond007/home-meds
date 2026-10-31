package com.homemeds.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Base64

/**
 * RSA key in Android Keystore. The Worker seals provider keys to it, so they
 * are only readable inside this app process and never pass through the page.
 */
object DeviceKeys {
    private const val ALIAS = "home-meds-ai"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun publicKey(): String {
        if (!store().containsAlias(ALIAS)) generate()
        return Base64.getEncoder().encodeToString(store().getCertificate(ALIAS).publicKey.encoded)
    }

    private fun generate() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .build(),
            )
        }.generateKeyPair()
    }

    @Synchronized
    fun open(sealed: JSONObject): JSONObject {
        val key = store().getKey(ALIAS, null) as? PrivateKey ?: throw UserError("设备密钥不存在，请重新发送")
        return try {
            JSONObject(Sealed.open(key, sealed))
        } catch (e: GeneralSecurityException) {
            // A key invalidated by the system cannot recover; the next request makes a new one.
            store().deleteEntry(ALIAS)
            throw UserError("无法解开 AI 凭据，请重新发送")
        }
    }
}
