package com.droidacoustic.pro.ui

import com.droidacoustic.pro.prediction.ReceiverAngularReport

/** Rendering adapter only; positions, marker geometry and classification are shared. */
object ReceiverAngularGlb {
    fun build(report:ReceiverAngularReport?,selectedId:String?=null):ByteArray? {
        val lines=report?.markerLines(selectedId).orEmpty();if(lines.isEmpty()) return null
        val positions=FloatArray(lines.size*6);val colors=FloatArray(lines.size*8)
        lines.forEachIndexed {i,l->
            listOf(l.from,l.to).forEachIndexed {j,p->
                positions[i*6+j*3]=p.x.toFloat();positions[i*6+j*3+1]=p.y.toFloat();positions[i*6+j*3+2]=p.z.toFloat()
                colors[i*8+j*4]=((l.colorRgb shr 16) and 255)/255f;colors[i*8+j*4+1]=((l.colorRgb shr 8) and 255)/255f
                colors[i*8+j*4+2]=(l.colorRgb and 255)/255f;colors[i*8+j*4+3]=1f
            }
        }
        return AimRaysGlb.assemble(positions,colors,lines.size*2)
    }
}
