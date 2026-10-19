package com.aipaca.android.engine.profiles

import com.aipaca.android.engine.Accelerator

object ProfilePolicy {

    private const val BACKEND_CPU = "cpu"
    private const val BACKEND_HEXAGON = "hexagon"
    private const val DEFAULT_GENERIC_SUFFIX = "default-generic.json"

    fun isHexagon(doc: ProfileDoc): Boolean = doc.backend.equals(BACKEND_HEXAGON, ignoreCase = true)

    fun filterForSettings(profiles: List<LoadedProfile>, accelerator: Accelerator): List<LoadedProfile> =
        if (accelerator == Accelerator.NPU) profiles else profiles.filterNot { isHexagon(it.doc) }

    fun cpuFallback(profiles: List<LoadedProfile>, failed: ProfileDoc): LoadedProfile? {
        val cpuProfiles = profiles.filter { it.doc.backend.equals(BACKEND_CPU, ignoreCase = true) }
        cpuProfiles.firstOrNull { overlaps(it.doc, failed) }?.let { return it }
        return cpuProfiles.firstOrNull { it.sourcePath.endsWith(DEFAULT_GENERIC_SUFFIX) }
    }

    private fun overlaps(candidate: ProfileDoc, failed: ProfileDoc): Boolean {
        val patternsOverlap = candidate.model.matchPatterns.any { it in failed.model.matchPatterns }
        val quantsOverlap = candidate.model.quants.any { candidateQuant ->
            failed.model.quants.any { it.equals(candidateQuant, ignoreCase = true) }
        }
        return patternsOverlap || quantsOverlap
    }
}
