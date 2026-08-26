package com.droidacoustic.pro.scene

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Reader for the CLF TAB text format.
 *
 * This is the *open* half of the Common Loudspeaker Format. The CLF Group
 * publishes the TAB definition; CF1/CF2 are closed binary distribution formats
 * built from it. Everything here is written against the published text format
 * and against files a user supplies, so there is no reverse engineering in this
 * path and nothing to license.
 *
 * ## Coordinate system
 *
 * A CLF balloon is NOT stored as azimuth/elevation. It is axis-relative
 * spherical, centred on the loudspeaker's own main axis, as declared by the
 * file's `<CABINET-SYSTEM> <on-axis> <+x> <up> <+z>` line:
 *
 * - **theta** - polar angle away from the on-axis direction, 0 to 180 degrees.
 *   0 is straight ahead, 180 is directly behind. Stored along each arc.
 * - **phi** - rotation about the on-axis direction, 0 to 355 degrees, measured
 *   from "up". 0 and 180 are the vertical plane, 90 and 270 the horizontal.
 *   One arc per phi.
 *
 * That layout means the first and last sample of every arc are the same two
 * points in space - dead ahead and dead behind - so they must be identical
 * across all arcs. [balloonIntegrityError] checks exactly that, and it is worth
 * keeping: it is the cheapest way to catch a misparsed grid.
 *
 * | | CLF2 | CLF1 |
 * |---|---|---|
 * | Resolution | 5 deg | 10 deg |
 * | Arcs (phi) | 72 | 36 |
 * | Samples per arc (theta) | 37 | 19 |
 * | Bands | 1/3 octave | 1/1 octave |
 */
object ClfTabParser {

    /** Third-octave centres a CLF2 file can carry, in file order. */
    val THIRD_OCTAVE_HZ = listOf(
        25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
        800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000,
        10000, 12500, 16000, 20000
    )

    /** Octave centres a CLF1 file can carry, in file order. */
    val OCTAVE_HZ = listOf(32, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /**
     * One frequency band of a balloon, on the file's native axis-relative grid.
     *
     * [attenuationDb] is indexed `[phiIndex][thetaIndex]`, in dB relative to the
     * on-axis direction. Values are almost all negative; a small positive
     * excursion is normal where an off-axis lobe exceeds the reference axis.
     */
    data class Band(
        val frequencyHz: Int,
        val attenuationDb: Array<FloatArray>,
        val resolutionDeg: Int
    ) {
        val arcCount: Int get() = attenuationDb.size
        val samplesPerArc: Int get() = attenuationDb.firstOrNull()?.size ?: 0

        /** Attenuation at an arbitrary direction, bilinear on the native grid. */
        fun at(thetaDeg: Float, phiDeg: Float): Float {
            if (arcCount == 0 || samplesPerArc == 0) return 0f
            val theta = thetaDeg.coerceIn(0f, 180f) / resolutionDeg
            val phi = (((phiDeg % 360f) + 360f) % 360f) / resolutionDeg

            val t0 = theta.toInt().coerceIn(0, samplesPerArc - 1)
            val t1 = (t0 + 1).coerceAtMost(samplesPerArc - 1)
            val tf = theta - t0

            val p0 = phi.toInt() % arcCount
            val p1 = (p0 + 1) % arcCount          // phi wraps: it is a full circle
            val pf = phi - phi.toInt()

            val a = attenuationDb[p0][t0] * (1f - tf) + attenuationDb[p0][t1] * tf
            val b = attenuationDb[p1][t0] * (1f - tf) + attenuationDb[p1][t1] * tf
            return a * (1f - pf) + b * pf
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Band) return false
            return frequencyHz == other.frequencyHz &&
                resolutionDeg == other.resolutionDeg &&
                attenuationDb.contentDeepEquals(other.attenuationDb)
        }

        override fun hashCode(): Int =
            31 * (31 * frequencyHz + resolutionDeg) + attenuationDb.contentDeepHashCode()
    }

    /** A parsed TAB file. */
    data class Speaker(
        val speakerId: String,
        val manufacturer: String,
        val model: String,
        val description: String,
        val resolutionDeg: Int,
        val sensitivityDb: Float?,
        val maxInputW: Float?,
        val bands: List<Band>,
        val tags: Map<String, List<String>>
    ) {
        fun bandNearest(frequencyHz: Int): Band? =
            bands.minByOrNull { abs(it.frequencyHz - frequencyHz) }
    }

    class ParseException(message: String) : IllegalArgumentException(message)

    /**
     * Parse a CLF TAB document.
     *
     * @throws ParseException with a specific reason. Callers that want a
     *   boolean should use [parseOrNull]; the message is worth surfacing to the
     *   user, because "this is a CF2, not a TAB" is a common and fixable mistake.
     */
    fun parse(text: String, speakerId: String = ""): Speaker {
        val tags = LinkedHashMap<String, List<String>>()
        val bandBlocks = LinkedHashMap<Int, MutableList<FloatArray>>()
        var currentBand: MutableList<FloatArray>? = null
        var format: String? = null

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r', '\n')
            if (line.isBlank()) return@forEach
            val trimmed = line.trimStart()

            if (trimmed.startsWith("<")) {
                val fields = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
                if (fields.isEmpty()) return@forEach
                val tag = fields[0].uppercase()
                when {
                    tag == "<CLF2>" || tag == "<CLF1>" -> format = tag
                    tag == "<BAND>" -> {
                        val hz = fields.getOrNull(1)?.toFloatOrNull()
                            ?: throw ParseException("<BAND> with no frequency")
                        currentBand = mutableListOf()
                        bandBlocks[hz.roundToInt()] = currentBand!!
                    }
                    else -> tags[tag] = fields.drop(1)
                }
                return@forEach
            }

            // A numeric row: one arc of the balloon for the band in progress.
            val band = currentBand ?: return@forEach
            val values = line.split('\t').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toFloatOrNull() }
            if (values.isNotEmpty()) band.add(values.toFloatArray())
        }

        val fmt = format ?: throw ParseException(
            "not a CLF TAB file: no <CLF1> or <CLF2> header line found"
        )
        if (bandBlocks.isEmpty()) throw ParseException("no <BAND> blocks found")

        val resolution = if (fmt == "<CLF2>") 5 else 10
        val expectedArcs = 360 / resolution
        val expectedSamples = 180 / resolution + 1
        val reversedArcs = tags["<BALLOON-ARC-ORDER>"]
            ?.any { it.equals("<reversed>", ignoreCase = true) } == true
        val symmetry = tags["<BALLOON-SYMMETRY>"]?.firstOrNull()?.lowercase() ?: "<none>"

        val bands = bandBlocks.map { (hz, arcs) ->
            arcs.forEachIndexed { i, arc ->
                if (arc.size != expectedSamples) {
                    throw ParseException(
                        "band $hz Hz arc ${i + 1}: expected $expectedSamples samples " +
                            "for a $resolution degree grid, found ${arc.size}"
                    )
                }
            }
            val full = expandSymmetry(arcs, expectedArcs, symmetry, hz)
            Band(hz, orderArcs(full, reversedArcs), resolution)
        }

        return Speaker(
            speakerId = speakerId.ifBlank { slug(tags.value("<MODELNAME>") ?: "clf_import") },
            manufacturer = tags.value("<MANUFACTURER>").orEmpty(),
            model = tags.value("<MODELNAME>").orEmpty(),
            description = tags.value("<DESCRIPTION>").orEmpty(),
            resolutionDeg = resolution,
            sensitivityDb = tags["<SENSITIVITY>"]?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            maxInputW = tags["<TOTMAXINPUT>"]?.firstNotNullOfOrNull { it.toFloatOrNull() },
            bands = bands,
            tags = tags
        )
    }

    fun parseOrNull(text: String, speakerId: String = ""): Speaker? =
        runCatching { parse(text, speakerId) }.getOrNull()

    /**
     * Mirror a symmetric balloon out to the full circle.
     *
     * A file may store only half or a quarter of the arcs when the cabinet is
     * symmetric. The stored run always starts at phi = 0, so the missing arcs
     * are its reflection.
     */
    private fun expandSymmetry(
        arcs: List<FloatArray>,
        expectedArcs: Int,
        symmetry: String,
        hz: Int
    ): List<FloatArray> {
        if (arcs.size == expectedArcs) return arcs
        if (arcs.isEmpty()) throw ParseException("band $hz Hz has no arcs")

        val half = expectedArcs / 2 + 1
        val quarter = expectedArcs / 4 + 1
        return when (arcs.size) {
            half -> List(expectedArcs) { i ->
                arcs[if (i < half) i else expectedArcs - i]
            }
            quarter -> List(expectedArcs) { i ->
                val q = expectedArcs / 4
                val folded = when {
                    i <= q -> i
                    i <= 2 * q -> 2 * q - i
                    i <= 3 * q -> i - 2 * q
                    else -> expectedArcs - i
                }
                arcs[folded.coerceIn(0, arcs.size - 1)]
            }
            else -> throw ParseException(
                "band $hz Hz: $symmetry symmetry with ${arcs.size} arcs does not " +
                    "expand to $expectedArcs (expected $expectedArcs, $half or $quarter)"
            )
        }
    }

    /**
     * Put arcs in ascending phi order.
     *
     * `<BALLOON-ARC-ORDER> <reversed>` means the arcs were written going the
     * other way round the axis, so arc *i* is at phi = -i steps. Reversing the
     * tail leaves arc 0 in place and puts the rest back in ascending order.
     */
    private fun orderArcs(arcs: List<FloatArray>, reversed: Boolean): Array<FloatArray> {
        if (!reversed || arcs.size <= 1) return arcs.toTypedArray()
        return (listOf(arcs[0]) + arcs.drop(1).reversed()).toTypedArray()
    }

    /**
     * Check the two directions every arc shares - dead ahead and dead behind.
     *
     * Returns null when the balloon is consistent, or a description of the
     * worst offender. A misparsed grid almost always breaks this.
     */
    fun balloonIntegrityError(band: Band, toleranceDb: Float = 0.05f): String? {
        if (band.arcCount < 2 || band.samplesPerArc < 2) return null
        listOf(0 to "on-axis", band.samplesPerArc - 1 to "rear-axis").forEach { (idx, name) ->
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            band.attenuationDb.forEach { arc ->
                lo = minOf(lo, arc[idx]); hi = maxOf(hi, arc[idx])
            }
            if (hi - lo > toleranceDb) {
                return "band ${band.frequencyHz} Hz: $name differs by %.2f dB across arcs"
                    .format(hi - lo)
            }
        }
        return null
    }

    /**
     * Convert an off-axis direction in the app's convention into the file's.
     *
     * The app thinks in horizontal azimuth and vertical elevation relative to
     * where the box is aimed. A CLF balloon thinks in polar angle from the axis
     * and rotation about it. With on-axis at +x and up at +z:
     *
     *     theta = acos(x),  phi = atan2(y, z)
     *
     * so phi = 0 is straight up and phi = 90 is horizontal, which is what the
     * measured data shows.
     */
    fun toAxisRelative(azimuthDeg: Float, elevationDeg: Float): Pair<Float, Float> {
        val az = Math.toRadians(azimuthDeg.toDouble())
        val el = Math.toRadians(elevationDeg.toDouble())
        val x = cos(el) * cos(az)
        val y = cos(el) * sin(az)
        val z = sin(el)
        val theta = Math.toDegrees(acos(x.coerceIn(-1.0, 1.0)))
        val phi = (Math.toDegrees(atan2(y, z)) + 360.0) % 360.0
        return theta.toFloat() to phi.toFloat()
    }

    /** Attenuation for a direction expressed the way the rest of the app expresses it. */
    fun attenuationDb(band: Band, azimuthDeg: Float, elevationDeg: Float): Float {
        val (theta, phi) = toAxisRelative(azimuthDeg, elevationDeg)
        return band.at(theta, phi)
    }

    /**
     * Resample onto the azimuth/elevation grid [ClfData] expects, so existing
     * consumers keep working without knowing about axis-relative coordinates.
     */
    fun toClfData(speaker: Speaker, stepDeg: Int = 5): ClfData {
        val azimuths = (0 until 360 step stepDeg).map { it.toFloat() }
        val elevations = (-90..90 step stepDeg).map { it.toFloat() }
        val patterns = speaker.bands.map { band ->
            val spl = Array(azimuths.size) { ai ->
                FloatArray(elevations.size) { ei ->
                    attenuationDb(band, azimuths[ai], elevations[ei])
                }
            }
            ClfPolarPattern(band.frequencyHz, azimuths, elevations, spl)
        }
        return ClfData(
            speakerId = speaker.speakerId,
            manufacturerName = speaker.manufacturer,
            modelName = speaker.model,
            patterns = patterns
        )
    }

    private fun Map<String, List<String>>.value(tag: String): String? =
        this[tag]?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("<") }

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "clf_import" }
}
