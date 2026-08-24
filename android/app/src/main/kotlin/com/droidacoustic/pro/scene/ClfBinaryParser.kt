package com.droidacoustic.pro.scene

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Lightweight binary CLF reader for CF1/CF2/GLL payloads.
 *
 * Current scope:
 * - Detect known binary container formats (CF1/CF2, EGLL)
 * - Extract printable metadata strings (manufacturer/model hints)
 * - Build simulation-ready [ClfData] using [ClfFactory] while true polar-grid
 *   decoding is implemented incrementally.
 */
object ClfBinaryParser {

    data class ParseResult(
        val data: ClfData,
        val usedExtractedMatrix: Boolean,
        val format: String
    )

    private val DEFAULT_FREQUENCIES = listOf(125, 250, 500, 1000, 2000, 4000, 8000)

    private enum class BinaryFormat {
        CF,
        GLL,
        DLL,
        OPAQUE,
        UNKNOWN
    }

    private data class GridHints(
        val azimuths: List<Float> = emptyList(),
        val elevations: List<Float> = emptyList(),
        val frequencies: List<Int> = emptyList(),
        val floatValues: List<Float> = emptyList()
    )

    fun parse(
        bytes: ByteArray,
        speakerId: String,
        manufacturerHint: String = "",
        modelHint: String = ""
    ): ClfData? {
        return parseDetailed(bytes, speakerId, manufacturerHint, modelHint)?.data
    }

    fun parseDetailed(
        bytes: ByteArray,
        speakerId: String,
        manufacturerHint: String = "",
        modelHint: String = ""
    ): ParseResult? {
        if (bytes.size < 8) return null

        val format = detectFormat(bytes)
        val tokens = extractAsciiTokens(bytes)

        if (format == BinaryFormat.UNKNOWN) {
            val hasHints = manufacturerHint.isNotBlank() || modelHint.isNotBlank()
            val hasUsefulTokens = tokens.size >= 6
            if (!hasHints && !hasUsefulTokens) return null
        }

        val manufacturer = chooseManufacturer(tokens, manufacturerHint)
        val model = chooseModel(tokens, modelHint, speakerId, format)
        val gridHints = extractGridHints(bytes, tokens)
        val frequencies = if (gridHints.frequencies.isNotEmpty()) {
            gridHints.frequencies
        } else {
            extractFrequencyCandidates(tokens)
        }
        val coverage = inferCoverageAngles(tokens, model)

        val isLineArray = isLikelyLineArray("$speakerId $model")
        val elementCount = when {
            isLineArray && model.contains("sub", ignoreCase = true) -> 6
            isLineArray -> 8
            else -> 1
        }

        val extractedPatterns = tryExtractPolarPatterns(
            floatValues = gridHints.floatValues,
            azimuths = gridHints.azimuths,
            elevations = gridHints.elevations,
            frequencies = frequencies
        )

        if (extractedPatterns.isNotEmpty()) {
            return ParseResult(
                data = ClfData(
                    speakerId = speakerId,
                    manufacturerName = manufacturer,
                    modelName = model,
                    patterns = extractedPatterns
                ),
                usedExtractedMatrix = true,
                format = format.name
            )
        }

        return ParseResult(
            data = buildInferredClfData(
                speakerId = speakerId,
                manufacturer = manufacturer,
                model = model,
                frequencies = frequencies,
                coverage = coverage,
                azimuthGrid = gridHints.azimuths,
                elevationGrid = gridHints.elevations,
                isLineArray = isLineArray,
                elementCount = elementCount
            ),
            usedExtractedMatrix = false,
            format = format.name
        )
    }

    private fun detectFormat(bytes: ByteArray): BinaryFormat {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0x41 && b1 == 0xBD) return BinaryFormat.CF

        if (bytes.size >= 4 &&
            bytes[0] == 'E'.code.toByte() &&
            bytes[1] == 'G'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'L'.code.toByte()
        ) {
            return BinaryFormat.GLL
        }

        // Windows DLL/EXE-style container (frequently seen in legacy EASE bundles).
        if (bytes.size >= 2 &&
            bytes[0] == 'M'.code.toByte() &&
            bytes[1] == 'Z'.code.toByte()
        ) {
            return BinaryFormat.DLL
        }

        // Opaque but non-empty binary payload; may still include useful metadata strings.
        return if (bytes.size >= 128) BinaryFormat.OPAQUE else BinaryFormat.UNKNOWN
    }

    private fun extractAsciiTokens(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()

        fun flush() {
            if (sb.length >= 4) {
                val token = sb.toString().trim()
                if (token.isNotBlank()) out += token
            }
            sb.setLength(0)
        }

        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v in 32..126) {
                sb.append(v.toChar())
                if (sb.length > 140) flush()
            } else {
                flush()
            }
        }
        flush()

        return out
            .asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 4..140 }
            .filterNot { it.startsWith("http", ignoreCase = true) }
            .filterNot { it.contains("JFIF") || it.contains("Exif") }
            .distinct()
            .take(400)
            .toList()
    }

    private fun chooseManufacturer(tokens: List<String>, hint: String): String {
        val explicit = hint.trim()
        if (explicit.isNotBlank()) return explicit

        val known = listOf(
            "JBL Professional" to "JBL",
            "JBL" to "JBL",
            "Electro-Voice" to "Electro-Voice",
            "EV" to "Electro-Voice",
            "QSC" to "QSC",
            "RCF" to "RCF",
            "d&b" to "d&b",
            "Martin Audio" to "Martin Audio",
            "EAW" to "EAW"
        )

        for ((needle, canonical) in known) {
            if (tokens.any { it.contains(needle, ignoreCase = true) }) {
                return canonical
            }
        }

        return tokens.firstOrNull { it.length in 3..32 } ?: "Unknown"
    }

    private fun chooseModel(
        tokens: List<String>,
        hint: String,
        speakerId: String,
        format: BinaryFormat
    ): String {
        val explicit = hint.trim()
        if (explicit.isNotBlank()) return explicit

        val candidates = tokens
            .asSequence()
            .filter { token ->
                val hasDigit = token.any { it.isDigit() }
                val hasLetter = token.any { it.isLetter() }
                hasDigit && hasLetter && token.length in 4..80
            }
            .filterNot { it.contains("copyright", ignoreCase = true) }
            .filterNot { it.contains("support", ignoreCase = true) }
            .filterNot { it.contains("manual", ignoreCase = true) }
            .filterNot { it.contains("www.", ignoreCase = true) }
            .toList()

        val ranked = candidates.sortedByDescending { scoreModelToken(it, speakerId) }
        if (ranked.isNotEmpty()) return ranked.first()

        val formatSuffix = when (format) {
            BinaryFormat.CF -> "CF"
            BinaryFormat.GLL -> "GLL"
            BinaryFormat.DLL -> "DLL"
            BinaryFormat.OPAQUE -> "BIN"
            BinaryFormat.UNKNOWN -> "BIN"
        }
        return "$speakerId ($formatSuffix)"
    }

    private fun scoreModelToken(token: String, speakerId: String): Int {
        val lower = token.lowercase()
        var score = 0
        if (lower.any { it.isDigit() }) score += 2
        if (lower.contains("jbl") || lower.contains("vtx") || lower.contains("srx") || lower.contains("prx")) score += 3
        if (Regex("\\b\\d{2,3}[xX]\\d{2,3}\\b").containsMatchIn(token)) score += 2
        if (speakerId.split('_').any { it.length >= 3 && lower.contains(it.lowercase()) }) score += 2
        if (lower.length in 6..48) score += 1
        return score
    }

    private fun extractFrequencyCandidates(tokens: List<String>): List<Int> {
        val found = mutableSetOf<Int>()
        val rx = Regex("\\b(\\d{2,5})\\s*(hz|khz)?\\b", RegexOption.IGNORE_CASE)

        for (token in tokens) {
            rx.findAll(token).forEach { m ->
                val raw = m.groupValues[1].toIntOrNull() ?: return@forEach
                val unit = m.groupValues[2].lowercase()
                val hz = when {
                    unit == "khz" -> raw * 1000
                    raw in 20..20000 -> raw
                    raw in 2..20 -> raw * 1000
                    else -> return@forEach
                }
                if (hz in 63..20000) found += hz
            }
        }

        val normalized = found
            .map { closestBand(it) }
            .distinct()
            .sorted()

        return if (normalized.size >= 4) normalized else DEFAULT_FREQUENCIES
    }

    private fun closestBand(value: Int): Int {
        val bands = listOf(63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000)
        return bands.minByOrNull { abs(it - value) } ?: value
    }

    private fun inferCoverageAngles(tokens: List<String>, model: String): Pair<Float, Float> {
        val searchable = (tokens + model).joinToString(" | ")
        val rx = Regex("\\b(\\d{2,3})\\s*([xX/×])\\s*(\\d{2,3})\\b")
        val match = rx.find(searchable)
        if (match != null) {
            val a = match.groupValues[1].toFloatOrNull()
            val b = match.groupValues[3].toFloatOrNull()
            if (a != null && b != null) {
                val h = a.coerceIn(20f, 180f)
                val v = b.coerceIn(20f, 180f)
                return h to v
            }
        }

        // Heuristics by known families when explicit coverage is absent.
        val lower = model.lowercase()
        return when {
            lower.contains("cbt") -> 120f to 40f
            lower.contains("vtx") -> 100f to 15f
            lower.contains("srx9") -> 90f to 15f
            lower.contains("prx") -> 90f to 60f
            lower.contains("sub") -> 180f to 180f
            else -> 100f to 70f
        }
    }

    private fun buildInferredClfData(
        speakerId: String,
        manufacturer: String,
        model: String,
        frequencies: List<Int>,
        coverage: Pair<Float, Float>,
        azimuthGrid: List<Float>,
        elevationGrid: List<Float>,
        isLineArray: Boolean,
        elementCount: Int
    ): ClfData {
        val (baseH, baseV) = coverage

        val azimuths = if (azimuthGrid.size >= 16) azimuthGrid else (0..360 step 5).map { it.toFloat() }
        val elevations = if (elevationGrid.size >= 11) elevationGrid else (-90..90 step 5).map { it.toFloat() }

        val patterns = frequencies.map { freq ->
            val freqNorm = (freq / 1000f).coerceIn(0.063f, 16f)
            val narrowing = (1f + 0.22f * ln(freqNorm + 1f)).coerceIn(0.8f, 1.9f)

            val horizBw = (baseH / narrowing).coerceIn(20f, 180f)
            val lineArrayVerticalFactor = if (isLineArray) (1f + elementCount * 0.07f).coerceAtMost(2.2f) else 1f
            val vertBw = (baseV / (narrowing * lineArrayVerticalFactor)).coerceIn(10f, 180f)

            val spl = Array(azimuths.size) { azIdx ->
                FloatArray(elevations.size) { elIdx ->
                    val az = normalize180(azimuths[azIdx])
                    val el = elevations[elIdx]

                    val hPenalty = beamPenalty(az, horizBw)
                    val vPenalty = beamPenalty(el, vertBw)

                    val rearPenalty = if (abs(az) > 120f) {
                        val rearT = ((abs(az) - 120f) / 60f).coerceIn(0f, 1f)
                        -6f - rearT * 8f
                    } else 0f

                    val hfEdgePenalty = if (freq >= 4000) {
                        val edge = (abs(az) / 180f).pow(2f)
                        -3f * edge
                    } else 0f

                    val onAxisBias = if (abs(az) <= 7.5f && abs(el) <= 5f) 1.5f else 0f

                    (hPenalty + vPenalty + rearPenalty + hfEdgePenalty + onAxisBias)
                        .coerceIn(-36f, 0f)
                }
            }

            ClfPolarPattern(
                frequencyHz = freq,
                azimuths = azimuths,
                elevations = elevations,
                spl = spl
            )
        }

        return ClfData(
            speakerId = speakerId,
            manufacturerName = manufacturer,
            modelName = model,
            patterns = patterns
        )
    }

    private fun beamPenalty(angleDeg: Float, beamwidthDeg: Float): Float {
        // Treat beamwidth as approx -6 dB coverage; map with smooth Gaussian-like rolloff.
        val sigma = (beamwidthDeg / 2f / 1.177f).coerceAtLeast(2f)
        val x = angleDeg / sigma
        return (-6f * (x * x).toFloat() / 2f).coerceAtLeast(-30f)
    }

    private fun normalize180(angleDeg: Float): Float {
        val a = ((angleDeg % 360f) + 360f) % 360f
        return if (a > 180f) a - 360f else a
    }

    private fun isLikelyLineArray(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("line") ||
            t.contains("array") ||
            t.contains(" vtx ") ||
            t.contains(" srx9") ||
            t.contains("cbt") ||
            t.contains("kara") ||
            t.contains("hdl") ||
            t.contains("w8") ||
            t.contains("la")
    }

    private fun extractGridHints(bytes: ByteArray, tokens: List<String>): GridHints {
        val values = readLittleEndianFloatCandidates(bytes)
        if (values.isEmpty()) return GridHints()

        val azimuths = detectRegularGrid(
            values = values,
            minValue = 0,
            maxValue = 360,
            minStep = 1,
            maxStep = 15,
            minPoints = 18,
            requireZero = true,
            preferWideSpan = true
        )

        val elevations = detectRegularGrid(
            values = values,
            minValue = -90,
            maxValue = 90,
            minStep = 1,
            maxStep = 15,
            minPoints = 11,
            requireZero = true,
            preferWideSpan = false
        )

        val freqFromFloats = values
            .asSequence()
            .map { it.roundToInt() }
            .filter { it in 63..20000 }
            .map { closestBand(it) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= 2 }
            .keys
            .sorted()

        val freqFromTokens = extractFrequencyCandidates(tokens)
        val frequencies = (freqFromFloats + freqFromTokens)
            .distinct()
            .sorted()
            .let { if (it.size >= 4) it else DEFAULT_FREQUENCIES }

        return GridHints(
            azimuths = azimuths,
            elevations = elevations,
            frequencies = frequencies,
            floatValues = values
        )
    }

    private fun readLittleEndianFloatCandidates(bytes: ByteArray): List<Float> {
        val out = ArrayList<Float>(4096)
        val limit = minOf(bytes.size - 4, 2_000_000)
        var i = 0
        while (i <= limit) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val bits = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            val f = Float.fromBits(bits)
            if (f.isFinite() && abs(f) <= 40000f) {
                out += f
            }
            i += 4
        }
        return out
    }

    private fun detectRegularGrid(
        values: List<Float>,
        minValue: Int,
        maxValue: Int,
        minStep: Int,
        maxStep: Int,
        minPoints: Int,
        requireZero: Boolean,
        preferWideSpan: Boolean
    ): List<Float> {
        val ints = values
            .asSequence()
            .mapNotNull { v ->
                if (v < minValue - 0.25f || v > maxValue + 0.25f) return@mapNotNull null
                val r = v.roundToInt()
                if (abs(v - r) <= 0.06f) r else null
            }
            .distinct()
            .sorted()
            .toList()

        if (ints.size < minPoints) return emptyList()
        val set = ints.toHashSet()

        var best: List<Int> = emptyList()
        for (step in minStep..maxStep) {
            for (start in ints) {
                if (!set.contains(start - step)) {
                    val seq = mutableListOf<Int>()
                    var v = start
                    while (set.contains(v)) {
                        seq += v
                        v += step
                    }
                    if (seq.size >= minPoints) {
                        val better = when {
                            seq.size > best.size -> true
                            seq.size < best.size -> false
                            preferWideSpan -> (seq.last() - seq.first()) > (best.lastOrNull() ?: 0) - (best.firstOrNull() ?: 0)
                            else -> false
                        }
                        if (better) best = seq
                    }
                }
            }
        }

        if (best.isEmpty()) return emptyList()
        if (requireZero && 0 !in best) return emptyList()

        return best.map { it.toFloat() }
    }

    private fun tryExtractPolarPatterns(
        floatValues: List<Float>,
        azimuths: List<Float>,
        elevations: List<Float>,
        frequencies: List<Int>
    ): List<ClfPolarPattern> {
        if (azimuths.size < 16 || elevations.size < 11) return emptyList()
        if (frequencies.size < 4) return emptyList()

        val cellsPerPattern = azimuths.size * elevations.size
        if (cellsPerPattern <= 0 || floatValues.size < cellsPerPattern) return emptyList()

        val patterns = mutableListOf<ClfPolarPattern>()
        var cursor = 0

        for (freq in frequencies) {
            val idx = findNextPatternOffset(floatValues, start = cursor, windowSize = cellsPerPattern)
            if (idx < 0) break

            val spl = Array(azimuths.size) { azIdx ->
                FloatArray(elevations.size) { elIdx ->
                    val flat = idx + azIdx * elevations.size + elIdx
                    floatValues[flat].coerceIn(-80f, 12f)
                }
            }

            patterns += ClfPolarPattern(
                frequencyHz = freq,
                azimuths = azimuths,
                elevations = elevations,
                spl = spl
            )
            cursor = idx + cellsPerPattern
        }

        // Require enough evidence before accepting extracted matrix data.
        val minRequired = minOf(4, frequencies.size)
        return if (patterns.size >= minRequired) patterns else emptyList()
    }

    private fun findNextPatternOffset(
        values: List<Float>,
        start: Int,
        windowSize: Int
    ): Int {
        if (windowSize <= 0 || values.size - start < windowSize) return -1

        val maxStart = values.size - windowSize
        var i = start.coerceAtLeast(0)
        while (i <= maxStart) {
            var inRange = 0
            var finite = 0
            var minVal = Float.POSITIVE_INFINITY
            var maxVal = Float.NEGATIVE_INFINITY

            var j = 0
            while (j < windowSize) {
                val v = values[i + j]
                if (v.isFinite()) {
                    finite += 1
                    if (v in -80f..12f) inRange += 1
                    if (v < minVal) minVal = v
                    if (v > maxVal) maxVal = v
                }
                j += 1
            }

            val finiteRatio = finite.toFloat() / windowSize.toFloat()
            val inRangeRatio = if (finite == 0) 0f else inRange.toFloat() / finite.toFloat()
            val dynamicRange = if (finite == 0) 0f else (maxVal - minVal)

            if (finiteRatio >= 0.98f && inRangeRatio >= 0.90f && dynamicRange >= 4f) {
                return i
            }

            // Skip quickly through impossible regions when mostly out of range.
            i += if (inRangeRatio < 0.2f) 64 else 8
        }

        return -1
    }
}
