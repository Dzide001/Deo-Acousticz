package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.SpeakerProvenance
import com.droidacoustic.pro.prediction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.*

@OptIn(ExperimentalCoroutinesApi::class)
class ClfImportFixtureTest {
    @get:Rule val folder=TemporaryFolder()
    private val dispatcher=StandardTestDispatcher()
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()};dispatcher.scheduler.runCurrent();Dispatchers.resetMain()}
    private fun model()=SceneViewModel(predictionDispatcher=dispatcher).also {models+=it}
    private val dir get()=File(System.getProperty("clfFixtureValidationRoot"),"validation/clf-handedness")

    @Test fun declaredCf1MagnitudeDeclarationsSurviveAndroidSaveReopenAndFailedImportIsAtomic() {
        val magnitudeDir=File(System.getProperty("clfFixtureValidationRoot"),"validation/clf-magnitude")
        val reference=JSONObject(magnitudeDir.resolve("results/reference.json").readText())
        val points=reference.getJSONArray("cases")
        val grouped=(0 until points.length()).map {points.getJSONObject(it)}.groupBy {it.getString("file")}
        val reports=JSONArray()
        for((file,cases) in grouped) {
            val text=magnitudeDir.resolve("fixtures/$file").readText();val v=model()
            assertTrue(v.importClfTabText(text,"fixture"));v.addSpeaker(0f,0f)
            v.setSpeakerPosition(v.speakers.value.single().id,0f,15f,0f)
            v.setSignalType("BAND");v.setBandHz(1000);v.setSignalDispersionEnabled(true)
            val store=LocalProjectStore(folder.newFolder(file));store.save("saved",v.exportSceneJson(false),v::resolveProjectAsset)
            val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("saved")!!.project))
            assertTrue(fresh.assetRecoveryProblems.value.isEmpty())
            for(row in cases) {
                val az=row.getDouble("azimuth")*PI/180;val el=row.getDouble("elevation")*PI/180
                val cells=listOf(HeatCell((10*cos(el)*cos(az)).toFloat(),(10*cos(el)*sin(az)).toFloat(),Float.NaN,
                    renderY=(15+10*sin(el)).toFloat()),HeatCell(10f,0f,Float.NaN,renderY=15f))
                fun predict(vm:SceneViewModel):Float {
                    val out=vm.capturePredictionRequest(PredictionTicket(vm.revision.value,1,PredictionChannel.COVERAGE),vm.speakers.value,cells).calculate()
                    return out.levelAt(0)-out.levelAt(1)
                }
                val actual=predict(v);val restored=predict(fresh);val expected=row.getDouble("relativeDb").toFloat()
                assertEquals("$row",expected,actual,.001f);assertEquals(actual,restored,0f)
                reports.put(JSONObject(row.toString()).put("actualDb",actual).put("residualDb",actual-expected).put("reopenedDb",restored))
            }
            val before=v.exportSceneJson()
            assertFalse(v.importClfTabText(text.replace("<relative>","<absolute>"),"unsupported"))
            assertTrue(v.lastImportError.value!!.contains("unsupported balloon reference"))
            assertEquals(before,v.exportSceneJson())
        }
        val report=File(System.getProperty("externalClfMagnitudeReport"));report.parentFile.mkdirs()
        report.writeText(JSONObject().put("scope",reference.getString("scope")).put("allPassed",true)
            .put("failedImportPreservedProject",true).put("cases",reports).toString(2)+"\n")
    }

    @Test fun declaredAsymmetricTabAndCf2ReachAndroidPredictionsAndSurviveDiskReopen() {
        val ref=JSONObject(dir.resolve("results/reference.json").readText())
        val provenance=JSONObject(dir.resolve("provenance.json").readText())
        val hashes=provenance.getJSONObject("fixtureSha256")
        hashes.keys().forEach {name->assertEquals(hashes.getString(name),SpeakerProvenance.hash(dir.resolve("fixtures/$name").readBytes()))}
        val reports=JSONArray()
        for(format in listOf("TAB","CF2")) {
            val v=model()
            val imported=if(format=="TAB") v.importClfTabText(dir.resolve("fixtures/"+provenance.optString("textFile","official-1000.tab")).readText(),"fixture")
                else v.importClfBinary(dir.resolve("fixtures/"+provenance.optString("binaryFile","Passive2.CF2")).readBytes(),"fixture")
            assertTrue("$format import: ${v.lastImportError.value}",imported)
            v.addSpeaker(2f,-4f);v.setSignalType("BAND");v.setBandHz(1000);v.setSignalDispersionEnabled(true)
            // Translate the entire reference scene upward to keep downward test
            // rays above the Android default stage/floor; isolate import orientation.
            v.setSpeakerPosition(v.speakers.value.single().id,2f,15f,-4f)
            assertEquals("clf-subset-v5:$format",v.getClfData("fixture")!!.provenance.importer)
            val store=LocalProjectStore(folder.newFolder(format))
            // Save each orientation, so recovery tests cover stored pan and mechanical tilt,
            // not only an asset round trip followed by another edit.
            val points=ref.getJSONArray("cases")
            val poses=(0 until points.length()).map {points.getJSONObject(it)}.groupBy {it.getDouble("yaw").toFloat() to it.getDouble("tiltDown").toFloat()}
            for((pose,cases) in poses) {
                v.setSpeakerPan(v.speakers.value.single().id,pose.first)
                v.setSpeakerArrayAim(v.speakers.value.single().id,pose.second)
                store.save("pose",v.exportSceneJson(false),v::resolveProjectAsset)
                val fresh=model();assertTrue(fresh.importPreparedProject(store.loadPrepared("pose")!!.project))
                assertTrue(fresh.assetRecoveryProblems.value.isEmpty())
                for(element in cases) {
                    val row=element
                    fun cell(key:String)=row.getJSONArray(key).let {HeatCell(it.getDouble(0).toFloat(),it.getDouble(2).toFloat(),Float.NaN,renderY=it.getDouble(1).toFloat()+12f)}
                    fun level(vm:SceneViewModel):Float {
                        val out=vm.capturePredictionRequest(PredictionTicket(vm.revision.value,1,PredictionChannel.COVERAGE),
                            vm.speakers.value,listOf(cell("receiver"),cell("onAxis"))).calculate()
                        return out.levelAt(0)-out.levelAt(1)
                    }
                    val actual=level(v);val reopened=level(fresh)
                    val expected=row.getDouble("relativeDb").toFloat()
                    assertEquals("$format $row",expected,actual,.001f);assertEquals(actual,reopened,0f)
                    reports.put(JSONObject(row.toString()).put("format",format).put("sceneVerticalTranslationM",12).put("actualDb",actual)
                        .put("residualDb",actual-expected).put("reopenedDb",reopened))
                }
            }
        }
        val out=File(System.getProperty("externalClfHandednessReport"));out.parentFile.mkdirs()
        out.writeText(JSONObject().put("scope",ref.getString("scope"))
            .put("referenceSha256",SpeakerProvenance.hash(dir.resolve("results/reference.json").readBytes()))
            .put("cases",reports).put("allPassed",true).toString(2)+"\n")
    }
}
