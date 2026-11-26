package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.scene.VenueBlock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Rigid yaw in the editor convention: positive turns +X toward +Z. */
object SceneGeometryTransforms {
    fun normalizedYaw(degrees: Float): Float {
        require(degrees.isFinite()) { "Invalid rotation" }
        return ((degrees % 360f) + 360f) % 360f
    }

    /** The same vertex-mean anchor used by the surface base/rake plane, including 3D imports. */
    fun center(surface: AudienceSurface): PlanPoint {
        val vertices = surface.vertices()
        require(vertices.isNotEmpty()) { "Surface has no anchor" }
        return PlanPoint(vertices.map { it.x }.average(), vertices.map { it.z }.average())
    }

    fun transform(surface: AudienceSurface, dx: Float = 0f, dy: Float = 0f,
        dz: Float = 0f, yawDeg: Float = 0f): AudienceSurface {
        require(listOf(dx,dy,dz,yawDeg).all { it.isFinite() }) { "Invalid transform" }
        surface.validateGeometry()
        val anchor = center(surface)
        val radians = normalizedYaw(yawDeg) * PI / 180.0
        val c = cos(radians); val s = sin(radians)
        fun point(p: PlanPoint): PlanPoint {
            val x = p.x-anchor.x; val z = p.z-anchor.z
            return PlanPoint(anchor.x+x*c-z*s+dx, anchor.z+x*s+z*c+dz)
        }
        val mesh = surface.importedMesh?.mapCoordinates(::point)
        return AudienceSurface(surface.id,surface.name,surface.zoneType,
            surface.vertices().map(::point),surface.baseHeightM+dy,surface.rakeDeg,
            normalizedYaw(surface.rakeDirectionDeg-yawDeg+180f)-180f,
            surface.holes().map { it.map(::point) },surface.exclusions().map { it.map(::point) },
            surface.vertexOffsetsM(),surface.elevationPoints().map {
                val p=point(PlanPoint(it.x,it.z)); it.copy(x=p.x,z=p.z)
            },surface.breaklines(),mesh).also { it.validateGeometry() }
    }

    /** heightM is the top-plane elevation; blockHeightM is thickness and must remain unchanged. */
    fun blockAt(block: VenueBlock, x: Float, y: Float, z: Float, yawDeg: Float): VenueBlock {
        require(listOf(x,y,z,yawDeg).all { it.isFinite() }) { "Invalid transform" }
        return block.copy(centerX=x.coerceIn(-200f,200f),heightM=y.coerceIn(0f,40f),
            centerZ=z.coerceIn(-200f,200f),rotationDeg=normalizedYaw(yawDeg))
    }
}
