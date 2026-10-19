package com.aipaca.android.engine.profiles

import com.aipaca.android.data.device.DeviceDescriptor
import com.aipaca.android.data.device.normalizeSoc
import com.aipaca.android.data.device.vendorKey
import com.aipaca.android.engine.Accelerator

data class MatchResult(val profile: LoadedProfile?, val reason: String)

class ProfileMatcher(private val catalog: ProfileCatalog) {

    private val deviceByProfileId: Map<String, String> =
        catalog.index.profiles.associate { it.id to it.device }

    fun match(
        device: DeviceDescriptor,
        modelFileName: String,
        repoId: String?,
        accelerator: Accelerator,
    ): MatchResult {
        val haystack = "${repoId ?: ""}/$modelFileName".lowercase()
        val quant = extractQuant(modelFileName)
        val candidates = catalog.profiles
            .filter { isCandidate(it, haystack) }
            .filter { isAllowed(it, accelerator) }
            .sortedBy { backendRank(it, accelerator) }

        candidates.firstOrNull { deviceRank(it, device) == RANK_EXACT && quantMatches(it, quant) }
            ?.let { return MatchResult(it, "exact-device+quant") }
        candidates.firstOrNull { deviceRank(it, device) == RANK_SAME_SOC && quantMatches(it, quant) }
            ?.let { return MatchResult(it, "same-soc+quant") }
        candidates.firstOrNull { deviceRank(it, device) == RANK_GENERIC && quantMatches(it, quant) }
            ?.let { return MatchResult(it, "generic-device+quant") }
        candidates.firstOrNull { deviceRank(it, device) == RANK_EXACT }
            ?.let { return MatchResult(it, "exact-device") }
        candidates.firstOrNull { deviceRank(it, device) == RANK_SAME_SOC }
            ?.let { return MatchResult(it, "same-soc") }
        candidates.firstOrNull { deviceRank(it, device) == RANK_GENERIC }
            ?.let { return MatchResult(it, "generic-device") }
        catalog.profiles.firstOrNull { isDefault(it) && isAllowed(it, accelerator) }
            ?.let { return MatchResult(it, "default") }
        return MatchResult(null, "no-match")
    }

    fun extractQuant(fileName: String): String? {
        val haystack = fileName.lowercase()
        return QUANTS.filter { haystack.contains(it.lowercase()) }.maxByOrNull { it.length }
    }

    private fun isCandidate(profile: LoadedProfile, haystack: String): Boolean =
        profile.doc.model.matchPatterns.any {
            Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(haystack)
        }

    private fun isAllowed(profile: LoadedProfile, accelerator: Accelerator): Boolean =
        accelerator == Accelerator.NPU || !ProfilePolicy.isHexagon(profile.doc)

    private fun backendRank(profile: LoadedProfile, accelerator: Accelerator): Int = when {
        profile.doc.backend.equals(accelerator.profileBackend, ignoreCase = true) -> 0
        profile.doc.backend.equals(BACKEND_CPU, ignoreCase = true) -> 1
        else -> 2
    }

    private fun deviceRank(profile: LoadedProfile, device: DeviceDescriptor): Int? {
        if (isDefault(profile)) return RANK_DEFAULT
        if (isGenericDevice(profile)) return RANK_GENERIC
        if (deviceKeyOf(profile) == device.deviceKey) return RANK_EXACT
        if (isSameSoc(profile, device)) return RANK_SAME_SOC
        return null
    }

    private fun isSameSoc(profile: LoadedProfile, device: DeviceDescriptor): Boolean {
        val spec = catalog.devices[deviceKeyOf(profile) ?: return false] ?: return false
        return normalizeSoc(spec.soc) == device.soc && vendorKey(spec.vendor) == device.vendor
    }

    private fun isGenericDevice(profile: LoadedProfile): Boolean =
        deviceKeyOf(profile)?.startsWith("generic") == true

    private fun deviceKeyOf(profile: LoadedProfile): String? = deviceByProfileId[profile.doc.id]

    private fun isDefault(profile: LoadedProfile): Boolean =
        profile.doc.model.family == "default"

    private fun quantMatches(profile: LoadedProfile, quant: String?): Boolean =
        quant != null && profile.doc.model.quants.any { it.equals(quant, ignoreCase = true) }

    private companion object {
        const val RANK_EXACT = 0
        const val RANK_SAME_SOC = 1
        const val RANK_GENERIC = 2
        const val RANK_DEFAULT = 3
        const val BACKEND_CPU = "cpu"

        val QUANTS = listOf(
            "Q4_0_8_8", "Q4_0_4_4", "Q4_K_XL", "Q4_K_M", "Q4_K_S",
            "Q5_K_M", "Q5_K_S", "Q6_K", "Q3_K_L", "Q3_K_M", "Q3_K_S",
            "Q8_0", "Q5_1", "Q5_0", "Q4_1", "Q4_0", "Q2_K_XL", "Q2_K",
            "IQ4_XS", "IQ4_NL", "IQ3_XS", "IQ3_M", "IQ2_M", "IQ2_XS",
            "BF16", "F16", "F32",
        )
    }
}
