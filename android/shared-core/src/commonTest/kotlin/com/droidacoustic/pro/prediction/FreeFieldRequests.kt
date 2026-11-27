package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*

/** Test setup only: all levels are calculated by the production immutable request. */
internal object FreeFieldRequests {
    val octaveBands=listOf(63,125,250,500,1000,2000,4000,8000)
    fun source(level:Float=100f,gain:Float=0f,eq:Map<Int,Float> = emptyMap(),
        yaw:Float=0f,tilt:Float=0f,dispersion:Boolean=false,elements:Int=1,
        delay:Double=0.0,inverted:Boolean=false,id:Int=0)=SourceSnapshot(
        id,0f,0f,0f,yaw,List(elements){ArrayGeometry.Pose(0f,0f,tilt,0f)},0f,0f,
        FrozenResponse.generic(level,"point_source",false,dispersion),gain,delay,inverted,eq)
    fun request(sources:List<SourceSnapshot>,receivers:List<ReceiverSnapshot>,band:Int=1000,
        air:Atmosphere=Atmosphere())=PredictionRequest(PredictionTicket(0,0,PredictionChannel.COVERAGE),
            sources,receivers,band,listOf(band),1f/24,emptyMap(),true,air)
    fun level(distance:Float=12f,band:Int=1000,sensitivity:Float=100f,gain:Float=0f,
        eq:Map<Int,Float> = emptyMap())=request(listOf(source(sensitivity,gain,eq)),
            listOf(ReceiverSnapshot(distance,0f,0f)),band).calculate().levelAt(0)
}
