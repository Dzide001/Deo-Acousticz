package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*

/** Explicit interchange envelope. Native proprietary formats are not inferred from file extensions. */
class ImportedComparison private constructor(
    val document: String, val run: SpeakerComparisonRun, val producer: String, val version: String,
    val fileName: String, val toleranceDb: Double, val synthetic: Boolean,
    private val measurementCalibrated: Boolean, val normalization: RelativeResponseNormalization?, val broadband: BroadbandDefinition?
) {
    val documentSha256 = SpeakerProvenance.hash(document.encodeToByteArray())
    internal fun blockingReasons(): List<String> = buildList {
        if(run.conditions.levelConvention !in listOf("ABSOLUTE_SPL","BROADBAND_SPL") && normalization==null) add("Relative response requires an explicit per-frequency normalization receiver")
        if(synthetic) add("Synthetic fixture; cannot qualify an external comparison")
        if(run.evidence==ComparisonEvidence.CONTROLLED_MEASUREMENT && !measurementCalibrated)
            add("Measurement calibration is unknown; imported levels are retained but cannot qualify a comparison")
    }
    companion object {
        const val MAX_DOCUMENT_CHARS=4*1024*1024
        const val MAX_PAYLOAD_CHARS=2*1024*1024
        const val MAX_SAMPLES=10000
        fun parse(document: String): ImportedComparison {
            require(document.length<=MAX_DOCUMENT_CHARS) {"Comparison document exceeds 4 MiB text limit"}
            require(document.trimStart().startsWith("{")) {"Choose a comparison JSON package; wrap CSV with explicit protocol metadata first"}
            val root=Json.parseToJsonElement(document).jsonObject
            val importVersion=root.getValue("version").jsonPrimitive.int
            require(root.text("schema")=="droidacoustic.comparison-import" && importVersion in 1..5) {"Unsupported comparison import schema/version"}
            val meta=root.getValue("source").jsonObject
            val producer=meta.text("producer");val version=meta.text("version");val fileName=meta.text("fileName")
            require(listOf(producer,version,fileName).all {it.isNotBlank()}) {"Producer, source version and original file name are required"}
            val synthetic=root.getValue("synthetic").jsonPrimitive.boolean
            val declared=ComparisonEvidence.valueOf(root.text("evidence"))
            val evidence=if(synthetic && declared!=ComparisonEvidence.ENGINE) ComparisonEvidence.SYNTHETIC else declared
            val c=root.getValue("conditions").jsonObject
            val broadband=root["broadband"]?.let {BroadbandDefinition.parse(it.jsonObject)}
            if(broadband!=null || c.containsKey("broadband")) require(importVersion in 3..5) {"Broadband definitions require comparison-import version 3 or 4"}
            c["broadband"]?.let { require(it.jsonPrimitive.content==broadband?.canonical()) {"Broadband declarations disagree"} }
            val normalization=root["normalization"]?.let {RelativeResponseNormalization.parse(it.jsonObject)}
            if(normalization!=null || c.containsKey("normalization")) require(importVersion in listOf(2,4,5)) {"Normalization requires comparison-import version 2 or 4"}
            c["normalization"]?.let { require(it.jsonPrimitive.content==normalization?.canonical()) {"Normalization declarations disagree"} }
            val conditions=SpeakerComparisonConditions(c.text("modelRevision"),c.text("preset"),c.text("geometry"),
                c.text("frequencyConvention"),c.text("levelConvention"),c.text("atmosphere"),c.text("ground"),
                DriveUnit.valueOf(c.text("driveUnit")),c["driveValue"]?.let { if(it==JsonNull) null else it.jsonPrimitive.double },normalization?.canonical(),broadband?.canonical())
            require(conditions.levelConvention !in listOf("ABSOLUTE_SPL","BROADBAND_SPL") || normalization==null) {"Absolute levels cannot declare relative normalization"}
            if(importVersion==2) require(normalization!=null && conditions.levelConvention!="ABSOLUTE_SPL") {"Import version 2 requires relative normalization"}
            require((conditions.levelConvention in listOf("BROADBAND_SPL","RELATIVE_BROADBAND_TO_REFERENCE"))== (broadband!=null)) {"Broadband definition and level convention disagree"}
            if(importVersion==3) require(broadband!=null && broadband.sourceSpectrum==null && normalization==null) {"Import version 3 requires absolute broadband data"}
            if(importVersion==4) require(broadband!=null && broadband.sourceSpectrum==null && normalization!=null && conditions.levelConvention=="RELATIVE_BROADBAND_TO_REFERENCE") {"Import version 4 requires relative weighted broadband normalization"}
            if(conditions.levelConvention=="RELATIVE_BROADBAND_TO_REFERENCE") require(importVersion in 4..5) {"Relative broadband requires import version 4 or 5"}
            if(importVersion==5) require(broadband?.sourceSpectrum!=null && conditions.levelConvention in listOf("BROADBAND_SPL","RELATIVE_BROADBAND_TO_REFERENCE") &&
                ((conditions.levelConvention=="BROADBAND_SPL")== (normalization==null))) {"Import version 5 requires explicit total-drive spectrum"}
            if(broadband?.sourceSpectrum!=null) require(importVersion==5) {"Explicit total drive requires import version 5"}
            normalization?.let {require(it.method==if(broadband!=null) "weighted-broadband-level-difference" else "per-frequency-level-difference") {"Normalization method conflicts with metric"}}
            if(conditions.levelConvention=="RELATIVE_TO_REFERENCE") require(importVersion==2) {"Reference-relative response requires import version 2"}
            require(conditions.driveValue==null || conditions.driveValue.isFinite() && conditions.driveValue>0) {"Invalid drive value"}
            val units=root.getValue("units").jsonObject
            require(units.text("frequency")== (if(broadband==null) "Hz" else "aggregate") && units.text("position")=="m") {"Only explicit Hz and metres are supported"}
            require(units.text("level")==when(conditions.levelConvention) {
                "ABSOLUTE_SPL" -> "dB_SPL"; "BROADBAND_SPL" -> "dB_${broadband!!.weighting}"; "RELATIVE_BROADBAND_TO_REFERENCE" -> "dB_relative_${broadband!!.weighting}"; "RELATIVE_TO_ON_AXIS", "RELATIVE_TO_REFERENCE" -> "dB_relative"; else -> error("Unsupported level convention")
            }) {"Level units conflict with the declared comparison convention"}
            val tolerance=root.getValue("toleranceDb").jsonPrimitive.double
            require(tolerance.isFinite() && tolerance>0) {"Tolerance must be finite and positive"}
            val payload=root.text("payload")
            require(payload.length<=MAX_PAYLOAD_CHARS) {"Comparison payload exceeds 2 MiB text limit"}
            val hash=SpeakerProvenance.hash(payload.encodeToByteArray())
            root["sourceSha256"]?.let { require(it.jsonPrimitive.content==hash) {"Original payload hash mismatch"} }
            fun number(p: JsonObject,key: String): Double = p.getValue(key).jsonPrimitive.double.also {
                require(it.isFinite()) {"Non-finite $key"}
            }
            val samples=when(root.text("format")) {
                "JSON_SAMPLES" -> {
                    val array=Json.parseToJsonElement(payload).jsonArray
                    require(array.size<=MAX_SAMPLES) {"Too many comparison samples"}
                    array.map { item -> val p=item.jsonObject
                        SpeakerComparisonSample(p.text("id"),number(p,"frequencyHz"),number(p,"xM"),number(p,"yM"),number(p,"zM"),
                            p.getValue("levelDb").let {if(it==JsonNull) null else it.jsonPrimitive.double.also {v -> require(v.isFinite()) {"Non-finite level"}}})
                    }
                }
                "CSV" -> {
                    val delimiter=root.text("delimiter").also {require(it in listOf(",",";","\t")) {"Unsupported CSV delimiter"}}.single()
                    val rows=ComparisonCsv.rows(payload,delimiter)
                    require(rows.isNotEmpty()) {"Empty CSV"}
                    val headers=rows.first().mapIndexed {i,s -> if(i==0) s.removePrefix("\uFEFF") else s}
                    require(headers.none {it.isBlank()} && headers.distinct().size==headers.size) {"Blank or duplicate CSV header"}
                    val columns=root.getValue("columns").jsonObject.mapValues {it.value.jsonPrimitive.content}
                    require(columns.keys.all {it in setOf("id","frequencyHz","levelDb","xM","yM","zM")}) {"Unknown column mapping"}
                    require(columns.keys.containsAll(listOf("frequencyHz","levelDb"))) {"Map frequencyHz and levelDb columns"}
                    require(columns.values.distinct().size==columns.size && columns.values.all {it in headers}) {"Missing or ambiguous mapped CSV column"}
                    val indices=columns.mapValues {headers.indexOf(it.value)}
                    val fixed=root["receiver"]?.jsonObject
                    val mappedPosition=listOf("xM","yM","zM").count {it in indices}
                    require(mappedPosition==3 && fixed==null || mappedPosition==0 && fixed!=null) {"Declare all coordinate columns or one fixed receiver"}
                    if("id" !in columns) require(fixed?.text("id")?.isNotBlank()==true) {"Map sample IDs or declare a fixed receiver ID"}
                    rows.drop(1).map {row ->
                        require(row.size==headers.size) {"CSV row has wrong column count"}
                        fun cell(key: String)=row[indices.getValue(key)]
                        fun numeric(key: String)=cell(key).trim().toDoubleOrNull()?.also {require(it.isFinite()) {"Non-finite $key"}} ?: error("Invalid numeric $key")
                        val hz=numeric("frequencyHz")
                        val id=if("id" in indices) cell("id") else fixed!!.text("id")+":"+hz.toString()
                        fun position(key: String)=if(mappedPosition==3) numeric(key) else number(fixed!!,key)
                        val level=cell("levelDb").trim().let {if(it.isEmpty()) null else numeric("levelDb")}
                        SpeakerComparisonSample(id,hz,position("xM"),position("yM"),position("zM"),level)
                    }
                }
                else -> error("Unsupported comparison payload format")
            }
            require(samples.isNotEmpty() && samples.size<=MAX_SAMPLES) {"Empty or oversized comparison data"}
            require(samples.all {it.id.isNotBlank() && (if(broadband!=null) it.hz==0.0 else it.hz>0)} && samples.map {it.id}.distinct().size==samples.size) {"Invalid/duplicate sample ID or frequency"}
            val calibration=meta["calibrationStatus"]?.jsonPrimitive?.content ?: "UNKNOWN"
            require(calibration in listOf("UNKNOWN","DECLARED")) {"Unsupported calibration status"}
            if(declared==ComparisonEvidence.CONTROLLED_MEASUREMENT) require(listOf("instrument","method","smoothing","timeWindow")
                .all {meta[it]?.jsonPrimitive?.content?.isNotBlank()==true}) {"Measurement instrument, method, smoothing and time window are required"}
            val calibrated=calibration=="DECLARED" && meta["calibrationReference"]?.jsonPrimitive?.content?.isNotBlank()==true
            return ImportedComparison(document,SpeakerComparisonRun(conditions,evidence,hash,samples.toList()),producer,version,fileName,
                tolerance,synthetic,calibrated,normalization,broadband)
        }
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also {require(it.isString) {"Expected text for $key"}}.content
    }
}

/** Bounded quoted CSV reader: doubled quotes, CRLF, embedded newlines; no locale or unit guessing. */
internal object ComparisonCsv {
    fun rows(input: String, delimiter: Char): List<List<String>> {
        val text=input.removePrefix("\uFEFF")
        val rows=mutableListOf<List<String>>();val row=mutableListOf<String>();val field=StringBuilder()
        var quoted=false;var closed=false;var i=0
        fun cell() {row+=field.toString();field.clear();closed=false;require(row.size<=64) {"Too many CSV columns"}}
        fun end() {cell();if(row.any {it.isNotEmpty()}) rows+=row.toList();row.clear()
            require(rows.size<=ImportedComparison.MAX_SAMPLES+1) {"Too many CSV rows"}}
        while(i<text.length) {
            val c=text[i++]
            if(quoted) {
                if(c=='"') {if(i<text.length && text[i]=='"') {field.append('"');i++} else {quoted=false;closed=true}}
                else field.append(c)
            } else when(c) {
                '"' -> {require(field.isEmpty() && !closed) {"Unexpected CSV quote"};quoted=true}
                delimiter -> cell()
                '\r','\n' -> {if(c=='\r' && i<text.length && text[i]=='\n') i++;end()}
                else -> {require(!closed) {"Unexpected text after CSV quote"};field.append(c)}
            }
        }
        require(!quoted) {"Unterminated CSV quote"}
        if(field.isNotEmpty() || row.isNotEmpty() || closed) end()
        return rows
    }
}

/** Frozen imported artifacts, not live scene predictions. Reports are always recomputed on restore. */
class ComparisonWorkspace private constructor(val reference: ImportedComparison?, val engine: ImportedComparison?) {
    fun import(document: String, asReference: Boolean): ComparisonWorkspace {
        return withImported(ImportedComparison.parse(document),asReference)
    }
    fun withImported(data: ImportedComparison, asReference: Boolean): ComparisonWorkspace {
        require(if(asReference) data.run.evidence!=ComparisonEvidence.ENGINE else data.run.evidence==ComparisonEvidence.ENGINE) {"Dataset evidence does not match the selected slot"}
        return if(asReference) ComparisonWorkspace(data,engine) else ComparisonWorkspace(reference,data)
    }
    fun clear()=empty()
    fun toJsonString(): String = buildJsonObject {
        put("version",1)
        reference?.let {put("referenceDocument",it.document)}
        engine?.let {put("engineDocument",it.document)}
    }.toString()
    fun validationReportJson(includeDocuments: Boolean = true): String = ComparisonValidation.report(reference,engine,
        Json.parseToJsonElement(reportJson()).jsonObject,includeDocuments)
    fun reportJson(): String {
        val a=reference;val b=engine
        val result=if(a==null || b==null) buildJsonObject {put("status","WAITING_FOR_DATA")} else {
            val reasons=a.blockingReasons()+b.blockingReasons()
            if(reasons.isNotEmpty()) buildJsonObject {put("status","NOT_COMPARABLE");put("reasons",JsonArray(reasons.map(::JsonPrimitive)))}
            else Json.parseToJsonElement(SpeakerComparisons.compare(a.run,b.run,a.toleranceDb)).jsonObject
        }
        return buildJsonObject {
            put("comparison",result);put("snapshotNotice","Frozen reference/engine snapshots; independent origin and conditions are declared, not authenticated. Current scene edits do not update these results.")
            put("synthetic",a?.synthetic==true || b?.synthetic==true)
            a?.let {put("referenceDocumentSha256",it.documentSha256);put("referenceProducer",it.producer)}
            b?.let {put("engineDocumentSha256",it.documentSha256);put("engineProducer",it.producer)}
        }.toString()
    }
    companion object {
        fun empty()=ComparisonWorkspace(null,null)
        fun parse(text: String): ComparisonWorkspace {
            require(text.length<=24*1024*1024) {"Comparison workspace too large"}
            val root=Json.parseToJsonElement(text).jsonObject
            require(root.getValue("version").jsonPrimitive.int==1) {"Unsupported comparison workspace version"}
            var state=empty()
            root["referenceDocument"]?.let {state=state.import(it.jsonPrimitive.content,true)}
            root["engineDocument"]?.let {state=state.import(it.jsonPrimitive.content,false)}
            return state
        }
    }
}
