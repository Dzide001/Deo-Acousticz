package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*

/** Explicit reviewed annotation, not an automatic inference of physical equivalence. */
data class ComparisonProtocolFieldReview(val field: String,val expectedOriginalValue: String,
    val reviewedValue: String,val rationale: String,val evidenceUrl: String)

object ComparisonProtocolReview {
    private val allowed=setOf("modelRevision","preset","geometry","frequencyConvention","atmosphere","ground")

    /** Keep the complete original envelope and each change; never alter levels, units, drive or calibration. */
    fun annotate(reference: ImportedComparison,expectedDocumentSha256: String,reviewer: String,fields: List<ComparisonProtocolFieldReview>): ImportedComparison {
        require(reference.documentSha256==expectedDocumentSha256) {"Original document changed since review"}
        require(reference.run.evidence in listOf(ComparisonEvidence.MANUFACTURER_DATA,
            ComparisonEvidence.MANUFACTURER_SOFTWARE_EXPORT,ComparisonEvidence.CONTROLLED_MEASUREMENT)) {"Review requires external reference evidence"}
        require(reviewer.isNotBlank() && fields.isNotEmpty() && fields.map {it.field}.distinct().size==fields.size) {"Name reviewer and unique reviewed fields"}
        val original=Json.parseToJsonElement(reference.document).jsonObject
        require("protocolReview" !in original) {"Review the original envelope, not an already annotated copy"}
        val conditions=original.getValue("conditions").jsonObject.toMutableMap()
        fields.forEach {f ->
            require(f.field in allowed && f.reviewedValue.isNotBlank() && f.rationale.length>=12 && f.evidenceUrl.startsWith("https://")) {"Unsupported or undocumented protocol annotation"}
            require(conditions[f.field]?.jsonPrimitive?.content==f.expectedOriginalValue) {"Original protocol changed at ${f.field}"}
            conditions[f.field]=JsonPrimitive(f.reviewedValue)
        }
        return ImportedComparison.parse(JsonObject(original+mapOf("conditions" to JsonObject(conditions),"protocolReview" to buildJsonObject {
            put("version",1);put("reviewer",reviewer);put("declaration","Explicit protocol mapping; not authentication or independent certification")
            put("originalDocumentSha256",reference.documentSha256);put("originalDocument",reference.document)
            put("fields",JsonArray(fields.map {f -> buildJsonObject {
                put("field",f.field);put("originalValue",f.expectedOriginalValue);put("reviewedValue",f.reviewedValue)
                put("rationale",f.rationale);put("evidenceUrl",f.evidenceUrl)
            }}))
        })).toString())
    }
}
