package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.BoundaryMaterial

/** One temporary scalar per miss geometry cell, not another geometry cache.
 * Angular absorption/scattering always keeps the exact per-emitter path. */
internal object DiffuseGeometryMaterial {
    fun constantScatteredFraction(materials:Set<BoundaryMaterial>,hz:Int):Double? {
        if(materials.any {it.isAngleDependent || it.scattering?.isAngleDependent==true}) return null
        return BoundaryMaterial.reflectionMaterial(materials,hz,0f)?.energyPartition(hz,0f)?.scattered ?: 0.0
    }
}
