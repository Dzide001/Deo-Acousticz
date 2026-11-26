package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.scene.*
import kotlin.math.*
import kotlin.test.*

/** Synthetic measured-data fixture, not manufacturer validation. No constant-grid fast path. */
class LargeVenueMeasuredArrayTest {
    private fun response(): ComplexSpeakerResponse {
        val az=listOf(0,8,10,12,14,18,45,90,180,270,342,348,358)
        val el=listOf(-90,-35,-12,-5,0,7,24,90)
        val patterns=listOf(3500,4000,4500,7000,8000,9000).joinToString { hz ->
            fun rows(imaginary:Boolean)=az.joinToString(prefix="[",postfix="]") { a ->
                el.joinToString(prefix="[",postfix="]") { e ->
                    val angle=min(a,360-a).toDouble()
                    val lobe=if(a==12) 1.0 else .0
                    val amplitude=10000*(.08+.7*exp(-(angle/40).pow(2)-(e/25.0).pow(2))+.4*lobe)*(1+.12*sin(hz/900.0))
                    val phase=.4*sin(a*PI/180)+.003*e+.2*cos(hz/1100.0)
                    (amplitude*if(imaginary) sin(phase) else cos(phase)).toString()
                }
            }
            """{"frequency":$hz,"azimuths":$az,"elevations":$el,"real":${rows(false)},"imaginary":${rows(true)}}"""
        }
        return ComplexSpeakerResponse.fromJsonString("""{"version":2,"convention":"${ComplexSpeakerResponse.CONVENTION}","referenceDrive":"1 V rms","provenance":"Synthetic nonuniform high-frequency benchmark","frequencyInterpolation":"cartesian-linear-hz","maximumInterpolationGapHz":1000,"patterns":[$patterns]}""")
    }

    @Test fun highFrequencyArraysMatchIndependentDenseComplexIntegralAtNearFarAndOffAxisReceivers() {
        val data=response();val r=MeasuredRadiator(emptyMap(),emptyMap(),complexResponse=data)
        val sources=List(64) {i->SoundSource(i/16,0f,6f+(i%16-7.5f)*.2f,listOf(-6f,6f,-12f,12f)[i/16],0f,0f,r,delayS=(i/16)*.0013)}
        val air=Atmosphere()
        for(hz in listOf(4000,8000)) for(point in listOf(Triple(15f,1.7f,0f),Triple(40f,1.7f,14f),Triple(75f,1.7f,-12f))) {
            val segments=Bands.segments(hz,1f/3f);val low=segments.first().f1;val high=segments.last().f2
            val geometry=sources.map {s ->
                val x=(point.first-s.x).toDouble();val y=(point.second-s.y).toDouble();val z=(point.third-s.z).toDouble()
                val d=sqrt(x*x+y*y+z*z)
                doubleArrayOf(d,atan2(z,x)*180/PI,asin(y/d)*180/PI)
            }
            fun dense(n:Int):Double {
                var sum=0.0
                repeat(n) {k ->
                    val f=low+(k+.5)*(high-low)/n;var re=0.0;var im=0.0
                    sources.forEachIndexed {i,s ->
                        val g=geometry[i];val p=data.bandAt(f,g[1].toFloat(),g[2].toFloat())!!
                        val scale=10.0.pow(-air.absorptionDbPerMetre(f)*g[0]/20)/g[0]
                        val angle=2*PI*f*(g[0]/air.speedOfSound+s.delayS)
                        re+=scale*(p.real*cos(angle)-p.imaginary*sin(angle));im+=scale*(p.real*sin(angle)+p.imaginary*cos(angle))
                    }
                    sum+=re*re+im*im
                }
                return sum/n
            }
            val reference=dense(8192);assertEquals(reference,dense(4096),reference*2e-5,"dense reference convergence")
            val actual=DirectField().predictBand(sources,point.first,point.second,point.third,segments).meanSquarePressure
            assertNotNull(actual);assertEquals(reference,actual,reference*5e-5,"$hz Hz at $point")
        }
        assertNull(DirectField().predictBand(sources,40f,1.7f,14f,Bands.segments(5000,1f/3f)).meanSquarePressure)
        assertNull(data.bandAt(6000.0,12f,0f)) // The intentionally missing frequency interval stays missing.
        val restored=ComplexSpeakerResponse.fromJsonString(data.toJsonString())
        for(hz in listOf(3550.0,4200.0,7100.0,8900.0)) for(a in listOf(-1f,11f,12f,13f,179f))
            assertEquals(data.bandAt(hz,a,-7f),restored.bandAt(hz,a,-7f))
    }
    @Test fun eightKilohertzScatteringWithNarrowDirectivityMatchesDenseSurfaceIntegral() {
        val data=response();val r=MeasuredRadiator(emptyMap(),emptyMap(),complexResponse=data)
        val src=List(32) {i->SoundSource(0,30f,(i-15.5f)*.1f,0f,168f,0f,r,delayS=(i%3)*.00001)}
        val material=BoundaryMaterial.custom("Benchmark",listOf(7000 to 0f,9000 to 0f))
            .withScattering(MaterialScattering.create(listOf(0f to listOf(7000 to 1f,9000 to 1f))))
        val wall=VenueBlock(0,widthM=2f,depthM=4f,heightM=2f,blockHeightM=4f,
            boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR,boundaryMaterial=material)
        val g=BoundaryGeometry.prepare(emptyList(),venue=VenueGeometry(stageHeightM=0f,blocks=listOf(wall)))
        val air=Atmosphere();val band=listOf(Bands.Segment(8000,7990.0,8010.0));val tone=Bands.Segment(8000,8000.0,8000.0)
        fun engine(capacity:Int)=CoherentDiffuse(src,BoundaryPoint(40.0,0.0,0.0),g,air,band,true,true,false,BoundaryWork(),capacity)
        val missing=mutableSetOf<MissingBand>();val cached=engine(65536)
        val actual=cached.bandPower(tone,missing,null,null)
        val cold=cached.geometryEvaluations
        assertTrue(cold>65536,"fixture must overflow the bounded geometry cache")
        assertEquals(actual,cached.bandPower(tone,missing,null,null),actual*1e-11)
        val warm=cached.geometryEvaluations-cold
        assertTrue(warm in 1 until cold,"overflow entries are recomputed, cached entries reused")
        assertEquals(actual,engine(0).bandPower(tone,missing,null,null),actual*1e-11)
        assertTrue(missing.isEmpty());assertTrue(actual>0)
        // Independent rectangular quadrature of the only visible face, x=1.
        // Interpolate speaker data, then sum incident complex flux before Lambertian propagation.
        fun dense(n:Int):Double {
            var total=0.0;val yaw=168*PI/180;val alpha=air.absorptionDbPerMetre(8000.0)
            repeat(n) {iy-> repeat(n) {iz->
                val y=-2+(iy+.5)*4/n;val z=-2+(iz+.5)*4/n;val dr=sqrt(39*39+y*y+z*z)
                var re=0.0;var im=0.0
                for(s in src) {
                    val dx=-29.0;val dy=y-s.y;val d=sqrt(dx*dx+dy*dy+z*z)
                    val az=(atan2(-sin(yaw)*dx+cos(yaw)*z,cos(yaw)*dx+sin(yaw)*z)*180/PI).toFloat()
                    val el=(asin(dy/d)*180/PI).toFloat();val p=data.bandAt(8000.0,az,el)!!
                    val scale=sqrt(29/d)/d*10.0.pow(-alpha*(d+dr)/20)
                    val phase=2*PI*8000*(d/air.speedOfSound+s.delayS)
                    re+=scale*(p.real*cos(phase)-p.imaginary*sin(phase));im+=scale*(p.real*sin(phase)+p.imaginary*cos(phase))
                }
                total+=(re*re+im*im)*39/(PI*dr.pow(3))*16/(n*n)
            }}
            return total
        }
        val reference=dense(240);assertEquals(reference,dense(120),reference*.003,"surface reference convergence")
        assertEquals(reference,actual,reference*.005,"8 kHz narrow-lobe scattering")
        println("8 kHz nonuniform scattering: adaptive=$actual dense=$reference geometry evaluations cold=$cold repeated=$warm")
    }

}
