package com.droidacoustic.pro.scene

import org.json.JSONArray
import org.json.JSONObject

/**
 * CLF (Common Loudspeaker Format) polar pattern data.
 * Represents frequency-dependent directivity.
 */
data class ClfPolarPattern(
    val frequencyHz: Int,                    // Center frequency (e.g., 1000)
    val azimuths: List<Float>,               // Azimuth angles in degrees (typically 0–360 or -180 to +180)
    val elevations: List<Float>,             // Elevation angles in degrees (typically -90 to +90)
    val spl: Array<FloatArray>               // spl[azimuth_idx][elevation_idx]: dB relative to on-axis
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClfPolarPattern) return false
        if (frequencyHz != other.frequencyHz) return false
        if (azimuths != other.azimuths) return false
        if (elevations != other.elevations) return false
        if (!spl.contentDeepEquals(other.spl)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = frequencyHz
        result = 31 * result + azimuths.hashCode()
        result = 31 * result + elevations.hashCode()
        result = 31 * result + spl.contentDeepHashCode()
        return result
    }
}

data class ClfData(
    val speakerId: String,                   // Identifier (e.g., "jbl_cbt_50la_1")
    val manufacturerName: String = "",
    val modelName: String = "",
    val patterns: List<ClfPolarPattern> = emptyList()  // Indexed by frequency
) {
    fun patternAt(frequencyHz: Int): ClfPolarPattern? {
        // Find closest frequency match
        return patterns.minByOrNull { kotlin.math.abs(it.frequencyHz - frequencyHz) }
            ?.takeIf { kotlin.math.abs(it.frequencyHz - frequencyHz) <= 200 }  // Within ±200Hz
    }

    fun splAtDirection(frequencyHz: Int, azimuthDeg: Float, elevationDeg: Float): Float? {
        val pattern = patternAt(frequencyHz) ?: return null
        return interpolatePolar(pattern, azimuthDeg, elevationDeg)
    }

    private fun interpolatePolar(
        pattern: ClfPolarPattern,
        azimuthDeg: Float,
        elevationDeg: Float
    ): Float {
        // Normalize angles
        val az = ((azimuthDeg % 360f) + 360f) % 360f
        val el = elevationDeg.coerceIn(-90f, 90f)

        // Find bracketing indices
        val azIdx = pattern.azimuths.binarySearch(az)
        val elIdx = pattern.elevations.binarySearch(el)

        // If exact match, return directly
        if (azIdx >= 0 && elIdx >= 0) {
            return pattern.spl[azIdx][elIdx]
        }

        // Bilinear interpolation
        val azIdxLo = if (azIdx >= 0) azIdx else -(azIdx + 1) - 1
        val azIdxHi = azIdxLo + 1
        val elIdxLo = if (elIdx >= 0) elIdx else -(elIdx + 1) - 1
        val elIdxHi = elIdxLo + 1

        val azLoSafe = azIdxLo.coerceIn(0, pattern.azimuths.size - 1)
        val azHiSafe = azIdxHi.coerceIn(0, pattern.azimuths.size - 1)
        val elLoSafe = elIdxLo.coerceIn(0, pattern.elevations.size - 1)
        val elHiSafe = elIdxHi.coerceIn(0, pattern.elevations.size - 1)

        val azLo = pattern.azimuths[azLoSafe]
        val azHi = pattern.azimuths[azHiSafe]
        val elLo = pattern.elevations[elLoSafe]
        val elHi = pattern.elevations[elHiSafe]

        val azSpan = (azHi - azLo).coerceAtLeast(0.01f)
        val elSpan = (elHi - elLo).coerceAtLeast(0.01f)

        val azT = ((az - azLo) / azSpan).coerceIn(0f, 1f)
        val elT = ((el - elLo) / elSpan).coerceIn(0f, 1f)

        val v00 = pattern.spl[azLoSafe][elLoSafe]
        val v10 = pattern.spl[azHiSafe][elLoSafe]
        val v01 = pattern.spl[azLoSafe][elHiSafe]
        val v11 = pattern.spl[azHiSafe][elHiSafe]

        val v0 = v00 * (1f - azT) + v10 * azT
        val v1 = v01 * (1f - azT) + v11 * azT
        return v0 * (1f - elT) + v1 * elT
    }
}

/**
 * Parses CLF data from JSON format.
 * Expected structure:
 * {
 *   "speakerId": "jbl_cbt_50la_1",
 *   "manufacturer": "JBL",
 *   "model": "CBT 50LA-1",
 *   "patterns": [
 *     {
 *       "frequency": 1000,
 *       "azimuths": [0, 5, 10, ..., 355],
 *       "elevations": [-90, -45, 0, 45, 90],
 *       "spl": [[...], [...], ...]
 *     },
 *     ...
 *   ]
 * }
 */
object ClfParser {
    fun parseJson(jsonString: String): ClfData? {
        return try {
            val root = JSONObject(jsonString)
            val speakerId = root.optString("speakerId", "unknown")
            val mfg = root.optString("manufacturer", "")
            val mdl = root.optString("model", "")

            val patterns = mutableListOf<ClfPolarPattern>()
            val patternsArray = root.optJSONArray("patterns") ?: JSONArray()
            for (i in 0 until patternsArray.length()) {
                val patObj = patternsArray.getJSONObject(i)
                val freq = patObj.optInt("frequency", 1000)
                val aziArray = patObj.optJSONArray("azimuths") ?: JSONArray()
                val eleArray = patObj.optJSONArray("elevations") ?: JSONArray()
                val splArray = patObj.optJSONArray("spl") ?: JSONArray()

                val azimuths = (0 until aziArray.length()).map { aziArray.getDouble(it).toFloat() }
                val elevations = (0 until eleArray.length()).map { eleArray.getDouble(it).toFloat() }

                val spl = Array(splArray.length()) { azIdx ->
                    val row = splArray.getJSONArray(azIdx)
                    FloatArray(row.length()) { elIdx -> row.getDouble(elIdx).toFloat() }
                }

                patterns.add(ClfPolarPattern(freq, azimuths, elevations, spl))
            }

            ClfData(speakerId, mfg, mdl, patterns)
        } catch (e: Exception) {
            null
        }
    }

    fun toJson(data: ClfData): String {
        val root = JSONObject()
        root.put("speakerId", data.speakerId)
        root.put("manufacturer", data.manufacturerName)
        root.put("model", data.modelName)

        val patternsArray = JSONArray()
        data.patterns.forEach { pat ->
            val patObj = JSONObject()
            patObj.put("frequency", pat.frequencyHz)
            patObj.put("azimuths", JSONArray(pat.azimuths.map { it.toDouble() }))
            patObj.put("elevations", JSONArray(pat.elevations.map { it.toDouble() }))
            val splArray = JSONArray()
            pat.spl.forEach { row ->
                splArray.put(JSONArray(row.map { it.toDouble() }))
            }
            patObj.put("spl", splArray)
            patternsArray.put(patObj)
        }
        root.put("patterns", patternsArray)
        return root.toString()
    }
}
