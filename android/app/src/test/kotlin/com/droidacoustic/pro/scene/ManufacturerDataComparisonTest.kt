package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Only file I/O/import adapters live here. Matching, calibration and comparison rules are shared. */
class ManufacturerDataComparisonTest {
    @Test fun matchedXd12SourceRowsAndDirectionalSamples() {
        val root=File(System.getProperty("manufacturerValidationRoot"))
        val source=File(System.getProperty("manufacturerValidationSource"))
        val output=File(System.getProperty("manufacturerValidationReport"))
        output.parentFile.mkdirs()
        val reference=File(root,"validation/speaker-data/results/reference.json")
        val raw=JSONObject(reference.readText())
        val tab=File(source,"CLF2_XD12.tab");val cf2=File(source,"Martin Audio-XD12.CF2")
        if(!tab.exists() || !cf2.exists()) {
            output.writeText(JSONObject().put("status","NOT_RUN").put("reason","Matched manufacturer source files unavailable").toString(2))
            assumeTrue("Matched manufacturer source files unavailable; NOT_RUN report retained",false)
        }
        val hashes=raw.getJSONObject("sourceSha256")
        for(file in listOf(tab,cf2)) assertEquals(file.name,hashes.getString(file.name),SpeakerProvenance.hash(file.readBytes()))
        assertEquals(raw.getString("referenceScriptSha256"),SpeakerProvenance.hash(File(root,"validation/speaker-data/reference.py").readBytes()))
        val text=ClfTabParser.toClfData(ClfTabParser.parse(tab.readText(Charsets.ISO_8859_1)))
        val binary=ClfTabParser.toClfData(ClfBinaryReader.parse(cf2.readBytes()))
        val rows=raw.getJSONArray("sourceRows")
        var rowError=0.0;var differentRows=0.0
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val hz=row.getInt("hz")
            rowError=maxOf(rowError,kotlin.math.abs(text.sensitivityDbByBand.getValue(hz)-row.getDouble("tabSensitivityDb")),
                kotlin.math.abs(binary.sensitivityDbByBand.getValue(hz)-row.getDouble("cf2AxialDb")))
            differentRows=maxOf(differentRows,kotlin.math.abs(row.getDouble("tabSensitivityDb")-row.getDouble("cf2AxialDb")))
        }
        val conditions=SpeakerComparisonConditions("XD12:2013 archive:matched raw balloon","raw CLF, no applied DSP",
            "source origin; +X axis; receivers encoded as azimuth/elevation degrees",
            "nominal exact tones","RELATIVE_TO_ON_AXIS","relative response; propagation cancels","off")
        val points=raw.getJSONArray("relativeSamples")
        fun samples(data: ClfData?)=(0 until points.length()).map {i ->
            val p=points.getJSONObject(i);val hz=p.getInt("hz")
            SpeakerComparisonSample(p.getString("id"),hz.toDouble(),p.getDouble("az"),p.getDouble("el"),0.0,
                if(data==null) p.getDouble("relativeDb") else
                    data.splAtDirection(hz,p.getDouble("az").toFloat(),p.getDouble("el").toFloat())?.toDouble())
        }
        val ref=SpeakerComparisonRun(conditions,ComparisonEvidence.MANUFACTURER_DATA,SpeakerProvenance.hash(reference.readBytes()),samples(null))
        val textResult=JSONObject(SpeakerComparisons.compare(ref,SpeakerComparisonRun(conditions,ComparisonEvidence.ENGINE,
            SpeakerProvenance.hash(ClfParser.toJson(text).toByteArray()),samples(text)),raw.getDouble("relativeToleranceDb")))
        val binaryResult=JSONObject(SpeakerComparisons.compare(ref,SpeakerComparisonRun(conditions,ComparisonEvidence.ENGINE,
            SpeakerProvenance.hash(ClfParser.toJson(binary).toByteArray()),samples(binary)),raw.getDouble("relativeToleranceDb")))
        val absolute=conditions.copy(levelConvention="ABSOLUTE_SPL")
        val blocked=JSONObject(SpeakerComparisons.compare(ref.copy(conditions=absolute),
            SpeakerComparisonRun(absolute,ComparisonEvidence.ENGINE,"b".repeat(64),samples(binary)),.1))
        val report=JSONObject().put("scope",raw.getString("scope")).put("sourceSha256",hashes)
            .put("referenceSha256",SpeakerProvenance.hash(reference.readBytes()))
            .put("maxSourceRowErrorDb",rowError).put("tabVersusCf2DifferentReferenceRowsMaxDb",differentRows)
            .put("tab",textResult).put("cf2",binaryResult).put("absoluteComparisonGate",blocked)
            .put("tabCalibration",JSONObject(SpeakerDataChecks.reportJson(text)))
            .put("cf2Calibration",JSONObject(SpeakerDataChecks.reportJson(binary)))
            .put("externalSoftwareComparison","PENDING_MATCHED_EXPORT")
        output.writeText(report.toString(2)+"\n")
        assertEquals("PASSED",textResult.getString("status"));assertEquals("PASSED",binaryResult.getString("status"))
        assertTrue("Reference rows differ from matched source",rowError<=raw.getDouble("referenceRowToleranceDb"))
        assertEquals(DriveUnit.WATTS,text.calibration.driveUnit);assertEquals(1.0,text.calibration.distanceM,0.0)
        assertFalse(binary.calibration.electricalReferenceKnown)
        assertEquals("NOT_COMPARABLE",blocked.getString("status"))
    }
}
