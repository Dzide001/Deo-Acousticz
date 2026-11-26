package com.droidacoustic.pro.scene

import com.droidacoustic.pro.geometry.AcousticBoundaryRole
import com.droidacoustic.pro.geometry.BoundaryMaterial

data class VenueGeometry(
    val widthM       : Float = 28f,
    val depthM       : Float = 28f,
    val wallHeightM  : Float = 8f,
    val stageCenterX : Float = 0f,
    val stageCenterZ : Float = -10f,
    val stageWidthM  : Float = 10f,
    val stageDepthM  : Float = 4f,
    val stageHeightM : Float = 1f,
    val stageSlopeDeg: Float = 0f,
    val blocks       : List<VenueBlock> = emptyList(),
    val stageBoundaryRole: AcousticBoundaryRole = AcousticBoundaryRole.OCCLUDING,
    val stageBoundaryMaterial: BoundaryMaterial = BoundaryMaterial.IDEAL_RIGID
)

data class VenueBlock(
    val id           : Int,
    val type         : String = "STAGE",
    val centerX      : Float = 0f,
    val centerZ      : Float = 0f,
    val widthM       : Float = 6f,
    val depthM       : Float = 3f,
    val heightM      : Float = 1f,
    val blockHeightM : Float = 1f,
    val slopeDeg     : Float = 0f,
    val rotationDeg  : Float = 0f,  // Phase 6: Yaw rotation in degrees, 0-360
    val label        : String = "Block",
    val boundaryRole : AcousticBoundaryRole = AcousticBoundaryRole.OCCLUDING,
    val boundaryMaterial: BoundaryMaterial = BoundaryMaterial.IDEAL_RIGID
)
