package com.droidacoustic.pro.scene

import com.droidacoustic.pro.geometry.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Blocks now use ideal geometric boundaries. No empirical grazing/diffraction losses remain. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObstructionTest {
    private val models=mutableListOf<SceneViewModel>()
    @Before fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun cleanup() {models.forEach {it.cancelAnalysisJobs()}; Dispatchers.resetMain()}
    private fun wall()=SceneViewModel(predictionDispatcher=Dispatchers.Main).also {
        models+=it; it.setStageBoundaryRole(AcousticBoundaryRole.NONE); it.addVenueBlock("WALL",0f,0f)
    }
    private fun blocked(v: SceneViewModel,y: Float)=v.isLineOfSightBlocked(0f,y,-5f,0f,y,5f)
    @Test fun opaqueWallBlocksEveryBandWithoutAddingAnEmpiricalLoss() {
        val v=wall()
        for (band in SceneViewModel.SUPPORTED_BANDS_HZ) {
            v.setBandHz(band); assertTrue(blocked(v,2f))
            assertEquals(Float.POSITIVE_INFINITY,v.estimateObstructionAttenuationDb(0f,2f,-5f,0f,2f,5f),0f)
        }
    }
    @Test fun clearAndNearEdgePathsHaveNoGrazingPenalty() {
        val v=wall()
        for (y in listOf(4.001f,4.05f,4.2f,6f)) {
            assertFalse(blocked(v,y)); assertEquals(0f,v.estimateObstructionAttenuationDb(0f,y,-5f,0f,y,5f),0f)
        }
    }
    @Test fun removalAndNoBoundaryRoleRestoreClearPath() {
        val v=wall(); assertTrue(blocked(v,2f))
        v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.NONE); assertFalse(blocked(v,2f))
        v.setVenueBlockBoundaryRole(0,AcousticBoundaryRole.RIGID_REFLECTOR); assertTrue(blocked(v,2f))
        v.removeVenueBlock(0); assertFalse(blocked(v,2f))
    }
    @Test fun elevatedBalconyKeepsUnderpassAndRotationChangesFootprint() {
        val v=wall(); v.setVenueBlockHeight(0,6f); v.setVenueBlockThickness(0,0.5f)
        assertFalse(blocked(v,2f)); assertTrue(blocked(v,5.75f))
        assertFalse(v.isLineOfSightBlocked(4f,5.75f,1f,4f,5.75f,4f))
        v.setVenueBlockRotation(0,90f)
        assertFalse(v.isLineOfSightBlocked(4f,5.75f,-4f,4f,5.75f,4f))
    }
    @Test fun visibilityIsReciprocalAndIndependentOfAnalysisProfile() {
        val v=wall()
        for (profile in SceneViewModel.ANALYSIS_PROFILES) {
            v.setAnalysisProfile(profile)
            assertEquals(v.isLineOfSightBlocked(0f,2f,-5f,0f,3f,5f),v.isLineOfSightBlocked(0f,3f,5f,0f,2f,-5f))
            assertTrue(blocked(v,2f))
        }
    }
    @Test fun endpointAndZeroLengthContactsDoNotSelfOcclude() {
        val v=wall()
        assertFalse(v.isLineOfSightBlocked(0f,2f,-5f,0f,2f,-0.25f))
        assertFalse(v.isLineOfSightBlocked(0f,2f,0f,0f,2f,0f))
    }
}
