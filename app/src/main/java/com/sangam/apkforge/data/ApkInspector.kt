package com.sangam.apkforge.data

import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.zip.ZipFile

/** Reads the manifest info, signature and file list of an APK. Call off the main thread. */
class ApkInspector(private val repo: AppRepository) {

    fun inspect(file: File, fromInstalledApp: Boolean, splitApks: List<String>): ApkDetails {
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS

        // Some APKs make Android choke when asked for every component at once,
        // so fall back to the bare minimum before giving up.
        val pi = repo.archiveInfo(file, flags)
            ?: repo.archiveInfo(file, 0)
            ?: throw IOException("Android could not read this file. It may not be a valid APK.")

        val ai = pi.applicationInfo
        ai?.sourceDir = file.absolutePath
        ai?.publicSourceDir = file.absolutePath

        return ApkDetails(
            file = file,
            label = ai?.let { runCatching { it.loadLabel(repo.packageManager).toString() }.getOrNull() }
                ?: pi.packageName,
            packageName = pi.packageName,
            versionName = pi.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(pi),
            minSdk = ai?.minSdkVersion,
            targetSdk = ai?.targetSdkVersion,
            sizeBytes = file.length(),
            permissions = pi.requestedPermissions?.toList().orEmpty().sorted(),
            activities = pi.activities?.map { it.name }.orEmpty(),
            services = pi.services?.map { it.name }.orEmpty(),
            receivers = pi.receivers?.map { it.name }.orEmpty(),
            providers = pi.providers?.map { it.name }.orEmpty(),
            signature = readSignature(file),
            entries = readEntries(file),
            fromInstalledApp = fromInstalledApp,
            splitApks = splitApks,
        )
    }

    private fun readEntries(file: File): List<ZipEntryInfo> =
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { ZipEntryInfo(it.name, it.size, it.compressedSize) }
                .sortedBy { it.path }
                .toList()
        }

    private fun readSignature(file: File): SignatureInfo = try {
        val result = ApkVerifier.Builder(file).build().verify()
        val schemes = buildList {
            if (result.isVerifiedUsingV1Scheme) add("v1 (JAR)")
            if (result.isVerifiedUsingV2Scheme) add("v2")
            if (result.isVerifiedUsingV3Scheme) add("v3")
        }
        SignatureInfo(
            verified = result.isVerified,
            schemes = schemes,
            certificates = result.signerCertificates.map { it.toCertInfo() },
            problems = result.errors.map { it.toString() },
        )
    } catch (e: Exception) {
        SignatureInfo(false, emptyList(), emptyList(), listOf(e.message ?: e.javaClass.simpleName))
    }

    private fun X509Certificate.toCertInfo(): CertInfo {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return CertInfo(
            subject = subjectX500Principal.name,
            issuer = issuerX500Principal.name,
            sha256 = digest.joinToString(":") { "%02X".format(it) },
            validUntil = DateFormat.getDateInstance(DateFormat.MEDIUM).format(notAfter),
        )
    }
}
