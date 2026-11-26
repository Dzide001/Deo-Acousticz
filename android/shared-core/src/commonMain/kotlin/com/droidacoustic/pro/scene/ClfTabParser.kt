package com.droidacoustic.pro.scene

import kotlin.math.PI
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

    const val IMPORTER_VERSION = "clf-subset-v5"

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
        /** On-axis level at 1 m per band, from the file's own sensitivity row. */
        val sensitivityByBand: Map<Int, Float> = emptyMap(),
        val maxInputW: Float?,
        val bands: List<Band>,
        val tags: Map<String, List<String>>,
        val sourceSha256: String? = null
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
            val line = rawLine.substringBefore('#').trimEnd('\r', '\n')
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
            val values = line.split('\t').map(String::trim).filter(String::isNotEmpty).map { value ->
                value.toFloatOrNull()?.takeIf {it.isFinite()}
                    ?: throw ParseException("invalid balloon magnitude: $value")
            }
            if (values.isNotEmpty()) band.add(values.toFloatArray())
        }

        val fmt = format ?: throw ParseException(
            "not a CLF TAB file: no <CLF1> or <CLF2> header line found"
        )
        if (bandBlocks.isEmpty()) throw ParseException("no <BAND> blocks found")

        val resolution = if (fmt == "<CLF2>") 5 else 10
        val expectedArcs = 360 / resolution
        val expectedSamples = 180 / resolution + 1
        val arcOrder=tags["<BALLOON-ARC-ORDER>"]?.firstOrNull()?.lowercase() ?: "<default>"
        if(arcOrder !in listOf("<default>","<normal>","<reversed>")) throw ParseException("unsupported arc order: $arcOrder")
        val reversedArcs=arcOrder=="<reversed>"
        val orderFields=tags["<BALLOON-ARC-ORDER>"].orEmpty()
        val rotateIndex=orderFields.indexOfFirst {it.equals("<ROTATE>",ignoreCase=true)}
        val rotate=if(rotateIndex<0) 0f else orderFields.getOrNull(rotateIndex+1)?.toFloatOrNull()
            ?: throw ParseException("<ROTATE> needs an angle")
        if(!rotate.isFinite() || abs(rotate/resolution-(rotate/resolution).roundToInt())>1e-5)
            throw ParseException("<ROTATE> must use $resolution degree steps")
        val symmetry = tags["<BALLOON-SYMMETRY>"]?.firstOrNull()?.lowercase() ?: "<none>"

        val referenceFields=tags["<BALLOON-REF>"].orEmpty()
        val reference=referenceFields.firstOrNull()?.lowercase() ?: "<relative>"
        if(reference!="<relative>") throw ParseException("unsupported balloon reference: $reference; import normalized relative magnitudes")
        val signIndex=referenceFields.indexOfFirst {it.equals("<SIGN>",ignoreCase=true)}
        val sign=if(signIndex<0) "<actual>" else referenceFields.getOrNull(signIndex+1)?.lowercase()
        if(sign !in listOf("<actual>","<reversed>")) throw ParseException("unsupported balloon sign: $sign")

        val bands = bandBlocks.map { (hz, arcs) ->
            arcs.forEachIndexed { i, arc ->
                if (arc.size != expectedSamples) {
                    throw ParseException(
                        "band $hz Hz arc ${i + 1}: expected $expectedSamples samples " +
                            "for a $resolution degree grid, found ${arc.size}"
                    )
                }
            }
            val magnitudes=arcs.map {row->FloatArray(row.size) {i->if(sign=="<reversed>") -row[i] else row[i]}}
            val ordered=expandSymmetry(magnitudes, expectedArcs, symmetry, hz, reversedArcs)
            val shift=((rotate % 360)/resolution).roundToInt()
            Band(hz, Array(expectedArcs) {i->ordered[((i-shift)%expectedArcs+expectedArcs)%expectedArcs]}, resolution)
        }

        // Preserve explicit missing slots. Sensitivity and axial spectrum are
        // different reference rows; an explicit missing sensitivity cannot be
        // repaired with the other row. CLF1 axial data is third-octave, not octave.
        fun parseRow(tag:String, frequencies:List<Int>):Map<Int,Float> {
            val tokens=tags[tag] ?: return emptyMap()
            if(tokens.size!=frequencies.size) throw ParseException("$tag requires ${frequencies.size} reference slots, found ${tokens.size}")
            return frequencies.zip(tokens).mapNotNull {(hz,token)->
                if(token.equals("<nodata>",ignoreCase=true)) null else {
                    val level=token.toFloatOrNull()?.takeIf {it.isFinite()}
                        ?: throw ParseException("$tag has invalid reference at $hz Hz: $token")
                    if(level in 30f..150f) hz to level else null
                }
            }.toMap()
        }
        if(fmt=="<CLF1>" && !tags.containsKey("<SENSITIVITY>") && tags.containsKey("<AXIAL-SPECTRUM>") &&
            tags["<TYPE>"]?.firstOrNull()?.equals("<powered>",ignoreCase=true)==true)
            throw ParseException("powered CF1 axial-only conversion is unsupported; supply normalized octave reference levels")
        val sensitivityByBand=if(tags.containsKey("<SENSITIVITY>"))
            parseRow("<SENSITIVITY>",bands.map {it.frequencyHz})
        else if(fmt=="<CLF2>") parseRow("<AXIAL-SPECTRUM>",THIRD_OCTAVE_HZ)
            .filterKeys {hz->bands.any {it.frequencyHz==hz}}
        else emptyMap() // No implicit third-octave -> octave drive normalization.

        return Speaker(
            speakerId = speakerId.ifBlank { slug(tags.value("<MODELNAME>") ?: "clf_import") },
            manufacturer = tags.value("<MANUFACTURER>").orEmpty(),
            model = tags.value("<MODELNAME>").orEmpty(),
            description = tags.value("<DESCRIPTION>").orEmpty(),
            resolutionDeg = resolution,
            sensitivityDb = sensitivityByBand.values.takeIf {it.isNotEmpty()}?.average()?.toFloat(),
            sensitivityByBand = sensitivityByBand,
            maxInputW = tags["<TOTMAXINPUT>"]?.firstNotNullOfOrNull { it.toFloatOrNull() },
            bands = bands,
            tags = tags,
            sourceSha256 = com.droidacoustic.pro.acoustics.SpeakerProvenance.hash(text.encodeToByteArray())
        )
    }

    fun parseOrNull(text: String, speakerId: String = ""): Speaker? =
        runCatching { parse(text, speakerId) }.getOrNull()

    /** Specification section 12.2 and footnotes 1-4: order the stored sector
     * first, then reflect according to the declared symmetry. Vertical sectors
     * start at phi=270, unlike horizontal/full sectors. Rotation follows this.
     */
    private fun expandSymmetry(arcs: List<FloatArray>, count: Int, symmetry: String,
        hz: Int, reversed: Boolean): Array<FloatArray> {
        val q=count/4
        val expected=when(symmetry) {
            "<none>" -> count
            "<horizontal>","<vertical>" -> 2*q+1
            "<full>" -> q+1
            "<rotational>" -> 1
            else -> throw ParseException("band $hz Hz: unsupported symmetry $symmetry")
        }
        if(arcs.size!=expected) throw ParseException("band $hz Hz: $symmetry requires $expected arcs, found ${arcs.size}")
        val sector=if(reversed && symmetry!="<none>") arcs.reversed() else arcs
        return Array(count) {i->
            val index=when(symmetry) {
                "<none>" -> if(reversed) (count-i)%count else i
                "<rotational>" -> 0
                "<horizontal>" -> minOf(i,count-i)
                "<vertical>" -> {
                    val upper=if(i in q..3*q) (2*q-i+count)%count else i
                    (upper+q)%count
                }
                else -> {val half=minOf(i,count-i);minOf(half,2*q-half)}
            }
            sector[index]
        }
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
                return "band ${band.frequencyHz} Hz: $name differs by ${((hi-lo)*100).roundToInt()/100f} dB across arcs"
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
     * CLF specification (4 March 2020), Fig. 1: phi 0 is up, 90 left,
     * 180 down, 270 right, as viewed from behind the cabinet. Native positive
     * azimuth is right, so the CLF left component has the opposite sign.
     */
    fun toAxisRelative(azimuthDeg: Float, elevationDeg: Float): Pair<Float, Float> {
        val az = azimuthDeg * PI / 180.0
        val el = elevationDeg * PI / 180.0
        val x = cos(el) * cos(az)
        val y = -cos(el) * sin(az) // CLF +Y is left; native positive azimuth is right.
        val z = sin(el)
        val theta = acos(x.coerceIn(-1.0, 1.0)) * 180.0 / PI
        val phi = (atan2(y, z) * 180.0 / PI + 360.0) % 360.0
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
        val format=speaker.tags["<FORMAT>"]?.firstOrNull()
        val basis=when {
            format=="CF1" || speaker.tags.containsKey("<SENSITIVITY>") -> LevelReferenceBasis.SENSITIVITY
            format=="CF2" || speaker.tags.containsKey("<AXIAL-SPECTRUM>") -> LevelReferenceBasis.AXIAL_SPECTRUM
            else -> LevelReferenceBasis.UNSPECIFIED
        }
        val powered=speaker.tags["<TYPE>"]?.firstOrNull()?.equals("<powered>",ignoreCase=true)==true
        val note=if(basis==LevelReferenceBasis.AXIAL_SPECTRUM)
            speaker.tags["<AXIAL-SPECTRUM-INFO>"]?.joinToString(" ") ?: "Axial-spectrum input normalization unspecified"
        else speaker.tags["<SENSITIVITY-INFO>"]?.joinToString(" ").orEmpty()
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
            patterns = patterns,
            sensitivityDbByBand = speaker.sensitivityByBand,
            measurementGrid = if (speaker.resolutionDeg == 10)
                com.droidacoustic.pro.acoustics.MeasurementGrid.OCTAVE
                else com.droidacoustic.pro.acoustics.MeasurementGrid.THIRD_OCTAVE,
            // The sensitivity row is carried at its existing 1 m reference. Raw
            // measurement distance/input voltage must not be applied again without
            // a verified contract for the exporter and normalization of this row.
            levelReferenceBasis = basis,
            calibration = com.droidacoustic.pro.acoustics.SpeakerDataChecks.clfCalibration(basis,powered,note),
            provenance = com.droidacoustic.pro.acoustics.SpeakerProvenance(
                origin = com.droidacoustic.pro.acoustics.SpeakerDataOrigin.USER_SUPPLIED,
                sourceName = speaker.model,
                sourceUrl = speaker.tags["<WEB-SITE>"]?.firstOrNull().orEmpty(),
                sourceSha256 = speaker.sourceSha256,
                importer = "$IMPORTER_VERSION:" + (speaker.tags["<FORMAT>"]?.firstOrNull() ?: "TAB"),
                normalizationNotes = if(format in listOf("CF1","CF2"))
                    "Binary ${if(basis==LevelReferenceBasis.SENSITIVITY) "sensitivity" else "axial-spectrum"} row; electrical normalization not decoded"
                    else note)
        )
    }

    private fun Map<String, List<String>>.value(tag: String): String? =
        this[tag]?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("<") }

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "clf_import" }
}
