package com.sangam.apkforge.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads the list of installed apps and their icons. */
class AppRepository(context: Context) {

    val packageManager: PackageManager = context.packageManager
    private val pm get() = packageManager

    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        installedPackages().mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val source = ai.sourceDir ?: return@mapNotNull null
            InstalledApp(
                packageName = pi.packageName,
                label = ai.loadLabel(pm).toString(),
                versionName = pi.versionName,
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                sourceDir = source,
                splitCount = ai.splitSourceDirs?.size ?: 0,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Icon of an installed app. */
    fun iconFor(packageName: String): Drawable? = try {
        pm.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Icon read directly from an APK file that may not be installed. */
    fun iconForApkFile(file: File): Drawable? {
        val pi = archiveInfo(file, 0) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = file.absolutePath
        ai.publicSourceDir = file.absolutePath
        return try {
            ai.loadIcon(pm)
        } catch (e: Exception) {
            null
        }
    }

    fun splitPathsFor(packageName: String): List<String> = try {
        val ai = pm.getApplicationInfo(packageName, 0)
        ai.splitSourceDirs?.toList().orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

    private fun installedPackages(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

    fun archiveInfo(file: File, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        }
}
