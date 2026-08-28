package com.droidacoustic.pro.scene

/**
 * Sabine absorption for one surface, per octave band.
 *
 * A single broadband coefficient cannot describe a real surface. Heavy carpet
 * absorbs about 2% at 125 Hz and 65% at 4 kHz - a factor of thirty across the
 * range a loudspeaker actually covers - so one number is wrong at both ends
 * whichever value is chosen. Every consumer of absorption in this app is
 * frequency dependent: RT60, the loss at each reflection, and the floor bounce.
 *
 * ## Where the numbers come from
 *
 * These are the representative published values that appear across the standard
 * references (Egan, Beranek, the manufacturer tables they draw on). They
 * describe a *type* of construction, not a specific product: a real room should
 * be measured, and a specified product has its own data sheet. They are here so
 * that "acoustic tile" behaves like acoustic tile rather than like a number
 * someone typed.
 *
 * Published tables run 125 Hz to 4 kHz. This app models 63 Hz to 8 kHz, so the
 * end bands hold the nearest published value rather than extrapolating a curve
 * nobody measured. That is a stated approximation, and it errs toward the
 * conservative: real absorption usually falls below 125 Hz rather than holding.
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
        SceneViewModel.SUPPORTED_BANDS_HZ.map { alphaAt(it) }.average().toFloat()

    companion object {
        /** A surface with no frequency behaviour, for a hand-entered number. */
        fun flat(alpha: Float, id: String = CUSTOM_ID, name: String = "Custom"): SurfaceMaterial {
            val a = alpha.coerceIn(0.01f, 1f)
            return SurfaceMaterial(id, name, SceneViewModel.SUPPORTED_BANDS_HZ.associateWith { a })
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
