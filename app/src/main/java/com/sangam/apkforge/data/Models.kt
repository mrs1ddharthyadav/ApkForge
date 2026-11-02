package com.sangam.apkforge.data

import java.io.File

/** An app installed on the phone, shown in the home list. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val isSystem: Boolean,
    val sourceDir: String,
    val splitCount: Int,
)

/** Everything we know about one APK after inspecting it. */
data class ApkDetails(
    val file: File,
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int?,
    val targetSdk: Int?,
    val sizeBytes: Long,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>,
    val signature: SignatureInfo,
    val entries: List<ZipEntryInfo>,
    /** True when this APK came from an installed app rather than a picked file. */
    val fromInstalledApp: Boolean,
    /** Extra split APK paths (Play Store "app bundles" install as several APKs). */
    val splitApks: List<String>,
)

data class SignatureInfo(
    val verified: Boolean,
    val schemes: List<String>,
    val certificates: List<CertInfo>,
    val problems: List<String>,
)

data class CertInfo(
    val subject: String,
    val issuer: String,
    val sha256: String,
    val validUntil: String,
)

data class ZipEntryInfo(
    val path: String,
    val size: Long,
    val compressedSize: Long,
)
