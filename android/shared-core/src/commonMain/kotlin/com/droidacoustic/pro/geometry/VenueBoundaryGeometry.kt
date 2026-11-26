package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.scene.VenueBlock
import com.droidacoustic.pro.scene.VenueGeometry
import kotlin.math.*

/** Canonical physical geometry, shared by prediction and platform renderers. +Y up, metres. */
data class BoundaryShape(val vertices: List<BoundaryPoint>, val triangles: List<SurfaceTriangle>)

object VenueBoundaryGeometry {
    private val boxFaces=listOf(SurfaceTriangle(0,2,1),SurfaceTriangle(0,3,2),
        SurfaceTriangle(4,5,6),SurfaceTriangle(4,6,7),SurfaceTriangle(0,1,5),SurfaceTriangle(0,5,4),
        SurfaceTriangle(1,2,6),SurfaceTriangle(1,6,5),SurfaceTriangle(2,3,7),SurfaceTriangle(2,7,6),
        SurfaceTriangle(3,0,4),SurfaceTriangle(3,4,7))

    fun block(block: VenueBlock): BoundaryShape {
        require(listOf(block.centerX,block.centerZ,block.widthM,block.depthM,block.heightM,
            block.blockHeightM,block.slopeDeg,block.rotationDeg).all {it.isFinite()} &&
            block.widthM>0 && block.depthM>0 && block.blockHeightM>0 && abs(block.slopeDeg)<89) {"Invalid block boundary geometry"}
        val slope=tan(block.slopeDeg.toDouble()*PI/180)
        val yaw=block.rotationDeg.toDouble()*PI/180; val c=cos(yaw); val s=sin(yaw)
        val points=listOf(-1 to -1,1 to -1,1 to 1,-1 to 1).map {(dx,dz) ->
            val x=dx*block.widthM.toDouble()/2; val z=dz*block.depthM.toDouble()/2
            // Mesh heights are Float; use the same representation so an exported/reimported shell is coincident.
            BoundaryPoint(block.centerX+x*c-z*s,(block.heightM+slope*z).toFloat().toDouble(),block.centerZ+x*s+z*c)
        }
        return BoundaryShape(points.map {it.copy(y=(it.y-block.blockHeightM).toFloat().toDouble())}+points,boxFaces)
    }

    /** The built-in stage extends from y=0 to its sloping top; clip at zero thickness. */
    fun stage(venue: VenueGeometry): BoundaryShape {
        require(listOf(venue.stageCenterX,venue.stageCenterZ,venue.stageWidthM,venue.stageDepthM,
            venue.stageHeightM,venue.stageSlopeDeg).all {it.isFinite()} && venue.stageWidthM>0 &&
            venue.stageDepthM>0 && venue.stageHeightM>=0 && abs(venue.stageSlopeDeg)<89) {"Invalid stage boundary geometry"}
        val slope=tan(venue.stageSlopeDeg.toDouble()*PI/180)
        var z0= -venue.stageDepthM.toDouble()/2; var z1= -z0
        if (slope>0) z0=max(z0,-venue.stageHeightM/slope)
        if (slope<0) z1=min(z1,-venue.stageHeightM/slope)
        if (z0>=z1 || (venue.stageHeightM==0f && slope==0.0)) return BoundaryShape(emptyList(),emptyList())
        val x0=venue.stageCenterX-venue.stageWidthM.toDouble()/2; val x1=venue.stageCenterX+venue.stageWidthM.toDouble()/2
        val bottom=listOf(x0 to z0,x1 to z0,x1 to z1,x0 to z1).map {(x,z)->BoundaryPoint(x,0.0,venue.stageCenterZ+z)}
        val points=bottom+bottom.map {it.copy(y=max(0.0,venue.stageHeightM+slope*(it.z-venue.stageCenterZ)).toFloat().toDouble())}
        return BoundaryShape(points,boxFaces.filter {t ->
            (points[t.b].point()-points[t.a].point()).cross(points[t.c].point()-points[t.a].point()).length()>1e-12
        })
    }
}
