package com.droidacoustic.pro.scene

/** Representative legacy material curves used by the optional global ground model.
 * These defaults are not product-specific measured data. Endpoint holding and
 * nearest-band lookup preserve existing projects; room RT60 is not predicted.
 * Explicit Blocks/imported faces use BoundaryMaterial instead.
 */
data class SurfaceMaterial(
    val id: String,
    val name: String,
    /** Absorption coefficient by octave centre. Values outside are clamped. */
    val alphaByBand: Map<Int, Float>
) {
    /** Absorption at [bandHz], holding the end values beyond the published range. */
    fun alphaAt(bandHz: Int): Float {
        alphaByBand[bandHz]?.let { return it.coerceIn(0.01f, 1f) }
        val bands = alphaByBand.keys.sorted()
        if (bands.isEmpty()) return 0.15f
        val nearest = when {
            bandHz <= bands.first() -> bands.first()
            bandHz >= bands.last() -> bands.last()
            else -> bands.minByOrNull { kotlin.math.abs(it - bandHz) }!!
        }
        return (alphaByBand[nearest] ?: 0.15f).coerceIn(0.01f, 1f)
    }

    /** Mean across the bands the app models - for a single-figure readout only. */
    fun averageAlpha(): Float =
        com.droidacoustic.pro.acoustics.OctaveWeighting.bands.map { alphaAt(it) }.average().toFloat()

    companion object {
        /** A surface with no frequency behaviour, for a hand-entered number. */
        fun flat(alpha: Float, id: String = CUSTOM_ID, name: String = "Custom"): SurfaceMaterial {
            val a = alpha.coerceIn(0.01f, 1f)
            return SurfaceMaterial(id, name, com.droidacoustic.pro.acoustics.OctaveWeighting.bands.associateWith { a })
        }

        const val CUSTOM_ID = "custom"

        private fun of(
            id: String, name: String,
            a125: Float, a250: Float, a500: Float, a1k: Float, a2k: Float, a4k: Float
        ) = SurfaceMaterial(
            id, name,
            mapOf(
                63 to a125,      // held: published tables start at 125 Hz
                125 to a125, 250 to a250, 500 to a500,
                1000 to a1k, 2000 to a2k, 4000 to a4k,
                8000 to a4k      // held: published tables stop at 4 kHz
            )
        )

        val CONCRETE = of("concrete", "Concrete, sealed", 0.01f, 0.01f, 0.02f, 0.02f, 0.02f, 0.03f)
        val CARPET = of("carpet", "Carpet, heavy on concrete", 0.02f, 0.06f, 0.14f, 0.37f, 0.60f, 0.65f)
        val WOOD_FLOOR = of("wood_floor", "Wood floor on joists", 0.15f, 0.11f, 0.10f, 0.07f, 0.06f, 0.07f)
        val GYPSUM = of("gypsum", "Gypsum board on studs", 0.29f, 0.10f, 0.05f, 0.04f, 0.07f, 0.09f)
        val BRICK = of("brick", "Brick, unglazed", 0.03f, 0.03f, 0.03f, 0.04f, 0.05f, 0.07f)
        val GLASS = of("glass", "Glass, heavy plate", 0.18f, 0.06f, 0.04f, 0.03f, 0.02f, 0.02f)
        val ACOUSTIC_TILE = of("acoustic_tile", "Acoustic tile, suspended", 0.34f, 0.42f, 0.62f, 0.79f, 0.83f, 0.75f)
        val ACOUSTIC_PANEL = of("acoustic_panel", "Acoustic panel, 50 mm", 0.20f, 0.55f, 0.90f, 0.95f, 0.90f, 0.85f)
        val CURTAIN = of("curtain", "Velour curtain, draped", 0.14f, 0.35f, 0.55f, 0.72f, 0.70f, 0.65f)
        val OPEN_TRUSS = of("open_truss", "Open truss / no ceiling", 0.55f, 0.60f, 0.65f, 0.70f, 0.70f, 0.70f)
        val AUDIENCE_SEATING = of("audience_seating", "Audience, upholstered", 0.39f, 0.57f, 0.80f, 0.94f, 0.92f, 0.87f)

        /** Everything offered in the picker, in a sensible order for choosing. */
        val CATALOGUE = listOf(
            CONCRETE, BRICK, GLASS, WOOD_FLOOR, GYPSUM,
            CARPET, CURTAIN, ACOUSTIC_PANEL, ACOUSTIC_TILE, OPEN_TRUSS, AUDIENCE_SEATING
        )

        fun byId(id: String): SurfaceMaterial? = CATALOGUE.firstOrNull { it.id == id }
    }
}
