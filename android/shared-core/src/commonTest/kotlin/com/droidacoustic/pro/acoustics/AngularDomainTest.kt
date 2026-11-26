package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AngularDomainTest {
    private val az=listOf(0f,5f,10f,350f)
    private val el=listOf(0f)
    private fun block(origin: String,a: List<Int>,operation: String?=null,method: String?=null)=buildJsonObject {
        put("origin",origin);put("azimuths",JsonArray(a.map(::JsonPrimitive)));put("elevations",JsonArray(listOf(JsonPrimitive(0))))
        put("basis","TEST ONLY provenance declaration");operation?.let {put("operation",it)};method?.let {put("method",it)}
    }
    private fun text(regions: List<JsonObject>)=buildJsonObject {
        put("version",1);put("provenance","TEST ONLY; angular origin, not an accuracy certificate");put("regions",JsonArray(regions))
    }.toString()
    private fun declaration()=AngularDomain.parse(text(listOf(block("MEASURED",listOf(0,10)),
        block("SYMMETRY",listOf(350),"MIRROR_AZIMUTH_ZERO"),block("INTERPOLATED",listOf(5),method="DB_LINEAR_ANGULAR"))),az,el)
    private fun data(d: AngularDomain?=declaration())=ClfData("test",patterns=listOf(ClfPolarPattern(1000,az,el,Array(az.size) {floatArrayOf(0f)},d)),sensitivityDbByBand=mapOf(1000 to 90f))
    @Test fun storedOriginsAndRuntimeInterpolationRemainSeparate() {
        val d=data()
        assertEquals("MEASURED",SpeakerAngularSupport.at(d,1000,0f,0f)!!.origin)
        assertEquals("SYMMETRY",SpeakerAngularSupport.at(d,1000,-10f,0f)!!.origin)
        assertEquals("INTERPOLATED",SpeakerAngularSupport.at(d,1000,5f,0f)!!.origin)
        assertFalse(SpeakerAngularSupport.at(d,1000,5f,0f)!!.runtimeInterpolation)
        val between=SpeakerAngularSupport.at(d,1000,2.5f,0f)!!
        assertEquals("INTERPOLATED",between.origin);assertTrue(between.runtimeInterpolation)
        assertEquals(listOf(AngularSampleOrigin.MEASURED,AngularSampleOrigin.INTERPOLATED),between.contributors)
        assertEquals("UNAVAILABLE",SpeakerAngularSupport.at(d,1000,0f,1f)!!.origin)
        assertNull(SpeakerAngularSupport.at(d,800,0f,0f))
        assertEquals("UNDECLARED",SpeakerAngularSupport.at(data(null),1000,0f,0f)!!.origin)
        val signedZero=PolarGrid(listOf(0f),listOf(-0.0f),listOf(listOf(0f)))
        assertEquals("UNDECLARED",signedZero.angularDomainAt(0f,0f).origin)
    }
    @Test fun declarationsRoundTripChangeAssetIdentityButDoNotChangeResponseValues() {
        val original=data();val decoded=ClfParser.parseJson(ClfParser.toJson(original))!!
        assertEquals(original,decoded)
        assertEquals(SpeakerAngularSupport.reportJson(original),SpeakerAngularSupport.reportJson(decoded))
        assertNotEquals(SpeakerProvenance.hash(ClfParser.toJson(original).encodeToByteArray()),SpeakerProvenance.hash(ClfParser.toJson(data(null)).encodeToByteArray()))
        for(a in listOf(-10f,0f,2.5f,5f,90f)) assertEquals(data(null).measuredRadiator().levelDb(1000,a,0f),original.measuredRadiator().levelDb(1000,a,0f))
        assertFalse(ClfParser.toJson(data(null)).contains("angularDomain"))
        assertTrue(SpeakerAngularSupport.warnings(original).any {"symmetry-derived" in it})
        assertTrue(SpeakerAngularSupport.warnings(original).any {"pre-interpolated" in it})
    }
    @Test fun invalidSelectorsOverlapsUnsupportedMethodsAndCyclesAreRejected() {
        val measured=block("MEASURED",listOf(0,10))
        val bad=listOf(listOf(measured,measured),listOf(block("MEASURED",listOf(360,0))),listOf(block("MEASURED",listOf(90))),
            listOf(block("SYMMETRY",listOf(350),"MIRROR_AZIMUTH_ZERO")),
            listOf(block("SYMMETRY",listOf(10,350),"MIRROR_AZIMUTH_ZERO")),
            listOf(block("INTERPOLATED",listOf(5),method="CARTESIAN_LINEAR_ANGULAR")),
            listOf(block("MEASURED",listOf(0)).let {JsonObject(it+("ignoredField" to JsonPrimitive(true)))}))
        bad.forEach {assertFails {AngularDomain.parse(text(it),az,el)}}
        val json=Json.parseToJsonElement(ClfParser.toJson(data(null))).jsonObject.toMutableMap()
        val pattern=json.getValue("patterns").jsonArray.single().jsonObject
        json["patterns"]=JsonArray(listOf(JsonObject(pattern+("angularDomain" to Json.parseToJsonElement(text(listOf(measured,measured)))))))
        assertNull(ClfParser.parseJson(JsonObject(json).toString()))
    }
    @Test fun partialDeclarationsDoNotFillUnknownNodesAndElevationMirrorRequiresMeasuredSource() {
        val partial=AngularDomain.parse(text(listOf(block("MEASURED",listOf(0)))),az,el)
        val d=data(partial)
        assertEquals("UNDECLARED",SpeakerAngularSupport.at(d,1000,10f,0f)!!.origin)
        assertTrue(SpeakerAngularSupport.reportJson(d).contains("DECLARED_PARTIAL"))
        val e=listOf(-10f,10f)
        fun row(origin:String,elevation:Int)=buildJsonObject {
            put("origin",origin);put("azimuths",JsonArray(listOf(JsonPrimitive(0))));put("elevations",JsonArray(listOf(JsonPrimitive(elevation))))
            put("basis","TEST ONLY elevation mirror");if(origin=="SYMMETRY") put("operation","MIRROR_ELEVATION_ZERO")
        }
        val domain=AngularDomain.parse(text(listOf(row("MEASURED",10),row("SYMMETRY",-10))),listOf(0f),e)
        val grid=PolarGrid(listOf(0f),e,listOf(listOf(0f,0f)),domain)
        assertEquals("SYMMETRY",grid.angularDomainAt(0f,-10f).origin)
    }
    @Test fun complexDeclarationsAreIndependentAndCannotClaimMagnitudeInterpolationMethod() {
        fun complex(domain: String)=buildJsonObject {
            put("version",1);put("convention",ComplexSpeakerResponse.CONVENTION);put("provenance","test");put("referenceDrive","test")
            putJsonArray("patterns") {add(buildJsonObject {
                put("frequency",1000);put("azimuths",JsonArray(az.map(::JsonPrimitive)));put("elevations",JsonArray(el.map(::JsonPrimitive)))
                put("real",JsonArray(az.map {JsonArray(listOf(JsonPrimitive(1)))}));put("imaginary",JsonArray(az.map {JsonArray(listOf(JsonPrimitive(0)))}))
                put("angularDomain",Json.parseToJsonElement(domain))
            })}
        }.toString()
        val good=text(listOf(block("INTERPOLATED",listOf(0,5,10,350),method="CARTESIAN_LINEAR_ANGULAR")))
        val c=ComplexSpeakerResponse.fromJsonString(complex(good))
        assertEquals("INTERPOLATED",c.angularDomainAt(1000,0f,0f)!!.origin)
        assertEquals("INTERPOLATED",ComplexSpeakerResponse.fromJsonString(c.toJsonString()).angularDomainAt(1000,0f,0f)!!.origin)
        assertFails {ComplexSpeakerResponse.fromJsonString(complex(text(listOf(block("INTERPOLATED",listOf(5),method="DB_LINEAR_ANGULAR")))))}
        val both=data().copy(complexResponse=c)
        assertEquals("MEASURED",SpeakerAngularSupport.at(both,1000,0f,0f)!!.origin)
        assertEquals("INTERPOLATED",SpeakerAngularSupport.at(both,1000,0f,0f,"complex")!!.origin)
    }
}
