package mextensionserver.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.pm.PackageInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import mextensionserver.util.Extension
import mextensionserver.util.PackageTools.dex2jar
import mextensionserver.util.PackageTools.getPackageInfo
import mextensionserver.util.PackageTools.loadExtensionSources
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

object MExtensionServerLoader {
    private val logger = KotlinLogging.logger {}
    private val tempDir = Files.createTempDirectory("mextensionserver").toFile()

    private const val MANGA_PACKAGE = "tachiyomi.extension"
    private const val ANIME_PACKAGE = "tachiyomi.animeextension"
    private const val METADATA_SOURCE_CLASS_SUFFIX = ".class"

    init {
        // Clean up temp directory on shutdown
        try {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    tempDir.deleteRecursively()
                },
            )
        } catch (e: IllegalStateException) {
            // Shutdown already in progress, ignore
        }
    }

    data class LoadedExtension(
        val source: Any,
        val packageInfo: PackageInfo,
        val jarFile: File,
    )

    fun loadExtensionFromBase64(
        base64Data: String,
        baseUrl: String? = null,
        lang: String? = null,
    ): LoadedExtension {
        val apkData = Base64.getDecoder().decode(base64Data)
        val tempApkFile = File(tempDir, "extension-${UUID.randomUUID()}.apk")
        val apkName = tempApkFile.name
        try {
            // Write APK data to temp file
            tempApkFile.writeBytes(apkData)

            // Get package info
            val packageInfo = getPackageInfo(tempApkFile.absolutePath)

            // Extract class name
            val metaData = packageInfo.applicationInfo.metaData
            var classNameSuffix = metaData.getString(MANGA_PACKAGE + METADATA_SOURCE_CLASS_SUFFIX)
            if (classNameSuffix == null) {
                classNameSuffix = metaData.getString(ANIME_PACKAGE + METADATA_SOURCE_CLASS_SUFFIX)
            }
            if (classNameSuffix == null) {
                throw IllegalArgumentException("No source class found in extension metadata")
            }
            val className =
                if (classNameSuffix.startsWith(".")) {
                    packageInfo.packageName + classNameSuffix
                } else {
                    classNameSuffix
                }
            logger.debug { "Main class for extension is $className" }

            // Convert to JAR
            val jarFile = File(tempDir, "extension-${UUID.randomUUID()}.jar")
            val dexFile = File(tempApkFile.absolutePath)

            dex2jar(dexFile, jarFile)

            // Extract assets and resources from APK
            Extension.extractAssetsFromApk(tempApkFile, jarFile)

            // Load extension sources
            val extensionMainClassInstance = loadExtensionSources(jarFile, className, tempApkFile)
            val sources: List<Any> =
                when (extensionMainClassInstance) {
                    is eu.kanade.tachiyomi.source.Source -> listOf(extensionMainClassInstance)
                    is eu.kanade.tachiyomi.source.SourceFactory -> extensionMainClassInstance.createSources()
                    is eu.kanade.tachiyomi.animesource.AnimeSource -> listOf(extensionMainClassInstance)
                    is eu.kanade.tachiyomi.animesource.AnimeSourceFactory -> extensionMainClassInstance.createSources()
                    else -> throw RuntimeException("Unknown source class type! ${extensionMainClassInstance.javaClass}")
                }

            val selectedSource =
                selectSource(sources, baseUrl, lang)
                    ?: throw IllegalArgumentException("No matching source found in extension")

            return LoadedExtension(selectedSource, packageInfo, jarFile)
        } catch (e: Exception) {
            logger.error(e) { "Failed to load extension from base64 data" }
            throw e
        } finally {
            // Clean up APK file
            if (tempApkFile.exists()) {
                tempApkFile.delete()
            }
        }
    }

    private fun selectSource(
        sources: List<Any>,
        baseUrl: String?,
        lang: String?,
    ): Any? {
        if (sources.isEmpty()) return null
        if (!baseUrl.isNullOrBlank()) {
            sources.firstOrNull { source ->
                try {
                    val url = source.javaClass.getMethod("getBaseUrl").invoke(source) as String
                    url == baseUrl || url.trimEnd('/') == baseUrl.trimEnd('/')
                } catch (_: Exception) {
                    false
                }
            }?.let { return it }
        }
        if (!lang.isNullOrBlank()) {
            sources.firstOrNull { source ->
                try {
                    val sourceLang = source.javaClass.getMethod("getLang").invoke(source) as String
                    sourceLang.equals(lang, ignoreCase = true)
                } catch (_: Exception) {
                    false
                }
            }?.let { return it }
        }
        return sources.firstOrNull()
    }

    fun cleanupTempFiles() {
        try {
            tempDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to cleanup temp files" }
        }
    }
}
