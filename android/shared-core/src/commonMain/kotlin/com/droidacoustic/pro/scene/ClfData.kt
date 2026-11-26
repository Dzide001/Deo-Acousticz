package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import kotlinx.serialization.json.*

/**
 * CLF (Common Loudspeaker Format) polar pattern data.
 * Represents frequency-dependent directivity in the normalized engine frame:
 * +X forward, +Y up, +Z right; positive azimuth turns toward speaker-right
 * when viewed from behind the cabinet. Importers must convert external conventions.
 */
data class ClfPolarPattern(
    val frequencyHz: Int,                    // Center frequency (e.g., 1000)
    val azimuths: List<Float>,               // Azimuth angles in degrees (typically 0–360 or -180 to +180)
    val elevations: List<Float>,             // Elevation angles in degrees (typically -90 to +90)
    val spl: Array<FloatArray>,              // spl[azimuth_idx][elevation_idx]: dB relative to on-axis
    val angularDomain: AngularDomain? = null
) {
    /**
     * Level relative to on-axis toward ([azimuthDeg], [elevationDeg]), bilinear
     * on the grid.
     *
     * Azimuth wraps: 357 degrees lies between the last arc and the first, which
     * the earlier interpolation clamped to the last arc alone.
     */
    fun at(azimuthDeg: Float, elevationDeg: Float): Float {
        return sharedGrid.at(azimuthDeg, elevationDeg)
    }

    internal val sharedGrid: PolarGrid by lazy {
        PolarGrid(azimuths, elevations, spl.map { it.toList() },angularDomain)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClfPolarPattern) return false
        if (frequencyHz != other.frequencyHz) return false
        if (azimuths != other.azimuths) return false
        if (elevations != other.elevations) return false
        if (!spl.contentDeepEquals(other.spl)) return false
        if (angularDomain?.toJsonString()!=other.angularDomain?.toJsonString()) return false
        return true
    }

    override fun hashCode(): Int {
        var result = frequencyHz
        result = 31 * result + azimuths.hashCode()
        result = 31 * result + elevations.hashCode()
        result = 31 * result + spl.contentDeepHashCode()
        result = 31 * result + (angularDomain?.toJsonString()?.hashCode() ?: 0)
        return result
    }
}

/** Which file row supplied levels; independent of declared electrical calibration. */
enum class LevelReferenceBasis { UNSPECIFIED, SENSITIVITY, AXIAL_SPECTRUM }

data class ClfData(
    val speakerId: String,                   // Identifier (e.g., "jbl_cbt_50la_1")
    val manufacturerName: String = "",
    val modelName: String = "",
    val patterns: List<ClfPolarPattern> = emptyList(),  // Indexed by frequency
    /**
     * On-axis level at 1 m for the reference drive, by band.
     *
     * A loudspeaker's response is not flat and the file says how it is not: a
     * QSC LA108 runs from 56 dB at 25 Hz to 105 dB at 12.5 kHz. Using one
     * number for every band - which is what happened before this was carried -
     * puts every band map and every weighted total at the wrong level. Empty
     * when the source did not provide one.
     */
    val sensitivityDbByBand: Map<Int, Float> = emptyMap(),
    val measurementGrid: MeasurementGrid = MeasurementGrid.THIRD_OCTAVE,
    val calibration: ReferenceCalibration = ReferenceCalibration(),
    val complexResponse: ComplexSpeakerResponse? = null,
    val provenance: SpeakerProvenance = SpeakerProvenance(),
    val levelReferenceBasis: LevelReferenceBasis = LevelReferenceBasis.UNSPECIFIED
) {
    fun measuredRadiator(useDirectivity: Boolean = true): MeasuredRadiator = MeasuredRadiator(
        sensitivityDbByBand,
        patterns.associate { p -> p.frequencyHz to { az: Float, el: Float -> p.at(az, el) } },
        measurementGrid, calibration, useDirectivity,
        patterns.associate {p -> p.frequencyHz to p.sharedGrid::variationDb}, complexResponse
    )

    fun patternAt(frequencyHz: Int): ClfPolarPattern? = patterns.firstOrNull { it.frequencyHz == frequencyHz }

    fun splAtDirection(frequencyHz: Int, azimuthDeg: Float, elevationDeg: Float): Float? =
        MeasuredBandTable(patterns.associateBy { it.frequencyHz }, measurementGrid)
            .valueAt(frequencyHz) { it.at(azimuthDeg, elevationDeg) }

}

/** Portable decoded-speaker format; unknown electrical calibration remains explicit. */
object ClfParser {
    fun parseJson(jsonString: String): ClfData? = runCatching {
        require(jsonString.length <= 64 * 1024 * 1024) { "Speaker data exceeds 64 MiB" }
        val root = Json.parseToJsonElement(jsonString).jsonObject
        fun JsonObject.text(key: String, default: String = "") = this[key]?.jsonPrimitive?.content ?: default
        val id = root.text("speakerId", "unknown")
        require(id.isNotBlank())
        val patterns = (root["patterns"]?.jsonArray ?: JsonArray(emptyList())).map { element ->
            val p = element.jsonObject
            val hz = p.getValue("frequency").jsonPrimitive.int
            fun floats(key: String) = p.getValue(key).jsonArray.map { it.jsonPrimitive.float }
            val rows = p.getValue("spl").jsonArray.map { row ->
                row.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            }.toTypedArray()
            ClfPolarPattern(hz, floats("azimuths"), floats("elevations"), rows,
                p["angularDomain"]?.let {AngularDomain.parse(it.toString(),floats("azimuths"),floats("elevations"))}).also {
                require(hz > 0 && it.sharedGrid.valid) { "Invalid polar grid at $hz Hz" }
            }
        }
        require(patterns.map { it.frequencyHz }.distinct().size == patterns.size) { "Duplicate polar bands" }
        val sensitivity = (root["sensitivityByBand"]?.jsonObject ?: JsonObject(emptyMap())).map { (k,v) ->
            val hz = k.toInt()
            val level = v.jsonPrimitive.float
            require(hz > 0 && level.isFinite()) { "Invalid sensitivity band" }
            hz to level
        }.toMap()
        val calibration = root["referenceCalibration"]?.jsonObject?.let { c ->
            ReferenceCalibration(c.getValue("distanceM").jsonPrimitive.double,
                DriveUnit.valueOf(c.text("driveUnit")), c["driveValue"]?.jsonPrimitive?.double,
                c.text("provenance", "User-supplied reference"))
        } ?: ReferenceCalibration()
        ClfData(id, root.text("manufacturer"), root.text("model"), patterns, sensitivity,
            MeasurementGrid.valueOf(root.text("measurementGrid", "THIRD_OCTAVE")), calibration,
            root["complexResponse"]?.let {ComplexSpeakerResponse.fromJsonString(it.toString())},
            root["provenance"]?.let {SpeakerProvenance.parse(it.jsonObject)} ?: SpeakerProvenance(),
            LevelReferenceBasis.valueOf(root.text("levelReferenceBasis","UNSPECIFIED")))
    }.getOrNull()

    /** Stable field/band ordering supplies a platform-independent content hash input. */
    fun toJson(data: ClfData): String = buildJsonObject {
        // Omit the legacy default so existing portable-asset hashes remain stable.
        if(data.levelReferenceBasis!=LevelReferenceBasis.UNSPECIFIED) put("levelReferenceBasis",data.levelReferenceBasis.name)
        data.complexResponse?.let {put("complexResponse",Json.parseToJsonElement(it.toJsonString()))}
        if(data.provenance!=SpeakerProvenance()) put("provenance",data.provenance.json())
        put("speakerId", data.speakerId)
        put("manufacturer", data.manufacturerName)
        put("model", data.modelName)
        put("measurementGrid", data.measurementGrid.name)
        putJsonObject("referenceCalibration") {
            put("distanceM", data.calibration.distanceM)
            put("driveUnit", data.calibration.driveUnit.name)
            data.calibration.driveValue?.let { put("driveValue", it) }
            put("provenance", data.calibration.provenance)
        }
        putJsonArray("patterns") {
            data.patterns.sortedBy { it.frequencyHz }.forEach { pat ->
                addJsonObject {
                    put("frequency", pat.frequencyHz)
                    pat.angularDomain?.toJsonString()?.let {put("angularDomain",Json.parseToJsonElement(it))}
                    put("azimuths", JsonArray(pat.azimuths.map(::JsonPrimitive)))
                    put("elevations", JsonArray(pat.elevations.map(::JsonPrimitive)))
                    put("spl", JsonArray(pat.spl.map { row -> JsonArray(row.map(::JsonPrimitive)) }))
                }
            }
        }
        putJsonObject("sensitivityByBand") {
            data.sensitivityDbByBand.toList().sortedBy { it.first }.forEach { (band, level) -> put(band.toString(), level) }
        }
    }.toString()
}
