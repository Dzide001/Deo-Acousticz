package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlin.math.*

/** Conservative O(N) enclosure of every correlated pair's path-difference variation. */
internal class IncidentPhaseEnvelope(capacity:Int) {
    private val bounds=DoubleArray(capacity*7)
    private val first=arrayOfNulls<BoundaryPoint>(capacity)
    private val distinct=BooleanArray(capacity)
    var additions=0
        private set
    fun clear() {first.fill(null);distinct.fill(false);additions=0}
    fun add(group:Int,position:BoundaryPoint,gx:Double,gy:Double,gz:Double,inverseMinimumDistance:Double) {
        additions++;val offset=group*7
        if(first[group]==null) {
            first[group]=position
            bounds[offset]=gx;bounds[offset+1]=gx
            bounds[offset+2]=gy;bounds[offset+3]=gy
            bounds[offset+4]=gz;bounds[offset+5]=gz
            bounds[offset+6]=inverseMinimumDistance
        } else {
            if(position!=first[group]) distinct[group]=true
            bounds[offset]=min(bounds[offset],gx);bounds[offset+1]=max(bounds[offset+1],gx)
            bounds[offset+2]=min(bounds[offset+2],gy);bounds[offset+3]=max(bounds[offset+3],gy)
            bounds[offset+4]=min(bounds[offset+4],gz);bounds[offset+5]=max(bounds[offset+5],gz)
            bounds[offset+6]=max(bounds[offset+6],inverseMinimumDistance)
        }
    }
    fun maximumSpan(radius:Double):Double {
        var span=0.0
        for(group in first.indices) if(distinct[group]) {
            val offset=group*7
            val gx=bounds[offset+1]-bounds[offset];val gy=bounds[offset+3]-bounds[offset+2];val gz=bounds[offset+5]-bounds[offset+4]
            // All pair gradients lie inside this box; both distance Hessians are <= max inverse distance.
            span=max(span,2*radius*(sqrt(gx*gx+gy*gy+gz*gz)+2*radius*bounds[offset+6]))
        }
        return span
    }
}
