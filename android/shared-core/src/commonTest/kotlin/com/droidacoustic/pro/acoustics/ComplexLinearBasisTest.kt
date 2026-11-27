package com.droidacoustic.pro.acoustics

import kotlin.test.*

class ComplexLinearBasisTest {
    private fun response(gap:Int=300):ComplexSpeakerResponse {
        val rows=listOf(900,1100,1300).joinToString {hz ->
            fun values(imaginary:Boolean)=listOf(0,45,180,270).joinToString(prefix="[",postfix="]") {az->
                listOf(-90,0,90).joinToString(prefix="[",postfix="]") {el->
                    (if(imaginary) (hz-1050)*.17+az*.03-el*.21 else hz*.19-az*.27+el*.11).toString()
                }
            }
            """{"frequency":$hz,"azimuths":[0,45,180,270],"elevations":[-90,0,90],"real":${values(false)},"imaginary":${values(true)}}"""
        }
        return ComplexSpeakerResponse.fromJsonString("""{"version":2,"convention":"${ComplexSpeakerResponse.CONVENTION}","provenance":"Asymmetric basis fixture","referenceDrive":"1 V rms","frequencyInterpolation":"cartesian-linear-hz","maximumInterpolationGapHz":$gap,"patterns":[$rows]}""")
    }
    @Test fun sharedEndpointReadsMatchScalarLookupExactlyAtMeasuredAndInteriorEdges() {
        val r=response()
        for((low,high) in listOf(900.0 to 1100.0,900.0 to 1050.0,950.0 to 1100.0,950.0 to 1050.0,1100.0 to 1300.0))
        for(az in listOf(0f,12f,80f,270f,-20f)) for(el in listOf(-80f,-1f,31f)) {
            val a=r.bandAt(low,az,el)!!;val b=r.bandAt(high,az,el)!!
            val out=DoubleArray(8){-777.0}
            assertTrue(r.linearBandBasis(low,high,az,el,out,2))
            assertContentEquals(doubleArrayOf(-777.0,-777.0,a.real,a.imaginary,b.real-a.real,b.imaginary-a.imaginary,-777.0,-777.0),out)
        }
    }
    @Test fun gapsAndIntervalsCrossingKnotsLeaveTheBasisUnavailableAndUntouched() {
        for((r,range) in listOf(response(100) to (900.0 to 1100.0),response() to (1000.0 to 1200.0),
            response() to (850.0 to 1000.0),response() to (1000.0 to 1350.0),response() to (1000.0 to 1000.0))) {
            val out=DoubleArray(4){-777.0}
            assertFalse(r.linearBandBasis(range.first,range.second,0f,0f,out,0))
            assertContentEquals(DoubleArray(4){-777.0},out)
        }
    }
    @Test fun customFallbackAndDisabledMeasuredDirectivityKeepTheirSemantics() {
        val r=response();val measured=MeasuredRadiator(emptyMap(),emptyMap(),complexResponse=r)
        val custom=object:Radiator {
            override fun levelDb(dataHz:Int,azimuthDeg:Float,elevationDeg:Float)=0f
            override fun complexBandAt(hz:Double,azimuthDeg:Float,elevationDeg:Float)=r.bandAt(hz,azimuthDeg,elevationDeg)
        }
        val expected=DoubleArray(4);val actual=DoubleArray(4)
        assertTrue(custom.complexBandBasis(950.0,1050.0,12f,31f,expected,0))
        assertTrue(measured.complexBandBasis(950.0,1050.0,12f,31f,actual,0));assertContentEquals(expected,actual)
        val bypass=MeasuredRadiator(emptyMap(),emptyMap(),useDirectivity=false,complexResponse=r)
        assertFalse(bypass.complexBandBasis(950.0,1050.0,12f,31f,actual,0))
    }
}
