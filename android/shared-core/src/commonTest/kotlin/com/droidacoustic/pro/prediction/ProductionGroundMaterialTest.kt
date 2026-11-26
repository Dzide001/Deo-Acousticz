package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.SurfaceMaterial
import kotlin.math.*
import kotlin.test.*

class ProductionGroundMaterialTest {
    private fun power(hz:Int,material:SurfaceMaterial?):Double {
        val source=SourceSnapshot(0,0f,0f,0f,0f,listOf(ArrayGeometry.Pose(0f,0f,0f,0f)),0f,0f,FrozenResponse.generic(100f,"generic",false,false))
        val floor=material?.let {m->Bands.THIRD_OCTAVE_HZ.associateWith {m.alphaAt(it)}}
        return PredictionRequest(PredictionTicket(1,1,PredictionChannel.COVERAGE),listOf(source),listOf(ReceiverSnapshot(10f,0f,0f)),
            hz,listOf(hz),1f/12,emptyMap(),true,Atmosphere(),floorAbsorption=floor,retainBandPowers=true).calculate().totalPowerAt(0)
    }
    @Test fun coincidentDirectAndImagePathsHaveTheAnalyticalMaterialBoost() {
        for(material in listOf(SurfaceMaterial.CARPET,SurfaceMaterial.ACOUSTIC_PANEL,SurfaceMaterial.flat(.4f)))
            for(hz in listOf(125,1000,4000)) {
                val expected=(1+sqrt(1.0-material.alphaAt(hz))).pow(2)
                assertEquals(expected,power(hz,material)/power(hz,null),1e-6,"${material.id} $hz Hz")
            }
    }
    @Test fun fullyAbsorbingGroundReturnsTheDirectFieldWithoutInventedRoomEnergy() {
        assertEquals(power(1000,null),power(1000,SurfaceMaterial.flat(1f)),0.0)
    }
}
