package com.sangam.apkforge.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** File helpers. All paths live under cache/work so FileProvider can share them. */
object FileOps {

    /** Files that contain several APKs (split APK bundles from SAI, APKPure, APKMirror...). */
    val BUNDLE_EXTENSIONS = listOf("apks", "xapk", "apkm")

    /**
     * True if [file] is a bundle of APKs rather than a single APK. Checks the extension first,
     * then looks inside: a real APK has AndroidManifest.xml at its root, a bundle has .apk files.
     */
    fun isBundle(file: File): Boolean {
        if (BUNDLE_EXTENSIONS.any { file.name.endsWith(".$it", true) }) return true
        return try {
            ZipFile(file).use { zip ->
                zip.getEntry("AndroidManifest.xml") == null &&
                    zip.entries().asSequence().any { it.name.endsWith(".apk", true) }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun workDir(context: Context, sub: String): File =
        File(context.cacheDir, "work/$sub").apply { mkdirs() }

    /** Copies a picked file (content:// Uri) into the app's cache so we can read it as a File. */
    fun copyUriToCache(context: Context, uri: Uri): File {
        val name = displayName(context, uri)?.let(::safeName) ?: "picked.apk"
        val dir = workDir(context, "opened")
        dir.listFiles()?.forEach { it.delete() }
        val keepName = name.endsWith(".apk", true) || BUNDLE_EXTENSIONS.any { name.endsWith(".$it", true) }
        val target = File(dir, if (keepName) name else "$name.apk")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file.")
        input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
        return target
    }

    /** Copies a file to a location the user picked with the system "Save as" screen. */
    fun copyFileToUri(context: Context, file: File, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not write to the chosen location.")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Copies one file from inside an APK to a user-chosen location. */
    fun copyZipEntryToUri(context: Context, apk: File, entryPath: String, uri: Uri) {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryPath) ?: throw IOException("$entryPath was not found in the APK.")
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not write to the chosen location.")
            out.use { o -> zip.getInputStream(entry).use { it.copyTo(o) } }
        }
    }

    /** Copies a file into cache/work/share so it can be handed to other apps. */
    fun stageForSharing(context: Context, file: File, name: String): File {
        val dir = workDir(context, "share")
        val target = File(dir, safeName(name))
        if (file.absolutePath != target.absolutePath) file.copyTo(target, overwrite = true)
        return target
    }

    /**
     * Opens Android's installer for an APK. Returns false if the user first has to allow
     * "Install unknown apps" for APK Forge (we open that settings page for them).
     */
    fun openInstaller(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IOException("No installer app was found on this phone.")
        }
        return true
    }

    fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "file" }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
