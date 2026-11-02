package com.sangam.apkforge.data

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs APKs with the key bundled in assets/signing/apkforge.p12.
 *
 * Every APK must be signed before Android will install it. After you edit an APK
 * its old signature breaks, so it has to be signed again. Because this key is not
 * the original developer's key, a re-signed app cannot be installed over the
 * original; uninstall the original first.
 */
object ApkSigning {

    private const val KEYSTORE_ASSET = "signing/apkforge.p12"
    private const val ALIAS = "apkforge"
    private val PASSWORD = "apkforge".toCharArray()

    fun sign(context: Context, input: File, output: File) {
        val keyStore = KeyStore.getInstance("PKCS12")
        context.assets.open(KEYSTORE_ASSET).use { keyStore.load(it, PASSWORD) }

        val key = keyStore.getKey(ALIAS, PASSWORD) as PrivateKey
        val cert = keyStore.getCertificate(ALIAS) as X509Certificate

        val signer = ApkSigner.SignerConfig.Builder("APKFORGE", key, listOf(cert)).build()

        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
