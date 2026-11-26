package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.*
import kotlin.math.max

internal data class PropagationArrival(val emitter:Int,val distance:Double,val time:Double,val az:Float,val el:Float,
    val floorPath:Boolean,val bounce:BoundaryPoint?,val materials:Set<BoundaryMaterial>,val incidenceDeg:Float)

/** One first-order path enumeration for magnitude and complex phase, including visibility
 * and coincident Block/Face/ground ownership. */
internal object SpecularPaths {
    fun prepare(sources:List<SoundSource>,receiver:BoundaryPoint,atmosphere:Atmosphere,
        floor:FloorReflection?,boundaries:BoundaryGeometry?,boundaryWork:BoundaryWork):List<PropagationArrival> {
        val px=receiver.x.toFloat();val py=receiver.y.toFloat();val pz=receiver.z.toFloat()
        val arrivals=mutableListOf<PropagationArrival>()
        val scratch=DoubleArray(3)
        fun sourcePoint(s: SoundSource)=BoundaryPoint(s.x.toDouble(),s.y.toDouble(),s.z.toDouble())
        fun append(i: Int,image: BoundaryPoint,floorPath: Boolean=false,bounce: BoundaryPoint?=null,materials: Set<BoundaryMaterial> = emptySet(),incidenceDeg: Float=0f) {
            val s=sources[i]
            s.toward(image.x.toFloat(),image.y.toFloat(),image.z.toFloat(),scratch)
            arrivals+=PropagationArrival(i,max(scratch[0],DirectField.MIN_DISTANCE_M),scratch[0]/atmosphere.speedOfSound+s.delayS,
                scratch[1].toFloat(),scratch[2].toFloat(),floorPath,bounce,materials,incidenceDeg)
        }
        for (i in sources.indices) {
            boundaryWork.check()
            if (boundaries?.blocked(sourcePoint(sources[i]),receiver,boundaryWork)!=true) append(i,receiver)
        }
        if (floor!=null) for (i in sources.indices) {
            val s=sources[i]; val sum=s.y.toDouble()+py
            val t=if (sum>1e-12) s.y/sum else 0.0
            val bounce=BoundaryPoint(s.x+(px.toDouble()-s.x)*t,0.0,s.z+(pz.toDouble()-s.z)*t)
            if (boundaries?.suppressesGroundBounce(bounce,boundaryWork)!=true &&
                boundaries?.blocked(sourcePoint(s),bounce,boundaryWork)!=true &&
                boundaries?.blocked(bounce,receiver,boundaryWork)!=true)
                append(i,BoundaryPoint(px.toDouble(),-py.toDouble(),pz.toDouble()),true,bounce,incidenceDeg=(kotlin.math.atan2(kotlin.math.hypot(px.toDouble()-s.x,pz.toDouble()-s.z),sum)*180/kotlin.math.PI).toFloat())
        }
        if (boundaries!=null) for (i in sources.indices) {
            for (path in boundaries.reflections(sourcePoint(sources[i]),receiver,boundaryWork)) {
                // The global ground model owns the y=0 image when enabled; never count it twice.
                if (floor!=null && kotlin.math.abs(path.imageReceiver.x-px)<1e-7 &&
                    kotlin.math.abs(path.imageReceiver.y+py)<1e-7 && kotlin.math.abs(path.imageReceiver.z-pz)<1e-7) continue
                append(i,path.imageReceiver,bounce=path.bounce,materials=path.materials,incidenceDeg=path.incidenceDeg)
            }
        }
        return arrivals
    }
}
