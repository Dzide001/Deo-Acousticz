package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.*
import kotlin.test.*

class DiffuseGeometryMaterialTest {
    private fun constant(alpha:Float,scatter:Float)=BoundaryMaterial.custom("Test",listOf(100 to alpha,2000 to alpha))
        .withScattering(MaterialScattering.create(listOf(0f to listOf(100 to scatter,2000 to scatter))))
    @Test fun matchesEveryAngleAndFrequencyForAngleIndependentPartitionsAndTies() {
        val materials=listOf(emptySet(),setOf(constant(.2f,.5f)),setOf(constant(.3f,.2f),constant(.1f,.8f)),
            setOf(constant(.2f,.5f),constant(.5f,.2f)),setOf(BoundaryMaterial.ABSORBING),
            setOf(BoundaryMaterial.custom("Frequency curve",listOf(100 to .1f,2000 to .8f)).withScattering(
                MaterialScattering.create(listOf(0f to listOf(100 to .2f,2000 to .7f))))))
        for(set in materials) for(hz in listOf(63,100,315,1000,2000,8000)) {
            val prepared=DiffuseGeometryMaterial.constantScatteredFraction(set,hz);assertNotNull(prepared)
            for(angle in listOf(0f,1f,30f,60f,89.9f,90f)) {
                val reference=BoundaryMaterial.reflectionMaterial(set,hz,angle)?.energyPartition(hz,angle)?.scattered ?: 0.0
                assertEquals(reference,prepared,0.0)
            }
        }
    }
    @Test fun angularAbsorptionOrScatteringAlwaysUsesPerEmitterFallback() {
        val angular=BoundaryMaterial.angular("Angular",listOf(0f to listOf(100 to .1f,2000 to .2f),90f to listOf(100 to .8f,2000 to .9f)))
        val scattering=constant(.2f,.5f).withScattering(MaterialScattering.create(listOf(
            0f to listOf(100 to .1f,2000 to .2f),90f to listOf(100 to .8f,2000 to .9f))))
        assertNull(DiffuseGeometryMaterial.constantScatteredFraction(setOf(angular),1000))
        assertNull(DiffuseGeometryMaterial.constantScatteredFraction(setOf(constant(.2f,.5f),scattering),1000))
    }
}
