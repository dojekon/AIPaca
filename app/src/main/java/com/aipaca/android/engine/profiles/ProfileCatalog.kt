package com.aipaca.android.engine.profiles

import android.content.Context
import android.content.res.AssetManager
import java.io.IOException

data class LoadedProfile(val doc: ProfileDoc, val sourcePath: String)

class ProfileCatalog(
    val index: ProfileIndexDoc,
    val devices: Map<String, DeviceSpecDoc>,
    val profiles: List<LoadedProfile>,
)

object ProfileCatalogLoader {

    fun fromAssets(context: Context, assetDir: String = "profiles"): ProfileCatalog {
        val assets = context.assets
        val indexText = readAsset(assets, "$assetDir/index.json")
        return fromJson(indexText) { path -> readAssetOrNull(assets, "$assetDir/$path") }
    }

    fun fromJson(indexText: String, resolver: (String) -> String?): ProfileCatalog {
        val index = ProfileParser.parseIndex(indexText)
        val devices = index.devices.associate { entry ->
            entry.key to ProfileParser.parseDevice(resolveFile(entry.file, resolver))
        }
        val profiles = index.profiles.map { entry ->
            LoadedProfile(ProfileParser.parseProfile(resolveFile(entry.file, resolver)), entry.file)
        }
        return ProfileCatalog(index, devices, profiles)
    }

    private fun resolveFile(path: String, resolver: (String) -> String?): String =
        resolver(path) ?: throw IllegalArgumentException("Profile catalog references missing file: $path")

    private fun readAsset(assets: AssetManager, path: String): String =
        readAssetOrNull(assets, path) ?: throw IllegalArgumentException("Missing asset: $path")

    private fun readAssetOrNull(assets: AssetManager, path: String): String? =
        try {
            assets.open(path).use { it.readBytes().decodeToString() }
        } catch (_: IOException) {
            null
        }
}
