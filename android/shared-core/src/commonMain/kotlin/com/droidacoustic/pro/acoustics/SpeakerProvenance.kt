package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.scene.ClfData
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString

/** Origin is a declaration, never a certificate of measurement accuracy or manufacturer approval. */
enum class SpeakerDataOrigin { UNSPECIFIED, GENERIC_ESTIMATE, USER_SUPPLIED, MANUFACTURER_DECLARED }

data class SpeakerProvenance(
    val origin: SpeakerDataOrigin = SpeakerDataOrigin.UNSPECIFIED,
    val sourceName: String = "",
    val sourceUrl: String = "",
    val sourceSha256: String? = null,
    val importer: String = "",
    val operatingPreset: String = "",
    val normalizationNotes: String = ""
) {
    init { require(sourceSha256 == null || Regex("[0-9a-f]{64}").matches(sourceSha256)) }
    fun json(): JsonObject = buildJsonObject {
        put("origin",origin.name);put("sourceName",sourceName);put("sourceUrl",sourceUrl)
        sourceSha256?.let {put("sourceSha256",it)}
        put("importer",importer);put("operatingPreset",operatingPreset);put("normalizationNotes",normalizationNotes)
    }
    companion object {
        fun hash(bytes: ByteArray) = bytes.toByteString().sha256().hex()
        fun parse(value: JsonObject): SpeakerProvenance {
            fun text(key: String) = value[key]?.jsonPrimitive?.content ?: ""
            return SpeakerProvenance(SpeakerDataOrigin.valueOf(text("origin").ifBlank {"UNSPECIFIED"}),
                text("sourceName"),text("sourceUrl"),value["sourceSha256"]?.jsonPrimitive?.content,
                text("importer"),text("operatingPreset"),text("normalizationNotes"))
        }
    }
}

object SpeakerDataChecks {
    /** Narrow, explicit CLF declaration; raw measurement distance is not a second normalization. */
    fun declaredClfCalibration(note: String): ReferenceCalibration {
        val declaration = Regex("Measured at ([0-9.]+) m and ([0-9.]+) Vrms and normalized to 1W/1m",RegexOption.IGNORE_CASE)
        val match = declaration.matchEntire(note.trim())
        val valid = match!=null && match.groupValues.drop(1).all { value ->
            value.toDoubleOrNull()?.let {it.isFinite() && it>0}==true
        }
        return if(valid) ReferenceCalibration(1.0,DriveUnit.WATTS,1.0,
            "File-declared 1 W / 1 m normalization; not independently verified")
        else ReferenceCalibration(provenance="CLF sensitivity row; electrical normalization unverified")
    }
    fun warnings(data: ClfData): List<String> = buildList {
        val p=data.provenance
        addAll(SpeakerAngularSupport.warnings(data))
        add(when(p.origin) {
            SpeakerDataOrigin.GENERIC_ESTIMATE -> "generic estimate; not measured manufacturer data."
            SpeakerDataOrigin.MANUFACTURER_DECLARED -> "manufacturer origin declared; accuracy not independently verified."
            SpeakerDataOrigin.USER_SUPPLIED -> "user-supplied data; manufacturer provenance unverified."
            SpeakerDataOrigin.UNSPECIFIED -> "data origin unspecified; manufacturer provenance unverified."
        })
        if(p.sourceSha256==null) add("original source hash unavailable.")
        if(!data.calibration.electricalReferenceKnown) add("electrical reference unknown; absolute-drive comparisons unavailable.")
        else add("electrical reference declared, not independently calibrated.")
        if(p.operatingPreset.isBlank()) add("operating preset unspecified; matched system comparisons need explicit settings.")
    }
    fun reportJson(data: ClfData): String = buildJsonObject {
        put("speakerId",data.speakerId);put("provenance",data.provenance.json())
        put("calibrationStatus",if(data.calibration.electricalReferenceKnown) "DECLARED" else "UNKNOWN")
        put("driveUnit",data.calibration.driveUnit.name)
        data.calibration.driveValue?.let {put("driveValue",it)}
        put("levelReferenceDistanceM",data.calibration.distanceM)
        put("angularSupport",Json.parseToJsonElement(SpeakerAngularSupport.reportJson(data)))
        put("externalValidation","pending")
        put("warnings",JsonArray(warnings(data).map(::JsonPrimitive)))
    }.toString()
}
