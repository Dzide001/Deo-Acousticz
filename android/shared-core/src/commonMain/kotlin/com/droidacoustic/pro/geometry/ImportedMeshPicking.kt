package com.droidacoustic.pro.geometry

import kotlin.math.*

/** Metres in the same +Y-up world coordinates as audience geometry. */
data class PickPoint3D(val x: Double,val y: Double,val z: Double) {
    internal fun point()=MeshPoint(x,y,z)
}

/** Distances are metres along the ray, independent of direction magnitude. */
data class ViewportRay(val origin: PickPoint3D,val direction: PickPoint3D,
    val minDistance: Double=0.0,val maxDistance: Double=Double.POSITIVE_INFINITY) {
    internal fun unitDirection(): MeshPoint? {
        val d=direction.point(); val length=d.length()
        return if (listOf(origin.x,origin.y,origin.z,d.x,d.y,d.z).all {it.isFinite()} &&
            length.isFinite() && length>1e-12 && minDistance.isFinite() && minDistance>=0 &&
            !maxDistance.isNaN() && maxDistance>=minDistance) d*(1/length) else null
    }
    companion object {
        /** Screen origin is top-left. Clipping planes are camera-axis distances, as in the renderer. */
        fun perspective(x: Double,y: Double,width: Double,height: Double,eye: PickPoint3D,
            target: PickPoint3D,up: PickPoint3D,verticalFovDegrees: Double,
            near: Double=0.1,far: Double=200.0): ViewportRay? {
            if (listOf(x,y,width,height,eye.x,eye.y,eye.z,target.x,target.y,target.z,up.x,up.y,up.z,
                    verticalFovDegrees,near,far).any {!it.isFinite()} || width<=0 || height<=0 ||
                x !in 0.0..width || y !in 0.0..height || verticalFovDegrees<=0 || verticalFovDegrees>=180 || near<0 || far<=near) return null
            fun unit(p: MeshPoint): MeshPoint? = p.length().let {if (it.isFinite() && it>1e-12) p*(1/it) else null}
            val forward=unit(target.point()-eye.point()) ?: return null
            val right=unit(forward.cross(up.point())) ?: return null
            val vertical=right.cross(forward)
            val scale=tan(verticalFovDegrees*PI/360)
            val direction=unit(forward+right*((2*x/width-1)*scale*width/height)+vertical*((1-2*y/height)*scale)) ?: return null
            val axial=direction.dot(forward)
            return ViewportRay(eye,PickPoint3D(direction.x,direction.y,direction.z),near/axial,far/axial)
        }
    }
}

data class ImportedFaceHit(val areaId: Int,val faceIndex: Int,val distanceM: Double,val position: PickPoint3D)
enum class FacePickMode { SINGLE, TOGGLE, FLAT_PATCH, GROUP }

/** Two-sided Möller–Trumbore intersection on original, transformed triangles.
 * No coverage, ear offsets or assignment filtering: excluded/context faces must remain editable.
 * Intrinsic openings have no triangles and are never filled. Plan cutouts do not delete source faces.
 */
object ImportedMeshPicking {
    fun pick(surfaces: List<AudienceSurface>,ray: ViewportRay,checkCancellation: ()->Unit = {}): ImportedFaceHit? {
        val direction=ray.unitDirection() ?: return null
        val origin=ray.origin.point(); var best: ImportedFaceHit?=null
        for (surface in surfaces) {
            checkCancellation()
            val mesh=surface.importedMesh ?: continue
            val points=ImportedSurfaceGeometry.points(surface)
            for ((index,t) in mesh.triangles().withIndex()) {
                checkCancellation()
                val a=points[t.a]; val e1=points[t.b]-a; val e2=points[t.c]-a
                val p=direction.cross(e2); val determinant=e1.dot(p)
                // Relative angular threshold, independent of triangle size. Both windings are pickable.
                if (abs(determinant)<=1e-12*e1.cross(e2).length()) continue
                val s=origin-a; val u=s.dot(p)/determinant
                if (u< -1e-9 || u>1+1e-9) continue
                val q=s.cross(e1); val v=direction.dot(q)/determinant
                if (v< -1e-9 || u+v>1+1e-9) continue
                val distance=e2.dot(q)/determinant
                if (!distance.isFinite() || distance<ray.minDistance || distance>ray.maxDistance) continue
                val previous=best
                // Shared-edge/vertex hits are deterministic, even if scene iteration order changes.
                val wins=previous==null || distance<previous.distanceM-1e-8 ||
                    (abs(distance-previous.distanceM)<=1e-8 &&
                        (surface.id<previous.areaId || surface.id==previous.areaId && index<previous.faceIndex))
                if (wins) {
                    val position=origin+direction*distance
                    best=ImportedFaceHit(surface.id,index,distance,PickPoint3D(position.x,position.y,position.z))
                }
            }
        }
        return best
    }

    /** Selection is scoped to one mesh; callers pass an empty set when switching zones. */
    fun select(mesh: AudienceMesh,current: Set<Int>,faceIndex: Int,mode: FacePickMode): Set<Int> {
        val count=mesh.triangles().size
        require(faceIndex in 0 until count && current.all {it in 0 until count}) {"Invalid imported face selection"}
        return when(mode) {
            FacePickMode.SINGLE -> setOf(faceIndex)
            FacePickMode.TOGGLE -> if (faceIndex in current) current-faceIndex else current+faceIndex
            FacePickMode.FLAT_PATCH -> mesh.coplanarFaces(faceIndex)
            FacePickMode.GROUP -> mesh.groupForFace(faceIndex)?.faces() ?: setOf(faceIndex)
        }
    }
}
