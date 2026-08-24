package com.droidacoustic.pro.scene

import kotlin.math.*

/**
 * Factory for generating realistic CLF (Common Loudspeaker Format) polar patterns
 * for different speaker types and models.
 */
object ClfFactory {
    
    /**
     * Generate polar pattern data based on speaker characteristics.
     * Creates realistic azimuth/elevation coverage with frequency-dependent narrowing.
     */
    fun generateClfData(
        speakerId: String,
        manufacturerName: String,
        modelName: String,
        isLineArray: Boolean,
        numElements: Int = 1
    ): ClfData {
        val frequencies = listOf(250, 500, 1000, 2000, 4000, 8000)
        val patterns = frequencies.map { freq ->
            generatePolarPattern(freq, isLineArray, numElements)
        }
        return ClfData(speakerId, manufacturerName, modelName, patterns)
    }

    private fun generatePolarPattern(frequencyHz: Int, isLineArray: Boolean, numElements: Int): ClfPolarPattern {
        // Frequency-dependent directivity: narrower at HF
        val freqFactor = (frequencyHz / 1000f).coerceIn(0.25f, 8f)
        val narrowingFactor = log10(freqFactor.toDouble()).toFloat() * 0.15f + 1f
        
        // Line arrays have vertical narrowing; point sources have omnidirectional vertical
        val verticalNarrow = if (isLineArray) (numElements * 0.15f * narrowingFactor).coerceIn(1f, 3f) else 1f
        val horizontalNarrow = narrowingFactor.coerceIn(0.5f, 2.5f)
        
        // Generate azimuths: 0° to 360° in 5° steps
        val azimuths = (0..360 step 5).map { it.toFloat() }
        
        // Generate elevations: -90° to +90° in 10° steps
        val elevations = (-90..90 step 10).map { it.toFloat() }
        
        // Generate SPL matrix
        val spl = Array(azimuths.size) { azIdx ->
            FloatArray(elevations.size) { elIdx ->
                val azDeg = azimuths[azIdx]
                val elDeg = elevations[elIdx]
                
                // Normalize angles to center around 0°
                val azOff = ((azDeg + 180f) % 360f - 180f)
                val elOff = elDeg
                
                // Horizontal polar pattern (cardioid-like: narrower front, wider back)
                val horizDist = abs(azOff)
                val horizPenalty = -3f * (horizDist / (90f / horizontalNarrow)).pow(1.5f)
                    .coerceAtMost(0f)
                
                // Vertical pattern (broader for point sources, narrower for line arrays)
                val vertDist = abs(elOff)
                val vertPenalty = -2.5f * (vertDist / (60f / verticalNarrow)).pow(1.3f)
                    .coerceAtMost(0f)
                
                // On-axis boost
                val onAxisBoost = if (horizDist < 15f && vertDist < 10f) 2f else 0f
                
                // Combine penalties (dB relative to on-axis)
                (horizPenalty + vertPenalty + onAxisBoost).coerceIn(-20f, 0f)
            }
        }
        
        return ClfPolarPattern(frequencyHz, azimuths, elevations, spl)
    }

    /**
     * Pre-generated CLF data for all major speakers.
     * Maps speaker preset ID to CLF data.
     */
    fun getDefaultClfRegistry(): Map<String, ClfData> = mapOf(
        // Generic/Other
        "point_small" to generateClfData("point_small", "Other", "Point 8\"", false, 1),
        "point_large" to generateClfData("point_large", "Other", "Point 12\"", false, 1),
        "line_array" to generateClfData("line_array", "Other", "Line Array", true, 8),
        
        // JBL CBT line arrays
        "jbl_cbt_50la_1" to generateClfData("jbl_cbt_50la_1", "JBL", "CBT 50LA-1", true, 8),
        "jbl_cbt_70j_1" to generateClfData("jbl_cbt_70j_1", "JBL", "CBT 70J-1", true, 10),
        "jbl_cbt_100la_1" to generateClfData("jbl_cbt_100la_1", "JBL", "CBT 100LA-1", true, 12),
        "jbl_cbt_200la_1" to generateClfData("jbl_cbt_200la_1", "JBL", "CBT 200LA-1", true, 16),
        "jbl_cbt_1000" to generateClfData("jbl_cbt_1000", "JBL", "CBT 1000", true, 16),
        "jbl_srx812p" to generateClfData("jbl_srx812p", "JBL", "SRX812P", false, 1),
        "jbl_vtx_a8" to generateClfData("jbl_vtx_a8", "JBL", "VTX A8", true, 8),
        
        // L-Acoustics
        "lacoustics_x12" to generateClfData("lacoustics_x12", "L-Acoustics", "X12", false, 1),
        "lacoustics_kara" to generateClfData("lacoustics_kara", "L-Acoustics", "Kara II", true, 8),
        
        // d&b audiotechnik
        "db_y10p" to generateClfData("db_y10p", "d&b", "Y10P", false, 1),
        "db_y8" to generateClfData("db_y8", "d&b", "Y8", true, 8),
        
        // Meyer Sound
        "meyer_ultra_x40" to generateClfData("meyer_ultra_x40", "Meyer", "ULTRA-X40", false, 1),
        "meyer_leopard" to generateClfData("meyer_leopard", "Meyer", "LEOPARD", true, 8),
        
        // Electro-Voice
        "ev_ekx12p" to generateClfData("ev_ekx12p", "Electro-Voice", "EKX-12P", false, 1),
        
        // QSC
        "qsc_k12_2" to generateClfData("qsc_k12_2", "QSC", "K12.2", false, 1),
        
        // RCF
        "rcf_hdl6a" to generateClfData("rcf_hdl6a", "RCF", "HDL 6-A", true, 8),
        
        // Adamson
        "adamson_s10" to generateClfData("adamson_s10", "Adamson", "S10", true, 8),
        
        // Martin Audio
        "martin_w8lc" to generateClfData("martin_w8lc", "Martin", "W8LC", true, 8)
    )
}
