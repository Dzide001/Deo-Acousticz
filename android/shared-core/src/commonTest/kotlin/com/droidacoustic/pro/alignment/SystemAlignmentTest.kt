package com.droidacoustic.pro.alignment
import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.test.*

class SystemAlignmentTest {
    private val receiver=AlignmentReceiver("FOH",BoundaryPoint(0.0,0.0,0.0))
    private fun source(id:Int,x:Float,y:Float=0f,z:Float=0f,delay:Double=0.0)=
        AlignmentSource(id,listOf(SoundSource(id,x,y,z,0f,0f,Radiator {_,_,_->90f},delayS=delay/1000)),delay)
    @Test fun threeDimensionalDistanceAndExistingDelayDetermineSignedCorrection() {
        val sources=listOf(source(1,3f,4f,delay=10.0),source(2,1f,2f,2f,delay=2.0))
        val row=SystemAlignment.compare(receiver,sources,1,1000.0)[1]
        assertEquals(5.0,row.earliestMs,1e-9)
        assertEquals(-10.0,row.relativeArrivalMs,1e-9)
        assertEquals(12.0,row.proposedDelayMs!!,1e-9)
        val aligned=SystemAlignment.compare(receiver,listOf(sources[0],source(2,1f,2f,2f,12.0)),1,1000.0)[1]
        assertEquals(0.0,aligned.relativeArrivalMs,1e-9)
    }
    @Test fun negativeAndOverLimitDelaysAreNotSilentlyClamped() {
        assertNull(SystemAlignment.compare(receiver,listOf(source(1,1f),source(2,10f)),1,1000.0)[1].proposedDelayMs)
        assertNull(SystemAlignment.compare(receiver,listOf(source(1,1000f),source(2,1f)),1,1000.0)[1].proposedDelayMs)
        val reduced=SystemAlignment.compare(receiver,listOf(source(1,1f,delay=10.0),source(2,2f,delay=20.0)),1,1000.0)[1]
        assertEquals(9.0,reduced.proposedDelayMs!!,1e-9)
    }
    @Test fun arraysRetainPerElementSteeringAndPhysicalArrivalSpread() {
        val poses=ArrayGeometry.poses(4,.3f,10f,List(3){2f})
        val emitters=ArrayGeometry.sources(poses,1,10f,3f,0f,0f,15f,343.0,Radiator {_,_,_->90f},delayS=.005)
        val result=SystemAlignment.compare(receiver,listOf(AlignmentSource(1,emitters,5.0)),1,343.0).single()
        val expected=emitters.map {kotlin.math.sqrt((it.x*it.x+it.y*it.y+it.z*it.z).toDouble())/343*1000+it.delayS*1000}
        assertEquals(expected.min(),result.earliestMs,1e-5)
        assertEquals(expected.max(),result.latestMs,1e-5)
        assertTrue(result.latestMs>result.earliestMs)
    }
    @Test fun speedHeightAndInvalidInputsAreHandledExplicitly() {
        val s=listOf(source(1,3f,4f))
        assertEquals(50.0,SystemAlignment.compare(receiver,s,1,100.0).single().earliestMs,1e-9)
        assertFails {SystemAlignment.compare(receiver,s,2,343.0)}
        assertFails {SystemAlignment.compare(receiver,s+s,1,343.0)}
        assertFails {SystemAlignment.compare(receiver,s,1,Double.NaN)}
        assertFails {AlignmentReceiver("Bad",BoundaryPoint(Double.NaN,0.0,0.0))}
    }
}
