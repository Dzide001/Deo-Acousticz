package com.droidacoustic.pro.acoustics

import kotlin.math.*
import kotlin.test.*

class ComplexBandFilterTest {
    private val air=Atmosphere()
    private val segments=listOf(Bands.Segment(1000,900.0,1100.0))
    private fun response(real1:Double=100.0,real2:Double=100.0,imaginary:Double=0.0,gap:Double=200.0):ComplexSpeakerResponse {
        val rows=listOf(900 to real1,1100 to real2).joinToString { (hz,r)->"""{"frequency":$hz,"azimuths":[0],"elevations":[-90,90],"real":[[$r,$r]],"imaginary":[[$imaginary,$imaginary]]}""" }
        return ComplexSpeakerResponse.fromJsonString("""{"version":2,"convention":"${ComplexSpeakerResponse.CONVENTION}","provenance":"analytic fixture","referenceDrive":"1 V rms","frequencyInterpolation":"cartesian-linear-hz","maximumInterpolationGapHz":$gap,"patterns":[$rows]}""")
    }
    private fun source(response:ComplexSpeakerResponse,filters:DspFilterChain=DspFilterChain(),delay:Double=0.0,inverted:Boolean=false)=
        SoundSource(0,0f,1f,0f,0f,0f,MeasuredRadiator(emptyMap(),emptyMap(),complexResponse=response),delayS=delay,inverted=inverted,filters=filters)
    private fun power(s:List<SoundSource>,floor:FloorReflection?=null)=DirectField(air).bandPower(s,1f,1f,0f,segments,floor=floor)
    @Test fun bandInterpolationIsExplicitBoundedAndPortable() {
        val r=response(100.0,200.0)
        assertEquals(150.0,r.bandAt(1000.0,0f,0f)!!.real,1e-9)
        assertNull(r.bandAt(899.9,0f,0f));assertNull(r.bandAt(1100.1,0f,0f))
        assertNull(response(gap=100.0).bandAt(1000.0,0f,0f))
        val restored=ComplexSpeakerResponse.fromJsonString(r.toJsonString())
        assertEquals(r.bandAt(1020.5,0f,0f),restored.bandAt(1020.5,0f,0f))
        assertFails {ComplexSpeakerResponse.fromJsonString(r.toJsonString().replace("cartesian-linear-hz","guess-phase"))}
        val legacy=ComplexSpeakerResponse.fromJsonString(r.toJsonString().replace("\"version\":2","\"version\":1"))
        assertNull(legacy.bandAt(1000.0,0f,0f))
    }
    @Test fun complexRampBandMatchesIndependentDenseIntegralAndDoesNotRequireMagnitudeTable() {
        val measured=power(listOf(source(response(100.0,200.0))))
        val n=20000
        val expected=(0 until n).sumOf {i ->
            val f=900+(i+.5)*200/n;val amplitude=100+(f-900)/2
            amplitude*amplitude*10.0.pow(-air.absorptionDbPerMetre(f)/10)
        }/n
        assertEquals(expected,measured,expected*2e-5)
        assertTrue(power(listOf(source(response(gap=100.0)))).isNaN())
    }
    @Test fun intrinsicPhasePolarityDelayAndRigidGroundAreIntegratedWithoutDoubleCounting() {
        val a=source(response());val quadrature=source(response(0.0,0.0,100.0))
        assertEquals(2.0,power(listOf(a,quadrature))/power(listOf(a)),2e-5)
        assertEquals(0.0,power(listOf(a,source(response(),inverted=true))),1e-8)
        val delayed=source(response(),delay=.00025)
        val expected=(0 until 20000).sumOf {i ->
            val f=900+(i+.5)*.01
            10000*10.0.pow(-air.absorptionDbPerMetre(f)/10)*(2+2*cos(2*PI*f*.00025))
        }/20000
        assertEquals(expected,power(listOf(a,delayed)),expected*2e-5)
        val floor=FloorReflection {0f};val distance=sqrt(5.0)
        val reflected=(0 until 20000).sumOf {i ->
            val f=900+(i+.5)*.01
            val direct=100*10.0.pow(-air.absorptionDbPerMetre(f)/20)
            val image=100/distance*10.0.pow(-air.absorptionDbPerMetre(f)*distance/20)
            direct*direct+image*image+2*direct*image*cos(2*PI*f*(distance-1)/air.speedOfSound)
        }/20000
        assertEquals(reflected,power(listOf(a),floor),reflected*2e-5)
    }
    @Test fun digitalCutoffPhasePeakingInverseAndFourthOrderCrossover() {
        val lp=DspFilter(FilterKind.LOW_PASS,1000.0);val hp=DspFilter(FilterKind.HIGH_PASS,1000.0)
        assertEquals(.5,lp.at(1000.0)!!.power,1e-10)
        assertEquals(90.0,atan2(lp.at(1000.0)!!.imaginary,lp.at(1000.0)!!.real)*180/PI,1e-8)
        assertEquals(-90.0,atan2(hp.at(1000.0)!!.imaginary,hp.at(1000.0)!!.real)*180/PI,1e-8)
        val peak=DspFilter(FilterKind.PEAK,1000.0,3.0,12.0)
        assertEquals(12.0,10*log10(peak.at(1000.0)!!.power),1e-8)
        val inverse=DspFilterChain(listOf(peak,peak.copy(gainDb=-12.0)))
        for(hz in listOf(20.0,500.0,1000.0,4000.0,20000.0)) {
            assertEquals(1.0,inverse.at(hz)!!.real,1e-10);assertEquals(0.0,inverse.at(hz)!!.imaginary,1e-10)
            val sum=lp.at(hz)!!*lp.at(hz)!!+hp.at(hz)!!*hp.at(hz)!!
            assertEquals(1.0,sum.power,1e-10)
        }
        val reference=power(listOf(source(response())))
        val combined=power(listOf(source(response(),DspFilterChain(listOf(lp,lp))),source(response(),DspFilterChain(listOf(hp,hp)))))
        assertEquals(reference,combined,reference*2e-5)
        assertNull(lp.at(24000.0));assertFails {lp.copy(q=0.0)}
        assertEquals(inverse,DspFilterChain.fromJsonString(inverse.toJsonString()))
    }
    @Test fun highQPeakConvergesToIndependentDenseReferenceAndLegacyMagnitudeGetsFilterPhase() {
        val f=DspFilter(FilterKind.PEAK,1007.0,20.0,24.0)
        val result=power(listOf(source(response(),DspFilterChain(listOf(f)))))
        val expected=(0 until 20000).sumOf {i->val hz=900+(i+.5)*.01
            10000*f.at(hz)!!.power*10.0.pow(-air.absorptionDbPerMetre(hz)/10)}/20000
        assertEquals(expected,result,expected*2e-5)
        val generic=SoundSource(0,0f,1f,0f,0f,0f,Radiator {_,_,_->40f},filters=DspFilterChain(listOf(f)))
        assertEquals(result,power(listOf(generic)),result*2e-5)
        assertFailsWith<IllegalStateException> {DirectField().predictBand(listOf(generic),1f,1f,0f,segments,boundaryWork=com.droidacoustic.pro.geometry.BoundaryWork({error("cancel")}))}
    }
    @Test fun subdivisionAndIndependentSourcePowerAreStableAndUnknownPhaseIsRejected() {
        val a=source(response(100.0,200.0));val b=source(response(0.0,0.0,100.0))
        val split=(0 until 20).map {Bands.Segment(1000,900.0+it*10,910.0+it*10)}
        val whole=power(listOf(a,b))
        assertEquals(whole,DirectField(air).bandPower(listOf(a,b),1f,1f,0f,split),whole*3e-5)
        assertEquals(power(listOf(a))+power(listOf(b)),DirectField(air).bandPower(listOf(a,b),1f,1f,0f,segments,coherent=false),whole*3e-5)
        val unknown=SoundSource(1,0f,1f,0f,0f,0f,Radiator {_,_,_->40f})
        assertTrue(power(listOf(a,unknown)).isNaN())
        assertTrue(power(listOf(a),FloorReflection {.5f}).isNaN())
    }

}
