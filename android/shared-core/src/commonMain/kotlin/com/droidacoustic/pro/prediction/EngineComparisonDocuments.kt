package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import kotlinx.serialization.json.*

/** Optional capture-only metadata: ordinary predictions do not hash/serialize speaker assets. */
data class ComparisonSourceData(val assetIdentity: String, val calibration: ReferenceCalibration?, val synthetic: Boolean, val directivityEnabled: Boolean = true) {
    init {require(assetIdentity.isNotBlank())}
    companion object {
        fun generic(id: String, sensitivityDb: Float, modelPackage: String, array: Boolean, dispersion: Boolean) =
            ComparisonSourceData(buildJsonObject {
                put("genericId",id);put("sensitivityDb",sensitivityDb);put("modelPackage",modelPackage);put("array",array)
            }.toString(),null,true,dispersion)
    }
    internal fun json() = buildJsonObject {
        put("assetIdentity",assetIdentity);put("synthetic",synthetic);put("directivityEnabled",directivityEnabled)
        calibration?.let {c -> put("referenceDistanceM",c.distanceM);put("driveUnit",c.driveUnit.name)
            c.driveValue?.let {put("referenceDrive",it)};put("calibrationProvenance",c.provenance)}
    }
}

internal object EngineComparisonDocuments {
    fun encode(c: SpeakerComparisonConditions,samples: List<SpeakerComparisonSample>,synthetic: Boolean,revision: Long,
        checkCancellation: () -> Unit, normalization: RelativeResponseNormalization? = null,
        referenceLevels: Map<Int,Double?> = emptyMap(), broadband: BroadbandDefinition? = null,
        bandDiagnostics: List<JsonObject> = emptyList(), referenceBandDiagnostics: JsonObject? = null): ImportedComparison {
        val payload=JsonArray(samples.map {p -> checkCancellation();buildJsonObject {
            put("id",p.id);put("frequencyHz",p.hz);put("xM",p.x);put("yM",p.y);put("zM",p.z)
            put("levelDb",p.levelDb?.let(::JsonPrimitive) ?: JsonNull)
        }}).toString()
        val conditions=buildJsonObject {
            put("modelRevision",c.modelRevision);put("preset",c.preset);put("geometry",c.geometry)
            put("frequencyConvention",c.frequencyConvention);put("levelConvention",c.levelConvention)
            put("atmosphere",c.atmosphere);put("ground",c.ground);put("driveUnit",c.driveUnit.name)
            c.driveValue?.let {put("driveValue",it)}
            c.normalization?.let {put("normalization",it)}
            c.broadband?.let {put("broadband",it)}
        }
        val document=buildJsonObject {
            put("schema","droidacoustic.comparison-import");put("version",if(broadband?.sourceSpectrum!=null) 5 else if(broadband!=null) (if(normalization==null) 3 else 4) else if(normalization==null) 1 else 2);put("evidence","ENGINE");put("synthetic",synthetic)
            putJsonObject("source") {
                put("producer","DroidAcoustic shared core");put("version","scene-capture-v1");put("fileName","engine-scene-snapshot.json")
                put("sceneRevision",revision);put("sceneConditionsSha256",SpeakerProvenance.hash(conditions.toString().encodeToByteArray()))
                put("coordinateNumerics","Requested metres; calculation uses Float coordinates with at most 1e-5 m conversion error")
                put("comparisonScope",if(broadband!=null && normalization!=null) "Difference of weighted broadband total levels; same weights/bands for numerator and reference; no per-band dB averaging or fitted offset" else if(broadband!=null) "Weighted sum of independent finite-band powers; raw per-band powers retained; no display floor or missing-band substitution" else if(normalization==null) "ABSOLUTE_SPL band power; no display floor; null for missing/nonpositive power; no automatic relative normalization"
                    else "Per-frequency total-field level difference; explicit denominator; no fitted offsets, distance correction or display floor")
                if(normalization!=null) put("normalizationReferences",JsonArray(referenceLevels.entries.sortedBy {it.key}.map {(hz,db) -> buildJsonObject {
                    put("frequencyHz",hz);if(broadband!=null) put("metric","weighted-broadband-aggregate");put("rawReferenceLevelDb",db?.let(::JsonPrimitive) ?: JsonNull)
                    put("status",if(db==null) "UNAVAILABLE" else if(db<normalization.minimumReferenceLevelDb) "BELOW_REFERENCE_FLOOR" else "VALID")
                }}))
            }
            broadband?.let {put("broadband",it.json());put("bandDiagnostics",JsonArray(bandDiagnostics))}
            referenceBandDiagnostics?.let {put("referenceBandDiagnostics",it)}
            normalization?.let {put("normalization",it.json())}
            put("conditions",conditions)
            putJsonObject("units") {put("frequency",if(broadband==null) "Hz" else "aggregate");put("position","m");put("level",if(broadband!=null) (if(normalization==null) "dB_${broadband.weighting}" else "dB_relative_${broadband.weighting}") else if(normalization==null) "dB_SPL" else "dB_relative")}
            put("format","JSON_SAMPLES");put("payload",payload);put("sourceSha256",SpeakerProvenance.hash(payload.encodeToByteArray()))
            put("toleranceDb",0.1) // Engine tolerance is not used; the independent reference supplies acceptance.
        }.toString()
        return ImportedComparison.parse(document)
    }
}
