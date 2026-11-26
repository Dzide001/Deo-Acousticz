package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class Cf1ReferenceValidationTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    private val dir get()=File(System.getProperty("manufacturerValidationRoot"),"validation/cf1-reference")

    @Test fun officialCf1SensitivitySlotsMatchTheirOwnTextRowsAndReopenWithoutInventedCalibration() {
        val reference=JSONObject(dir.resolve("results/reference.json").readText())
        val manifest=JSONObject(dir.resolve("provenance.json").readText())
        assertEquals(reference.getString("referenceScriptSha256"),SpeakerProvenance.hash(dir.resolve("reference.py").readBytes()))
        assertEquals(reference.getString("provenanceSha256"),SpeakerProvenance.hash(dir.resolve("provenance.json").readBytes()))
        val points=reference.getJSONArray("cases");val reports=JSONArray()
        val pairs=manifest.getJSONArray("pairs")
        for(i in 0 until pairs.length()) {
            val pair=pairs.getJSONObject(i);val binary=dir.resolve("fixtures/"+pair.getString("binary"));val text=dir.resolve("fixtures/"+pair.getString("text"))
            assertEquals(pair.getString("binarySha256"),SpeakerProvenance.hash(binary.readBytes()))
            assertEquals(pair.getString("fixtureTextSha256"),SpeakerProvenance.hash(text.readBytes()))
            val vmBinary=model();val vmText=model()
            assertTrue(vmBinary.importClfBinary(binary.readBytes(),"official"));assertTrue(vmText.importClfTabText(text.readText(),"official"))
            for(v in listOf(vmBinary,vmText)) {
                v.addSpeaker(0f,0f);v.setSpeakerPosition(v.speakers.value.single().id,0f,15f,0f)
                v.setSignalType("BAND");v.setSignalDispersionEnabled(true)
                assertFalse(v.getClfData("official")!!.calibration.electricalReferenceKnown)
            }
            val store=LocalProjectStore(folder.newFolder("pair$i"));store.save("saved",vmBinary.exportSceneJson(false),vmBinary::resolveProjectAsset)
            val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("saved")!!.project))
            assertFalse(fresh.getClfData("official")!!.calibration.electricalReferenceKnown)
            for(j in 0 until points.length()) {
                val row=points.getJSONObject(j);if(row.getString("binary")!=binary.name) continue
                val hz=row.getInt("hz");val expected=row.getDouble("referenceDb").toFloat()
                val binaryDb=vmBinary.getClfData("official")!!.sensitivityDbByBand.getValue(hz)
                val textDb=vmText.getClfData("official")!!.sensitivityDbByBand.getValue(hz)
                assertEquals(expected,binaryDb,.0001f);assertEquals(expected,textDb,.0001f)
                fun level(v:SceneViewModel):Float {
                    v.setBandHz(hz)
                    return v.capturePredictionRequest(PredictionTicket(v.revision.value,1,PredictionChannel.COVERAGE),v.speakers.value,
                        listOf(HeatCell(10f,0f,Float.NaN,renderY=15f)),bands=listOf(hz)).calculate().levelAt(0)
                }
                val before=level(vmBinary);val after=level(fresh);val textLevel=level(vmText)
                assertTrue(before.isFinite());assertEquals(textLevel,before,.0001f);assertEquals(before,after,0f)
                reports.put(JSONObject(row.toString()).put("decodedReferenceDb",binaryDb.toDouble())
                    .put("referenceResidualDb",binaryDb.toDouble()-row.getDouble("referenceDb"))
                    .put("textPredictionDb",textLevel).put("binaryPredictionDb",before).put("reopenedPredictionDb",after))
            }
        }
        val powered=dir.resolve("fixtures/Powered.CF1")
        assertEquals(manifest.getString("poweredSha256"),SpeakerProvenance.hash(powered.readBytes()))
        val missing=ClfTabParser.toClfData(ClfBinaryReader.parse(powered.readBytes()))
        assertTrue(missing.sensitivityDbByBand.isEmpty());assertFalse(missing.calibration.electricalReferenceKnown)
        assertTrue(missing.measuredRadiator().levelDb(1000,0f,0f).isNaN())
        val out=File(System.getProperty("cf1ReferenceReport"));out.parentFile.mkdirs()
        out.writeText(JSONObject().put("scope",reference.getString("scope")).put("allPassed",true)
            .put("cases",reports).put("poweredReference","UNAVAILABLE").put("electricalCalibration","UNKNOWN")
            .put("referenceSha256",SpeakerProvenance.hash(dir.resolve("results/reference.json").readBytes())).toString(2)+"\n")
    }

    @Test fun missingSensitivitySlotSurvivesAndroidAssetSaveReopen() {
        val original=dir.resolve("fixtures/clf1_active_point.tab").readText()
        val lines=original.lines().toMutableList();val index=lines.indexOfFirst {it.startsWith("<SENSITIVITY>")}
        val fields=lines[index].split('\t').toMutableList();fields[4]="<nodata>" // 1000 Hz is the fourth declared band.
        lines[index]=fields.joinToString("\t")
        val v=model();assertTrue(v.importClfTabText(lines.joinToString("\n"),"missing"));v.addSpeaker(0f,0f)
        assertEquals(setOf(125,250,500,2000,4000),v.getClfData("missing")!!.sensitivityDbByBand.keys)
        val store=LocalProjectStore(folder.newFolder("missing"));store.save("saved",v.exportSceneJson(false),v::resolveProjectAsset)
        val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("saved")!!.project))
        val restored=fresh.getClfData("missing")!!
        assertEquals(v.getClfData("missing")!!.sensitivityDbByBand,restored.sensitivityDbByBand)
        assertTrue(restored.measuredRadiator().levelDb(1000,0f,0f).isNaN())
        assertTrue(restored.measuredRadiator().levelDb(500,0f,0f).isFinite())
    }
}
