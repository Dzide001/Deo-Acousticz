package com.droidacoustic.pro.scene

import com.droidacoustic.pro.alignment.*
import com.droidacoustic.pro.traces.*

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.persistence.*
import com.droidacoustic.pro.prediction.*
import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.statistics.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import com.droidacoustic.pro.engine.AcousticEngine
import com.droidacoustic.pro.optimizer.OptimizerParam
import com.droidacoustic.pro.optimizer.OptimizerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream
import kotlin.math.pow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.sqrt

// =============================================================================
// Domain types
// =============================================================================

data class PlacedSpeaker(
    val id         : Int,
    val x          : Float,           // world-space X (metres)
    val z          : Float,           // world-space Z (metres)
    val heightM    : Float = 1.2f,    // cabinet height above floor
    val sensitivity: Float = 100f,   // generic reference level at 1 m; no implied electrical calibration
    val arrayElements: Int = 1,      // 1 = point source, >1 = vertical line-array
    val arraySpacingM: Float = 0.2f, // inter-element spacing (metres)
    val arrayInterBoxSplayDeg: Float = 0f, // uniform inter-box splay angle (deg)
    val arraySplayByBoxDeg: List<Float> = emptyList(), // per-joint splay profile (N-1 joints)
    val arrayAimDeg: Float = 0f,     // mechanical aim (down-positive)
    val panDeg     : Float = 0f,     // horizontal aiming / yaw
    val arraySteerDeg: Float = 0f,   // electronic steering angle (vertical plane)
    val arrayEdgeTaperDb: Float = 0f,// attenuation at top/bottom element edges
    val modelPackageId: String = "generic",
    /**
     * Which speaker preset this box was placed from.
     *
     * Carries the identity that links a placed box back to measured CLF data in
     * the registry. Without it the only thing recorded is [modelPackageId],
     * which selects synthetic fallback coefficients and says nothing about
     * which actual loudspeaker this is. Blank on scenes saved before this
     * existed, which simply means no measured data is available for them.
     */
    val presetId   : String = "",
    val requiresMeasuredData: Boolean = false,
    val sourceId   : Int? = null,
    val label      : String = "SPK ${id + 1}"
)

/**
 * Absolute vertical aim of every element in an array, in the app's down-positive
 * convention.
 *
 * Cumulative splay opens the array downward from the top box, then the whole
 * fan is re-centred so its mean sits on the speaker's stated aim - otherwise
 * adding splay would swing the array away from where the user pointed it.
 *
 * The summation, the aim-ray overlay and the inspector all read this. It used to
 * be written out three times, which is how the mesh and the rays came to
 * disagree about a sign.
 */
fun PlacedSpeaker.elementAimsDeg(): List<Float> {
    return elementPoses().map { it.tiltDownDeg }
}

fun PlacedSpeaker.elementPoses(): List<ArrayGeometry.Pose> = ArrayGeometry.poses(
    arrayElements, arraySpacingM, arrayAimDeg,
    if (arraySplayByBoxDeg.size == arrayElements - 1) arraySplayByBoxDeg
    else List((arrayElements - 1).coerceAtLeast(0)) { arrayInterBoxSplayDeg }
)

data class SpeakerSource(
    val id: Int,
    val name: String = "Source ${id + 1}",
    val role: String = "MAINS",
    val brand: String = "Other",
    val series: String = "Other",
    val modelPresetId: String? = null,
    val modelPackageId: String = "generic",
    val linkedSourceId: Int? = null,
    val linkedMotionMode: String = "SAME",
    val collapsed: Boolean = false
)

data class SpeakerResult(
    val speaker   : PlacedSpeaker,
    val distanceM : Float,
    val splDb     : Float,
    val airLossDb : Float
)

data class ListenerPos(
    val x          : Float = 0f,
    val z          : Float = 0f,
    val earHeightM : Float = 1.2f
)

data class HeatCell(
    val x     : Float,
    val z     : Float,
    val splDb : Float,
    val sourceAreaId: Int? = null,
    val sourceAreaName: String? = null,
    val sourceZoneType: String? = null,
    val sourceRakeDeg: Float = 0f,
    val sourceRakeDirectionDeg: Float = 0f,
    val renderY: Float = 0f,
    val sampleIndex: Int? = null,
    val receiverY: Float? = null,
    val surfaceKey: String? = null
)

data class AudiencePoint(
    val id          : Int,
    val x           : Float,
    val z           : Float,
    val earHeightM  : Float = 1.2f,
    val sourceAreaId: Int? = null,
    val name        : String = "AUD ${id + 1}"
)

data class AudienceArea(
    val id       : Int,
    val name     : String = "AREA ${id + 1}",
    val zoneType : String = "AUDIENCE_SEATED",
    val baseHeightM: Float = 0f,
    val rakeDeg: Float = 0f,
    val rakeDirectionDeg: Float = 0f,
    val rotationDeg: Float = 0f,
    val linkedZoneId: Int? = null,
    val vertices : List<Pair<Float, Float>>,
    val holes: List<List<Pair<Float, Float>>> = emptyList(),
    val exclusions: List<List<Pair<Float, Float>>> = emptyList(),
    val vertexOffsetsM: List<Float> = emptyList(),
    val elevationPoints: List<AudienceElevationPoint> = emptyList(),
    val breaklines: List<AudienceBreakline> = emptyList(),
    val importedMesh: AudienceMesh? = null
)

data class ImportedFaceSelection(val areaId: Int,val faceIndices: Set<Int>)

data class AudienceCutoutTarget(val areaId: Int, val hole: Boolean)

data class SpeakerModelPackage(
    val id            : String,
    val name          : String,
    val markerHeightM : Float,
    val crossHalfM    : Float,
    val cabinetWidthM : Float,
    val cabinetDepthM : Float,
    val cabinetHeightM: Float,
    val colorR        : Float,
    val colorG        : Float,
    val colorB        : Float,
    val modelAssetPath: String? = null
)

data class SpeakerPreset(
    val id              : String,
    val name            : String,
    val sensitivityDb   : Float,
    val heightM         : Float,
    val arrayElements   : Int   = 1,      // Phase 8: >1 → line-array mode
    val elementSpacingM : Float = 0.2f,   // inter-element spacing (metres)
    val brand           : String = "Other",
    val series          : String = "Other",
    val model           : String = "Other"
)

data class SpeakerDsp(
    val speakerId : Int,
    val delayMs   : Float = 0f,          // 0-200 ms  (Phase 8: arrival-time shift)
    val gainDb    : Float = 0f,          // +/-12 dB system gain trim
    val polarity  : Boolean = false,     // true = polarity invert (Phase 8: coherent sum)
    val eqBands   : Map<Int, Float> = emptyMap(),  // legacy magnitude-only offsets
    val filters: com.droidacoustic.pro.acoustics.DspFilterChain = com.droidacoustic.pro.acoustics.DspFilterChain()
)

/**
 * The vertical -6 dB edges of a box, as drawn by the aim-ray overlay.
 *
 * Asymmetric on purpose: real boxes are not symmetric about their axis. The
 * Martin Audio XD12 measures about 10 degrees up and 25 down at 2 kHz, and
 * collapsing that to one number throws away the half a designer cares about.
 */
data class CoverageEdges(
    val upDeg: Float,
    val downDeg: Float,
    val leftDeg: Float,
    val rightDeg: Float,
    /** True when read off a measured balloon rather than the synthetic model. */
    val measured: Boolean
)

data class EarlyReflection(
    val speakerLabel : String,
    val surfaceName  : String,
    val delayMs      : Float,
    val pathLengthM  : Float,
    val splDb        : Float
)

data class Rt60Estimate(
    val widthM    : Float,
    val depthM    : Float,
    val heightM   : Float,
    val volumeM3  : Float,
    val rt60S     : Float
)

data class StiEstimate(
    val sti: Float,
    val quality: String,
    val alconsPct: Float
)

data class IndustryCatalogLoadResult(
    val presetsAdded: Int,
    val modelPackagesAdded: Int,
    val ok: Boolean,
    val message: String? = null,
    val catalogVersion: Int? = null,
    val catalogSchema: String? = null
)

data class IndustryCatalogInfo(
    val schema: String,
    val version: Int,
    val presetCount: Int,
    val modelPackageCount: Int
)

data class ClfIngestionStats(
    val indexedSpeakers: Int = 0,
    val parsedJsonSpeakers: Int = 0,
    val parsedBinarySpeakers: Int = 0,
    val extractedBinarySpeakers: Int = 0,
    val inferredBinarySpeakers: Int = 0,
    val registrySpeakers: Int = 0,
    val pendingBinarySpeakers: Int = 0,
    val unresolvedExternalSpeakers: Int = 0,
    val strictExtractedOnlyMode: Boolean = false
)

/**
 * Phase 8 — material-aware surface absorption coefficients.
 * Each value is a Sabine absorption coefficient in [0, 1].
 * Defaults represent a typical treated venue (Phase 7 baselines).
 */
data class RoomMaterials(
    val floor        : SurfaceMaterial = SurfaceMaterial.CARPET,
    val ceiling      : SurfaceMaterial = SurfaceMaterial.ACOUSTIC_TILE,
    val wall         : SurfaceMaterial = SurfaceMaterial.GYPSUM,
    val roomHeightM  : Float = 8f       // ceiling height (metres)
) {
    fun floorAlphaAt(bandHz: Int) = floor.alphaAt(bandHz)
    fun ceilingAlphaAt(bandHz: Int) = ceiling.alphaAt(bandHz)
    fun wallAlphaAt(bandHz: Int) = wall.alphaAt(bandHz)
}

// =============================================================================
// ViewModel
// =============================================================================


class SceneViewModel(
    private val predictionRunner: PredictionRunner = PredictionRunner { request ->
        val context = currentCoroutineContext()
        request.calculate { context.ensureActive() }
    },
    private val predictionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val geometryCache: GeometryCache = GeometryCache(),
    private val snapshotOnly: Boolean = false
) : ViewModel() {

    companion object {
        const val SCENE_SCHEMA_VERSION = ProjectArchive.VERSION
        private const val MIN_SUPPORTED_SCENE_VERSION = 1
        private const val MAX_HISTORY_ENTRIES = 80
        private const val AREA_GRID_SNAP_M = 0.25f
        private const val AREA_ANGLE_SNAP_DEG = 15f
        private const val AREA_MIN_SEGMENT_M = 0.35f
        private const val AREA_MIN_POLYGON_AREA_M2 = 0.25f
        val SUPPORTED_BANDS_HZ = listOf(63, 125, 250, 500, 1000, 2000, 4000, 8000)
        val ANALYSIS_PROFILES = listOf("Fast", "Balanced", "Precision")

        const val WEIGHTING_Z = "Z"
        const val WEIGHTING_A = "A"
        const val WEIGHTING_C = "C"
        val WEIGHTINGS = listOf(WEIGHTING_Z, WEIGHTING_A, WEIGHTING_C)

        /**
         * IEC 61672 frequency weightings at the octave centres this app models.
         *
         * A approximates the ear's reduced sensitivity to low frequencies at
         * moderate level, and is the curve almost every noise limit and licence
         * condition is written in. C is nearly flat and rolls off only at the
         * extremes, which makes it the honest one for high-level music - and the
         * gap between a C and an A reading is a direct measure of how much low
         * end a system is putting out. Z is no weighting at all: what the
         * physics produced.
         *
         * These are per-band offsets, so applied to a single band they only
         * shift the number. They mean something once bands are summed, which is
         * what [broadbandSplDb] is for.
         */
        val WEIGHTING_DB: Map<String, Map<Int, Float>> = OctaveWeighting.tables

        fun weightingDb(weighting: String, bandHz: Int): Float =
            WEIGHTING_DB[weighting]?.get(bandHz) ?: 0f

        /**
         * Power-sum a set of per-band levels into one broadband figure, applying
         * the weighting curve on the way.
         *
         * Bands are independent slices of the spectrum, so they add as power -
         * never as pressure. Summing them coherently would be claiming the
         * bands are phase-related to each other, which they are not.
         */
        fun broadbandSplDb(perBand: Map<Int, Float>, weighting: String): Float? {
            var power = 0.0
            perBand.forEach { (band, spl) ->
                if (spl.isFinite()) {
                    power += Math.pow(10.0, (spl + weightingDb(weighting, band)) / 10.0)
                }
            }
            return if (power <= 0.0) null else (10.0 * log10(power)).toFloat()
        }

        const val CONTOUR_OFF = "OFF"
        const val CONTOUR_BANDS = "BANDS"
        const val CONTOUR_LINES = "LINES"
        val CONTOUR_MODES = listOf(CONTOUR_OFF, CONTOUR_BANDS, CONTOUR_LINES)

        /** Step between iso-levels, in dB. Three is the usual coverage grain. */
        const val CONTOUR_STEP_DB = 3f

        const val SPL_SCALE_AUTO = "AUTO"
        const val SPL_SCALE_TARGET = "TARGET"
        const val SPL_SCALE_FIXED = "FIXED"
        val SPL_SCALE_MODES = listOf(SPL_SCALE_AUTO, SPL_SCALE_TARGET, SPL_SCALE_FIXED)

        /**
         * The dB window the colour ramp spans. Pure, and takes every input as a
         * parameter, so a composable can call it on values it collected as state
         * and actually recompose when the scale changes.
         */
        fun splScaleWindow(
            mode: String,
            targetDb: Float,
            spanDb: Float,
            fixedMinDb: Float,
            fixedMaxDb: Float,
            cells: List<HeatCell>
        ): Pair<Float, Float> = when (mode) {
            SPL_SCALE_TARGET -> (targetDb - spanDb) to (targetDb + spanDb)
            SPL_SCALE_FIXED -> fixedMinDb to fixedMaxDb
            else -> {
                val lo = cells.minOfOrNull { it.splDb } ?: 70f
                val hi = cells.map { it.splDb }.filter { it.isFinite() }.maxOrNull() ?: 100f
                if (hi - lo < 0.1f) lo to (lo + 0.1f) else lo to hi
            }
        }
        val ZONE_TYPES = listOf("AUDIENCE_SEATED", "AUDIENCE_STANDING", "STAGE", "OBSTACLE", "WALL")
        val VENUE_BLOCK_TYPES = listOf("STAGE", "SEATING_BANK", "BALCONY", "OBSTACLE", "WALL")
        val SOURCE_ROLES = listOf("MAINS", "SUBS", "SURROUND", "DELAYS", "FRONTFILL", "OUTFILL")
        val LINK_MOTION_MODES = listOf("SAME", "OPPOSITE")
        val SPEAKER_MODEL_PACKAGES = listOf(
            SpeakerModelPackage("generic", "Generic", 2.0f, 0.35f, 0.42f, 0.40f, 0.65f, 0.0f, 0.9f, 0.9f),
            SpeakerModelPackage("line_array", "Line Array", 3.8f, 0.45f, 0.55f, 0.42f, 0.28f, 0.0f, 0.75f, 1.0f),
            SpeakerModelPackage("point_source", "Point Source", 1.8f, 0.30f, 0.40f, 0.35f, 0.60f, 0.1f, 1.0f, 0.6f),
            // JBL CBT series (dimensions from publicly listed product specs where available)
            SpeakerModelPackage("jbl_cbt_50la_1", "JBL CBT 50LA-1", 2.0f, 0.35f, 0.099f, 0.153f, 0.528f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_cbt_70j_1", "JBL CBT 70J-1", 2.2f, 0.35f, 0.170f, 0.237f, 0.694f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_cbt_70je_1", "JBL CBT 70JE-1", 2.2f, 0.35f, 0.170f, 0.237f, 0.694f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_cbt_100la_1", "JBL CBT 100LA-1", 2.4f, 0.35f, 0.099f, 0.153f, 1.016f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_cbt_200la_1", "JBL CBT 200LA-1", 2.8f, 0.35f, 0.150f, 0.216f, 2.000f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_cbt_1000", "JBL CBT 1000", 2.6f, 0.35f, 0.250f, 0.345f, 1.020f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_cbt_1000e", "JBL CBT 1000E", 2.6f, 0.35f, 0.250f, 0.345f, 1.020f, 0.95f, 0.55f, 0.05f),
            // Additional real-world starter packages (approximate cabinet envelopes)
            SpeakerModelPackage("jbl_srx812p", "JBL SRX812P", 1.8f, 0.32f, 0.38f, 0.40f, 0.64f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_vtx_a8", "JBL VTX A8", 6.8f, 0.36f, 0.70f, 0.37f, 0.27f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_vtx_b18", "JBL VTX B18", 1.1f, 0.40f, 1.21f, 0.56f, 0.79f, 0.95f, 0.55f, 0.05f),
            // JBL EON ONE series
            SpeakerModelPackage("jbl_eon_one_compact", "JBL EON ONE Compact", 1.3f, 0.28f, 0.255f, 0.291f, 0.399f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_eon_one_mk2", "JBL EON ONE MK2", 1.4f, 0.30f, 0.335f, 0.400f, 0.597f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_eon_one", "JBL EON ONE", 1.4f, 0.30f, 0.370f, 0.490f, 0.597f, 0.95f, 0.55f, 0.05f),
            // JBL VTX series (expanded)
            SpeakerModelPackage("jbl_vtx_a6", "JBL VTX A6", 6.8f, 0.36f, 0.700f, 0.375f, 0.260f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_vtx_a12", "JBL VTX A12", 6.9f, 0.40f, 1.082f, 0.356f, 0.349f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_vtx_a12w", "JBL VTX A12W", 6.9f, 0.40f, 1.082f, 0.356f, 0.349f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_vtx_b28", "JBL VTX B28", 1.2f, 0.45f, 1.199f, 0.689f, 0.558f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_vtx_g28", "JBL VTX G28", 1.2f, 0.45f, 1.206f, 0.679f, 0.547f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_vtx_f12", "JBL VTX F12", 1.8f, 0.34f, 0.696f, 0.440f, 0.360f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_vtx_f35", "JBL VTX F35", 1.8f, 0.38f, 0.894f, 0.356f, 0.349f, 0.95f, 0.55f, 0.05f),
            // JBL SRX900 series
            SpeakerModelPackage("jbl_srx906la", "JBL SRX906LA", 6.5f, 0.36f, 0.810f, 0.325f, 0.270f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_srx910la", "JBL SRX910LA", 6.6f, 0.38f, 1.040f, 0.370f, 0.300f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_srx918s", "JBL SRX918S", 1.0f, 0.40f, 0.680f, 0.780f, 0.500f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_srx928s", "JBL SRX928S", 1.1f, 0.45f, 1.110f, 0.760f, 0.560f, 0.95f, 0.55f, 0.05f),
            // JBL PRX900 series
            SpeakerModelPackage("jbl_prx908", "JBL PRX908", 1.6f, 0.28f, 0.300f, 0.300f, 0.500f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_prx912", "JBL PRX912", 1.7f, 0.31f, 0.360f, 0.360f, 0.640f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_prx915", "JBL PRX915", 1.8f, 0.33f, 0.450f, 0.390f, 0.720f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_prx925", "JBL PRX925", 1.8f, 0.34f, 0.460f, 0.390f, 0.810f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_prx915xlf", "JBL PRX915XLF", 1.0f, 0.40f, 0.520f, 0.600f, 0.630f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_prx918xlf", "JBL PRX918XLF", 1.0f, 0.42f, 0.560f, 0.680f, 0.700f, 0.95f, 0.55f, 0.05f),
            // JBL EON700 series
            SpeakerModelPackage("jbl_eon710", "JBL EON710", 1.7f, 0.30f, 0.332f, 0.300f, 0.587f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_eon712", "JBL EON712", 1.7f, 0.31f, 0.385f, 0.310f, 0.669f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_eon715", "JBL EON715", 1.8f, 0.33f, 0.438f, 0.351f, 0.717f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("jbl_eon718s", "JBL EON718S", 1.0f, 0.40f, 0.560f, 0.660f, 0.640f, 0.95f, 0.55f, 0.05f),
            SpeakerModelPackage("lacoustics_x12", "L-Acoustics X12", 1.8f, 0.32f, 0.55f, 0.37f, 0.36f, 0.90f, 0.30f, 0.15f),
            SpeakerModelPackage("lacoustics_kara", "L-Acoustics Kara II", 6.5f, 0.35f, 0.59f, 0.36f, 0.26f, 0.90f, 0.30f, 0.15f),
            SpeakerModelPackage("db_y10p", "d&b Y10P", 1.8f, 0.30f, 0.31f, 0.37f, 0.59f, 0.35f, 0.35f, 0.40f),
            SpeakerModelPackage("db_y8", "d&b Y8", 6.5f, 0.35f, 0.59f, 0.37f, 0.19f, 0.35f, 0.35f, 0.40f),
            SpeakerModelPackage("meyer_ultra_x40", "Meyer ULTRA-X40", 1.8f, 0.30f, 0.34f, 0.31f, 0.53f, 0.20f, 0.70f, 0.30f),
            SpeakerModelPackage("meyer_leopard", "Meyer LEOPARD", 6.8f, 0.35f, 0.52f, 0.55f, 0.29f, 0.20f, 0.70f, 0.30f),
            SpeakerModelPackage("ev_ekx12p", "Electro-Voice EKX-12P", 1.7f, 0.31f, 0.37f, 0.40f, 0.61f, 0.10f, 0.55f, 0.95f),
            SpeakerModelPackage("qsc_k12_2", "QSC K12.2", 1.7f, 0.31f, 0.35f, 0.35f, 0.60f, 0.65f, 0.65f, 0.70f),
            SpeakerModelPackage("rcf_hdl6a", "RCF HDL 6-A", 6.2f, 0.34f, 0.47f, 0.38f, 0.24f, 0.90f, 0.15f, 0.10f),
            SpeakerModelPackage("adamson_s10", "Adamson S10", 6.6f, 0.36f, 0.53f, 0.38f, 0.28f, 0.80f, 0.35f, 0.10f),
            SpeakerModelPackage("martin_w8lc", "Martin W8LC", 6.5f, 0.36f, 0.58f, 0.42f, 0.29f, 0.75f, 0.55f, 0.15f)
        )
        val SPEAKER_PRESETS = listOf(
            SpeakerPreset("point_small", "Other Point 8\"",  96f,  1.4f, brand = "Other", series = "Generic", model = "Point 8\""),
            SpeakerPreset("point_large", "Other Point 12\"", 100f, 1.6f, brand = "Other", series = "Generic", model = "Point 12\""),
            SpeakerPreset("line_array",  "Other Line Array",  104f, 5.5f, arrayElements = 8, elementSpacingM = 0.2f, brand = "Other", series = "Generic", model = "Line Array"),
            // JBL CBT series starter presets
            SpeakerPreset("jbl_cbt_50la_1", "JBL CBT 50LA-1", 93f, 2.2f, arrayElements = 8, elementSpacingM = 0.075f, brand = "JBL", series = "CBT", model = "CBT 50LA-1"),
            SpeakerPreset("jbl_cbt_70j_1", "JBL CBT 70J-1", 96f, 2.4f, arrayElements = 10, elementSpacingM = 0.075f, brand = "JBL", series = "CBT", model = "CBT 70J-1"),
            SpeakerPreset("jbl_cbt_70je_1", "JBL CBT 70JE-1", 96f, 2.4f, arrayElements = 10, elementSpacingM = 0.075f, brand = "JBL", series = "CBT", model = "CBT 70JE-1"),
            SpeakerPreset("jbl_cbt_100la_1", "JBL CBT 100LA-1", 95f, 2.6f, arrayElements = 12, elementSpacingM = 0.075f, brand = "JBL", series = "CBT", model = "CBT 100LA-1"),
            SpeakerPreset("jbl_cbt_200la_1", "JBL CBT 200LA-1", 98f, 3.0f, arrayElements = 16, elementSpacingM = 0.10f, brand = "JBL", series = "CBT", model = "CBT 200LA-1"),
            SpeakerPreset("jbl_cbt_1000", "JBL CBT 1000", 102f, 3.2f, arrayElements = 16, elementSpacingM = 0.10f, brand = "JBL", series = "CBT", model = "CBT 1000"),
            SpeakerPreset("jbl_cbt_1000e", "JBL CBT 1000E", 102f, 3.2f, arrayElements = 16, elementSpacingM = 0.10f, brand = "JBL", series = "CBT", model = "CBT 1000E"),
            // Additional real-world presets
            SpeakerPreset("jbl_srx812p", "JBL SRX812P", 98f, 1.7f, brand = "JBL", series = "SRX", model = "SRX812P"),
            SpeakerPreset("jbl_vtx_a8", "JBL VTX A8", 105f, 6.8f, arrayElements = 8, elementSpacingM = 0.14f, brand = "JBL", series = "VTX", model = "A8"),
            SpeakerPreset("jbl_vtx_b18", "JBL VTX B18", 99f, 1.1f, brand = "JBL", series = "VTX", model = "B18"),
            // JBL EON ONE series
            SpeakerPreset("jbl_eon_one_compact", "JBL EON ONE Compact", 90f, 1.3f, brand = "JBL", series = "EON ONE", model = "EON ONE Compact"),
            SpeakerPreset("jbl_eon_one_mk2", "JBL EON ONE MK2", 91f, 1.4f, brand = "JBL", series = "EON ONE", model = "EON ONE MK2"),
            SpeakerPreset("jbl_eon_one", "JBL EON ONE", 90f, 1.4f, brand = "JBL", series = "EON ONE", model = "EON ONE"),
            // JBL VTX series (expanded)
            SpeakerPreset("jbl_vtx_a6", "JBL VTX A6", 105f, 6.8f, arrayElements = 8, elementSpacingM = 0.13f, brand = "JBL", series = "VTX", model = "A6"),
            SpeakerPreset("jbl_vtx_a12", "JBL VTX A12", 106f, 6.9f, arrayElements = 8, elementSpacingM = 0.16f, brand = "JBL", series = "VTX", model = "A12"),
            SpeakerPreset("jbl_vtx_a12w", "JBL VTX A12W", 106f, 6.9f, arrayElements = 8, elementSpacingM = 0.16f, brand = "JBL", series = "VTX", model = "A12W"),
            SpeakerPreset("jbl_vtx_b28", "JBL VTX B28", 100f, 1.2f, brand = "JBL", series = "VTX", model = "B28"),
            SpeakerPreset("jbl_vtx_g28", "JBL VTX G28", 100f, 1.2f, brand = "JBL", series = "VTX", model = "G28"),
            SpeakerPreset("jbl_vtx_f12", "JBL VTX F12", 101f, 1.8f, brand = "JBL", series = "VTX", model = "F12"),
            SpeakerPreset("jbl_vtx_f35", "JBL VTX F35", 103f, 1.8f, brand = "JBL", series = "VTX", model = "F35"),
            // JBL SRX900 series
            SpeakerPreset("jbl_srx906la", "JBL SRX906LA", 104f, 6.5f, arrayElements = 6, elementSpacingM = 0.16f, brand = "JBL", series = "SRX900", model = "SRX906LA"),
            SpeakerPreset("jbl_srx910la", "JBL SRX910LA", 105f, 6.6f, arrayElements = 6, elementSpacingM = 0.18f, brand = "JBL", series = "SRX900", model = "SRX910LA"),
            SpeakerPreset("jbl_srx918s", "JBL SRX918S", 99f, 1.0f, brand = "JBL", series = "SRX900", model = "SRX918S"),
            SpeakerPreset("jbl_srx928s", "JBL SRX928S", 100f, 1.1f, brand = "JBL", series = "SRX900", model = "SRX928S"),
            // JBL PRX900 series
            SpeakerPreset("jbl_prx908", "JBL PRX908", 97f, 1.6f, brand = "JBL", series = "PRX900", model = "PRX908"),
            SpeakerPreset("jbl_prx912", "JBL PRX912", 98f, 1.7f, brand = "JBL", series = "PRX900", model = "PRX912"),
            SpeakerPreset("jbl_prx915", "JBL PRX915", 99f, 1.8f, brand = "JBL", series = "PRX900", model = "PRX915"),
            SpeakerPreset("jbl_prx925", "JBL PRX925", 100f, 1.8f, brand = "JBL", series = "PRX900", model = "PRX925"),
            SpeakerPreset("jbl_prx915xlf", "JBL PRX915XLF", 98f, 1.0f, brand = "JBL", series = "PRX900", model = "PRX915XLF"),
            SpeakerPreset("jbl_prx918xlf", "JBL PRX918XLF", 99f, 1.0f, brand = "JBL", series = "PRX900", model = "PRX918XLF"),
            // JBL EON700 series
            SpeakerPreset("jbl_eon710", "JBL EON710", 97f, 1.7f, brand = "JBL", series = "EON700", model = "EON710"),
            SpeakerPreset("jbl_eon712", "JBL EON712", 98f, 1.7f, brand = "JBL", series = "EON700", model = "EON712"),
            SpeakerPreset("jbl_eon715", "JBL EON715", 99f, 1.8f, brand = "JBL", series = "EON700", model = "EON715"),
            SpeakerPreset("jbl_eon718s", "JBL EON718S", 98f, 1.0f, brand = "JBL", series = "EON700", model = "EON718S"),
            SpeakerPreset("lacoustics_x12", "L-Acoustics X12", 99f, 1.8f, brand = "L-Acoustics", series = "X", model = "X12"),
            SpeakerPreset("lacoustics_kara", "L-Acoustics Kara II", 105f, 6.5f, arrayElements = 8, elementSpacingM = 0.19f, brand = "L-Acoustics", series = "K", model = "Kara II"),
            SpeakerPreset("db_y10p", "d&b Y10P", 100f, 1.8f, brand = "d&b", series = "Y", model = "Y10P"),
            SpeakerPreset("db_y8", "d&b Y8", 104f, 6.5f, arrayElements = 8, elementSpacingM = 0.19f, brand = "d&b", series = "Y", model = "Y8"),
            SpeakerPreset("meyer_ultra_x40", "Meyer ULTRA-X40", 100f, 1.8f, brand = "Meyer", series = "ULTRA-X", model = "ULTRA-X40"),
            SpeakerPreset("meyer_leopard", "Meyer LEOPARD", 106f, 6.8f, arrayElements = 8, elementSpacingM = 0.19f, brand = "Meyer", series = "LEO", model = "LEOPARD"),
            SpeakerPreset("ev_ekx12p", "Electro-Voice EKX-12P", 98f, 1.7f, brand = "Electro-Voice", series = "EKX", model = "EKX-12P"),
            SpeakerPreset("qsc_k12_2", "QSC K12.2", 99f, 1.7f, brand = "QSC", series = "K", model = "K12.2"),
            SpeakerPreset("rcf_hdl6a", "RCF HDL 6-A", 104f, 6.2f, arrayElements = 8, elementSpacingM = 0.19f, brand = "RCF", series = "HDL", model = "HDL 6-A"),
            SpeakerPreset("adamson_s10", "Adamson S10", 105f, 6.6f, arrayElements = 8, elementSpacingM = 0.18f, brand = "Adamson", series = "S", model = "S10"),
            SpeakerPreset("martin_w8lc", "Martin W8LC", 104f, 6.5f, arrayElements = 8, elementSpacingM = 0.18f, brand = "Martin", series = "W8", model = "W8LC")
        )
        val INDUSTRY_STARTER_PRESETS = listOf(
            SpeakerPreset("jbl_srx812p", "JBL SRX812P (starter)", 98f, 1.7f, brand = "JBL", series = "SRX", model = "SRX812P"),
            SpeakerPreset("jbl_vtx_a8", "JBL VTX A8 (starter)", 105f, 6.8f, arrayElements = 8, elementSpacingM = 0.14f, brand = "JBL", series = "VTX", model = "A8"),
            SpeakerPreset("jbl_vtx_b18", "JBL VTX B18 (starter)", 99f, 1.1f, brand = "JBL", series = "VTX", model = "B18"),
            SpeakerPreset("jbl_eon_one_compact", "JBL EON ONE Compact (starter)", 90f, 1.3f, brand = "JBL", series = "EON ONE", model = "EON ONE Compact"),
            SpeakerPreset("jbl_eon_one_mk2", "JBL EON ONE MK2 (starter)", 91f, 1.4f, brand = "JBL", series = "EON ONE", model = "EON ONE MK2"),
            SpeakerPreset("jbl_eon_one", "JBL EON ONE (starter)", 90f, 1.4f, brand = "JBL", series = "EON ONE", model = "EON ONE"),
            SpeakerPreset("jbl_vtx_a6", "JBL VTX A6 (starter)", 105f, 6.8f, arrayElements = 8, elementSpacingM = 0.13f, brand = "JBL", series = "VTX", model = "A6"),
            SpeakerPreset("jbl_vtx_a12", "JBL VTX A12 (starter)", 106f, 6.9f, arrayElements = 8, elementSpacingM = 0.16f, brand = "JBL", series = "VTX", model = "A12"),
            SpeakerPreset("jbl_vtx_a12w", "JBL VTX A12W (starter)", 106f, 6.9f, arrayElements = 8, elementSpacingM = 0.16f, brand = "JBL", series = "VTX", model = "A12W"),
            SpeakerPreset("jbl_vtx_b28", "JBL VTX B28 (starter)", 100f, 1.2f, brand = "JBL", series = "VTX", model = "B28"),
            SpeakerPreset("jbl_vtx_g28", "JBL VTX G28 (starter)", 100f, 1.2f, brand = "JBL", series = "VTX", model = "G28"),
            SpeakerPreset("jbl_vtx_f12", "JBL VTX F12 (starter)", 101f, 1.8f, brand = "JBL", series = "VTX", model = "F12"),
            SpeakerPreset("jbl_vtx_f35", "JBL VTX F35 (starter)", 103f, 1.8f, brand = "JBL", series = "VTX", model = "F35"),
            SpeakerPreset("jbl_srx906la", "JBL SRX906LA (starter)", 104f, 6.5f, arrayElements = 6, elementSpacingM = 0.16f, brand = "JBL", series = "SRX900", model = "SRX906LA"),
            SpeakerPreset("jbl_srx910la", "JBL SRX910LA (starter)", 105f, 6.6f, arrayElements = 6, elementSpacingM = 0.18f, brand = "JBL", series = "SRX900", model = "SRX910LA"),
            SpeakerPreset("jbl_srx918s", "JBL SRX918S (starter)", 99f, 1.0f, brand = "JBL", series = "SRX900", model = "SRX918S"),
            SpeakerPreset("jbl_srx928s", "JBL SRX928S (starter)", 100f, 1.1f, brand = "JBL", series = "SRX900", model = "SRX928S"),
            SpeakerPreset("jbl_prx908", "JBL PRX908 (starter)", 97f, 1.6f, brand = "JBL", series = "PRX900", model = "PRX908"),
            SpeakerPreset("jbl_prx912", "JBL PRX912 (starter)", 98f, 1.7f, brand = "JBL", series = "PRX900", model = "PRX912"),
            SpeakerPreset("jbl_prx915", "JBL PRX915 (starter)", 99f, 1.8f, brand = "JBL", series = "PRX900", model = "PRX915"),
            SpeakerPreset("jbl_prx925", "JBL PRX925 (starter)", 100f, 1.8f, brand = "JBL", series = "PRX900", model = "PRX925"),
            SpeakerPreset("jbl_prx915xlf", "JBL PRX915XLF (starter)", 98f, 1.0f, brand = "JBL", series = "PRX900", model = "PRX915XLF"),
            SpeakerPreset("jbl_prx918xlf", "JBL PRX918XLF (starter)", 99f, 1.0f, brand = "JBL", series = "PRX900", model = "PRX918XLF"),
            SpeakerPreset("jbl_eon710", "JBL EON710 (starter)", 97f, 1.7f, brand = "JBL", series = "EON700", model = "EON710"),
            SpeakerPreset("jbl_eon712", "JBL EON712 (starter)", 98f, 1.7f, brand = "JBL", series = "EON700", model = "EON712"),
            SpeakerPreset("jbl_eon715", "JBL EON715 (starter)", 99f, 1.8f, brand = "JBL", series = "EON700", model = "EON715"),
            SpeakerPreset("jbl_eon718s", "JBL EON718S (starter)", 98f, 1.0f, brand = "JBL", series = "EON700", model = "EON718S"),
            SpeakerPreset("lacoustics_x12", "L-Acoustics X12 (starter)", 99f, 1.8f, brand = "L-Acoustics", series = "X", model = "X12"),
            SpeakerPreset("lacoustics_kara", "L-Acoustics Kara II (starter)", 105f, 6.5f, arrayElements = 8, elementSpacingM = 0.19f, brand = "L-Acoustics", series = "K", model = "Kara II"),
            SpeakerPreset("db_y10p", "d&b Y10P (starter)", 100f, 1.8f, brand = "d&b", series = "Y", model = "Y10P"),
            SpeakerPreset("db_y8", "d&b Y8 (starter)", 104f, 6.5f, arrayElements = 8, elementSpacingM = 0.19f, brand = "d&b", series = "Y", model = "Y8"),
            SpeakerPreset("meyer_ultra_x40", "Meyer ULTRA-X40 (starter)", 100f, 1.8f, brand = "Meyer", series = "ULTRA-X", model = "ULTRA-X40"),
            SpeakerPreset("meyer_leopard", "Meyer LEOPARD (starter)", 106f, 6.8f, arrayElements = 8, elementSpacingM = 0.19f, brand = "Meyer", series = "LEO", model = "LEOPARD"),
            SpeakerPreset("ev_ekx12p", "Electro-Voice EKX-12P (starter)", 98f, 1.7f, brand = "Electro-Voice", series = "EKX", model = "EKX-12P"),
            SpeakerPreset("qsc_k12_2", "QSC K12.2 (starter)", 99f, 1.7f, brand = "QSC", series = "K", model = "K12.2"),
            SpeakerPreset("rcf_hdl6a", "RCF HDL 6-A (starter)", 104f, 6.2f, arrayElements = 8, elementSpacingM = 0.19f, brand = "RCF", series = "HDL", model = "HDL 6-A")
        )
    }

    // Lazy: AcousticEngine's companion loads the native library on class init, which
    // no JVM unit test can satisfy. Nothing touches the engine during construction -
    // the compute paths are guarded by engineReady - so deferring it makes the view
    // model constructible off-device without changing behaviour on one.
    private val engine by lazy { AcousticEngine() }
    private var appAssetManager: AssetManager? = null

    private val _speakers    = MutableStateFlow<List<PlacedSpeaker>>(emptyList())
    val speakers: StateFlow<List<PlacedSpeaker>> = _speakers.asStateFlow()

    private val _results     = MutableStateFlow<List<SpeakerResult>>(emptyList())
    val results: StateFlow<List<SpeakerResult>> = _results.asStateFlow()

    private val _heatmap     = MutableStateFlow<List<HeatCell>>(emptyList())
    val heatmap: StateFlow<List<HeatCell>> = _heatmap.asStateFlow()

    private val _audience    = MutableStateFlow<List<AudiencePoint>>(emptyList())
    val audience: StateFlow<List<AudiencePoint>> = _audience.asStateFlow()

    private val _audienceAreas = MutableStateFlow<List<AudienceArea>>(emptyList())
    val audienceAreas: StateFlow<List<AudienceArea>> = _audienceAreas.asStateFlow()

    private val _areaDraft = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    val areaDraft: StateFlow<List<Pair<Float, Float>>> = _areaDraft.asStateFlow()
    private val _cutoutTarget = MutableStateFlow<AudienceCutoutTarget?>(null)
    val cutoutTarget = _cutoutTarget.asStateFlow()
    private val _elevationTarget = MutableStateFlow<Int?>(null)
    val elevationTarget = _elevationTarget.asStateFlow()
    private val _audienceGeometryError = MutableStateFlow<String?>(null)
    val audienceGeometryError = _audienceGeometryError.asStateFlow()
    private val _importedFaceSelection = MutableStateFlow<ImportedFaceSelection?>(null)
    val importedFaceSelection = _importedFaceSelection.asStateFlow()

    private val _facePickMode = MutableStateFlow(FacePickMode.SINGLE)
    val facePickMode = _facePickMode.asStateFlow()
    fun setFacePickMode(mode: FacePickMode) { _facePickMode.value=mode }
    fun pickImportedFace(ray: ViewportRay): ImportedFaceHit? {
        val hit=ImportedMeshPicking.pick(_audienceAreas.value.map {it.sharedSurface()},ray)
        if (hit==null) { clearImportedFaceSelection(); return null }
        val mesh=_audienceAreas.value.first {it.id==hit.areaId}.importedMesh!!
        val current=_importedFaceSelection.value?.takeIf {it.areaId==hit.areaId}?.faceIndices.orEmpty()
        selectImportedFaces(hit.areaId,ImportedMeshPicking.select(mesh,current,hit.faceIndex,_facePickMode.value))
        return hit
    }

    fun selectImportedFaces(areaId: Int,indices: Collection<Int>): Boolean {
        val mesh=_audienceAreas.value.find {it.id==areaId}?.importedMesh ?: return false
        val count=mesh.triangles().size
        if (indices.size>count || indices.any {it !in 0 until count}) return false
        _importedFaceSelection.value=ImportedFaceSelection(areaId,indices.toSet())
        return true
    }
    fun clearImportedFaceSelection() { _importedFaceSelection.value=null }
    private fun editImportedMesh(areaId: Int,edit: (AudienceMesh)->AudienceMesh): Boolean {
        val area=_audienceAreas.value.find {it.id==areaId} ?: return false
        val mesh=area.importedMesh ?: return false
        val updated=runCatching {edit(mesh)}
        if (updated.isFailure) { _audienceGeometryError.value=updated.exceptionOrNull()?.message; return false }
        if (updated.getOrThrow()===mesh) { _audienceGeometryError.value=null; return true }
        return updateAudienceElevation(area.copy(importedMesh=updated.getOrThrow()))
    }
    fun assignImportedFaces(areaId: Int,indices: Collection<Int>,assignment: MeshFaceAssignment): Boolean =
        editImportedMesh(areaId) {it.assignFaces(indices,assignment)}
    fun createImportedFaceGroup(areaId: Int,name: String,indices: Collection<Int>): Boolean = editImportedMesh(areaId) {
        require(indices.isNotEmpty()) { "Select faces before creating a group" }
        it.createFaceGroup(name,indices)
    }
    fun renameImportedFaceGroup(areaId: Int,groupId: Int,name: String): Boolean =
        editImportedMesh(areaId) {it.renameFaceGroup(groupId,name)}
    fun setImportedFaceGroupMembers(areaId: Int,groupId: Int,indices: Collection<Int>): Boolean =
        editImportedMesh(areaId) {it.setFaceGroupMembers(groupId,indices)}
    fun addToImportedFaceGroup(areaId: Int,groupId: Int,indices: Collection<Int>): Boolean =
        editImportedMesh(areaId) {it.addToFaceGroup(groupId,indices)}
    fun ungroupImportedFaces(areaId: Int,indices: Collection<Int>): Boolean =
        editImportedMesh(areaId) {it.ungroupFaces(indices)}
    fun removeImportedFaceGroup(areaId: Int,groupId: Int): Boolean =
        editImportedMesh(areaId) {it.removeFaceGroup(groupId)}
    fun assignImportedBoundaryRole(areaId: Int,indices: Collection<Int>,role: AcousticBoundaryRole): Boolean =
        editImportedMesh(areaId) {it.assignBoundaryRoles(indices,role)}
    fun assignImportedGroupBoundaryRole(areaId: Int,groupId: Int,role: AcousticBoundaryRole): Boolean =
        editImportedMesh(areaId) {it.assignGroupBoundaryRole(groupId,role)}
    fun assignImportedFaceGroup(areaId: Int,groupId: Int,assignment: MeshFaceAssignment): Boolean =
        editImportedMesh(areaId) {it.assignFaceGroup(groupId,assignment)}
    fun selectImportedFaceGroup(areaId: Int,groupId: Int): Boolean {
        val mesh=_audienceAreas.value.find {it.id==areaId}?.importedMesh ?: return false
        val group=runCatching {mesh.faceGroup(groupId)}
        if (group.isFailure) { _audienceGeometryError.value=group.exceptionOrNull()?.message; return false }
        _audienceGeometryError.value=null
        return selectImportedFaces(areaId,group.getOrThrow().faces())
    }

    private val _engineReady = MutableStateFlow(false)
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    private val _speakerPresets = MutableStateFlow(SPEAKER_PRESETS)
    val speakerPresets: StateFlow<List<SpeakerPreset>> = _speakerPresets.asStateFlow()

    private val _speakerSources = MutableStateFlow<List<SpeakerSource>>(emptyList())
    val speakerSources: StateFlow<List<SpeakerSource>> = _speakerSources.asStateFlow()

    private val _activeSpeakerSourceId = MutableStateFlow<Int?>(null)
    val activeSpeakerSourceId: StateFlow<Int?> = _activeSpeakerSourceId.asStateFlow()

    private val _speakerModelPackages = MutableStateFlow(SPEAKER_MODEL_PACKAGES)
    val speakerModelPackages: StateFlow<List<SpeakerModelPackage>> = _speakerModelPackages.asStateFlow()

    private val _selectedPresetId = MutableStateFlow(SPEAKER_PRESETS.first().id)
    val selectedPresetId: StateFlow<String> = _selectedPresetId.asStateFlow()

    private val _selectedBandHz = MutableStateFlow(1000)
    val selectedBandHz: StateFlow<Int> = _selectedBandHz.asStateFlow()

    private val _signalLevelDbu = MutableStateFlow(0f)
    val signalLevelDbu: StateFlow<Float> = _signalLevelDbu.asStateFlow()

    /** Z, A or C. Only meaningful across a broadband sum - see WEIGHTING_DB. */
    private val _weighting = MutableStateFlow(WEIGHTING_Z)
    val weighting: StateFlow<String> = _weighting.asStateFlow()

    private val _signalType = MutableStateFlow("BAND")
    val signalType: StateFlow<String> = _signalType.asStateFlow()

    private val _signalBandwidthOct = MutableStateFlow(1f / 3f)
    val signalBandwidthOct: StateFlow<Float> = _signalBandwidthOct.asStateFlow()

    private val _signalResolution = MutableStateFlow(24)
    val signalResolution: StateFlow<Int> = _signalResolution.asStateFlow()

    /**
     * How iso-levels are shown over the coverage map. A view layer, not a
     * calculation.
     *
     * "Bands" quantises the ramp itself into steps, so the boundaries appear as
     * colour changes with nothing drawn on top - the same information as a
     * contour line, without an overlay competing with the field for attention.
     * "Lines" draws them explicitly, which is easier to trace across a busy map.
     */
    private val _contourMode = MutableStateFlow(CONTOUR_OFF)
    val contourMode: StateFlow<String> = _contourMode.asStateFlow()

    /** Draw aim rays from each box, ArrayCalc style. A view layer, not a calculation. */
    private val _receiverAngularOverlayEnabled=MutableStateFlow(false)
    val receiverAngularOverlayEnabled=_receiverAngularOverlayEnabled.asStateFlow()
    private val _receiverAngularSelection=MutableStateFlow("listener")
    val receiverAngularSelection=_receiverAngularSelection.asStateFlow()
    private fun angularReceiverIds()=listOf("listener")+_alignmentReferences.value.receivers.map {"reference:${it.id}"}
    private val _receiverCameraFocus=MutableStateFlow<com.droidacoustic.pro.geometry.ReceiverCameraFocus?>(null)
    val receiverCameraFocus=_receiverCameraFocus.asStateFlow()
    private val _receiverFrameToken=MutableStateFlow(0L)
    val receiverFrameToken=_receiverFrameToken.asStateFlow()
    private val _receiverFrameMessage=MutableStateFlow<String?>(null)
    val receiverFrameMessage=_receiverFrameMessage.asStateFlow()
    fun receiverFrameMessage(message:String?) {_receiverFrameMessage.value=message}
    fun frameAngularReceiver(view:String):Boolean {
        val mode=com.droidacoustic.pro.geometry.ReceiverFrameView.entries.firstOrNull {it.name==view} ?: return false
        val id=com.droidacoustic.pro.geometry.ReceiverMarkerSelection.accept(_receiverAngularSelection.value,angularReceiverIds()) ?: return false
        _receiverCameraFocus.value=com.droidacoustic.pro.geometry.ReceiverCameraFocus(id,mode,_receiverCameraFocus.value?.spanM ?: 8f)
        _receiverFrameToken.value++;_receiverFrameMessage.value=null;return true
    }
    fun setReceiverFrameView(view:String) {
        val mode=com.droidacoustic.pro.geometry.ReceiverFrameView.entries.firstOrNull {it.name==view} ?: return
        _receiverCameraFocus.value=_receiverCameraFocus.value?.copy(view=mode)
        if(_receiverCameraFocus.value!=null) _receiverFrameToken.value++
    }
    fun scaleReceiverFrame(factor:Float) {
        if(!factor.isFinite() || factor<=0) return
        _receiverCameraFocus.value=_receiverCameraFocus.value?.let {it.copy(spanM=(it.spanM*factor).coerceIn(2f,64f))}
        if(_receiverCameraFocus.value!=null) _receiverFrameToken.value++
    }
    fun clearReceiverFrame() {_receiverCameraFocus.value=null;_receiverFrameMessage.value=null}
    fun receiverFrameTarget():com.droidacoustic.pro.prediction.AngularReceiver? {
        val id=_receiverCameraFocus.value?.receiverId ?: return null
        if(id=="listener") {val p=_listener.value;return com.droidacoustic.pro.prediction.AngularReceiver(id,"Listener",BoundaryPoint(p.x.toDouble(),p.earHeightM.toDouble(),p.z.toDouble()))}
        return _alignmentReferences.value.receivers.firstOrNull {"reference:${it.id}"==id}?.let {com.droidacoustic.pro.prediction.AngularReceiver(id,it.receiver.name,it.receiver.position)}
    }
    fun selectAngularReceiver(id:String):Boolean {
        val accepted=com.droidacoustic.pro.geometry.ReceiverMarkerSelection.accept(id,angularReceiverIds()) ?: return false
        _receiverAngularSelection.value=accepted;return true
    }
    fun setReceiverAngularOverlayEnabled(enabled:Boolean) {
        if(enabled==_receiverAngularOverlayEnabled.value) return
        _receiverAngularOverlayEnabled.value=enabled
    }
    /** Main-thread freeze; support calculation is done by the caller on its worker. */
    fun captureReceiverAngularOverlay():Pair<com.droidacoustic.pro.prediction.PredictionRequest,List<com.droidacoustic.pro.prediction.AngularReceiver>> {
        val p=_listener.value
        val targets=listOf(com.droidacoustic.pro.prediction.AngularReceiver("listener","Listener",com.droidacoustic.pro.geometry.BoundaryPoint(p.x.toDouble(),p.earHeightM.toDouble(),p.z.toDouble())))+
            _alignmentReferences.value.receivers.map {r->com.droidacoustic.pro.prediction.AngularReceiver("reference:${r.id}",r.receiver.name,r.receiver.position)}
        return capturePredictionRequest(PredictionTicket(_revision.value,0,PredictionChannel.LISTENER),_speakers.value.toList(),emptyList()) to targets
    }
    private val _aimRaysEnabled = MutableStateFlow(false)
    val aimRaysEnabled: StateFlow<Boolean> = _aimRaysEnabled.asStateFlow()

    private val _groundReflectionEnabled = MutableStateFlow(false)
    val groundReflectionEnabled: StateFlow<Boolean> = _groundReflectionEnabled.asStateFlow()

    private val _signalInterferenceEnabled = MutableStateFlow(true)
    val signalInterferenceEnabled: StateFlow<Boolean> = _signalInterferenceEnabled.asStateFlow()

    private val _signalAutoCalculate = MutableStateFlow(false)
    val signalAutoCalculate: StateFlow<Boolean> = _signalAutoCalculate.asStateFlow()

    private val _signalSplEnabled = MutableStateFlow(true)
    val signalSplEnabled: StateFlow<Boolean> = _signalSplEnabled.asStateFlow()

    // ─── SPL colour scale ─────────────────────────────────────────────────────
    // AUTO rescales the ramp to whatever the current calculation happened to
    // produce, so red means "loudest cell in this run" and nothing more - two
    // designs cannot be compared, and you cannot read "within 6 dB of target"
    // off the map. TARGET and FIXED pin the ramp to absolute dB instead.
    private val _splScaleMode = MutableStateFlow(SPL_SCALE_AUTO)
    val splScaleMode: StateFlow<String> = _splScaleMode.asStateFlow()

    private val _splTargetDb = MutableStateFlow(95f)
    val splTargetDb: StateFlow<Float> = _splTargetDb.asStateFlow()

    private val _splSpanDb = MutableStateFlow(6f)
    val splSpanDb: StateFlow<Float> = _splSpanDb.asStateFlow()

    private val _splFixedMinDb = MutableStateFlow(70f)
    val splFixedMinDb: StateFlow<Float> = _splFixedMinDb.asStateFlow()

    private val _splFixedMaxDb = MutableStateFlow(105f)
    val splFixedMaxDb: StateFlow<Float> = _splFixedMaxDb.asStateFlow()

    private val _signalDispersionEnabled = MutableStateFlow(true)
    val signalDispersionEnabled: StateFlow<Boolean> = _signalDispersionEnabled.asStateFlow()

    private val _signalCoverageEnabled = MutableStateFlow(true)
    val signalCoverageEnabled: StateFlow<Boolean> = _signalCoverageEnabled.asStateFlow()

    private val _highestSplDb = MutableStateFlow<Float?>(null)
    val highestSplDb: StateFlow<Float?> = _highestSplDb.asStateFlow()

    private val _temperatureC = MutableStateFlow(20f)
    val temperatureC: StateFlow<Float> = _temperatureC.asStateFlow()

    private val _humidityPct = MutableStateFlow(50f)
    val humidityPct: StateFlow<Float> = _humidityPct.asStateFlow()

    private val _dspMap = MutableStateFlow<Map<Int, SpeakerDsp>>(emptyMap())
    val dspMap: StateFlow<Map<Int, SpeakerDsp>> = _dspMap.asStateFlow()

    private val _combinedSplDb = MutableStateFlow<Float?>(null)
    val combinedSplDb: StateFlow<Float?> = _combinedSplDb.asStateFlow()

    // Compatibility states only; no legacy room solver publishes these in production.
    private val _earlyReflections = MutableStateFlow<List<EarlyReflection>>(emptyList())
    val earlyReflections: StateFlow<List<EarlyReflection>> = _earlyReflections.asStateFlow()

    private val _rt60Estimate = MutableStateFlow<Rt60Estimate?>(null)
    val rt60Estimate: StateFlow<Rt60Estimate?> = _rt60Estimate.asStateFlow()

    private val _stiEstimate = MutableStateFlow<StiEstimate?>(null)
    val stiEstimate: StateFlow<StiEstimate?> = _stiEstimate.asStateFlow()

    // CLF (Common Loudspeaker Format) polar pattern registry: maps speaker ID to parsed CLF data.
    // NOTE: This now starts empty and is populated by real-data ingestion paths (JSON imports or bundled assets).
    private val projectAssets = SpeakerAssetCache()
    private var projectAssetReferences: Map<String, SpeakerAssetRef> = emptyMap()
    private val _assetRecoveryProblems = MutableStateFlow<List<AssetProblem>>(emptyList())
    val assetRecoveryProblems: StateFlow<List<AssetProblem>> = _assetRecoveryProblems.asStateFlow()
    fun projectAssetPayloads(): Map<String, String> = projectAssets.snapshot()
    fun resolveProjectAsset(hash: String): String? = projectAssets.resolve(hash)

    private val _clfRegistry = MutableStateFlow<Map<String, ClfData>>(emptyMap())
    val clfRegistry: StateFlow<Map<String, ClfData>> = _clfRegistry.asStateFlow()

    // Bundled manufacturer CLF binaries discovered from assets/clf/sources.json.
    // These are tracked for ingestion status; in-app parser attempts CF/CF2/GLL/DLL
    // metadata-aware decoding with inferred directivity generation.
    private val _clfBinaryAssets = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val clfBinaryAssets: StateFlow<Map<String, List<String>>> = _clfBinaryAssets.asStateFlow()

    // Additional manufacturer data assets that are known but still unresolved in-app
    // (e.g. unsupported payloads, archive decode failures, tool bundles).
    private val _clfExternalAssets = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val clfExternalAssets: StateFlow<Map<String, List<String>>> = _clfExternalAssets.asStateFlow()
    private val _clfSourceStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val clfSourceStatus: StateFlow<Map<String, String>> = _clfSourceStatus.asStateFlow()
    private val _clfIngestionStats = MutableStateFlow(ClfIngestionStats())
    val clfIngestionStats: StateFlow<ClfIngestionStats> = _clfIngestionStats.asStateFlow()
    private val _strictExtractedBinaryClfOnly = MutableStateFlow(false)
    val strictExtractedBinaryClfOnly: StateFlow<Boolean> = _strictExtractedBinaryClfOnly.asStateFlow()

    private val _coverageComparisonSelection=MutableStateFlow<CoverageComparisonSelection?>(null)
    val coverageComparisonSelection=_coverageComparisonSelection.asStateFlow()
    private val _coverageDifference=MutableStateFlow<CoverageDifference?>(null)
    val coverageDifference=_coverageDifference.asStateFlow()
    private val _coverageComparisonError=MutableStateFlow<String?>(null)
    val coverageComparisonError=_coverageComparisonError.asStateFlow()
    private val _coverageComparisonRunning=MutableStateFlow(false)
    val coverageComparisonRunning=_coverageComparisonRunning.asStateFlow()
    private var comparisonJob: Job?=null
    private var comparisonGeneration=0L

    fun selectCoverageComparison(baselineId: Int,candidateId: Int) {
        require(listOf(baselineId,candidateId).all {id -> _designVariants.value.variants.any {it.id==id}})
        val selection=CoverageComparisonSelection(baselineId,candidateId)
        if(selection==_coverageComparisonSelection.value) return
        pushUndoCheckpoint()
        _coverageComparisonSelection.value=selection
    }

    /** The Android adapter reads saved scene fields through the existing import path.
     * It never restores into the live editor; requests, geometry checks and delta math are shared. */
    internal fun variantCoverageRequest(variant: DesignVariant): PredictionRequest {
        val isolated=SceneViewModel(predictionRunner,predictionDispatcher,geometryCache,snapshotOnly=true)
        val opened=ProjectArchive.open(variant.sceneJson)
        require(isolated.applyPreparedProject(opened,false)) {isolated.lastImportError.value ?: "Invalid saved design"}
        require(isolated.speakers.value.isNotEmpty()) {"Saved design has no speakers"}
        return isolated.capturePredictionRequest(PredictionTicket(0,variant.id.toLong(),PredictionChannel.COVERAGE),
            isolated.speakers.value,emptyList(),coveragePlan=isolated.captureCoveragePlan())
    }

    fun calculateCoverageComparison() {
        comparisonJob?.cancel()
        val generation=++comparisonGeneration
        _coverageDifference.value=null;_coverageComparisonError.value=null;_coverageComparisonRunning.value=false
        val selection=_coverageComparisonSelection.value ?: return
        val variants=_designVariants.value.variants
        val a=variants.firstOrNull {it.id==selection.baselineId}
        val b=variants.firstOrNull {it.id==selection.candidateId}
        if(a==null || b==null) {_coverageComparisonError.value="Select two saved designs";return}
        _coverageComparisonRunning.value=true
        comparisonJob=viewModelScope.launch {
            try {
                val result=withContext(predictionDispatcher) {
                    val context=currentCoroutineContext()
                    CoverageComparisons.calculate(variantCoverageRequest(a),variantCoverageRequest(b)) {context.ensureActive()}
                }
                if(generation==comparisonGeneration) _coverageDifference.value=result
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(error: Exception) {
                if(generation==comparisonGeneration) _coverageComparisonError.value=error.message ?: "Comparison failed"
            } finally {if(generation==comparisonGeneration) _coverageComparisonRunning.value=false}
        }
    }

    private val _designVariants=MutableStateFlow(DesignVariants.empty())
    val designVariants=_designVariants.asStateFlow()
    fun captureDesignVariant(name: String): Boolean = runCatching {
        val updated=_designVariants.value.capture(name,exportSceneJson(false,includeDesignLibrary=false),projectAssets::resolve)
        pushUndoCheckpoint();_designVariants.value=updated;_lastImportError.value=null
    }.onFailure {_lastImportError.value=it.message ?: "Unable to capture design"}.isSuccess
    fun restoreDesignVariant(id: Int): Boolean {
        val prepared=runCatching {_designVariants.value.prepareRestore(id,JSONObject(exportSceneJson(false,includeDesignLibrary=false)).apply {
            _coverageComparisonSelection.value?.let {put("coverageComparison",JSONObject(it.toJsonString()))}
        }.toString(),projectAssets::resolve)}
            .getOrElse {_lastImportError.value=it.message ?: "Unable to restore design";return false}
        return applyPreparedProject(prepared,true)
    }
    fun removeDesignVariant(id: Int) {
        val updated=_designVariants.value.remove(id)
        pushUndoCheckpoint();_designVariants.value=updated
        _coverageComparisonSelection.value?.let {if(it.baselineId==id || it.candidateId==id) _coverageComparisonSelection.value=null}
    }
    fun renameDesignVariant(id: Int,name: String) {
        if(name.isBlank() || name.length>200) return
        val updated=_designVariants.value.rename(id,name)
        pushUndoCheckpoint();_designVariants.value=updated
    }

    private val _referenceTraceSelection=MutableStateFlow(ReferenceTraceSelection())
    val referenceTraceSelection=_referenceTraceSelection.asStateFlow()
    private val _referenceTraceRunning=MutableStateFlow(false)
    val referenceTraceRunning=_referenceTraceRunning.asStateFlow()
    private val _referenceTraceMessage=MutableStateFlow<String?>(null)
    val referenceTraceMessage=_referenceTraceMessage.asStateFlow()
    private var referenceTraceJob: Job?=null
    private var referenceTraceGeneration=0L

    fun selectTraceReceivers(ids: List<Int>) {
        val selection=ReferenceTraceSelection(ids)
        selection.receivers(_alignmentReferences.value)
        if(selection.ids==_referenceTraceSelection.value.ids) return
        pushUndoCheckpoint();_referenceTraceSelection.value=selection
    }
    fun captureReferenceTraces(name: String) {
        referenceTraceJob?.cancel()
        val generation=++referenceTraceGeneration
        _referenceTraceRunning.value=false;_referenceTraceMessage.value=null
        val prepared=runCatching {
            require(_speakers.value.isNotEmpty()) {"Add a speaker before capturing traces"}
            val receivers=_referenceTraceSelection.value.receivers(_alignmentReferences.value)
            require(receivers.isNotEmpty()) {"Select at least one saved reference receiver"}
            require(_savedTraces.value.traces.size+receivers.size<=64) {"Not enough space: at most 64 saved traces are supported"}
            val inputs=JSONObject(exportSceneJson(false,includeDesignLibrary=false)).apply {remove("savedTraces");remove("referenceTraceSelection");remove("receiverAngularOverlayEnabled");remove("receiverAngularSelection");remove("receiverCameraFocus")}.toString()
            val metadata=TraceMetadata(receivers.first().receiver.position,
                if(_signalType.value=="SPECTRUM") 1f else _signalBandwidthOct.value,_temperatureC.value,_humidityPct.value,
                TraceComparisons.fingerprint(inputs),System.currentTimeMillis(),predictionDataWarnings().joinToString("\n"))
            val capture=ReferenceTraceCapture(name,receivers,metadata)
            val ticket=PredictionTicket(_revision.value,generation,PredictionChannel.LISTENER)
            val cells=receivers.map {r->val p=r.receiver.position;HeatCell(p.x.toFloat(),p.z.toFloat(),Float.NaN,renderY=p.y.toFloat())}
            capture to capturePredictionRequest(ticket,_speakers.value.toList(),cells,retainBandPowers=true)
        }.getOrElse {_referenceTraceMessage.value=it.message ?: "Unable to capture traces";return}
        val (capture,request)=prepared
        _referenceTraceRunning.value=true
        referenceTraceJob=viewModelScope.launch {
            try {
                val output=withContext(predictionDispatcher) {predictionRunner.run(request)}
                if(generation!=referenceTraceGeneration || request.ticket.sceneRevision!=_revision.value || output.ticket!=request.ticket) return@launch
                val updated=capture.capture(_savedTraces.value,output)
                // Detach the completed job before recording history, which invalidates pending work.
                referenceTraceJob=null
                pushUndoCheckpoint();_savedTraces.value=updated
                _referenceTraceMessage.value="Captured ${capture.receivers.size} reference traces for $name"
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(error:Exception) {if(generation==referenceTraceGeneration) _referenceTraceMessage.value=error.message ?: "Trace capture failed"}
            finally {if(generation==referenceTraceGeneration) _referenceTraceRunning.value=false}
        }
    }

    private val _comparisonSourceSpectrum=MutableStateFlow(BroadbandSourceSpectrum.preset("PER_BAND_REFERENCE"))
    val comparisonSourceSpectrum=_comparisonSourceSpectrum.asStateFlow()
    private val _broadbandDrive=MutableStateFlow(BroadbandDrive.DEFAULT)
    val broadbandDrive=_broadbandDrive.asStateFlow()
    fun setBroadbandDrive(mode:BroadbandDrive) {
        if(mode==_broadbandDrive.value) return
        pushUndoCheckpoint();_broadbandDrive.value=mode;recomputeSignalIfNeeded()
    }
    fun setComparisonSourceSpectrum(text: String): Boolean = runCatching {
        val spectrum=BroadbandSourceSpectrum.parseJson(text)
        spectrum.fractions(OctaveWeighting.bands)
        if(spectrum.canonical()!=_comparisonSourceSpectrum.value.canonical()) {pushUndoCheckpoint();_comparisonSourceSpectrum.value=spectrum}
        _lastImportError.value=null
    }.onFailure {_lastImportError.value=it.message ?: "Invalid comparison spectrum"}.isSuccess

    private val engineCaptureGate=PredictionGate()
    private var engineCaptureJob: Job?=null
    private var engineCaptureTicket: PredictionTicket?=null
    private val _engineCaptureRunning=MutableStateFlow(false)
    val engineCaptureRunning=_engineCaptureRunning.asStateFlow()
    private val _engineCaptureMessage=MutableStateFlow<String?>(null)
    val engineCaptureMessage=_engineCaptureMessage.asStateFlow()
    internal data class PendingEngineCapture(val request: PredictionRequest,val ticket: PredictionTicket,
        val targets: List<SpeakerComparisonSample>,val referenceHash: String,
        val normalization: RelativeResponseNormalization?,val levelConvention: String)
    internal fun prepareEngineCapture(): PendingEngineCapture {
        val reference=_comparisonWorkspace.value.reference ?: error("Import reference sample locations/frequencies first")
        require(reference.run.conditions.levelConvention in listOf("ABSOLUTE_SPL","BROADBAND_SPL") || reference.normalization!=null) {"Relative capture needs an explicit normalization receiver in import version 2"}
        require(_speakers.value.isNotEmpty()) {"Place a speaker before capture"}
        require((_signalType.value=="SPECTRUM")== (reference.run.conditions.levelConvention in listOf("BROADBAND_SPL","RELATIVE_BROADBAND_TO_REFERENCE"))) {"Select Spectrum for broadband references, or Band for per-frequency references"}
        val ticket=engineCaptureGate.begin(PredictionChannel.LISTENER,_revision.value)
        val request=capturePredictionRequest(ticket,_speakers.value.toList(),emptyList(),forComparison=true)
        engineCaptureTicket=ticket
        return PendingEngineCapture(request,ticket,reference.run.samples.toList(),reference.documentSha256,reference.normalization,reference.run.conditions.levelConvention)
    }
    internal fun finishEngineCapture(pending: PendingEngineCapture,result: ImportedComparison): Boolean {
        if(_comparisonWorkspace.value.reference?.documentSha256!=pending.referenceHash ||
            !engineCaptureGate.complete(pending.ticket,_revision.value)) return false
        _engineCaptureRunning.value=false;engineCaptureJob=null
        applyComparisonWorkspace(_comparisonWorkspace.value.withImported(result,false))
        _engineCaptureMessage.value="Captured immutable engine snapshot; inspect protocol differences before treating it as matched"
        return true
    }
    fun captureEngineComparison() {
        engineCaptureJob?.cancel()
        val pending=runCatching {prepareEngineCapture()}.getOrElse {_engineCaptureRunning.value=false;_engineCaptureMessage.value=it.message;return}
        _engineCaptureRunning.value=true;_engineCaptureMessage.value=null
        engineCaptureJob=viewModelScope.launch {
            try {
                val result=withContext(predictionDispatcher) {
                    val context=currentCoroutineContext()
                    pending.request.captureComparison(pending.targets,pending.ticket.sceneRevision,pending.normalization,pending.levelConvention,pending.levelConvention in listOf("BROADBAND_SPL","RELATIVE_BROADBAND_TO_REFERENCE")) {context.ensureActive()}
                }
                finishEngineCapture(pending,result)
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(failure: Exception) {if(engineCaptureTicket==pending.ticket) _engineCaptureMessage.value=failure.message ?: "Capture failed"}
            finally {if(engineCaptureTicket==pending.ticket) _engineCaptureRunning.value=false}
        }
    }

    private val _comparisonWorkspace=MutableStateFlow(ComparisonWorkspace.empty())
    val comparisonWorkspace=_comparisonWorkspace.asStateFlow()
    fun importComparisonDocument(document: String, asReference: Boolean): Boolean = runCatching {
        val prepared=_comparisonWorkspace.value.import(document,asReference)
        applyComparisonWorkspace(prepared)
    }.onFailure {_lastImportError.value=it.message ?: "Invalid comparison data"}.isSuccess
    /** UI parses off Main, then merges one immutable imported artifact into the current workspace. */
    fun applyComparisonImport(data: ImportedComparison, asReference: Boolean): Boolean = runCatching {
        applyComparisonWorkspace(_comparisonWorkspace.value.withImported(data,asReference))
    }.onFailure {_lastImportError.value=it.message ?: "Invalid comparison data"}.isSuccess
    private fun applyComparisonWorkspace(prepared: ComparisonWorkspace) {
        if(prepared.toJsonString()!=_comparisonWorkspace.value.toJsonString()) {
            pushUndoCheckpoint();_comparisonWorkspace.value=prepared
        }
        _lastImportError.value=null
    }
    fun clearComparisonImports() = applyComparisonWorkspace(ComparisonWorkspace.empty())

    private val _savedTraces=MutableStateFlow(SavedTraces.empty())
    val savedTraces=_savedTraces.asStateFlow()
    private val _currentTrace=MutableStateFlow<SavedTrace?>(null)
    val currentTrace=_currentTrace.asStateFlow()
    fun captureListenerTrace(name: String): Boolean {
        if(name.isBlank()) return false
        val trace=_currentTrace.value ?: return false
        val ticket=listenerResultTicket ?: return false
        if(!predictionGate.isLatest(ticket,_revision.value) || _savedTraces.value.traces.size>=64) return false
        val updated=_savedTraces.value.capture(name,trace.metadata,trace.powers,trace.phases,trace.phaseConvention,trace.delayReferenceS)
        pushUndoCheckpoint();_savedTraces.value=updated;return true
    }
    fun setOverlayInspection(selection:com.droidacoustic.pro.traces.MatchSampleSelection?,changedOnly:Boolean) {
        val current=_savedTraces.value
        if(current.inspectedSample==selection && current.overlayChangedOnly==changedOnly) return
        val updated=current.withInspectedSample(selection).withOverlayChangedOnly(changedOnly)
        pushUndoCheckpoint();_savedTraces.value=updated
    }
    fun applyExclusionHistoryPreview(preview:com.droidacoustic.pro.traces.ExclusionHistoryPreview):Boolean {
        val current=_savedTraces.value
        val updated=runCatching {com.droidacoustic.pro.traces.ExclusionHistoryPreviews.apply(current,preview)}.getOrNull() ?: return false
        if(updated!==current) {pushUndoCheckpoint();_savedTraces.value=updated}
        return true
    }
    fun navigateMatchingExclusionHistory(view:com.droidacoustic.pro.traces.ExclusionHistoryView,position:Int):Boolean {
        val current=_savedTraces.value
        val updated=runCatching {current.navigateExclusionHistory(view,position)}.getOrNull() ?: return false
        if(updated!==current) {pushUndoCheckpoint();_savedTraces.value=updated}
        return true
    }
    fun undoMatchingExclusion(a:Int,b:Int,redo:Boolean=false) {
        val current=_savedTraces.value
        val updated=if(redo) current.redoExclusion(a,b) else current.undoExclusion(a,b)
        if(updated===current) return
        pushUndoCheckpoint();_savedTraces.value=updated
    }
    fun editMatchingRange(original:com.droidacoustic.pro.traces.MatchRangeExclusion,replacement:com.droidacoustic.pro.traces.MatchRangeExclusion):Boolean {
        val updated=runCatching {_savedTraces.value.replaceRangeExclusion(original,replacement)}.getOrNull() ?: return false
        if(original!=replacement) {pushUndoCheckpoint();_savedTraces.value=updated}
        return true
    }
    fun excludeMatchingRange(range:com.droidacoustic.pro.traces.MatchRangeExclusion,exclude:Boolean) {
        if((range in _savedTraces.value.rangeExclusions)==exclude) return
        val updated=_savedTraces.value.withRangeExcluded(range,exclude)
        pushUndoCheckpoint();_savedTraces.value=updated
    }
    fun excludeMatchingSample(sample:com.droidacoustic.pro.traces.MatchSampleSelection,exclude:Boolean) {
        if((sample in _savedTraces.value.manualExclusions)==exclude) return
        val updated=_savedTraces.value.withSampleExcluded(sample,exclude)
        pushUndoCheckpoint();_savedTraces.value=updated
    }
    fun inspectMatchingSample(selection:com.droidacoustic.pro.traces.MatchSampleSelection?) {
        if(_savedTraces.value.inspectedSample==selection) return
        val updated=_savedTraces.value.withInspectedSample(selection)
        pushUndoCheckpoint();_savedTraces.value=updated
    }
    fun setMatchingFrequencyRange(range:com.droidacoustic.pro.traces.MatchingFrequencyRange?) {
        if(_savedTraces.value.matchingFrequencyRange==range) return
        val updated=_savedTraces.value.withMatchingFrequencyRange(range)
        pushUndoCheckpoint();_savedTraces.value=updated
    }
    fun applyTraceReferenceMatch(preview:com.droidacoustic.pro.traces.TraceReferenceMatch):Boolean {
        val updated=runCatching {com.droidacoustic.pro.traces.TraceReferenceMatching.apply(_savedTraces.value,preview)}.getOrNull() ?: return false
        pushUndoCheckpoint();_savedTraces.value=updated;return true
    }
    fun setTraceDelayReference(id:Int,seconds:Double?) {
        val current=_savedTraces.value.traces.firstOrNull {it.id==id} ?: return
        if(current.manualDelayReferenceS==seconds) return
        val updated=_savedTraces.value.withDelayReference(id,seconds)
        pushUndoCheckpoint();_savedTraces.value=updated
    }
    fun setTraceRelativeDisplay(relative:Boolean) {
        if(_savedTraces.value.relativeToReference==relative) return
        val updated=_savedTraces.value.withRelativeDisplay(relative)
        pushUndoCheckpoint();_savedTraces.value=updated
    }
    fun removeSavedTrace(id: Int) {
        val updated=_savedTraces.value.remove(id)
        pushUndoCheckpoint();_savedTraces.value=updated
    }

    private val _alignmentReferences = MutableStateFlow(AlignmentReferences.empty())
    val alignmentReferences = _alignmentReferences.asStateFlow()
    private val _alignmentReceiver = MutableStateFlow<AlignmentReceiver?>(null)
    val alignmentReceiver = _alignmentReceiver.asStateFlow()
    private val _alignmentSourceId = MutableStateFlow<Int?>(null)
    val alignmentSourceId = _alignmentSourceId.asStateFlow()
    private fun publishAlignment(state: AlignmentReferences) {
        _alignmentReferences.value=state
        _receiverCameraFocus.value=_receiverCameraFocus.value?.reconcile(angularReceiverIds())
        _receiverAngularSelection.value=com.droidacoustic.pro.geometry.ReceiverMarkerSelection.reconcile(_receiverAngularSelection.value,angularReceiverIds()) ?: "listener"
        _referenceTraceSelection.value=_referenceTraceSelection.value.reconcile(state)
        _alignmentReceiver.value=state.selected?.receiver
        _alignmentSourceId.value=state.selected?.referenceSourceId
    }
    fun addAlignmentReceiver(name: String, x: Float, y: Float, z: Float) {
        val updated=_alignmentReferences.value.add(AlignmentReceiver(name,BoundaryPoint(x.toDouble(),y.toDouble(),z.toDouble())),
            _alignmentSourceId.value ?: _speakers.value.firstOrNull()?.id)
        pushUndoCheckpoint();publishAlignment(updated)
    }
    fun selectAlignmentReceiver(id: Int) {
        val updated=_alignmentReferences.value.select(id)
        pushUndoCheckpoint();publishAlignment(updated)
    }
    fun removeAlignmentReceiver(id: Int) {
        val updated=_alignmentReferences.value.remove(id)
        pushUndoCheckpoint();publishAlignment(updated)
    }
    fun renameAlignmentReceiver(id: Int, name: String) {
        if(name.isBlank()) return
        val state=_alignmentReferences.value;val entry=state.receivers.firstOrNull {it.id==id} ?: return
        val updated=state.update(id,entry.receiver.copy(name=name),entry.referenceSourceId)
        pushUndoCheckpoint();publishAlignment(updated)
    }
    fun setAlignmentReceiver(name: String, x: Float, y: Float, z: Float) {
        val state=_alignmentReferences.value;val selected=state.selected
        if(selected==null) {addAlignmentReceiver(name,x,y,z);return}
        val updated=state.update(selected.id,AlignmentReceiver(name,BoundaryPoint(x.toDouble(),y.toDouble(),z.toDouble())),selected.referenceSourceId)
        pushUndoCheckpoint();publishAlignment(updated)
    }
    fun setAlignmentSource(id: Int) {
        if(_speakers.value.none {it.id==id}) return
        val state=_alignmentReferences.value;val selected=state.selected ?: return
        val updated=state.update(selected.id,selected.receiver,id)
        pushUndoCheckpoint();publishAlignment(updated)
    }
    private fun alignmentSources(air: Atmosphere): List<AlignmentSource> =_speakers.value.map {s ->
            val dsp=_dspMap.value[s.id] ?: SpeakerDsp(s.id)
            AlignmentSource(s.id,predictionSources(s,dsp,air),dsp.delayMs.toDouble())
        }
    fun allAlignmentComparisons(): List<ReceiverAlignmentComparison> {
        val air=Atmosphere(_temperatureC.value,_humidityPct.value)
        return _alignmentReferences.value.compare(alignmentSources(air),air.speedOfSound)
    }
    fun alignmentComparisons(): List<ArrivalComparison> = allAlignmentComparisons()
        .firstOrNull {it.receiver.id==_alignmentReferences.value.selectedId}?.arrivals.orEmpty()
    fun previewAlignmentDelay(sourceId: Int): AlignmentDelayPreview? {
        val air=Atmosphere(_temperatureC.value,_humidityPct.value)
        return _alignmentReferences.value.previewDelay(alignmentSources(air),air.speedOfSound,sourceId)
    }
    /** Reject a stale preview instead of silently applying a different adjustment. */
    fun applyAlignmentPreview(preview: AlignmentDelayPreview): Boolean {
        val current=previewAlignmentDelay(preview.sourceId) ?: return false
        if(current!=preview) return false
        setDelay(current.sourceId,current.proposedDelayMs.toFloat());return true
    }
    fun applyAlignmentDelay(sourceId: Int): Boolean = previewAlignmentDelay(sourceId)?.let(::applyAlignmentPreview) ?: false

    private fun adjustmentInputsJson()=JSONObject(exportSceneJson(false)).apply {
        remove("receiverAngularOverlayEnabled");remove("receiverAngularSelection");remove("receiverCameraFocus")
    }.toString()
    private fun SpeakerDsp.systemDsp() = SystemDsp(gainDb,delayMs,polarity)
    /** Snapshot on Main; calculate both states off Main without touching the live scene. */
    suspend fun previewSystemAdjustment(sourceId: Int, proposed: SystemDsp): SystemAdjustmentPreview? {
        if(_speakers.value.none {it.id==sourceId}) return null
        val current=_dspMap.value[sourceId] ?: SpeakerDsp(sourceId)
        val change=SystemAdjustment(sourceId,current.systemDsp(),proposed)
        val references=_alignmentReferences.value
        if(!change.changed || references.receivers.isEmpty()) return null
        val key=adjustmentInputsJson()
        val cells=references.receivers.map {r -> val p=r.receiver.position
            HeatCell(p.x.toFloat(),p.z.toFloat(),Float.NaN,renderY=p.y.toFloat()) }
        val ticket=PredictionTicket(_revision.value,0,PredictionChannel.LISTENER)
        val before=capturePredictionRequest(ticket,_speakers.value.toList(),cells,retainBandPowers=true)
        val dsps=_dspMap.value+(sourceId to current.copy(gainDb=proposed.gainDb,delayMs=proposed.delayMs,polarity=proposed.inverted))
        val after=capturePredictionRequest(ticket,_speakers.value.toList(),cells,dsps=dsps,retainBandPowers=true)
        val air=Atmosphere(_temperatureC.value,_humidityPct.value)
        val sources=alignmentSources(air)
        val result=withContext(Dispatchers.Default) {
            val context=currentCoroutineContext()
            SystemAdjustmentComparison.calculate(change,key,references,sources,air.speedOfSound,before,after) {context.ensureActive()}
        }
        return result.takeIf {it.applicable(adjustmentInputsJson(),(_dspMap.value[sourceId] ?: SpeakerDsp(sourceId)).systemDsp())}
    }
    fun applySystemAdjustment(preview: SystemAdjustmentPreview): Boolean {
        val id=preview.adjustment.sourceId
        if(_speakers.value.none {it.id==id}) return false
        val current=_dspMap.value[id] ?: SpeakerDsp(id)
        if(!preview.applicable(adjustmentInputsJson(),current.systemDsp())) return false
        val next=preview.adjustment.apply(current.systemDsp()) ?: return false
        pushUndoCheckpoint()
        _dspMap.value=_dspMap.value+(id to current.copy(gainDb=next.gainDb,delayMs=next.delayMs,polarity=next.inverted))
        recalculate();refreshHeatmap()
        return true
    }

    private val _listener    = MutableStateFlow(ListenerPos())
    val listener: StateFlow<ListenerPos> = _listener.asStateFlow()

    private val _roomMaterials = MutableStateFlow(RoomMaterials())
    val roomMaterials: StateFlow<RoomMaterials> = _roomMaterials.asStateFlow()

    private val _venueGeometry = MutableStateFlow(VenueGeometry())
    val venueGeometry: StateFlow<VenueGeometry> = _venueGeometry.asStateFlow()

    private val _reflectionOrder = MutableStateFlow(2)
    val reflectionOrder: StateFlow<Int> = _reflectionOrder.asStateFlow()

    private val _analysisProfile = MutableStateFlow("Balanced")
    val analysisProfile: StateFlow<String> = _analysisProfile.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────
    // Phase 9: ArrayCalc Optimizer state
    // ─────────────────────────────────────────────────────────────────────
    private val _optimizerEnabled = MutableStateFlow(false)
    val optimizerEnabled: StateFlow<Boolean> = _optimizerEnabled.asStateFlow()

    private val _optimizerMode = MutableStateFlow("MINIMIZE_VARIANCE")  // or "MAXIMIZE_COVERAGE"
    val optimizerMode: StateFlow<String> = _optimizerMode.asStateFlow()

    private val _optimizerParams = MutableStateFlow<List<OptimizerParam>>(emptyList())
    val optimizerParams: StateFlow<List<OptimizerParam>> = _optimizerParams.asStateFlow()

    private val _optimizerResult = MutableStateFlow<OptimizerResult?>(null)
    val optimizerResult: StateFlow<OptimizerResult?> = _optimizerResult.asStateFlow()

    private val _optimizerIsRunning = MutableStateFlow(false)
    val optimizerIsRunning: StateFlow<Boolean> = _optimizerIsRunning.asStateFlow()


    private val _coverageGeometry = MutableStateFlow<PreparedAudienceGeometry?>(null)
    val coverageGeometry: StateFlow<PreparedAudienceGeometry?> = _coverageGeometry.asStateFlow()
    private val _coverageStatistics = MutableStateFlow<CoverageStatistics?>(null)
    val coverageStatistics: StateFlow<CoverageStatistics?> = _coverageStatistics.asStateFlow()

    private val predictionGate = PredictionGate()
    private val _predictionState = MutableStateFlow(PredictionState.EMPTY)
    val predictionState: StateFlow<PredictionState> = _predictionState.asStateFlow()
    private val _predictionValidity=MutableStateFlow<PredictionValidity?>(null)
    val predictionValidity=_predictionValidity.asStateFlow()
    private var listenerValidity: PredictionValidity?=null
    private var coverageValidity: PredictionValidity?=null
    private var listenerResultTicket: PredictionTicket? = null
    private var coverageResultTicket: PredictionTicket? = null
    private fun refreshPredictionState() {
        _predictionValidity.value=if(_signalCoverageEnabled.value) coverageValidity else listenerValidity
        val channels = buildSet {
            if (_signalSplEnabled.value && _speakers.value.isNotEmpty()) {
                add(PredictionChannel.LISTENER)
                if (_signalCoverageEnabled.value) add(PredictionChannel.COVERAGE)
            }
        }
        _predictionState.value = predictionGate.state(channels)
    }

    private var autoRecomputeJob: Job? = null
    private var recalcJob: Job? = null
    private var heatmapJob: Job? = null

    /**
     * Stop every background analysis job and wait for them to unwind.
     *
     * viewModelScope does this on its own when the ViewModel is cleared, which
     * covers the app. Tests have no ViewModelStore to clear, so without an
     * explicit hook a finished test leaves recalculation still running and the
     * next one trips over `Dispatchers.Main is used concurrently with setting
     * it`. Bounded, so a wedged job cannot hang a suite.
     */
    internal fun cancelAnalysisJobs(timeoutMs: Long = 3_000) {
        val jobs = listOfNotNull(engineCaptureJob,referenceTraceJob,comparisonJob,autoRecomputeJob, recalcJob, heatmapJob)
        jobs.forEach { it.cancel() }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (jobs.any { it.isActive } && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    private val _activeZoneType = MutableStateFlow("AUDIENCE_SEATED")
    val activeZoneType: StateFlow<String> = _activeZoneType.asStateFlow()

    private val _activeZoneBaseHeightM = MutableStateFlow(0f)
    val activeZoneBaseHeightM: StateFlow<Float> = _activeZoneBaseHeightM.asStateFlow()

    private val _activeZoneRakeDeg = MutableStateFlow(0f)
    val activeZoneRakeDeg: StateFlow<Float> = _activeZoneRakeDeg.asStateFlow()

    private val _activeZoneRakeDirectionDeg = MutableStateFlow(0f)
    val activeZoneRakeDirectionDeg: StateFlow<Float> = _activeZoneRakeDirectionDeg.asStateFlow()

    private val _selectedSpeakerModelPackageId = MutableStateFlow("generic")
    val selectedSpeakerModelPackageId: StateFlow<String> = _selectedSpeakerModelPackageId.asStateFlow()

    private val _lastImportError = MutableStateFlow<String?>(null)
    val lastImportError: StateFlow<String?> = _lastImportError.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var isApplyingHistory = false

    private var nextId = 0
    private var nextSourceId = 0
    private var nextAudienceId = 0
    private var nextAreaId = 0

    private fun updateHistoryState() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    /**
     * Bumped whenever anything the scene JSON captures changes.
     *
     * The autosave used to be triggered by a hand-written list of the flows it
     * cared about, and that list fell behind the state it was meant to capture:
     * the analysis type, the weighting, the contour mode and the aim-ray toggle
     * were all written into the JSON but changing them never triggered a write,
     * so they came back at their defaults after a restart. A single counter
     * cannot drift, because it does not enumerate anything.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Records that persisted state changed. Safe to call more often than needed. */
    private fun touch() {
        engineCaptureJob?.cancel();engineCaptureJob=null
        if(_engineCaptureRunning.value) _engineCaptureMessage.value="Capture discarded: scene or comparison inputs changed"
        _engineCaptureRunning.value=false
        referenceTraceGeneration++
        referenceTraceJob?.cancel()
        _referenceTraceRunning.value=false
        _referenceTraceMessage.value=null
        comparisonGeneration++
        comparisonJob?.cancel()
        _coverageDifference.value=null
        _coverageComparisonRunning.value=false
        _coverageComparisonError.value=null
        _revision.value = _revision.value + 1
        clearAnalysisResults(PredictionState.STALE)
        if (snapshotOnly) return
        val changedRevision = _revision.value
        autoRecomputeJob = viewModelScope.launch {
            // touch() precedes the synchronous mutation. Capture only after that edit completes.
            yield()
            if (_revision.value != changedRevision || !_signalAutoCalculate.value) return@launch
            if (predictionGate.needsRequest(PredictionChannel.LISTENER, changedRevision)) recalculate()
            if (predictionGate.needsRequest(PredictionChannel.COVERAGE, changedRevision)) refreshHeatmap()
        }
    }

    /** The absorption curve, so a scene reopens with the numbers it was designed on. */
    private fun alphaJson(m: SurfaceMaterial): JSONObject {
        val o = JSONObject()
        SUPPORTED_BANDS_HZ.forEach { o.put(it.toString(), m.alphaAt(it).toDouble()) }
        return o
    }

    /**
     * Restore one surface, newest form first.
     *
     * A catalogue id is enough on its own and keeps the scene readable. The
     * stored curve is used when the id is unknown - a custom surface, or a
     * catalogue entry from a later version. Failing both, a scene from before
     * per-band absorption existed carries one broadband number, which becomes a
     * flat curve: the same answer that version would have given.
     */
    private fun readSurface(rm: JSONObject, key: String, fallback: SurfaceMaterial): SurfaceMaterial {
        SurfaceMaterial.byId(rm.optString("${key}Material", ""))?.let { return it }
        rm.optJSONObject("${key}AlphaByBand")?.let { curve ->
            val byBand = SUPPORTED_BANDS_HZ.mapNotNull { band ->
                val v = curve.optDouble(band.toString(), Double.NaN)
                if (v.isNaN()) null else band to v.toFloat().coerceIn(0.01f, 1f)
            }.toMap()
            if (byBand.isNotEmpty()) {
                return SurfaceMaterial(SurfaceMaterial.CUSTOM_ID, "Custom", byBand)
            }
        }
        val legacy = rm.optDouble("${key}Alpha", Double.NaN)
        if (!legacy.isNaN()) return SurfaceMaterial.flat(legacy.toFloat())
        return fallback
    }

    private fun pushUndoCheckpoint() {
        touch()
        if (isApplyingHistory) return
        val snapshot = exportSceneJson(includeClfRegistry = false)
        if (undoStack.isEmpty() || undoStack.last() != snapshot) {
            undoStack.addLast(snapshot)
            while (undoStack.size > MAX_HISTORY_ENTRIES) undoStack.removeFirst()
        }
        redoStack.clear()
        updateHistoryState()
    }

    fun undoScene(): Boolean {
        if (undoStack.isEmpty()) return false
        val current = exportSceneJson(includeClfRegistry = false)
        val target = undoStack.removeLast()
        if (redoStack.isEmpty() || redoStack.last() != current) {
            redoStack.addLast(current)
            while (redoStack.size > MAX_HISTORY_ENTRIES) redoStack.removeFirst()
        }
        isApplyingHistory = true
        val ok = importSceneJsonInternal(target, recordHistory = false)
        isApplyingHistory = false
        if (!ok) {
            // Restore stack pointers if apply fails.
            undoStack.addLast(target)
            if (redoStack.isNotEmpty()) redoStack.removeLast()
        }
        updateHistoryState()
        return ok
    }

    fun redoScene(): Boolean {
        if (redoStack.isEmpty()) return false
        val current = exportSceneJson(includeClfRegistry = false)
        val target = redoStack.removeLast()
        if (undoStack.isEmpty() || undoStack.last() != current) {
            undoStack.addLast(current)
            while (undoStack.size > MAX_HISTORY_ENTRIES) undoStack.removeFirst()
        }
        isApplyingHistory = true
        val ok = importSceneJsonInternal(target, recordHistory = false)
        isApplyingHistory = false
        if (!ok) {
            // Restore stack pointers if apply fails.
            redoStack.addLast(target)
            if (undoStack.isNotEmpty()) undoStack.removeLast()
        }
        updateHistoryState()
        return ok
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun initEngine(assetManager: AssetManager) {
        appAssetManager = assetManager
        viewModelScope.launch {
            _engineReady.value = withContext(Dispatchers.Default) {
                engine.init(assetManager)
            }
            launch(Dispatchers.Default) {
                loadBundledClfSources(assetManager)
            }
        }
    }

    fun setStrictExtractedBinaryClfOnly(enabled: Boolean) {
        if (_strictExtractedBinaryClfOnly.value == enabled) return
        _strictExtractedBinaryClfOnly.value = enabled
        val assets = appAssetManager ?: return
        viewModelScope.launch(Dispatchers.Default) {
            loadBundledClfSources(assets)
        }
    }

    /**
     * Loads bundled manufacturer CLF source inventory from assets/clf/sources.json.
     *
     * Supported local asset ingestion:
     *  - *.json : parsed via [ClfParser] and added to [_clfRegistry]
    *  - *.cf1 / *.cf2 / *.gll / *.dll / *.spk : parsed via [ClfBinaryParser] (metadata + directivity synthesis)
     *  - *.zip : scanned for JSON/binary payloads; selective binary decode attempted per speaker
     */
    private fun loadBundledClfSources(assetManager: AssetManager) {
        runCatching {
            val discoveredPresets = discoverBundledSpeakerPresets(assetManager)
            if (discoveredPresets.isNotEmpty()) {
                val merged = LinkedHashMap<String, SpeakerPreset>()
                (_speakerPresets.value + discoveredPresets).forEach { preset ->
                    merged[preset.id] = preset
                }
                _speakerPresets.value = merged.values.toList()
                if (_speakerPresets.value.none { it.id == _selectedPresetId.value }) {
                    _selectedPresetId.value = _speakerPresets.value.first().id
                }
            }

            val indexText = assetManager.open("clf/sources.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val root = JSONObject(indexText)
            val speakers = root.optJSONArray("speakers") ?: JSONArray()

            val parsedRegistry = _clfRegistry.value.toMutableMap()
            val binaryMap = mutableMapOf<String, MutableList<String>>()
            val externalMap = mutableMapOf<String, MutableList<String>>()
            val statusMap = mutableMapOf<String, String>()
            val jsonParsedSpeakers = mutableSetOf<String>()
            val binaryParsedSpeakers = mutableSetOf<String>()
            val extractedBinarySpeakers = mutableSetOf<String>()
            val inferredBinarySpeakers = mutableSetOf<String>()
            val strictExtractedOnly = _strictExtractedBinaryClfOnly.value

            for (i in 0 until speakers.length()) {
                val speakerObj = speakers.optJSONObject(i) ?: continue
                val speakerId = speakerObj.optString("speakerId", "").trim()
                if (speakerId.isBlank()) continue
                val brandHint = speakerObj.optString("brand", "").trim()
                val modelHint = speakerObj.optString("model", "").trim()

                val sources = speakerObj.optJSONArray("sources") ?: JSONArray()
                for (j in 0 until sources.length()) {
                    val srcObj = sources.optJSONObject(j) ?: continue
                    val localAsset = srcObj.optString("localAsset", "").trim()
                    val convertedJsonAsset = srcObj.optString("convertedJsonAsset", "").trim()

                    // Prefer explicit converted JSON sidecar when provided.
                    if (convertedJsonAsset.isNotBlank()) {
                        val jsonText = runCatching {
                            assetManager.open(convertedJsonAsset).use { it.readBytes().toString(Charsets.UTF_8) }
                        }.getOrNull()
                        if (jsonText != null) {
                            val parsed = ClfParser.parseJson(jsonText)
                            if (parsed != null && parsed.patterns.isNotEmpty()) {
                                val normalized = if (parsed.speakerId == speakerId) parsed else parsed.copy(speakerId = speakerId)
                                parsedRegistry[speakerId] = normalized
                                jsonParsedSpeakers.add(speakerId)
                                statusMap[speakerId] = "JSON"
                                continue
                            }
                        }
                    }

                    if (localAsset.isBlank()) continue

                    when {
                        localAsset.endsWith(".json", ignoreCase = true) -> {
                            val jsonText = runCatching {
                                assetManager.open(localAsset).use { it.readBytes().toString(Charsets.UTF_8) }
                            }.getOrNull() ?: continue

                            val parsed = ClfParser.parseJson(jsonText) ?: continue
                            val normalized = if (parsed.speakerId == speakerId) parsed else parsed.copy(speakerId = speakerId)
                            if (normalized.patterns.isNotEmpty()) {
                                parsedRegistry[speakerId] = normalized
                                jsonParsedSpeakers.add(speakerId)
                                statusMap[speakerId] = "JSON"
                            }
                        }
                        localAsset.endsWith(".cf1", ignoreCase = true) ||
                            localAsset.endsWith(".cf2", ignoreCase = true) ||
                            localAsset.endsWith(".gll", ignoreCase = true) ||
                            localAsset.endsWith(".dll", ignoreCase = true) ||
                            localAsset.endsWith(".spk", ignoreCase = true) -> {
                            binaryMap.getOrPut(speakerId) { mutableListOf() }.add(localAsset)
                            val bytes = runCatching {
                                assetManager.open(localAsset).use { it.readBytes() }
                            }.getOrNull() ?: continue

                            val parsedBinary = ClfBinaryParser.parseDetailed(
                                bytes = bytes,
                                speakerId = speakerId,
                                manufacturerHint = brandHint,
                                modelHint = modelHint
                            )
                            if (parsedBinary != null && parsedBinary.data.patterns.isNotEmpty()) {
                                if (!strictExtractedOnly || parsedBinary.usedExtractedMatrix) {
                                    parsedRegistry[speakerId] = parsedBinary.data
                                    statusMap[speakerId] = if (parsedBinary.usedExtractedMatrix) "Binary Extracted" else "Binary Inferred"
                                }
                                binaryParsedSpeakers.add(speakerId)
                                if (parsedBinary.usedExtractedMatrix) {
                                    extractedBinarySpeakers.add(speakerId)
                                } else {
                                    inferredBinarySpeakers.add(speakerId)
                                }
                            }
                        }
                        localAsset.endsWith(".zip", ignoreCase = true) -> {
                            // Inspect archive and discover embedded CLF payloads.
                            runCatching {
                                var decodedFromArchive = parsedRegistry.containsKey(speakerId)
                                var decodeAttempts = 0
                                assetManager.open(localAsset).use { input ->
                                    ZipInputStream(input).use { zip ->
                                        var entry = zip.nextEntry
                                        while (entry != null) {
                                            val entryName = entry.name
                                            if (!entry.isDirectory) {
                                                when {
                                                    entryName.endsWith(".cf1", ignoreCase = true) ||
                                                        entryName.endsWith(".cf2", ignoreCase = true) ||
                                                        entryName.endsWith(".gll", ignoreCase = true) ||
                                                        entryName.endsWith(".dll", ignoreCase = true) ||
                                                        entryName.endsWith(".spk", ignoreCase = true) -> {
                                                        binaryMap
                                                            .getOrPut(speakerId) { mutableListOf() }
                                                            .add("$localAsset!$entryName")

                                                        val shouldAttemptDecode = !decodedFromArchive &&
                                                            decodeAttempts < 8 &&
                                                            isLikelyBinaryMatch(entryName, speakerId, modelHint)
                                                        if (shouldAttemptDecode) {
                                                            decodeAttempts += 1
                                                            val bytes = zip.readBytes()
                                                            val parsedBinary = ClfBinaryParser.parseDetailed(
                                                                bytes = bytes,
                                                                speakerId = speakerId,
                                                                manufacturerHint = brandHint,
                                                                modelHint = modelHint
                                                            )
                                                            if (parsedBinary != null && parsedBinary.data.patterns.isNotEmpty()) {
                                                                if (!strictExtractedOnly || parsedBinary.usedExtractedMatrix) {
                                                                    parsedRegistry[speakerId] = parsedBinary.data
                                                                    statusMap[speakerId] = if (parsedBinary.usedExtractedMatrix) "Binary Extracted" else "Binary Inferred"
                                                                }
                                                                binaryParsedSpeakers.add(speakerId)
                                                                if (parsedBinary.usedExtractedMatrix) {
                                                                    extractedBinarySpeakers.add(speakerId)
                                                                    decodedFromArchive = true
                                                                } else {
                                                                    inferredBinarySpeakers.add(speakerId)
                                                                }
                                                            }
                                                        }
                                                    }
                                                    entryName.endsWith(".json", ignoreCase = true) -> {
                                                        // Read entry bytes, try parse as app JSON CLF schema.
                                                        val bytes = zip.readBytes()
                                                        val jsonText = bytes.toString(Charsets.UTF_8)
                                                        val parsed = ClfParser.parseJson(jsonText)
                                                        if (parsed != null && parsed.patterns.isNotEmpty()) {
                                                            val normalized = if (parsed.speakerId == speakerId) parsed else parsed.copy(speakerId = speakerId)
                                                            parsedRegistry[speakerId] = normalized
                                                            jsonParsedSpeakers.add(speakerId)
                                                            statusMap[speakerId] = "JSON"
                                                        }
                                                    }
                                                }
                                            }
                                            zip.closeEntry()
                                            entry = zip.nextEntry
                                        }
                                    }
                                }
                            }.onFailure {
                                externalMap.getOrPut(speakerId) { mutableListOf() }.add(localAsset)
                            }
                        }
                        else -> {
                            externalMap.getOrPut(speakerId) { mutableListOf() }.add(localAsset)
                        }
                    }
                }
            }


            // Fill unresolved statuses for display/audit.
            for (i in 0 until speakers.length()) {
                val speakerObj = speakers.optJSONObject(i) ?: continue
                val speakerId = speakerObj.optString("speakerId", "").trim()
                if (speakerId.isBlank() || statusMap.containsKey(speakerId)) continue

                val hasBinary = binaryMap[speakerId]?.isNotEmpty() == true
                val hasExternal = externalMap[speakerId]?.isNotEmpty() == true
                val hasParsed = parsedRegistry[speakerId]?.patterns?.isNotEmpty() == true

                statusMap[speakerId] = when {
                    hasParsed -> "Parsed"
                    hasBinary -> "Pending Binary"
                    hasExternal -> "External Only"
                    else -> "Untracked"
                }
            }

            // Publish catalogue changes on the same owner thread as request capture/results.
            viewModelScope.launch(Dispatchers.Main.immediate) {
                touch()
                _clfRegistry.value = parsedRegistry + _clfRegistry.value
                _clfBinaryAssets.value = binaryMap.mapValues { it.value.toList() }
                _clfExternalAssets.value = externalMap.mapValues { it.value.toList() }
                _clfSourceStatus.value = statusMap.toMap()
                val pendingBinary = binaryMap.keys.count { key -> parsedRegistry[key]?.patterns?.isNotEmpty() != true }
                _clfIngestionStats.value = ClfIngestionStats(
                    indexedSpeakers = speakers.length(),
                    parsedJsonSpeakers = jsonParsedSpeakers.size,
                    parsedBinarySpeakers = binaryParsedSpeakers.size,
                    extractedBinarySpeakers = extractedBinarySpeakers.size,
                    inferredBinarySpeakers = inferredBinarySpeakers.size,
                    registrySpeakers = parsedRegistry.size,
                    pendingBinarySpeakers = pendingBinary,
                    unresolvedExternalSpeakers = externalMap.size,
                    strictExtractedOnlyMode = strictExtractedOnly
                )
                recomputeSignalIfNeeded()
            }
        }.onFailure {
            _lastImportError.value = it.message ?: "Failed to load bundled CLF sources"
        }
    }

    private fun discoverBundledSpeakerPresets(assetManager: AssetManager): List<SpeakerPreset> {
        val discovered = linkedMapOf<String, SpeakerPreset>()
        fun walk(path: String) {
            val entries = runCatching { assetManager.list(path)?.toList().orEmpty() }.getOrDefault(emptyList())
            for (entry in entries) {
                val child = if (path.isBlank()) entry else "$path/$entry"
                val children = runCatching { assetManager.list(child)?.toList().orEmpty() }.getOrDefault(emptyList())
                if (children.isNotEmpty()) {
                    walk(child)
                    continue
                }

                if (!entry.endsWith(".cf2", ignoreCase = true) &&
                    !entry.endsWith(".cf1", ignoreCase = true) &&
                    !entry.endsWith(".gll", ignoreCase = true) &&
                    !entry.endsWith(".dll", ignoreCase = true) &&
                    !entry.endsWith(".spk", ignoreCase = true)
                ) continue

                val preset = speakerPresetFromAssetPath(child)
                if (preset != null && discovered[preset.id] == null && _speakerPresets.value.none { it.id == preset.id }) {
                    discovered[preset.id] = preset
                }
            }
        }

        walk("clf/raw")
        return discovered.values.toList()
    }

    private fun speakerPresetFromAssetPath(assetPath: String): SpeakerPreset? {
        val fileName = assetPath.substringAfterLast('/').substringBeforeLast('.')
        val normalized = fileName
            .lowercase()
            .replace("d&b audiotechnik-", "")
            .replace("d&b audiotechnik ", "")
            .replace("d&b", "")
            .replace(Regex("\\s+(horizontal|vertical)$"), "")
            .replace(Regex("\\s+hf\\s+\\d+[a-zåų̨]*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+\\d+x\\d+$"), "")
            .replace(Regex("\\s+\\d+u.*$"), "")
            .replace(Regex("\\s+[^a-z0-9-]+$"), "")
            .trim()

        if (normalized.isBlank()) return null

        val model = normalized.uppercase().replace(Regex("\\s+"), " ")
        val id = model.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { return null }

        val family = when {
            model.startsWith("CI") -> "Ci"
            model.startsWith("MAX") -> "MAX"
            model.startsWith("B") -> "B"
            model.startsWith("E") -> "E"
            model.startsWith("M") -> "M"
            model.startsWith("Q") -> "Q"
            model.startsWith("T") -> "T"
            model.startsWith("V") -> "V"
            model.startsWith("Y") -> "Y"
            model.firstOrNull()?.isDigit() == true -> "S"
            else -> "Other"
        }

        val sensitivity = when (family) {
            "Ci" -> 97f
            "MAX" -> 100f
            "B" -> 101f
            "E" -> 98f
            "M" -> 99f
            "Q" -> 100f
            "T" -> 101f
            "V" -> 102f
            "Y" -> 103f
            "S" -> 100f
            else -> 100f
        }

        val heightM = when {
            model.contains("SUB") -> 0.9f
            family == "Ci" -> 0.65f
            family == "B" -> 0.65f
            family == "T" -> 0.9f
            family == "MAX" -> 0.95f
            else -> 0.6f
        }

        val arrayElements = when {
            model.contains("SUB") -> 1
            model.startsWith("Y7P") || model.startsWith("V7P") || model.startsWith("Y10P") || model.startsWith("V10P") -> 1
            model.startsWith("24C") || model.startsWith("12S") || model.startsWith("10S") || model.startsWith("8S") -> 1
            model.startsWith("Q10") || model.startsWith("Q7") -> 1
            else -> 1
        }

        return SpeakerPreset(
            id = "db_${id}",
            name = "d&b $model",
            sensitivityDb = sensitivity,
            heightM = heightM,
            arrayElements = arrayElements,
            elementSpacingM = 0.19f,
            brand = "d&b",
            series = family,
            model = model
        )
    }

    private fun isLikelyBinaryMatch(entryName: String, speakerId: String, modelHint: String): Boolean {
        val entry = entryName.lowercase()
        val idTokens = speakerId
            .lowercase()
            .split('_', '-', ' ')
            .filter { it.length >= 3 }

        val modelTokens = modelHint
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.length >= 3 }

        val tokens = (idTokens + modelTokens)
            .distinct()
            .take(8)

        if (tokens.isEmpty()) return true

        var hitCount = 0
        for (token in tokens) {
            if (entry.contains(token)) hitCount += 1
        }
        return hitCount >= 2 || tokens.any { it.length >= 5 && entry.contains(it) }
    }

    override fun onCleared() {
        super.onCleared()
        geometryCache.clear()
        viewModelScope.launch { engine.destroy() }
    }

    // ─── Speaker management ───────────────────────────────────────────────────

    fun addSpeakerSource() {
        pushUndoCheckpoint()
        val id = nextSourceId++
        val src = SpeakerSource(id = id, name = "Source ${id + 1}")
        _speakerSources.value = _speakerSources.value + src
        _activeSpeakerSourceId.value = src.id
    }

    fun removeSpeakerSource(sourceId: Int) {
        if (_speakerSources.value.none { it.id == sourceId }) return
        pushUndoCheckpoint()
        _speakerSources.value = _speakerSources.value
            .filter { it.id != sourceId }
            .map { if (it.linkedSourceId == sourceId) it.copy(linkedSourceId = null) else it }
        _speakers.value = _speakers.value.map { if (it.sourceId == sourceId) it.copy(sourceId = null) else it }
        _activeSpeakerSourceId.value = _speakerSources.value.firstOrNull()?.id
        recalculate(); refreshHeatmap()
    }

    fun setActiveSpeakerSource(sourceId: Int?) {
        if (sourceId != null && _speakerSources.value.none { it.id == sourceId }) return
        _activeSpeakerSourceId.value = sourceId
    }

    fun setSpeakerSourceName(sourceId: Int, name: String) {
        val clean = name.trim().ifBlank { "Source ${sourceId + 1}" }
        _speakerSources.value = _speakerSources.value.map { if (it.id == sourceId) it.copy(name = clean) else it }
    }

    fun setSpeakerLabel(speakerId: Int, label: String) {
        val speaker = _speakers.value.firstOrNull { it.id == speakerId } ?: return
        val clean = label.trim().ifBlank { speaker.label }
        if (clean == speaker.label) return
        pushUndoCheckpoint()
        _speakers.value = _speakers.value.map {
            if (it.id == speakerId) it.copy(label = clean) else it
        }
        recalculate()
        refreshHeatmap()
    }

    fun setSpeakerSourceRole(sourceId: Int, role: String) {
        _speakerSources.value = _speakerSources.value.map { if (it.id == sourceId) it.copy(role = role) else it }
    }

    fun toggleSpeakerSourceCollapsed(sourceId: Int) {
        _speakerSources.value = _speakerSources.value.map { if (it.id == sourceId) it.copy(collapsed = !it.collapsed) else it }
    }

    fun setSpeakerSourceLink(sourceId: Int, linkedSourceId: Int?) {
        if (linkedSourceId != null && _speakerSources.value.none { it.id == linkedSourceId }) return
        _speakerSources.value = _speakerSources.value.map { src ->
            when (src.id) {
                sourceId -> src.copy(linkedSourceId = linkedSourceId)
                linkedSourceId -> src.copy(linkedSourceId = sourceId)
                else -> if (linkedSourceId == null && src.linkedSourceId == sourceId) src.copy(linkedSourceId = null) else src
            }
        }
    }

    fun setSpeakerSourceLinkMotionMode(sourceId: Int, mode: String) {
        if (!LINK_MOTION_MODES.contains(mode)) return
        _speakerSources.value = _speakerSources.value.map { src ->
            if (src.id == sourceId) src.copy(linkedMotionMode = mode) else src
        }
    }

    fun setSpeakerSourceBrand(sourceId: Int, brand: String) {
        val src = _speakerSources.value.firstOrNull { it.id == sourceId } ?: return
        if (src.brand == brand) return
        _speakerSources.value = _speakerSources.value.map {
            if (it.id != sourceId) it else it.copy(brand = brand, series = "Other", modelPresetId = null)
        }
    }

    fun setSpeakerSourceSeries(sourceId: Int, series: String) {
        val src = _speakerSources.value.firstOrNull { it.id == sourceId } ?: return
        if (src.series == series) return
        _speakerSources.value = _speakerSources.value.map {
            if (it.id != sourceId) it else it.copy(series = series, modelPresetId = null)
        }
    }

    private fun modelPackageIdForPreset(presetId: String?, fallback: String): String {
        if (presetId != null && isKnownModelPackage(presetId)) return presetId
        val preset = _speakerPresets.value.firstOrNull { it.id == presetId }
        if (preset != null) {
            val inferred = if (preset.arrayElements > 1) "line_array" else "point_source"
            if (isKnownModelPackage(inferred)) return inferred
        }
        return fallback.takeIf { isKnownModelPackage(it) } ?: "generic"
    }

    fun setSpeakerSourceModel(sourceId: Int, presetId: String?) {
        val src = _speakerSources.value.firstOrNull { it.id == sourceId } ?: return
        val pkgId = modelPackageIdForPreset(presetId, src.modelPackageId)
        _speakerSources.value = _speakerSources.value.map {
            if (it.id != sourceId) it else it.copy(modelPresetId = presetId, modelPackageId = pkgId)
        }
    }

    private fun linkedSourceIds(sourceId: Int?): Set<Int> {
        if (sourceId == null) return emptySet()
        val sources = _speakerSources.value
        val root = sources.firstOrNull { it.id == sourceId } ?: return emptySet()
        // Sharing an acoustic source is not an instruction to move every cabinet.
        // Only an explicitly linked *other* source follows an individual speaker edit.
        val set = linkedSetOf<Int>()
        root.linkedSourceId?.let { set += it }
        sources.filter { it.linkedSourceId == sourceId }.forEach { set += it.id }
        return set
    }

    private fun linkedMotionModeForSource(sourceId: Int?): String {
        if (sourceId == null) return "SAME"
        return _speakerSources.value.firstOrNull { it.id == sourceId }?.linkedMotionMode
            ?.takeIf { LINK_MOTION_MODES.contains(it) }
            ?: "SAME"
    }

    private fun normalizePanDeg(panDeg: Float): Float {
        return (((panDeg + 180f) % 360f + 360f) % 360f) - 180f
    }

    fun addSpeaker(x: Float, z: Float) {
        if (_speakerSources.value.isEmpty()) {
            val id = nextSourceId++
            val src = SpeakerSource(id = id, name = "Source ${id + 1}")
            _speakerSources.value = _speakerSources.value + src
            _activeSpeakerSourceId.value = src.id
        }
        val activeSourceId = _activeSpeakerSourceId.value ?: _speakerSources.value.firstOrNull()?.id ?: return
        pushUndoCheckpoint()
        val id  = nextId++
        val activeSource = _speakerSources.value.firstOrNull { it.id == activeSourceId }
            ?: _speakerSources.value.firstOrNull()
            ?: return
        val presets = _speakerPresets.value.ifEmpty { SPEAKER_PRESETS }
        val selectedPreset = presets.firstOrNull { it.id == _selectedPresetId.value } ?: presets.first()
        val preset = activeSource?.modelPresetId?.let { pid -> presets.firstOrNull { it.id == pid } } ?: selectedPreset
        val sourceName = activeSource?.name ?: preset.name
        val modelPkg = activeSource?.modelPackageId?.takeIf { isKnownModelPackage(it) }
            ?: _selectedSpeakerModelPackageId.value
        _speakers.value = _speakers.value + PlacedSpeaker(
            id    = id,
            x     = x,
            z     = z,
            heightM = preset.heightM,
            sensitivity = preset.sensitivityDb,
            arrayElements = preset.arrayElements,
            arraySpacingM = preset.elementSpacingM,
            arraySplayByBoxDeg = List((preset.arrayElements - 1).coerceAtLeast(0)) { 0f },
            panDeg = 0f,
            modelPackageId = modelPkg,
            presetId = preset.id,
            requiresMeasuredData = _clfRegistry.value.containsKey(preset.id) || preset.series == "CLF",
            sourceId = activeSource?.id,
            label = "${sourceName} ${id + 1}"
        )
        recalculate()
        refreshHeatmap()
    }

    fun setSpeakerPreset(presetId: String) {
        if (_speakerPresets.value.none { it.id == presetId }) return
        if (_selectedPresetId.value == presetId) return
        pushUndoCheckpoint()
        _selectedPresetId.value = presetId
    }

    /** Load/merge an industry starter preset pack (public-spec approximations). */
    fun loadIndustryStarterPresets(): Int {
        val merged = LinkedHashMap<String, SpeakerPreset>()
        _speakerPresets.value.forEach { merged[it.id] = it }
        var added = 0
        INDUSTRY_STARTER_PRESETS.forEach { preset ->
            if (!merged.containsKey(preset.id)) {
                merged[preset.id] = preset
                added++
            }
        }
        if (added > 0) {
            _speakerPresets.value = merged.values.toList()
            if (_speakerPresets.value.none { it.id == _selectedPresetId.value }) {
                _selectedPresetId.value = _speakerPresets.value.first().id
            }
        }
        return added
    }

    /** Load bundled starter catalog from assets/speaker_catalogs/industry_starter.json. */
    fun loadBundledIndustryCatalog(): IndustryCatalogLoadResult {
        val assets = appAssetManager ?: return IndustryCatalogLoadResult(
            presetsAdded = 0,
            modelPackagesAdded = 0,
            ok = false,
            message = "Assets unavailable"
        )

        val text = runCatching {
            assets.open("speaker_catalogs/industry_starter.json").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse {
            _lastImportError.value = it.message ?: "Failed to read bundled industry catalog"
            return IndustryCatalogLoadResult(
                presetsAdded = 0,
                modelPackagesAdded = 0,
                ok = false,
                message = _lastImportError.value
            )
        }

        val meta = runCatching { parseIndustryCatalogInfo(text) }.getOrNull()

        val presetIdsBefore = _speakerPresets.value.map { it.id }.toSet()
        val packageIdsBefore = _speakerModelPackages.value.map { it.id }.toSet()

        val presetsOk = importSpeakerLibraryJson(text)
        val packagesOk = importSpeakerModelPackagesJson(text)

        val presetIdsAfter = _speakerPresets.value.map { it.id }.toSet()
        val packageIdsAfter = _speakerModelPackages.value.map { it.id }.toSet()
        val presetsAdded = (presetIdsAfter - presetIdsBefore).size
        val modelPackagesAdded = (packageIdsAfter - packageIdsBefore).size

        val ok = presetsOk || packagesOk
        return IndustryCatalogLoadResult(
            presetsAdded = presetsAdded,
            modelPackagesAdded = modelPackagesAdded,
            ok = ok,
            message = if (ok) null else (_lastImportError.value ?: "Bundled industry catalog import failed"),
            catalogVersion = meta?.version,
            catalogSchema = meta?.schema
        )
    }

    fun getBundledIndustryCatalogInfo(): IndustryCatalogInfo? {
        val assets = appAssetManager ?: return null
        val text = runCatching {
            assets.open("speaker_catalogs/industry_starter.json").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return null
        return runCatching { parseIndustryCatalogInfo(text) }.getOrNull()
    }

    private fun parseIndustryCatalogInfo(text: String): IndustryCatalogInfo {
        val root = JSONObject(text)
        val presets = root.optJSONArray("presets")?.length() ?: 0
        val modelPackages = root.optJSONArray("modelPackages")?.length() ?: 0
        return IndustryCatalogInfo(
            schema = root.optString("schema", "droidacoustic.speaker-catalog"),
            version = root.optInt("version", 1).coerceAtLeast(1),
            presetCount = presets,
            modelPackageCount = modelPackages
        )
    }

    private fun sanitizeModelPackageId(raw: String, fallback: String): String {
        val id = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        return if (id.isBlank()) fallback else id
    }

    private fun normalizeModelPackageColor(value: Double, fallback: Float): Float {
        val v = value.toFloat()
        return when {
            v.isNaN() -> fallback
            v > 1f -> (v / 255f).coerceIn(0f, 1f)
            else -> v.coerceIn(0f, 1f)
        }
    }

    private fun parseSpeakerModelPackage(obj: JSONObject, idx: Int): SpeakerModelPackage {
        val fallbackId = "imported_pkg_$idx"
        val id = sanitizeModelPackageId(obj.optString("id", fallbackId), fallbackId)
        val name = obj.optString("name", "Imported Package ${idx + 1}").trim().ifBlank { "Imported Package ${idx + 1}" }
        val markerHeightM = obj.optDouble("markerHeightM", 2.0).toFloat().coerceIn(0.2f, 30f)
        val crossHalfM = obj.optDouble("crossHalfM", 0.35).toFloat().coerceIn(0.05f, 5f)
        val cabinetWidthM = obj.optDouble("cabinetWidthM", 0.42).toFloat().coerceIn(0.01f, 6f)
        val cabinetDepthM = obj.optDouble("cabinetDepthM", 0.40).toFloat().coerceIn(0.01f, 6f)
        val cabinetHeightM = obj.optDouble("cabinetHeightM", 0.65).toFloat().coerceIn(0.01f, 6f)

        val colorObj = obj.optJSONObject("color")
        val colorR = normalizeModelPackageColor(
            colorObj?.optDouble("r", obj.optDouble("colorR", 0.0)) ?: obj.optDouble("colorR", 0.0),
            0f
        )
        val colorG = normalizeModelPackageColor(
            colorObj?.optDouble("g", obj.optDouble("colorG", 0.9)) ?: obj.optDouble("colorG", 0.9),
            0.9f
        )
        val colorB = normalizeModelPackageColor(
            colorObj?.optDouble("b", obj.optDouble("colorB", 0.9)) ?: obj.optDouble("colorB", 0.9),
            0.9f
        )

        val modelAssetPath = obj.optString("modelAssetPath", "").trim().ifBlank { null }
        return SpeakerModelPackage(
            id = id,
            name = name,
            markerHeightM = markerHeightM,
            crossHalfM = crossHalfM,
            cabinetWidthM = cabinetWidthM,
            cabinetDepthM = cabinetDepthM,
            cabinetHeightM = cabinetHeightM,
            colorR = colorR,
            colorG = colorG,
            colorB = colorB,
            modelAssetPath = modelAssetPath
        )
    }

    private fun parseSpeakerModelPackageArray(text: String): List<SpeakerModelPackage> {
        val items = mutableListOf<SpeakerModelPackage>()

        if (text.startsWith("[")) {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                items += parseSpeakerModelPackage(obj, i)
            }
            return items
        }

        val root = JSONObject(text)
        val arr = root.optJSONArray("modelPackages")
            ?: root.optJSONArray("speakerModelPackages")
            ?: root.optJSONArray("packages")

        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                items += parseSpeakerModelPackage(obj, i)
            }
        } else {
            items += parseSpeakerModelPackage(root, 0)
        }
        return items
    }

    private fun mergeModelPackages(imported: List<SpeakerModelPackage>): List<SpeakerModelPackage> {
        val merged = LinkedHashMap<String, SpeakerModelPackage>()
        SPEAKER_MODEL_PACKAGES.forEach { merged[it.id] = it }
        imported.forEach { merged[it.id] = it }
        return merged.values.toList()
    }

    private fun isKnownModelPackage(packageId: String): Boolean {
        return _speakerModelPackages.value.any { it.id == packageId }
    }

    /** Phase 3+ — import speaker model package JSON from clipboard (supports array or object). */
    fun importSpeakerModelPackagesJson(json: String): Boolean {
        return runCatching {
            val items = parseSpeakerModelPackageArray(json.trim())
            if (items.isEmpty()) throw IllegalArgumentException("No speaker model packages found")
            _speakerModelPackages.value = mergeModelPackages(items)
            if (!isKnownModelPackage(_selectedSpeakerModelPackageId.value)) {
                _selectedSpeakerModelPackageId.value = "generic"
            }
            _lastImportError.value = null
        }.onFailure {
            _lastImportError.value = it.message ?: "Invalid speaker model package JSON"
        }.isSuccess
    }

    /** Phase 3 — import a speaker library JSON object/array from clipboard. */
    fun importSpeakerLibraryJson(json: String): Boolean {
        return runCatching {
            val text = json.trim()
            val items = mutableListOf<SpeakerPreset>()

            fun parsePreset(obj: JSONObject, idx: Int): SpeakerPreset {
                val idBase = obj.optString("id", "imported_$idx").trim().ifBlank { "imported_$idx" }
                val id = idBase.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
                val name = obj.optString("name", "Imported ${idx + 1}").trim().ifBlank { "Imported ${idx + 1}" }
                val sensitivity = obj.optDouble("sensitivityDb", obj.optDouble("sensitivity", 100.0)).toFloat().coerceIn(80f, 130f)
                val height = obj.optDouble("heightM", 1.8).toFloat().coerceIn(0.5f, 20f)
                val elements = obj.optInt("arrayElements", 1).coerceIn(1, 16)
                val spacing = obj.optDouble("elementSpacingM", 0.2).toFloat().coerceIn(0.05f, 1.0f)
                val brand = obj.optString("brand", "Other").trim().ifBlank { "Other" }
                val series = obj.optString("series", "Other").trim().ifBlank { "Other" }
                val model = obj.optString("model", name).trim().ifBlank { name }
                return SpeakerPreset(id, name, sensitivity, height, elements, spacing, brand, series, model)
            }

            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    items += parsePreset(obj, i)
                }
            } else {
                val root = JSONObject(text)
                val arr = root.optJSONArray("presets")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        items += parsePreset(obj, i)
                    }
                } else {
                    items += parsePreset(root, 0)
                }
            }

            if (items.isEmpty()) throw IllegalArgumentException("No speaker presets found")

            val merged = LinkedHashMap<String, SpeakerPreset>()
            (_speakerPresets.value + items).forEach { merged[it.id] = it }
            _speakerPresets.value = merged.values.toList()
            if (_speakerPresets.value.none { it.id == _selectedPresetId.value }) {
                _selectedPresetId.value = _speakerPresets.value.first().id
            }
            _lastImportError.value = null
        }.onFailure {
            _lastImportError.value = it.message ?: "Invalid speaker library JSON"
        }.isSuccess
    }

    /**
     * Phase 3 — minimal CLF text import.
     * Accepts common key/value lines, e.g.:
     * ModelName=My Box, Sensitivity=99, Height=1.6, ArrayElements=1, ElementSpacing=0.2
     */
    /**
     * Import a CLF TAB document - the published text half of the format.
     *
     * This is the supported route for real measured directivity: the user
     * supplies a file they hold, it is parsed on the device, and nothing
     * third-party is redistributed. Falls back to the older `key=value` sketch
     * for anything that is not a TAB document, so existing simple imports keep
     * working.
     */
    fun importClfTabText(text: String, speakerId: String = ""): Boolean =
        importClfSpeaker(speakerId) { ClfTabParser.parse(text, it) }

    /** Import a CF2 binary the user supplied. */
    fun importClfBinary(bytes: ByteArray, speakerId: String = ""): Boolean =
        importClfSpeaker(speakerId) { ClfBinaryReader.parse(bytes, it) }

    private fun importClfSpeaker(
        speakerId: String,
        read: (String) -> ClfTabParser.Speaker
    ): Boolean {
        return runCatching {
            val speaker = read(speakerId)
            if (speaker.bands.isEmpty()) throw IllegalArgumentException("CLF file contains no bands")

            speaker.bands.firstNotNullOfOrNull { ClfTabParser.balloonIntegrityError(it) }?.let {
                throw IllegalArgumentException("CLF balloon failed its consistency check - $it")
            }

            val data = ClfTabParser.toClfData(speaker)
            validateAssetRecovery(data)
            clearAnalysisResults()
            _clfRegistry.value = _clfRegistry.value + (data.speakerId to data)
            _assetRecoveryProblems.value = _assetRecoveryProblems.value.filterNot { it.speakerId == data.speakerId }
            _clfSourceStatus.value = _clfSourceStatus.value + (data.speakerId to (speaker.tags["<FORMAT>"]?.firstOrNull() ?: "TAB"))

            val name = speaker.model.ifBlank { data.speakerId }
            if (_speakerPresets.value.none { it.id == data.speakerId }) {
                _speakerPresets.value = _speakerPresets.value + SpeakerPreset(
                    id = data.speakerId,
                    name = name,
                    sensitivityDb = speaker.sensitivityDb?.coerceIn(80f, 130f) ?: 100f,
                    heightM = 1.8f,
                    brand = speaker.manufacturer.ifBlank { "Other" },
                    series = "CLF",
                    model = name
                )
            }
            _selectedPresetId.value = data.speakerId
            touch()
            _lastImportError.value = null
            recalculate(); refreshHeatmap()
        }.onFailure {
            _lastImportError.value = it.message ?: "Could not read that CLF file"
        }.isSuccess
    }

    fun importClfText(text: String): Boolean {
        // A real CLF TAB document announces itself on the first line.
        if (text.lineSequence().any { it.trimStart().startsWith("<CLF") }) {
            return importClfTabText(text)
        }
        return runCatching {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            if (lines.isEmpty()) throw IllegalArgumentException("Empty CLF text")

            val map = mutableMapOf<String, String>()
            lines.forEach { line ->
                val parts = line.split('=', ':', limit = 2)
                if (parts.size == 2) map[parts[0].trim().lowercase()] = parts[1].trim()
            }

            val name = map["modelname"] ?: map["name"] ?: map["model"] ?: "CLF Imported"
            val sensitivity = (map["sensitivity"] ?: map["sensitivitydb"])?.toFloatOrNull()?.coerceIn(80f, 130f) ?: 100f
            val height = (map["height"] ?: map["heightm"])?.toFloatOrNull()?.coerceIn(0.5f, 20f) ?: 1.8f
            val elements = (map["arrayelements"] ?: map["elements"])?.toIntOrNull()?.coerceIn(1, 16) ?: 1
            val spacing = (map["elementspacing"] ?: map["elementspacingm"] ?: map["spacing"])?.toFloatOrNull()?.coerceIn(0.05f, 1.0f) ?: 0.2f

            val idBase = name.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
            var id = if (idBase.isBlank()) "clf_import" else "clf_$idBase"
            val existing = _speakerPresets.value.map { it.id }.toSet()
            var suffix = 2
            while (id in existing) {
                id = "${idBase}_$suffix"
                suffix++
            }

            val preset = SpeakerPreset(
                id = id,
                name = name,
                sensitivityDb = sensitivity,
                heightM = height,
                arrayElements = elements,
                elementSpacingM = spacing,
                brand = "Other",
                series = "Other",
                model = name
            )
            _speakerPresets.value = _speakerPresets.value + preset
            _selectedPresetId.value = preset.id
            _lastImportError.value = null
        }.onFailure {
            _lastImportError.value = it.message ?: "Invalid CLF text"
        }.isSuccess
    }

    /** Import CLF (Common Loudspeaker Format) JSON data for speaker polar pattern. */
    fun importClfJsonData(json: String): Boolean {
        return runCatching {
            val clfData = ClfParser.parseJson(json) ?: throw IllegalArgumentException("Failed to parse CLF JSON")
            if (clfData.patterns.isEmpty()) throw IllegalArgumentException("CLF data contains no polar patterns")
            validateAssetRecovery(clfData)
            
            // Register CLF data in registry
            val newRegistry = _clfRegistry.value.toMutableMap()
            newRegistry[clfData.speakerId] = clfData
            _assetRecoveryProblems.value = _assetRecoveryProblems.value.filterNot { it.speakerId == clfData.speakerId }
            clearAnalysisResults()
            _clfRegistry.value = newRegistry
            val status = _clfSourceStatus.value.toMutableMap()
            status[clfData.speakerId] = "JSON"
            _clfSourceStatus.value = status
            touch()
            
            _lastImportError.value = null
            true
        }.onFailure {
            _lastImportError.value = it.message ?: "Invalid CLF JSON data"
        }.isSuccess
    }

    /** Export CLF data for a specific speaker. */
    fun exportClfData(speakerId: String): String? {
        val clf = _clfRegistry.value[speakerId] ?: return null
        return ClfParser.toJson(clf)
    }

    /** Get CLF data for speaker if registered. */
    fun getClfData(speakerId: String): ClfData? {
        return _clfRegistry.value[speakerId]
    }

    /**
     * Returns true when the speaker has a real parsed CLF dataset available for calculations.
     */
    fun hasParsedClfData(speakerId: String): Boolean {
        return _clfRegistry.value[speakerId]?.patterns?.isNotEmpty() == true
    }

    /**
     * Returns true when bundled binary CLF files exist for this speaker but no parsed dataset
     * was produced by the current in-app ingestion pipeline.
     */
    fun hasPendingBinaryClfData(speakerId: String): Boolean {
        val hasBinary = _clfBinaryAssets.value[speakerId]?.isNotEmpty() == true
        val hasParsed = _clfRegistry.value[speakerId]?.patterns?.isNotEmpty() == true
        return hasBinary && !hasParsed
    }

    /** Returns bundled binary CLF asset paths (CF/CF2/GLL/DLL/SPK) known for this speaker. */
    fun getPendingBinaryClfAssets(speakerId: String): List<String> {
        return _clfBinaryAssets.value[speakerId] ?: emptyList()
    }

    /** Returns unresolved bundled manufacturer source assets (e.g. unsupported/failing archives). */
    fun getExternalClfAssets(speakerId: String): List<String> {
        return _clfExternalAssets.value[speakerId] ?: emptyList()
    }

    fun getClfIngestionSummary(): String {
        val s = _clfIngestionStats.value
        return "CLF indexed=${s.indexedSpeakers}, json=${s.parsedJsonSpeakers}, binary=${s.parsedBinarySpeakers} (extracted=${s.extractedBinarySpeakers}, inferred=${s.inferredBinarySpeakers}), registry=${s.registrySpeakers}, pendingBinary=${s.pendingBinarySpeakers}, external=${s.unresolvedExternalSpeakers}, strictExtractedOnly=${s.strictExtractedOnlyMode}"
    }

    /** Clear CLF data for a specific speaker. */
    fun clearClfData(speakerId: String) {
        _clfRegistry.value[speakerId]?.let { projectAssets.capture(it) }
        projectAssetReferences = projectAssetReferences + (speakerId to SpeakerAssetRef(null))
        touch()
        clearAnalysisResults()
        if (_clfRegistry.value.containsKey(speakerId)) {
            _speakers.value = _speakers.value.map {
                if (it.presetId == speakerId) it.copy(requiresMeasuredData = true) else it
            }
        }
        val newRegistry = _clfRegistry.value.toMutableMap()
        newRegistry.remove(speakerId)
        _clfRegistry.value = newRegistry
        val newStatus = _clfSourceStatus.value.toMutableMap()
        newStatus.remove(speakerId)
        _clfSourceStatus.value = newStatus
        recalculate(); refreshHeatmap()
    }

    fun setActiveZoneType(zoneType: String) {
        if (!ZONE_TYPES.contains(zoneType)) return
        if (_activeZoneType.value == zoneType) return
        _activeZoneType.value = zoneType
    }

    fun setActiveZoneBaseHeight(heightM: Float) {
        val h = heightM.coerceIn(0f, 40f)
        if (_activeZoneBaseHeightM.value == h) return
        _activeZoneBaseHeightM.value = h
    }

    fun setActiveZoneRakeDeg(rakeDeg: Float) {
        val r = rakeDeg.coerceIn(-30f, 30f)
        if (_activeZoneRakeDeg.value == r) return
        _activeZoneRakeDeg.value = r
    }

    fun setActiveZoneRakeDirectionDeg(directionDeg: Float) {
        val d = ((directionDeg + 180f) % 360f + 360f) % 360f - 180f
        if (_activeZoneRakeDirectionDeg.value == d) return
        _activeZoneRakeDirectionDeg.value = d
    }

    fun setSelectedSpeakerModelPackage(packageId: String) {
        if (!isKnownModelPackage(packageId)) return
        if (_selectedSpeakerModelPackageId.value == packageId) return
        _selectedSpeakerModelPackageId.value = packageId
    }

    fun setSpeakerModelPackage(speakerId: Int, packageId: String) {
        if (!isKnownModelPackage(packageId)) return
        pushUndoCheckpoint()
        _speakers.value = _speakers.value.map { spk ->
            if (spk.id != speakerId) spk else spk.copy(modelPackageId = packageId)
        }
        refreshHeatmap()
    }

    /** Render-only drag preview. Does not change saved state, history or predictions. */
    fun previewSpeakerManipulation(speakerId: Int, x: Float, y: Float, z: Float,
        pan: Float, tilt: Float): List<PlacedSpeaker> {
        val current = _speakers.value
        if (listOf(x,y,z,pan,tilt).any { !it.isFinite() }) return current
        val anchor = current.firstOrNull { it.id == speakerId } ?: return current
        val target = anchor.copy(x=x.coerceIn(-200f,200f),heightM=y.coerceIn(0f,40f),
            z=z.coerceIn(-200f,200f),panDeg=pan.coerceIn(-180f,180f),arrayAimDeg=tilt.coerceIn(-30f,30f))
        val linked = linkedSourceIds(anchor.sourceId)
        val sign = if (linkedMotionModeForSource(anchor.sourceId)=="OPPOSITE") -1f else 1f
        val deltaPan = normalizePanDeg(target.panDeg-anchor.panDeg)
        return current.map { spk -> when {
            spk.id==speakerId -> target
            spk.sourceId!=null && spk.sourceId in linked -> spk.copy(
                x=(spk.x+sign*(target.x-anchor.x)).coerceIn(-200f,200f),
                heightM=(spk.heightM+sign*(target.heightM-anchor.heightM)).coerceIn(0f,40f),
                z=(spk.z+sign*(target.z-anchor.z)).coerceIn(-200f,200f),
                panDeg=normalizePanDeg(spk.panDeg+sign*deltaPan),
                arrayAimDeg=(spk.arrayAimDeg+sign*(target.arrayAimDeg-anchor.arrayAimDeg)).coerceIn(-30f,30f))
            else -> spk
        } }
    }

    fun setSpeakerPan(speakerId: Int, panDeg: Float) {
        val speakersNow = _speakers.value
        val anchor = speakersNow.firstOrNull { it.id == speakerId } ?: return
        val sourceId = anchor.sourceId
        val linkedIds = linkedSourceIds(sourceId)
        val motionMode = linkedMotionModeForSource(sourceId)
        val targetPan = panDeg.coerceIn(-180f, 180f)
        val deltaPan = normalizePanDeg(targetPan - anchor.panDeg)
        pushUndoCheckpoint()
        _speakers.value = speakersNow.map { spk ->
            when {
                spk.id == speakerId -> spk.copy(panDeg = targetPan)
                linkedIds.isNotEmpty() && spk.sourceId != null && spk.sourceId in linkedIds -> {
                    val signedDelta = if (motionMode == "OPPOSITE") -deltaPan else deltaPan
                    spk.copy(panDeg = normalizePanDeg(spk.panDeg + signedDelta))
                }
                else -> spk
            }
        }
        recalculate(); refreshHeatmap()
    }

    fun setSpeakerPosition(speakerId: Int, x: Float, y: Float, z: Float) {
        val speakersNow = _speakers.value
        val anchor = speakersNow.firstOrNull { it.id == speakerId } ?: return
        val sourceId = anchor.sourceId
        val linkedIds = linkedSourceIds(sourceId)
        val motionMode = linkedMotionModeForSource(sourceId)
        val targetX = x.coerceIn(-200f, 200f)
        val targetY = y.coerceIn(0f, 40f)
        val targetZ = z.coerceIn(-200f, 200f)
        val dx = targetX - anchor.x
        val dy = targetY - anchor.heightM
        val dz = targetZ - anchor.z
        pushUndoCheckpoint()
        _speakers.value = speakersNow.map { spk ->
            when {
                spk.id == speakerId -> spk.copy(x = targetX, heightM = targetY, z = targetZ)
                linkedIds.isNotEmpty() && spk.sourceId != null && spk.sourceId in linkedIds -> {
                    val sign = if (motionMode == "OPPOSITE") -1f else 1f
                    spk.copy(
                        x = (spk.x + sign * dx).coerceIn(-200f, 200f),
                        heightM = (spk.heightM + sign * dy).coerceIn(0f, 40f),
                        z = (spk.z + sign * dz).coerceIn(-200f, 200f)
                    )
                }
                else -> spk
            }
        }
        recalculate(); refreshHeatmap()
    }

    fun duplicateSpeaker(speakerId: Int) {
        val src = _speakers.value.firstOrNull { it.id == speakerId } ?: return
        pushUndoCheckpoint()
        val id = nextId++
        val copy = src.copy(
            id = id,
            x = (src.x + 1f).coerceIn(-200f, 200f),
            z = (src.z + 1f).coerceIn(-200f, 200f),
            label = "${src.label} Copy"
        )
        _speakers.value = _speakers.value + copy
        val srcDsp = _dspMap.value[speakerId]
        if (srcDsp != null) {
            _dspMap.value = _dspMap.value + (id to srcDsp.copy(speakerId = id))
        }
        recalculate(); refreshHeatmap()
    }

    fun mirrorSpeakerX(speakerId: Int, referenceSpeakerId: Int? = null) {
        val speakers = _speakers.value
        if (speakers.none { it.id == speakerId }) return
        val refX = referenceSpeakerId?.let { refId -> speakers.firstOrNull { it.id == refId }?.x } ?: 0f
        pushUndoCheckpoint()
        _speakers.value = speakers.map { spk ->
            if (spk.id != speakerId) spk else spk.copy(
                x = (2f * refX - spk.x).coerceIn(-200f, 200f),
                panDeg = ((360f - spk.panDeg) % 360f + 360f) % 360f
            )
        }
        recalculate(); refreshHeatmap()
    }

    fun mirrorSpeakerY(speakerId: Int, referenceSpeakerId: Int? = null) {
        val speakers = _speakers.value
        if (speakers.none { it.id == speakerId }) return
        val refZ = referenceSpeakerId?.let { refId -> speakers.firstOrNull { it.id == refId }?.z } ?: 0f
        pushUndoCheckpoint()
        _speakers.value = speakers.map { spk ->
            if (spk.id != speakerId) spk else spk.copy(
                z = (2f * refZ - spk.z).coerceIn(-200f, 200f),
                panDeg = ((180f - spk.panDeg) % 360f + 360f) % 360f
            )
        }
        recalculate(); refreshHeatmap()
    }

    // --- DSP per-speaker controls ----------------------------------------

    /** +/-12 dB gain trim - updates heatmap and listener results immediately. */
    fun setFilters(speakerId: Int, filters: com.droidacoustic.pro.acoustics.DspFilterChain) {
        if(_speakers.value.none {it.id==speakerId}) return
        val current=_dspMap.value[speakerId] ?: SpeakerDsp(speakerId)
        if(current.filters==filters) return
        pushUndoCheckpoint()
        _dspMap.value=_dspMap.value+(speakerId to current.copy(filters=filters))
        recalculate();refreshHeatmap()
    }

    fun setGain(speakerId: Int, db: Float) {
        pushUndoCheckpoint()
        val cur = _dspMap.value[speakerId] ?: SpeakerDsp(speakerId)
        _dspMap.value = _dspMap.value + (speakerId to cur.copy(gainDb = db.coerceIn(-12f, 12f)))
        recalculate(); refreshHeatmap()
    }

    /** Delay 0-200 ms - stored now, drives coherent summation in Phase 8. */
    fun setDelay(speakerId: Int, ms: Float) {
        pushUndoCheckpoint()
        val cur = _dspMap.value[speakerId] ?: SpeakerDsp(speakerId)
        _dspMap.value = _dspMap.value + (speakerId to cur.copy(delayMs = ms.coerceIn(0f, 200f)))
        recalculate(); refreshHeatmap()
    }

    /** Polarity invert toggle - stored now, affects Phase 8 coherent summation. */
    fun setPolarity(speakerId: Int, inverted: Boolean) {
        pushUndoCheckpoint()
        val cur = _dspMap.value[speakerId] ?: SpeakerDsp(speakerId)
        _dspMap.value = _dspMap.value + (speakerId to cur.copy(polarity = inverted))
        recalculate(); refreshHeatmap()
    }

    /** Per-band EQ (+/-6 dB). Immediately adjusts active-band SPL calculation. */
    fun setEqBand(speakerId: Int, bandHz: Int, gainDb: Float) {
        pushUndoCheckpoint()
        val cur = _dspMap.value[speakerId] ?: SpeakerDsp(speakerId)
        val newBands = cur.eqBands + (bandHz to gainDb.coerceIn(-6f, 6f))
        _dspMap.value = _dspMap.value + (speakerId to cur.copy(eqBands = newBands))
        recalculate(); refreshHeatmap()
    }

    /** Phase 8: per-speaker vertical array element count. */
    fun setSpeakerArrayElements(speakerId: Int, elements: Int) {
        pushUndoCheckpoint()
        val target = elements.coerceIn(1, 16)
        _speakers.value = _speakers.value.map { spk ->
            if (spk.id != speakerId) {
                spk
            } else {
                val joints = (target - 1).coerceAtLeast(0)
                val resized = MutableList(joints) { idx ->
                    spk.arraySplayByBoxDeg.getOrNull(idx) ?: spk.arrayInterBoxSplayDeg
                }
                spk.copy(
                    arrayElements = target,
                    arraySplayByBoxDeg = resized
                )
            }
        }
        recalculate(); refreshHeatmap()
    }

    /** Phase 8: per-speaker vertical array spacing in metres. */
    fun setSpeakerArraySpacing(speakerId: Int, spacingM: Float) {
        pushUndoCheckpoint()
        _speakers.value = _speakers.value.map { spk ->
            if (spk.id != speakerId) spk else spk.copy(arraySpacingM = spacingM.coerceIn(0.05f, 0.6f))
        }
        recalculate(); refreshHeatmap()
    }

    /** Phase 8: per-speaker electronic steering angle in degrees. */
    fun setSpeakerArraySteer(speakerId: Int, steerDeg: Float) {
        pushUndoCheckpoint()
        _speakers.value = _speakers.value.map { spk ->
            if (spk.id != speakerId) spk else spk.copy(arraySteerDeg = steerDeg.coerceIn(-30f, 30f))
        }
        recalculate(); refreshHeatmap()
    }

    /** Phase 8: per-speaker mechanical aim in degrees (down-positive). */
    fun setSpeakerArrayAim(speakerId: Int, aimDeg: Float) {
        val speakersNow = _speakers.value
        val anchor = speakersNow.firstOrNull { it.id == speakerId } ?: return
        val sourceId = anchor.sourceId
        val linkedIds = linkedSourceIds(sourceId)
        val motionMode = linkedMotionModeForSource(sourceId)
        val targetAim = aimDeg.coerceIn(-30f, 30f)
        val deltaAim = targetAim - anchor.arrayAimDeg
        pushUndoCheckpoint()
        _speakers.value = speakersNow.map { spk ->
            when {
                spk.id == speakerId -> spk.copy(arrayAimDeg = targetAim)
                linkedIds.isNotEmpty() && spk.sourceId != null && spk.sourceId in linkedIds -> {
                    val sign = if (motionMode == "OPPOSITE") -1f else 1f
                    spk.copy(arrayAimDeg = (spk.arrayAimDeg + sign * deltaAim).coerceIn(-30f, 30f))
                }
                else -> spk
            }
        }
        recalculate(); refreshHeatmap()
    }

    /** Phase 8: per-speaker uniform inter-box splay angle in degrees. */
    fun setSpeakerArraySplay(speakerId: Int, splayDeg: Float) {
        pushUndoCheckpoint()
        val s = splayDeg.coerceIn(0f, 10f)
        _speakers.value = _speakers.value.map { spk ->
            if (spk.id != speakerId) {
                spk
            } else {
                val joints = (spk.arrayElements - 1).coerceAtLeast(0)
                spk.copy(
                    arrayInterBoxSplayDeg = s,
                    arraySplayByBoxDeg = List(joints) { s }
                )
            }
        }
        recalculate(); refreshHeatmap()
    }

    /** Phase 8: set one per-box splay joint angle (advanced profile). */
    fun setSpeakerArraySplayAt(speakerId: Int, jointIndex: Int, splayDeg: Float) {
        pushUndoCheckpoint()
        val s = splayDeg.coerceIn(0f, 10f)
        _speakers.value = _speakers.value.map { spk ->
            if (spk.id != speakerId) {
                spk
            } else {
                val joints = (spk.arrayElements - 1).coerceAtLeast(0)
                if (joints == 0 || jointIndex !in 0 until joints) return@map spk
                val profile = MutableList(joints) { idx ->
                    spk.arraySplayByBoxDeg.getOrNull(idx) ?: spk.arrayInterBoxSplayDeg
                }
                profile[jointIndex] = s
                spk.copy(arraySplayByBoxDeg = profile)
            }
        }
        recalculate(); refreshHeatmap()
    }

    /** Phase 8: per-speaker edge taper in dB at the top/bottom elements. */
    fun setSpeakerArrayEdgeTaper(speakerId: Int, taperDb: Float) {
        pushUndoCheckpoint()
        _speakers.value = _speakers.value.map { spk ->
            if (spk.id != speakerId) spk else spk.copy(arrayEdgeTaperDb = taperDb.coerceIn(0f, 12f))
        }
        recalculate(); refreshHeatmap()
    }

    fun removeSpeaker(id: Int) {
        pushUndoCheckpoint()
        _speakers.value = _speakers.value.filter { it.id != id }
        _dspMap.value = _dspMap.value - id
        recalculate()
        refreshHeatmap()
    }

    fun clearAll() {
        _cutoutTarget.value = null
        _elevationTarget.value = null
        _audienceGeometryError.value = null
        _importedFaceSelection.value = null
        _areaDraft.value = emptyList()
        geometryCache.clear()
        pushUndoCheckpoint()
        _comparisonSourceSpectrum.value=BroadbandSourceSpectrum.preset("PER_BAND_REFERENCE")
        _broadbandDrive.value=BroadbandDrive.DEFAULT
        _comparisonWorkspace.value=ComparisonWorkspace.empty()
        _speakers.value = emptyList()
        _speakerSources.value = emptyList()
        _activeSpeakerSourceId.value = null
        _results.value  = emptyList()
        _heatmap.value  = emptyList()
        _coverageGeometry.value = null
        _coverageStatistics.value = null
        _dspMap.value   = emptyMap()
        _combinedSplDb.value = null
        _earlyReflections.value = emptyList()
        _rt60Estimate.value = null
        _roomMaterials.value = RoomMaterials()
        _venueGeometry.value = VenueGeometry()
        _activeZoneType.value = "AUDIENCE_SEATED"
        _activeZoneBaseHeightM.value = 0f
        _activeZoneRakeDeg.value = 0f
        _activeZoneRakeDirectionDeg.value = 0f
        _speakerModelPackages.value = SPEAKER_MODEL_PACKAGES
        _selectedSpeakerModelPackageId.value = "generic"
        _signalLevelDbu.value = 0f
        _signalType.value = "BAND"
        _signalBandwidthOct.value = 1f / 3f
        _signalResolution.value = 24
        _groundReflectionEnabled.value = false
        _groundReflectionPhase.value=null
        _signalInterferenceEnabled.value = true
        _signalAutoCalculate.value = false
        _signalSplEnabled.value = true
        _signalDispersionEnabled.value = true
        _receiverAngularOverlayEnabled.value=false
        _receiverAngularSelection.value="listener"
        clearReceiverFrame()
        _signalCoverageEnabled.value = true
        _highestSplDb.value = null
    }

    /**
     * Export full scene + acoustics state to JSON.
     * Includes geometry, DSP, audience, atmosphere, and room material settings.
     */
    fun exportSceneJson(includeClfRegistry: Boolean = true, includeDesignLibrary: Boolean = true): String {
        val root = JSONObject()
        root.put("version", SCENE_SCHEMA_VERSION)
        root.put("schema", "droidacoustic.scene")
        root.put("comparisonSourceSpectrum",JSONObject(_comparisonSourceSpectrum.value.canonical()))
        root.put("broadbandDrive",_broadbandDrive.value.name)
        if(_comparisonWorkspace.value.reference!=null || _comparisonWorkspace.value.engine!=null)
            root.put("comparisonWorkspace",JSONObject(_comparisonWorkspace.value.toJsonString()))
        root.put("selectedPresetId", _selectedPresetId.value)
        root.put("activeZoneType", _activeZoneType.value)
        root.put("activeZoneBaseHeightM", _activeZoneBaseHeightM.value)
        root.put("activeZoneRakeDeg", _activeZoneRakeDeg.value)
        root.put("activeZoneRakeDirectionDeg", _activeZoneRakeDirectionDeg.value)
        root.put("selectedSpeakerModelPackageId", _selectedSpeakerModelPackageId.value)
        root.put("selectedBandHz", _selectedBandHz.value)
        root.put("signalLevelDbu", _signalLevelDbu.value)
        root.put("signalType", _signalType.value)
        root.put("weighting", _weighting.value)
        root.put("signalBandwidthOct", _signalBandwidthOct.value)
        root.put("signalResolution", _signalResolution.value)
        root.put("groundReflectionEnabled", _groundReflectionEnabled.value)
        _groundReflectionPhase.value?.let {root.put("groundReflectionPhase",JSONObject(it.toJsonString()))}
        root.put("bandLevelConvention", "fixed-total-drive-flat-psd")
        root.put("driveLevelConvention", "gain-db-relative-to-model-reference")
        root.put("speakerDataWarnings", JSONArray(predictionDataWarnings()))

        root.put("spectrumConvention", _broadbandDrive.value.convention)
        root.put("signalInterferenceEnabled", _signalInterferenceEnabled.value)
        root.put("aimRaysEnabled", _aimRaysEnabled.value)
        root.put("receiverAngularOverlayEnabled",_receiverAngularOverlayEnabled.value)
        if(_receiverAngularSelection.value!="listener") root.put("receiverAngularSelection",_receiverAngularSelection.value)
        _receiverCameraFocus.value?.let {root.put("receiverCameraFocus",JSONObject(it.toJsonString()))}
        root.put("contourMode", _contourMode.value)
        root.put("signalAutoCalculate", _signalAutoCalculate.value)
        root.put("splScaleMode", _splScaleMode.value)
        root.put("splTargetDb", _splTargetDb.value.toDouble())
        root.put("splSpanDb", _splSpanDb.value.toDouble())
        root.put("splFixedMinDb", _splFixedMinDb.value.toDouble())
        root.put("splFixedMaxDb", _splFixedMaxDb.value.toDouble())
        root.put("signalSplEnabled", _signalSplEnabled.value)
        root.put("signalDispersionEnabled", _signalDispersionEnabled.value)
        root.put("signalCoverageEnabled", _signalCoverageEnabled.value)
        root.put("temperatureC", _temperatureC.value)
        root.put("humidityPct", _humidityPct.value)
        root.put("reflectionOrder", _reflectionOrder.value)
        root.put("analysisProfile", _analysisProfile.value)

        root.put(
            "speakerLibrary",
            JSONArray().apply {
                _speakerPresets.value.forEach { p ->
                    put(
                        JSONObject()
                            .put("id", p.id)
                            .put("name", p.name)
                            .put("sensitivityDb", p.sensitivityDb)
                            .put("heightM", p.heightM)
                            .put("arrayElements", p.arrayElements)
                            .put("elementSpacingM", p.elementSpacingM)
                            .put("brand", p.brand)
                            .put("series", p.series)
                            .put("model", p.model)
                    )
                }
            }
        )

        root.put(
            "speakerSources",
            JSONArray().apply {
                _speakerSources.value.forEach { s ->
                    put(
                        JSONObject()
                            .put("id", s.id)
                            .put("name", s.name)
                            .put("role", s.role)
                            .put("brand", s.brand)
                            .put("series", s.series)
                            .put("modelPresetId", s.modelPresetId ?: "")
                            .put("modelPackageId", s.modelPackageId)
                            .put("linkedSourceId", s.linkedSourceId ?: -1)
                            .put("linkedMotionMode", s.linkedMotionMode)
                            .put("collapsed", s.collapsed)
                    )
                }
            }
        )
        root.put("activeSpeakerSourceId", _activeSpeakerSourceId.value ?: -1)

        root.put(
            "speakerModelPackages",
            JSONArray().apply {
                _speakerModelPackages.value.forEach { pkg ->
                    put(
                        JSONObject()
                            .put("id", pkg.id)
                            .put("name", pkg.name)
                            .put("markerHeightM", pkg.markerHeightM)
                            .put("crossHalfM", pkg.crossHalfM)
                            .put("cabinetWidthM", pkg.cabinetWidthM)
                            .put("cabinetDepthM", pkg.cabinetDepthM)
                            .put("cabinetHeightM", pkg.cabinetHeightM)
                            .put("colorR", pkg.colorR)
                            .put("colorG", pkg.colorG)
                            .put("colorB", pkg.colorB)
                            .put("modelAssetPath", pkg.modelAssetPath ?: "")
                    )
                }
            }
        )

        root.put(
            "venueGeometry",
            JSONObject()
                .put("widthM", _venueGeometry.value.widthM)
                .put("depthM", _venueGeometry.value.depthM)
                .put("wallHeightM", _venueGeometry.value.wallHeightM)
                .put("stageCenterX", _venueGeometry.value.stageCenterX)
                .put("stageCenterZ", _venueGeometry.value.stageCenterZ)
                .put("stageWidthM", _venueGeometry.value.stageWidthM)
                .put("stageDepthM", _venueGeometry.value.stageDepthM)
                .put("stageHeightM", _venueGeometry.value.stageHeightM)
                .put("stageSlopeDeg", _venueGeometry.value.stageSlopeDeg)
                .put("stageBoundaryRole", _venueGeometry.value.stageBoundaryRole.name)
                .put("stageBoundaryMaterial", org.json.JSONTokener(_venueGeometry.value.stageBoundaryMaterial.toJsonString()).nextValue())
                .put(
                    "blocks",
                    JSONArray().apply {
                        _venueGeometry.value.blocks.forEach { b ->
                            put(
                                JSONObject()
                                    .put("id", b.id)
                                    .put("type", b.type)
                                    .put("centerX", b.centerX)
                                    .put("centerZ", b.centerZ)
                                    .put("widthM", b.widthM)
                                    .put("depthM", b.depthM)
                                    .put("heightM", b.heightM)
                                    .put("blockHeightM", b.blockHeightM)
                                    .put("slopeDeg", b.slopeDeg)
                                                                        .put("rotationDeg", b.rotationDeg)
                                    .put("label", b.label)
                                    .put("boundaryRole", b.boundaryRole.name)
                                    .put("boundaryMaterial", org.json.JSONTokener(b.boundaryMaterial.toJsonString()).nextValue())
                            )
                        }
                    }
                )
        )

        if(includeDesignLibrary) {
            root.put("designVariants",JSONObject(_designVariants.value.toJsonString()))
            _coverageComparisonSelection.value?.let {root.put("coverageComparison",JSONObject(it.toJsonString()))}
        }
        root.put("referenceTraceSelection",JSONObject(_referenceTraceSelection.value.toJsonString()))
        root.put("savedTraces",JSONObject(_savedTraces.value.toJsonString()))
        root.put("alignmentReferences",JSONObject(_alignmentReferences.value.toJsonString()))
        _alignmentReceiver.value?.let {r ->
            root.put("alignmentReference",JSONObject().put("name",r.name).put("x",r.position.x)
                .put("y",r.position.y).put("z",r.position.z).put("sourceId",_alignmentSourceId.value ?: JSONObject.NULL))
        }
        root.put(
            "listener",
            JSONObject()
                .put("x", _listener.value.x)
                .put("z", _listener.value.z)
                .put("earHeightM", _listener.value.earHeightM)
        )

        root.put(
            "roomMaterials",
            JSONObject()
                .put("floorMaterial", _roomMaterials.value.floor.id)
                .put("ceilingMaterial", _roomMaterials.value.ceiling.id)
                .put("wallMaterial", _roomMaterials.value.wall.id)
                .put("floorAlphaByBand", alphaJson(_roomMaterials.value.floor))
                .put("ceilingAlphaByBand", alphaJson(_roomMaterials.value.ceiling))
                .put("wallAlphaByBand", alphaJson(_roomMaterials.value.wall))
                .put("roomHeightM", _roomMaterials.value.roomHeightM)
        )

        root.put(
            "speakers",
            JSONArray().apply {
                _speakers.value.forEach { s ->
                    put(
                        JSONObject()
                            .put("id", s.id)
                            .put("x", s.x)
                            .put("z", s.z)
                            .put("heightM", s.heightM)
                            .put("sensitivity", s.sensitivity)
                            .put("arrayElements", s.arrayElements)
                            .put("arraySpacingM", s.arraySpacingM)
                            .put("arrayInterBoxSplayDeg", s.arrayInterBoxSplayDeg)
                            .put("arraySplayByBoxDeg", JSONArray().apply { s.arraySplayByBoxDeg.forEach { put(it) } })
                            .put("arrayAimDeg", s.arrayAimDeg)
                            .put("panDeg", s.panDeg)
                            .put("arraySteerDeg", s.arraySteerDeg)
                            .put("arrayEdgeTaperDb", s.arrayEdgeTaperDb)
                            .put("modelPackageId", s.modelPackageId)
                            .put("presetId", s.presetId)
                            .put("requiresMeasuredData", requiresMeasuredData(s))
                            .put("sourceId", s.sourceId ?: -1)
                            .put("label", s.label)
                    )
                }
            }
        )

        root.put(
            "dsp",
            JSONArray().apply {
                _dspMap.value.values.forEach { d ->
                    put(
                        JSONObject()
                            .put("speakerId", d.speakerId)
                            .put("delayMs", d.delayMs)
                            .put("gainDb", d.gainDb)
                            .put("polarity", d.polarity)
                            .put("filters", JSONObject(d.filters.toJsonString()))
                            .put(
                                "eqBands",
                                JSONObject().apply {
                                    d.eqBands.forEach { (band, gain) -> put(band.toString(), gain) }
                                }
                            )
                    )
                }
            }
        )

        root.put(
            "audience",
            JSONArray().apply {
                _audience.value.forEach { a ->
                    val obj = JSONObject()
                        .put("id", a.id)
                        .put("x", a.x)
                        .put("z", a.z)
                        .put("earHeightM", a.earHeightM)
                        .put("name", a.name)
                    a.sourceAreaId?.let { obj.put("sourceAreaId", it) }
                    put(obj)
                }
            }
        )

        root.put(
            "audienceAreas",
            JSONArray().apply {
                _audienceAreas.value.forEach { area ->
                    put(
                        JSONObject()
                            .put("id", area.id)
                            .put("name", area.name)
                            .put("zoneType", area.zoneType)
                            .put("baseHeightM", area.baseHeightM)
                            .put("rakeDeg", area.rakeDeg)
                            .put("rakeDirectionDeg", area.rakeDirectionDeg)
                            .put("rotationDeg", area.rotationDeg)
                            .put("holes", audienceRingsJson(area.holes))
                            .put("exclusions", audienceRingsJson(area.exclusions))
                            .put("vertexOffsetsM", JSONArray(area.vertexOffsetsM))
                            .put("elevationPoints", audienceElevationPointsJson(area.elevationPoints))
                            .put("breaklines", audienceBreaklinesJson(area.breaklines))
                            .put("geometryType",if (area.importedMesh==null) "AUTHORED" else "IMPORTED_MESH")
                            .apply { area.importedMesh?.let { put("audienceMesh",JSONObject(AudienceMeshJson.encode(it))) } }
                            .apply { area.linkedZoneId?.let { put("linkedZoneId", it) } }
                            .put(
                                "vertices",
                                JSONArray().apply {
                                    area.vertices.forEach { (x, z) ->
                                        put(JSONObject().put("x", x).put("z", z))
                                    }
                                }
                            )
                    )
                }
            }
        )

        val refs = projectAssetReferences.toMutableMap()
        _clfRegistry.value.forEach { (id, data) -> refs[id] = projectAssets.capture(data).reference }
        _speakers.value.filter { requiresMeasuredData(it) }.forEach { refs.putIfAbsent(it.presetId, SpeakerAssetRef(null)) }
        projectAssetReferences = refs.toMap()
        return ProjectArchive.encode(root.toString(), refs, projectAssets::resolve, includeClfRegistry)
    }

    /**
     * Import a scene previously created by [exportSceneJson].
     * Returns true on success, false on parse/validation failure.
     */
    fun importSceneJson(json: String): Boolean = importSceneJsonInternal(json, recordHistory = true)

    /** Phase 2 — export venue-only package for venue import workflows. */
    fun exportVenueJson(): String {
        val root = JSONObject()
        root.put("schema", "droidacoustic.venue")
        root.put("version", 7)
        root.put(
            "venueGeometry",
            JSONObject()
                .put("widthM", _venueGeometry.value.widthM)
                .put("depthM", _venueGeometry.value.depthM)
                .put("wallHeightM", _venueGeometry.value.wallHeightM)
                .put("stageCenterX", _venueGeometry.value.stageCenterX)
                .put("stageCenterZ", _venueGeometry.value.stageCenterZ)
                .put("stageWidthM", _venueGeometry.value.stageWidthM)
                .put("stageDepthM", _venueGeometry.value.stageDepthM)
                .put("stageHeightM", _venueGeometry.value.stageHeightM)
                .put("stageSlopeDeg", _venueGeometry.value.stageSlopeDeg)
                .put("stageBoundaryRole", _venueGeometry.value.stageBoundaryRole.name)
                .put("stageBoundaryMaterial", org.json.JSONTokener(_venueGeometry.value.stageBoundaryMaterial.toJsonString()).nextValue())
                .put(
                    "blocks",
                    JSONArray().apply {
                        _venueGeometry.value.blocks.forEach { b ->
                            put(
                                JSONObject()
                                    .put("id", b.id)
                                    .put("type", b.type)
                                    .put("centerX", b.centerX)
                                    .put("centerZ", b.centerZ)
                                    .put("widthM", b.widthM)
                                    .put("depthM", b.depthM)
                                    .put("heightM", b.heightM)
                                    .put("blockHeightM", b.blockHeightM)
                                    .put("slopeDeg", b.slopeDeg)
                                    .put("rotationDeg", b.rotationDeg)
                                    .put("label", b.label)
                                    .put("boundaryRole", b.boundaryRole.name)
                                    .put("boundaryMaterial", org.json.JSONTokener(b.boundaryMaterial.toJsonString()).nextValue())
                            )
                        }
                    }
                )
        )
        root.put(
            "roomMaterials",
            JSONObject()
                .put("floorMaterial", _roomMaterials.value.floor.id)
                .put("ceilingMaterial", _roomMaterials.value.ceiling.id)
                .put("wallMaterial", _roomMaterials.value.wall.id)
                .put("floorAlphaByBand", alphaJson(_roomMaterials.value.floor))
                .put("ceilingAlphaByBand", alphaJson(_roomMaterials.value.ceiling))
                .put("wallAlphaByBand", alphaJson(_roomMaterials.value.wall))
                .put("roomHeightM", _roomMaterials.value.roomHeightM)
        )
        return root.toString()
    }

    /** Phase 2 — import venue-only package without replacing full scene. */
    fun importVenueJson(json: String): Boolean {
        return runCatching {
            com.droidacoustic.pro.persistence.VenueBoundaryJson.validatePackage(json)
            val root = JSONObject(json)
            val vg = root.optJSONObject("venueGeometry") ?: root
            val wallH = vg.optDouble("wallHeightM", _venueGeometry.value.wallHeightM.toDouble()).toFloat().coerceIn(3f, 30f)
            val loadedBlocks = mutableListOf<VenueBlock>()
            vg.optJSONArray("blocks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val type = o.optString("type", "OBSTACLE").let { if (VENUE_BLOCK_TYPES.contains(it)) it else "OBSTACLE" }
                    loadedBlocks += VenueBlock(
                        id = o.optInt("id", i),
                        type = type,
                        centerX = o.optDouble("centerX", 0.0).toFloat().coerceIn(-200f, 200f),
                        centerZ = o.optDouble("centerZ", 0.0).toFloat().coerceIn(-200f, 200f),
                        widthM = o.optDouble("widthM", 6.0).toFloat().coerceIn(0.2f, 120f),
                        depthM = o.optDouble("depthM", 3.0).toFloat().coerceIn(0.2f, 120f),
                        heightM = o.optDouble("heightM", 1.0).toFloat().coerceIn(0f, 40f),
                        blockHeightM = o.optDouble("blockHeightM", 1.0).toFloat().coerceIn(0.1f, 40f),
                        slopeDeg = o.optDouble("slopeDeg", 0.0).toFloat().coerceIn(-30f, 30f),
                        rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat().coerceIn(0f, 360f),
                        label = o.optString("label", "$type ${i + 1}"),
                        boundaryRole = AcousticBoundaryRole.valueOf(o.optString("boundaryRole", "OCCLUDING")),
                        boundaryMaterial = BoundaryMaterial.fromJsonString(o.opt("boundaryMaterial").let { if (it == null) "\"IDEAL_RIGID\"" else if (it is String) JSONObject.quote(it) else it.toString() })
                    )
                }
            }

            pushUndoCheckpoint()
            _venueGeometry.value = VenueGeometry(
                widthM = vg.optDouble("widthM", _venueGeometry.value.widthM.toDouble()).toFloat().coerceIn(8f, 400f),
                depthM = vg.optDouble("depthM", _venueGeometry.value.depthM.toDouble()).toFloat().coerceIn(8f, 400f),
                wallHeightM = wallH,
                stageCenterX = vg.optDouble("stageCenterX", _venueGeometry.value.stageCenterX.toDouble()).toFloat().coerceIn(-200f, 200f),
                stageCenterZ = vg.optDouble("stageCenterZ", _venueGeometry.value.stageCenterZ.toDouble()).toFloat().coerceIn(-200f, 200f),
                stageWidthM = vg.optDouble("stageWidthM", _venueGeometry.value.stageWidthM.toDouble()).toFloat().coerceIn(1f, 100f),
                stageDepthM = vg.optDouble("stageDepthM", _venueGeometry.value.stageDepthM.toDouble()).toFloat().coerceIn(1f, 80f),
                stageHeightM = vg.optDouble("stageHeightM", _venueGeometry.value.stageHeightM.toDouble()).toFloat().coerceIn(0f, 20f),
                stageSlopeDeg = vg.optDouble("stageSlopeDeg", _venueGeometry.value.stageSlopeDeg.toDouble()).toFloat().coerceIn(-20f, 20f),
                blocks = loadedBlocks,
                stageBoundaryRole = AcousticBoundaryRole.valueOf(vg.optString("stageBoundaryRole", "OCCLUDING")),
                stageBoundaryMaterial = BoundaryMaterial.fromJsonString(vg.opt("stageBoundaryMaterial").let { if (it == null) "\"IDEAL_RIGID\"" else if (it is String) JSONObject.quote(it) else it.toString() })
            )
            root.optJSONObject("roomMaterials")?.let { rm ->
                _roomMaterials.value = RoomMaterials(
                    floor = readSurface(rm, "floor", _roomMaterials.value.floor),
                    ceiling = readSurface(rm, "ceiling", _roomMaterials.value.ceiling),
                    wall = readSurface(rm, "wall", _roomMaterials.value.wall),
                    roomHeightM = rm.optDouble("roomHeightM", wallH.toDouble()).toFloat().coerceIn(3f, 30f)
                )
            } ?: run {
                _roomMaterials.value = _roomMaterials.value.copy(roomHeightM = wallH)
            }
            recalculate(force = true)
            refreshHeatmap(force = true)
            _lastImportError.value = null
        }.onFailure {
            _lastImportError.value = it.message ?: "Invalid venue JSON"
        }.isSuccess
    }

    /** Phase 10 — export a project report JSON bundle for tooling / CI / docs. */
    fun exportProjectReportJson(): String {
        val root = JSONObject()
        root.put("schema", "droidacoustic.project-report")
        root.put("version", 2)
        root.put("importedComparison",JSONObject(_comparisonWorkspace.value.reportJson()))
        root.put("speakerCount", _speakers.value.size)
        root.put("audiencePointCount", _audience.value.size)
        root.put("audienceAreaCount", _audienceAreas.value.size)
        root.put("predictionState", _predictionState.value.name)
        root.put("sceneRevision", _revision.value)
        listenerResultTicket?.takeIf { predictionGate.isLatest(it, _revision.value) }?.let { ticket ->
            root.put("predictionRevision", ticket.sceneRevision)
            listenerValidity?.let {root.put("listenerValidity",JSONObject(it.toJsonString()))}
            _combinedSplDb.value?.let { root.put("combinedSplDb", it) }
        }
        coverageResultTicket?.takeIf { predictionGate.isLatest(it, _revision.value) }?.let { ticket ->
            coverageValidity?.let {root.put("coverageValidity",JSONObject(it.toJsonString()))}
            _coverageStatistics.value?.let { statistics ->
                root.put("coverageRevision",ticket.sceneRevision)
                root.put("coverageStatistics",statistics.toReportJson())
            }
        }
        root.put("predictionModel", JSONObject(PredictionModelScope.reportJson()))
        root.put("venue", JSONObject(exportVenueJson()).optJSONObject("venueGeometry"))
        root.put("selectedBandHz", _selectedBandHz.value)
        root.put("signalLevelDbu", _signalLevelDbu.value)
        root.put("signalType", _signalType.value)
        root.put("weighting", _weighting.value)
        root.put("signalBandwidthOct", _signalBandwidthOct.value)
        root.put("signalResolution", _signalResolution.value)
        root.put("groundReflectionEnabled", _groundReflectionEnabled.value)
        _groundReflectionPhase.value?.let {root.put("groundReflectionPhase",JSONObject(it.toJsonString()))}
        root.put("bandLevelConvention", "fixed-total-drive-flat-psd")
        root.put("driveLevelConvention", "gain-db-relative-to-model-reference")
        root.put("speakerDataWarnings", JSONArray(predictionDataWarnings()))
        root.put("speakerDataValidation", JSONArray(_speakers.value.mapNotNull { clfDataFor(it) }
            .distinctBy { it.speakerId }.map { JSONObject(SpeakerDataChecks.reportJson(it)) }))
        root.put("spectrumConvention", _broadbandDrive.value.convention)
        root.put("broadbandDrive",_broadbandDrive.value.name)
        root.put("signalInterferenceEnabled", _signalInterferenceEnabled.value)
        root.put("aimRaysEnabled", _aimRaysEnabled.value)
        root.put("receiverAngularOverlayEnabled",_receiverAngularOverlayEnabled.value)
        if(_receiverAngularSelection.value!="listener") root.put("receiverAngularSelection",_receiverAngularSelection.value)
        _receiverCameraFocus.value?.let {root.put("receiverCameraFocus",JSONObject(it.toJsonString()))}
        root.put("contourMode", _contourMode.value)
        root.put("signalAutoCalculate", _signalAutoCalculate.value)
        root.put("splScaleMode", _splScaleMode.value)
        root.put("splTargetDb", _splTargetDb.value.toDouble())
        root.put("splSpanDb", _splSpanDb.value.toDouble())
        root.put("splFixedMinDb", _splFixedMinDb.value.toDouble())
        root.put("splFixedMaxDb", _splFixedMaxDb.value.toDouble())
        root.put("signalSplEnabled", _signalSplEnabled.value)
        root.put("signalDispersionEnabled", _signalDispersionEnabled.value)
        root.put("signalCoverageEnabled", _signalCoverageEnabled.value)
        root.put("temperatureC", _temperatureC.value)
        root.put("humidityPct", _humidityPct.value)
        root.put("reflectionOrder", PredictionModelScope.maxReflectionOrder)
        root.put("analysisProfile", _analysisProfile.value)
        return root.toString()
    }

    /** Export current speaker model packages as standalone JSON for clipboard/library sharing. */
    fun exportSpeakerModelPackagesJson(): String {
        val root = JSONObject()
        root.put("schema", "droidacoustic.speaker-model-packages")
        root.put("version", 1)
        root.put(
            "modelPackages",
            JSONArray().apply {
                _speakerModelPackages.value.forEach { pkg ->
                    put(
                        JSONObject()
                            .put("id", pkg.id)
                            .put("name", pkg.name)
                            .put("markerHeightM", pkg.markerHeightM)
                            .put("crossHalfM", pkg.crossHalfM)
                            .put("cabinetWidthM", pkg.cabinetWidthM)
                            .put("cabinetDepthM", pkg.cabinetDepthM)
                            .put("cabinetHeightM", pkg.cabinetHeightM)
                            .put("colorR", pkg.colorR)
                            .put("colorG", pkg.colorG)
                            .put("colorB", pkg.colorB)
                            .put("modelAssetPath", pkg.modelAssetPath ?: "")
                    )
                }
            }
        )
        return root.toString()
    }

    private fun importSceneJsonInternal(json: String, recordHistory: Boolean): Boolean {
        val opened = runCatching {
            ProjectArchive.open(json, if (recordHistory) ({ _: String -> null }) else projectAssets::resolve)
        }.getOrElse {
            _lastImportError.value = it.message ?: "Invalid scene JSON"
            return false
        }
        return applyPreparedProject(opened, recordHistory)
    }

    /** UI decodes/hashes large assets on a worker, then applies prepared state on Main. */
    fun importPreparedProject(opened: OpenedProject): Boolean = applyPreparedProject(opened, true)

    private fun applyPreparedProject(opened: OpenedProject, recordHistory: Boolean): Boolean {
        return runCatching {
            val root = JSONObject(opened.sceneJson)
            val decodedGroundPhase=root.optJSONObject("groundReflectionPhase")?.let {ReflectionPhase.fromJsonString(it.toString())}
            val decodedVariants=root.optJSONObject("designVariants")?.let {DesignVariants.fromJsonString(it.toString())} ?: DesignVariants.empty()
            val decodedComparison=root.optJSONObject("coverageComparison")?.let {CoverageComparisonSelection.fromJsonString(it.toString())}
                ?.takeIf {selection -> listOf(selection.baselineId,selection.candidateId).all {id -> decodedVariants.variants.any {it.id==id}}}
            val decodedSpectrum=root.optJSONObject("comparisonSourceSpectrum")?.let {BroadbandSourceSpectrum.parseJson(it.toString())} ?: BroadbandSourceSpectrum.preset("PER_BAND_REFERENCE")
            val decodedDrive=BroadbandDrive.fromStoredName(if(root.has("broadbandDrive")) root.getString("broadbandDrive") else null)
            val decodedImportedComparison=root.optJSONObject("comparisonWorkspace")?.let {ComparisonWorkspace.parse(it.toString())} ?: ComparisonWorkspace.empty()
            val decodedTraces=root.optJSONObject("savedTraces")?.let {SavedTraces.fromJsonString(it.toString())} ?: SavedTraces.empty()
            val savedAlignment=root.optJSONObject("alignmentReferences")
            val alignment=root.optJSONObject("alignmentReference")
            val decodedAlignment=if(savedAlignment!=null) AlignmentReferences.fromJsonString(savedAlignment.toString())
                else if(alignment!=null) AlignmentReferences.empty().add(AlignmentReceiver(alignment.getString("name"),
                    BoundaryPoint(alignment.getDouble("x"),alignment.getDouble("y"),alignment.getDouble("z"))),
                    alignment.takeIf {!it.isNull("sourceId")}?.getInt("sourceId"))
                else AlignmentReferences.empty()
            val decodedTraceSelection=(root.optJSONObject("referenceTraceSelection")?.let {ReferenceTraceSelection.fromJsonString(it.toString())}
                ?: ReferenceTraceSelection()).reconcile(decodedAlignment)
            val decodedReceiverFocus=root.optJSONObject("receiverCameraFocus")?.let {com.droidacoustic.pro.geometry.ReceiverCameraFocus.parse(it.toString())}
                ?.reconcile(listOf("listener")+decodedAlignment.receivers.map {"reference:${it.id}"})
            if (recordHistory) pushUndoCheckpoint() else touch()
            clearAnalysisResults()

            _speakerSources.value = emptyList()
            _activeSpeakerSourceId.value = null
            nextSourceId = 0

            val presetId = root.optString("selectedPresetId", _selectedPresetId.value)
            if (_speakerPresets.value.any { it.id == presetId }) {
                _selectedPresetId.value = presetId
            }

            root.optJSONArray("speakerLibrary")?.let { arr ->
                val imported = mutableListOf<SpeakerPreset>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    imported += SpeakerPreset(
                        id = o.optString("id", "imported_$i"),
                        name = o.optString("name", "Imported ${i + 1}"),
                        sensitivityDb = o.optDouble("sensitivityDb", 100.0).toFloat().coerceIn(80f, 130f),
                        heightM = o.optDouble("heightM", 1.8).toFloat().coerceIn(0.5f, 20f),
                        arrayElements = o.optInt("arrayElements", 1).coerceIn(1, 16),
                        elementSpacingM = o.optDouble("elementSpacingM", 0.2).toFloat().coerceIn(0.05f, 1.0f),
                        brand = o.optString("brand", "Other"),
                        series = o.optString("series", "Other"),
                        model = o.optString("model", o.optString("name", "Imported ${i + 1}"))
                    )
                }
                if (imported.isNotEmpty()) {
                    val merged = LinkedHashMap<String, SpeakerPreset>()
                    (_speakerPresets.value + imported).forEach { merged[it.id] = it }
                    _speakerPresets.value = merged.values.toList()
                }
            }

            root.optJSONArray("speakerSources")?.let { arr ->
                val loaded = mutableListOf<SpeakerSource>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    loaded += SpeakerSource(
                        id = o.optInt("id", i),
                        name = o.optString("name", "Source ${i + 1}"),
                        role = o.optString("role", "MAINS"),
                        brand = o.optString("brand", "Other"),
                        series = o.optString("series", "Other"),
                        modelPresetId = o.optString("modelPresetId", "").ifBlank { null },
                        modelPackageId = o.optString("modelPackageId", "generic").let { if (isKnownModelPackage(it)) it else "generic" },
                        linkedSourceId = o.optInt("linkedSourceId", -1).takeIf { it >= 0 },
                        linkedMotionMode = o.optString("linkedMotionMode", "SAME").let { if (LINK_MOTION_MODES.contains(it)) it else "SAME" },
                        collapsed = o.optBoolean("collapsed", false)
                    )
                }
                _speakerSources.value = loaded
                nextSourceId = (loaded.maxOfOrNull { it.id } ?: -1) + 1
            }
            _activeSpeakerSourceId.value = root.optInt("activeSpeakerSourceId", -1).takeIf { it >= 0 && _speakerSources.value.any { s -> s.id == it } }

            root.optJSONArray("speakerModelPackages")?.let { arr ->
                val imported = mutableListOf<SpeakerModelPackage>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    imported += parseSpeakerModelPackage(o, i)
                }
                if (imported.isNotEmpty()) {
                    _speakerModelPackages.value = mergeModelPackages(imported)
                }
            }

            val zoneType = root.optString("activeZoneType", _activeZoneType.value)
            _activeZoneType.value = if (ZONE_TYPES.contains(zoneType)) zoneType else "AUDIENCE_SEATED"
            _activeZoneBaseHeightM.value = root.optDouble("activeZoneBaseHeightM", _activeZoneBaseHeightM.value.toDouble()).toFloat().coerceIn(0f, 40f)
            _activeZoneRakeDeg.value = root.optDouble("activeZoneRakeDeg", _activeZoneRakeDeg.value.toDouble()).toFloat().coerceIn(-30f, 30f)
            val rawRakeDir = root.optDouble("activeZoneRakeDirectionDeg", _activeZoneRakeDirectionDeg.value.toDouble()).toFloat()
            _activeZoneRakeDirectionDeg.value = ((rawRakeDir + 180f) % 360f + 360f) % 360f - 180f

            val modelPackage = root.optString("selectedSpeakerModelPackageId", _selectedSpeakerModelPackageId.value)
            _selectedSpeakerModelPackageId.value = if (isKnownModelPackage(modelPackage)) {
                modelPackage
            } else {
                "generic"
            }

            _selectedBandHz.value = root.optInt("selectedBandHz", _selectedBandHz.value)
                .coerceIn(SUPPORTED_BANDS_HZ.min(), SUPPORTED_BANDS_HZ.max())
            _signalLevelDbu.value = root.optDouble("signalLevelDbu", _signalLevelDbu.value.toDouble()).toFloat().coerceIn(-24f, 24f)
            _signalType.value = root.optString("signalType", _signalType.value).takeIf { it == "BAND" || it == "SPECTRUM" } ?: "BAND"
            _weighting.value = root.optString("weighting", _weighting.value).takeIf { it in WEIGHTINGS } ?: WEIGHTING_Z
            _signalBandwidthOct.value = root.optDouble("signalBandwidthOct", _signalBandwidthOct.value.toDouble()).toFloat().coerceIn(1f / 12f, 1f)
            _signalResolution.value = root.optInt("signalResolution", _signalResolution.value).coerceIn(3, 96)
            _groundReflectionEnabled.value = root.optBoolean("groundReflectionEnabled", false)
            _groundReflectionPhase.value=decodedGroundPhase
            _signalInterferenceEnabled.value = root.optBoolean("signalInterferenceEnabled", _signalInterferenceEnabled.value)
            _aimRaysEnabled.value = root.optBoolean("aimRaysEnabled", _aimRaysEnabled.value)
            _receiverAngularOverlayEnabled.value=root.optBoolean("receiverAngularOverlayEnabled",false)
            _contourMode.value = root.optString("contourMode", _contourMode.value)
                .takeIf { it in CONTOUR_MODES } ?: CONTOUR_OFF
            _signalAutoCalculate.value = root.optBoolean("signalAutoCalculate", _signalAutoCalculate.value)
            // Restored directly, not through setSplTargetDb: that setter adopts
            // Target mode when the scale is on Auto, which is right for someone
            // typing a target and wrong for reloading a scene that was
            // deliberately saved on Auto.
            _splTargetDb.value = root
                .optDouble("splTargetDb", _splTargetDb.value.toDouble())
                .toFloat().coerceIn(40f, 140f)
            root.optString("splScaleMode", _splScaleMode.value)
                .takeIf { it in SPL_SCALE_MODES }
                ?.let { _splScaleMode.value = it }
            setSplSpanDb(root.optDouble("splSpanDb", _splSpanDb.value.toDouble()).toFloat())
            setSplFixedMinDb(root.optDouble("splFixedMinDb", _splFixedMinDb.value.toDouble()).toFloat())
            setSplFixedMaxDb(root.optDouble("splFixedMaxDb", _splFixedMaxDb.value.toDouble()).toFloat())
            _signalSplEnabled.value = root.optBoolean("signalSplEnabled", _signalSplEnabled.value)
            _signalDispersionEnabled.value = root.optBoolean("signalDispersionEnabled", _signalDispersionEnabled.value)
            _signalCoverageEnabled.value = root.optBoolean("signalCoverageEnabled", _signalCoverageEnabled.value)
            _temperatureC.value = root.optDouble("temperatureC", _temperatureC.value.toDouble()).toFloat()
                .coerceIn(-10f, 45f)
            _humidityPct.value = root.optDouble("humidityPct", _humidityPct.value.toDouble()).toFloat()
                .coerceIn(5f, 100f)
            _reflectionOrder.value = root.optInt("reflectionOrder", _reflectionOrder.value).coerceIn(1, 3)
            val profile = root.optString("analysisProfile", _analysisProfile.value)
            _analysisProfile.value = if (ANALYSIS_PROFILES.contains(profile)) profile else "Balanced"

            root.optJSONObject("venueGeometry")?.let { vg ->
                val wallH = vg.optDouble("wallHeightM", _venueGeometry.value.wallHeightM.toDouble()).toFloat().coerceIn(3f, 30f)
                val loadedBlocks = mutableListOf<VenueBlock>()
                vg.optJSONArray("blocks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val type = o.optString("type", "OBSTACLE").let { if (VENUE_BLOCK_TYPES.contains(it)) it else "OBSTACLE" }
                        loadedBlocks += VenueBlock(
                            id = o.optInt("id", i),
                            type = type,
                            centerX = o.optDouble("centerX", 0.0).toFloat().coerceIn(-200f, 200f),
                            centerZ = o.optDouble("centerZ", 0.0).toFloat().coerceIn(-200f, 200f),
                            widthM = o.optDouble("widthM", 6.0).toFloat().coerceIn(0.2f, 120f),
                            depthM = o.optDouble("depthM", 3.0).toFloat().coerceIn(0.2f, 120f),
                            heightM = o.optDouble("heightM", 1.0).toFloat().coerceIn(0f, 40f),
                            blockHeightM = o.optDouble("blockHeightM", 1.0).toFloat().coerceIn(0.1f, 40f),
                            slopeDeg = o.optDouble("slopeDeg", 0.0).toFloat().coerceIn(-30f, 30f),
                                                        rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat().coerceIn(0f, 360f),
                            label = o.optString("label", "$type ${i + 1}"),
                        boundaryRole = AcousticBoundaryRole.valueOf(o.optString("boundaryRole", "OCCLUDING")),
                        boundaryMaterial = BoundaryMaterial.fromJsonString(o.opt("boundaryMaterial").let { if (it == null) "\"IDEAL_RIGID\"" else if (it is String) JSONObject.quote(it) else it.toString() })
                        )
                    }
                }
                _venueGeometry.value = VenueGeometry(
                    widthM = vg.optDouble("widthM", _venueGeometry.value.widthM.toDouble()).toFloat().coerceIn(8f, 400f),
                    depthM = vg.optDouble("depthM", _venueGeometry.value.depthM.toDouble()).toFloat().coerceIn(8f, 400f),
                    wallHeightM = wallH,
                    stageCenterX = vg.optDouble("stageCenterX", _venueGeometry.value.stageCenterX.toDouble()).toFloat().coerceIn(-200f, 200f),
                    stageCenterZ = vg.optDouble("stageCenterZ", _venueGeometry.value.stageCenterZ.toDouble()).toFloat().coerceIn(-200f, 200f),
                    stageWidthM = vg.optDouble("stageWidthM", _venueGeometry.value.stageWidthM.toDouble()).toFloat().coerceIn(1f, 100f),
                    stageDepthM = vg.optDouble("stageDepthM", _venueGeometry.value.stageDepthM.toDouble()).toFloat().coerceIn(1f, 80f),
                    stageHeightM = vg.optDouble("stageHeightM", _venueGeometry.value.stageHeightM.toDouble()).toFloat().coerceIn(0f, 20f),
                    stageSlopeDeg = vg.optDouble("stageSlopeDeg", _venueGeometry.value.stageSlopeDeg.toDouble()).toFloat().coerceIn(-20f, 20f),
                    blocks = loadedBlocks,
                stageBoundaryRole = AcousticBoundaryRole.valueOf(vg.optString("stageBoundaryRole", "OCCLUDING")),
                stageBoundaryMaterial = BoundaryMaterial.fromJsonString(vg.opt("stageBoundaryMaterial").let { if (it == null) "\"IDEAL_RIGID\"" else if (it is String) JSONObject.quote(it) else it.toString() })
                )
                _roomMaterials.value = _roomMaterials.value.copy(roomHeightM = wallH)
            }

            _designVariants.value=decodedVariants
            _coverageComparisonSelection.value=decodedComparison
            _comparisonSourceSpectrum.value=decodedSpectrum
            _broadbandDrive.value=decodedDrive
            _comparisonWorkspace.value=decodedImportedComparison
            _savedTraces.value=decodedTraces
            publishAlignment(decodedAlignment)
            _receiverCameraFocus.value=decodedReceiverFocus
            _receiverFrameMessage.value=null
            _receiverAngularSelection.value=com.droidacoustic.pro.geometry.ReceiverMarkerSelection.reconcile(root.optString("receiverAngularSelection","listener"),angularReceiverIds()) ?: "listener"
            _referenceTraceSelection.value=decodedTraceSelection
            root.optJSONObject("listener")?.let { l ->
                _listener.value = ListenerPos(
                    x = l.optDouble("x", 0.0).toFloat(),
                    z = l.optDouble("z", 0.0).toFloat(),
                    earHeightM = l.optDouble("earHeightM", 1.2).toFloat().coerceIn(0.5f, 2.5f)
                )
            }

            root.optJSONObject("roomMaterials")?.let { rm ->
                _roomMaterials.value = RoomMaterials(
                    floor = readSurface(rm, "floor", SurfaceMaterial.CARPET),
                    ceiling = readSurface(rm, "ceiling", SurfaceMaterial.ACOUSTIC_TILE),
                    wall = readSurface(rm, "wall", SurfaceMaterial.GYPSUM),
                    roomHeightM = rm.optDouble("roomHeightM", 8.0).toFloat().coerceIn(3f, 30f)
                )
            }

            val loadedSpeakers = mutableListOf<PlacedSpeaker>()
            root.optJSONArray("speakers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val elements = o.optInt("arrayElements", 1).coerceIn(1, 16)
                    val joints = (elements - 1).coerceAtLeast(0)
                    val profileJson = o.optJSONArray("arraySplayByBoxDeg")
                    val profile = MutableList(joints) { idx ->
                        profileJson?.optDouble(idx, o.optDouble("arrayInterBoxSplayDeg", 0.0))?.toFloat() ?: 0f
                    }.map { it.coerceIn(0f, 10f) }

                    loadedSpeakers += PlacedSpeaker(
                        id = o.optInt("id", i),
                        x = o.optDouble("x", 0.0).toFloat(),
                        z = o.optDouble("z", 0.0).toFloat(),
                        heightM = o.optDouble("heightM", 1.2).toFloat(),
                        sensitivity = o.optDouble("sensitivity", 100.0).toFloat(),
                        arrayElements = elements,
                        arraySpacingM = o.optDouble("arraySpacingM", 0.2).toFloat().coerceIn(0.05f, 0.6f),
                        arrayInterBoxSplayDeg = o.optDouble("arrayInterBoxSplayDeg", 0.0).toFloat().coerceIn(0f, 10f),
                        arraySplayByBoxDeg = profile,
                        arrayAimDeg = o.optDouble("arrayAimDeg", 0.0).toFloat().coerceIn(-30f, 30f),
                        panDeg = o.optDouble("panDeg", 0.0).toFloat().coerceIn(-180f, 180f),
                        arraySteerDeg = o.optDouble("arraySteerDeg", 0.0).toFloat().coerceIn(-30f, 30f),
                        arrayEdgeTaperDb = o.optDouble("arrayEdgeTaperDb", 0.0).toFloat().coerceIn(0f, 12f),
                        modelPackageId = o.optString("modelPackageId", "generic").let { if (isKnownModelPackage(it)) it else "generic" },
                        presetId = o.optString("presetId", ""),
                        requiresMeasuredData = o.optBoolean("requiresMeasuredData", false) ||
                            opened.references.containsKey(o.optString("presetId", "")),
                        sourceId = o.optInt("sourceId", -1).takeIf { it >= 0 },
                        label = o.optString("label", "SPK ${i + 1}")
                    )
                }
            }
            _speakers.value = loadedSpeakers

            val loadedDsp = mutableMapOf<Int, SpeakerDsp>()
            root.optJSONArray("dsp")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val speakerId = o.optInt("speakerId", -1)
                    if (speakerId < 0) continue
                    val eqObj = o.optJSONObject("eqBands")
                    val eq = mutableMapOf<Int, Float>()
                    if (eqObj != null) {
                        eqObj.keys().forEach { key ->
                            key.toIntOrNull()?.let { band ->
                                eq[band] = eqObj.optDouble(key, 0.0).toFloat().coerceIn(-6f, 6f)
                            }
                        }
                    }
                    loadedDsp[speakerId] = SpeakerDsp(
                        speakerId = speakerId,
                        delayMs = o.optDouble("delayMs", 0.0).toFloat().coerceIn(0f, 200f),
                        gainDb = o.optDouble("gainDb", 0.0).toFloat().coerceIn(-12f, 12f),
                        polarity = o.optBoolean("polarity", false),
                        eqBands = eq,
                        filters = o.optJSONObject("filters")?.let {com.droidacoustic.pro.acoustics.DspFilterChain.fromJsonString(it.toString())} ?: com.droidacoustic.pro.acoustics.DspFilterChain()
                    )
                }
            }
            _dspMap.value = loadedDsp

            val loadedAudience = mutableListOf<AudiencePoint>()
            root.optJSONArray("audience")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    loadedAudience += AudiencePoint(
                        id = o.optInt("id", i),
                        x = o.optDouble("x", 0.0).toFloat(),
                        z = o.optDouble("z", 0.0).toFloat(),
                        earHeightM = o.optDouble("earHeightM", 1.2).toFloat(),
                        sourceAreaId = if (o.has("sourceAreaId")) o.optInt("sourceAreaId", -1).takeIf { it >= 0 } else null,
                        name = o.optString("name", "AUD ${i + 1}")
                    )
                }
            }
            _audience.value = loadedAudience

            val loadedAreas = mutableListOf<AudienceArea>()
            root.optJSONArray("audienceAreas")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val verts = mutableListOf<Pair<Float, Float>>()
                    o.optJSONArray("vertices")?.let { vArr ->
                        for (vi in 0 until vArr.length()) {
                            val vo = vArr.optJSONObject(vi) ?: continue
                            verts += vo.optDouble("x", 0.0).toFloat() to vo.optDouble("z", 0.0).toFloat()
                        }
                    }
                    val importedMesh=o.optJSONObject("audienceMesh")?.let { AudienceMeshJson.decode(it.toString()) }
                    loadedAreas += AudienceArea(
                        id = o.optInt("id", i),
                        name = o.optString("name", "AREA ${i + 1}"),
                        zoneType = o.optString("zoneType", "AUDIENCE_SEATED").let { if (ZONE_TYPES.contains(it)) it else "AUDIENCE_SEATED" },
                        baseHeightM = o.optDouble("baseHeightM", 0.0).toFloat().coerceIn(0f, 40f),
                        rakeDeg = o.optDouble("rakeDeg", 0.0).toFloat().coerceIn(-30f, 30f),
                        rakeDirectionDeg = o.optDouble("rakeDirectionDeg", 0.0).toFloat().let { ((it + 180f) % 360f + 360f) % 360f - 180f },
                        rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat().let { ((it % 360f) + 360f) % 360f },
                        linkedZoneId = if (o.has("linkedZoneId")) o.optInt("linkedZoneId", -1).takeIf { it >= 0 } else null,
                        vertices = importedMesh?.boundary()?.map { it.x.toFloat() to it.z.toFloat() } ?: verts,
                        holes = audienceRingsFromJson(o.optJSONArray("holes")),
                        exclusions = audienceRingsFromJson(o.optJSONArray("exclusions")),
                        vertexOffsetsM = o.optJSONArray("vertexOffsetsM")?.let { a ->
                            (0 until a.length()).map { a.getDouble(it).toFloat() } }.orEmpty(),
                        elevationPoints = audienceElevationPointsFromJson(o.optJSONArray("elevationPoints")),
                        breaklines = audienceBreaklinesFromJson(o.optJSONArray("breaklines")),
                        importedMesh = importedMesh
                    )
                }
            }
            _audienceAreas.value = loadedAreas
            _areaDraft.value = emptyList()
            _cutoutTarget.value = null
            _elevationTarget.value = null
            _audienceGeometryError.value = null
            _importedFaceSelection.value = null

            nextId = (_speakers.value.maxOfOrNull { it.id } ?: -1) + 1
            nextAudienceId = (_audience.value.maxOfOrNull { it.id } ?: -1) + 1
            nextAreaId = (_audienceAreas.value.maxOfOrNull { it.id } ?: -1) + 1

            projectAssetReferences = opened.references
            _assetRecoveryProblems.value = opened.problems
            _clfRegistry.value = opened.speakers
            opened.speakers.forEach { (id, data) -> projectAssets.seed(data, opened.assets.getValue(id)) }
            _clfSourceStatus.value = opened.speakers.keys.associateWith { "PROJECT" }
            // Imported presets are available now; selection must follow library restoration.
            if (_speakerPresets.value.any { it.id == presetId }) _selectedPresetId.value = presetId

            recalculate(force = true)
            refreshHeatmap(force = true)
            _lastImportError.value = null
        }.onFailure {
            _lastImportError.value = it.message ?: "Invalid scene JSON"
        }.isSuccess
    }

    fun moveListener(x: Float, z: Float) {
        pushUndoCheckpoint()
        _listener.value = _listener.value.copy(x=x, z=z)
        recalculate()
    }

    fun setBandHz(bandHz: Int) {
        if (bandHz !in SUPPORTED_BANDS_HZ) return
        if (_selectedBandHz.value == bandHz) return
        pushUndoCheckpoint()
        _selectedBandHz.value = bandHz
        recomputeSignalIfNeeded()
    }

    fun setSignalLevelDbu(value: Float) {
        val clamped = value.coerceIn(-24f, 24f)
        if (_signalLevelDbu.value == clamped) return
        pushUndoCheckpoint()
        _signalLevelDbu.value = clamped
        recomputeSignalIfNeeded()
    }

    fun setSignalType(type: String) {
        if (type != "BAND" && type != "SPECTRUM") return
        if (_signalType.value == type) return
        pushUndoCheckpoint()
        _signalType.value = type
        recomputeSignalIfNeeded()
    }

    fun setSignalBandwidthOct(value: Float) {
        val clamped = value.coerceIn(1f / 12f, 1f)
        if (kotlin.math.abs(_signalBandwidthOct.value - clamped) < 1e-4f) return
        pushUndoCheckpoint()
        _signalBandwidthOct.value = clamped
        recomputeSignalIfNeeded()
    }

    fun setSignalResolution(value: Int) {
        val clamped = value.coerceIn(3, 96)
        if (_signalResolution.value == clamped) return
        pushUndoCheckpoint()
        _signalResolution.value = clamped
        recomputeSignalIfNeeded()
    }

    fun setWeighting(w: String) {
        if (w !in WEIGHTINGS || _weighting.value == w) return
        pushUndoCheckpoint()
        _weighting.value = w
        recomputeSignalIfNeeded()
    }

    fun setContourMode(mode: String) {
        // Not undoable - it is a way of looking at the map, not a change to the
        // design - but it is still saved, so it still has to mark the scene.
        if (mode in CONTOUR_MODES && _contourMode.value != mode) {
            _contourMode.value = mode
            touch()
        }
    }

    /**
     * The level contours are drawn against.
     *
     * A design target if one is set, because that is the level the system is
     * being built to hold. Otherwise the loudest point on the plane, which makes
     * the contours read as "how far down from the hot spot" - useful, but a
     * moving reference, so it changes as the design changes.
     */
    fun contourReferenceDb(cells: List<HeatCell>): Float? {
        if (cells.isEmpty()) return null
        if (_splScaleMode.value == SPL_SCALE_TARGET) return _splTargetDb.value
        // Not the maximum. The single loudest cell is usually the one directly
        // under a box, and referencing it drags every contour into a tight knot
        // around the speaker instead of across the listening plane. The 95th
        // percentile is the same number for a smooth field and far steadier when
        // one cell happens to sit in the near field.
        if (cells === _heatmap.value) return _coverageStatistics.value?.overall?.p95Db?.toFloat()
        // Legacy standalone callers without surface topology retain sample-based scaling.
        val sorted = cells.map { it.splDb }.sorted()
        val idx = ((sorted.size - 1) * 0.95f).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /** Purely visual, so it neither pushes undo nor triggers a recalculation. */
    fun setAimRaysEnabled(enabled: Boolean) {
        if (_aimRaysEnabled.value == enabled) return
        _aimRaysEnabled.value = enabled
        touch()
    }

    private val _groundReflectionPhase=MutableStateFlow<ReflectionPhase?>(null)
    val groundReflectionPhase=_groundReflectionPhase.asStateFlow()
    fun setGroundReflectionPhase(phase:ReflectionPhase?) {
        if(phase==_groundReflectionPhase.value) return
        pushUndoCheckpoint();_groundReflectionPhase.value=phase;recomputeSignalIfNeeded()
    }

    fun setGroundReflectionEnabled(enabled: Boolean) {
        if (_groundReflectionEnabled.value == enabled) return
        pushUndoCheckpoint()
        _groundReflectionEnabled.value = enabled
        recomputeSignalIfNeeded()
    }

    fun setSignalInterferenceEnabled(enabled: Boolean) {
        if (_signalInterferenceEnabled.value == enabled) return
        pushUndoCheckpoint()
        _signalInterferenceEnabled.value = enabled
        recomputeSignalIfNeeded()
    }

    // Checkpointed like the other scene settings, so a scale change is undoable
    // and lands in the recovery snapshot.
    fun setSplScaleMode(mode: String) {
        if (mode !in SPL_SCALE_MODES || _splScaleMode.value == mode) return
        pushUndoCheckpoint()
        _splScaleMode.value = mode
    }

    /**
     * The design target, and the scale that shows it.
     *
     * Setting a target selects Target mode when the scale was on Auto. Auto
     * restretches the ramp to whatever each calculation happens to contain, so
     * 95-98 dB and 85-105 dB paint the same picture and cannot be compared
     * between runs - which is the mistake the target exists to prevent. Someone
     * who has just stated the level they are designing to should not have to
     * find a second control before the map honours it.
     *
     * A deliberate choice of Fixed window is left alone.
     */
    fun setSplTargetDb(db: Float) {
        val v = db.coerceIn(40f, 140f)
        val adopt = _splScaleMode.value == SPL_SCALE_AUTO
        if (_splTargetDb.value == v && !adopt) return
        pushUndoCheckpoint()
        _splTargetDb.value = v
        if (adopt) _splScaleMode.value = SPL_SCALE_TARGET
    }

    fun setSplSpanDb(db: Float) {
        val v = db.coerceIn(1f, 40f)
        if (_splSpanDb.value == v) return
        pushUndoCheckpoint()
        _splSpanDb.value = v
    }

    fun setSplFixedMinDb(db: Float) {
        val lo = db.coerceIn(0f, 160f)
        if (_splFixedMinDb.value == lo) return
        pushUndoCheckpoint()
        _splFixedMinDb.value = lo
        if (_splFixedMaxDb.value < lo + 1f) _splFixedMaxDb.value = lo + 1f
    }

    fun setSplFixedMaxDb(db: Float) {
        val hi = db.coerceIn(1f, 161f)
        if (_splFixedMaxDb.value == hi) return
        pushUndoCheckpoint()
        _splFixedMaxDb.value = hi
        if (_splFixedMinDb.value > hi - 1f) _splFixedMinDb.value = hi - 1f
    }

    /**
     * The dB window the colour ramp spans, for the current scale mode. The mesh
     * and the legend both read it, so the key on screen always describes the map
     * it sits next to. AUTO falls back to the data, which is why it is the only
     * mode that needs the cells at all.
     */
    fun resolveSplScale(cells: List<HeatCell>): Pair<Float, Float> = splScaleWindow(
        _splScaleMode.value,
        _splTargetDb.value,
        _splSpanDb.value,
        _splFixedMinDb.value,
        _splFixedMaxDb.value,
        cells
    )

    fun setSignalAutoCalculate(enabled: Boolean) {
        if (_signalAutoCalculate.value == enabled) return
        _signalAutoCalculate.value = enabled
        if (enabled) {
            recalculate(force = true)
            refreshHeatmap(force = true)
        }
    }

    fun setSignalSplEnabled(enabled: Boolean) {
        if (_signalSplEnabled.value == enabled) return
        pushUndoCheckpoint()
        _signalSplEnabled.value = enabled
        recalculate()
        refreshHeatmap()
    }

    fun setSignalDispersionEnabled(enabled: Boolean) {
        if (_signalDispersionEnabled.value == enabled) return
        pushUndoCheckpoint()
        _signalDispersionEnabled.value = enabled
        recalculate()
        refreshHeatmap()
    }

    fun setSignalCoverageEnabled(enabled: Boolean) {
        if (_signalCoverageEnabled.value == enabled) return
        pushUndoCheckpoint()
        _signalCoverageEnabled.value = enabled
        refreshHeatmap()
    }

    fun recalculateSignal() {
        recalculate(force = true)
        refreshHeatmap(force = true)
    }

    fun setTemperatureC(value: Float) {
        pushUndoCheckpoint()
        _temperatureC.value = value.coerceIn(-10f, 45f)
        recomputeSignalIfNeeded()
    }

    fun setHumidityPct(value: Float) {
        pushUndoCheckpoint()
        _humidityPct.value = value.coerceIn(5f, 100f)
        recomputeSignalIfNeeded()
    }

    private fun recomputeSignalIfNeeded() {
        if (_signalAutoCalculate.value) {
            recalculate()
            refreshHeatmap()
        }
    }

    fun setReflectionOrder(order: Int) {
        val clamped = order.coerceIn(1, 3)
        if (_reflectionOrder.value == clamped) return
        pushUndoCheckpoint()
        _reflectionOrder.value = clamped
        recalculate()
    }

    fun setAnalysisProfile(profile: String) {
        if (!ANALYSIS_PROFILES.contains(profile)) return
        if (_analysisProfile.value == profile) return
        pushUndoCheckpoint()
        _analysisProfile.value = profile
        recalculate()
        refreshHeatmap()
    }

    fun setVenueSize(widthM: Float, depthM: Float) {
        val w = widthM.coerceIn(8f, 400f)
        val d = depthM.coerceIn(8f, 400f)
        if (_venueGeometry.value.widthM == w && _venueGeometry.value.depthM == d) return
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(widthM = w, depthM = d)
        recalculate()
        refreshHeatmap()
    }

    fun setVenueWallHeight(heightM: Float) {
        val h = heightM.coerceIn(3f, 30f)
        if (_venueGeometry.value.wallHeightM == h && _roomMaterials.value.roomHeightM == h) return
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(wallHeightM = h)
        _roomMaterials.value = _roomMaterials.value.copy(roomHeightM = h)
        recalculate()
        refreshHeatmap()
    }

    fun setStageCenter(x: Float, z: Float) {
        val nx = x.coerceIn(-200f, 200f)
        val nz = z.coerceIn(-200f, 200f)
        if (_venueGeometry.value.stageCenterX == nx && _venueGeometry.value.stageCenterZ == nz) return
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(stageCenterX = nx, stageCenterZ = nz)
        refreshHeatmap()
    }

    fun setStageSize(widthM: Float, depthM: Float) {
        val w = widthM.coerceIn(1f, 100f)
        val d = depthM.coerceIn(1f, 80f)
        if (_venueGeometry.value.stageWidthM == w && _venueGeometry.value.stageDepthM == d) return
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(stageWidthM = w, stageDepthM = d)
        refreshHeatmap()
    }

    fun setStageHeight(heightM: Float) {
        val h = heightM.coerceIn(0f, 20f)
        if (_venueGeometry.value.stageHeightM == h) return
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(stageHeightM = h)
        refreshHeatmap()
    }

    fun setStageSlope(slopeDeg: Float) {
        val s = slopeDeg.coerceIn(-20f, 20f)
        if (_venueGeometry.value.stageSlopeDeg == s) return
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(stageSlopeDeg = s)
        refreshHeatmap()
    }

    fun setVenueBlockMaterial(id: Int,material: BoundaryMaterial) {
        val b=_venueGeometry.value.blocks.firstOrNull {it.id==id} ?: return
        if(b.boundaryMaterial==material) return
        pushUndoCheckpoint()
        _venueGeometry.value=_venueGeometry.value.copy(blocks=_venueGeometry.value.blocks.map {if(it.id==id) it.copy(boundaryMaterial=material) else it})
        refreshHeatmap()
    }
    fun setStageMaterial(material: BoundaryMaterial) {
        if(_venueGeometry.value.stageBoundaryMaterial==material) return
        pushUndoCheckpoint(); _venueGeometry.value=_venueGeometry.value.copy(stageBoundaryMaterial=material); refreshHeatmap()
    }
    fun setImportedMaterial(id: Int,faces: Collection<Int>,material: BoundaryMaterial)=
        editImportedMesh(id) {it.assignBoundaryMaterial(faces,material)}

    fun setVenueBlockBoundaryRole(blockId: Int,role: AcousticBoundaryRole) {
        val block=_venueGeometry.value.blocks.firstOrNull {it.id==blockId} ?: return
        if (block.boundaryRole==role) return
        pushUndoCheckpoint()
        _venueGeometry.value=_venueGeometry.value.copy(blocks=_venueGeometry.value.blocks.map {if (it.id==blockId) it.copy(boundaryRole=role) else it})
        refreshHeatmap()
    }
    fun setStageBoundaryRole(role: AcousticBoundaryRole) {
        if (_venueGeometry.value.stageBoundaryRole==role) return
        pushUndoCheckpoint()
        _venueGeometry.value=_venueGeometry.value.copy(stageBoundaryRole=role)
        refreshHeatmap()
    }

    fun addVenueBlock(type: String) {
        addVenueBlock(type, 0f, 0f)
    }

    fun addVenueBlock(type: String, centerX: Float, centerZ: Float) {
        val blockType = if (VENUE_BLOCK_TYPES.contains(type)) type else "OBSTACLE"
        pushUndoCheckpoint()
        val nextId = ((_venueGeometry.value.blocks.maxOfOrNull { it.id } ?: -1) + 1)
        val countForType = _venueGeometry.value.blocks.count { it.type == blockType } + 1
        val label = when (blockType) {
            "STAGE" -> "Stage $countForType"
            "SEATING_BANK" -> "Seating $countForType"
            "BALCONY" -> "Balcony $countForType"
            "WALL" -> "Wall $countForType"
            else -> "Obstacle $countForType"
        }
        val block = VenueBlock(
            id = nextId,
            type = blockType,
            centerX = centerX.coerceIn(-200f, 200f),
            centerZ = centerZ.coerceIn(-200f, 200f),
            widthM = if (blockType == "WALL") 10f else 6f,
            depthM = if (blockType == "WALL") 0.5f else 3f,
            heightM = if (blockType == "BALCONY") 4f else if (blockType == "WALL") 4f else 1f,
            blockHeightM = if (blockType == "WALL") 4f else 1f,
            slopeDeg = if (blockType == "SEATING_BANK") 8f else 0f,
            label = label
        )
        _venueGeometry.value = _venueGeometry.value.copy(blocks = _venueGeometry.value.blocks + block)
        refreshHeatmap()
    }

    fun removeVenueBlock(blockId: Int) {
        if (_venueGeometry.value.blocks.none { it.id == blockId }) return
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(blocks = _venueGeometry.value.blocks.filter { it.id != blockId })
        refreshHeatmap()
    }

    fun setVenueBlockType(blockId: Int, type: String) {
        if (!VENUE_BLOCK_TYPES.contains(type)) return
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = _venueGeometry.value.blocks.map { b -> if (b.id != blockId) b else b.copy(type = type) }
        )
        refreshHeatmap()
    }

    /** Pure render preview; y is the block elevation, not its thickness. */
    fun previewVenueBlockManipulation(blockId: Int, x: Float, y: Float, z: Float,
        rotationDeg: Float): VenueGeometry {
        val current=_venueGeometry.value
        val block=current.blocks.firstOrNull { it.id==blockId } ?: return current
        val updated=runCatching { SceneGeometryTransforms.blockAt(block,x,y,z,rotationDeg) }.getOrNull() ?: return current
        return current.copy(blocks=current.blocks.map { if(it.id==blockId) updated else it })
    }

    fun commitVenueBlockManipulation(blockId: Int, x: Float, y: Float, z: Float,
        rotationDeg: Float): Boolean {
        val updated=previewVenueBlockManipulation(blockId,x,y,z,rotationDeg)
        if(updated==_venueGeometry.value) return false
        pushUndoCheckpoint()
        _venueGeometry.value=updated
        recalculate(); refreshHeatmap()
        return true
    }

    /** Absolute target centroid/base/yaw, with no state, history or prediction mutation. */
    fun previewAudienceAreaManipulation(areaId: Int, x: Float, y: Float, z: Float,
        rotationDeg: Float): List<AudienceArea> {
        val current=_audienceAreas.value
        if(listOf(x,y,z,rotationDeg).any { !it.isFinite() }) return current
        val area=current.firstOrNull {it.id==areaId} ?: return current
        val updated=runCatching {
            val surface=area.sharedSurface()
            val center=SceneGeometryTransforms.center(surface)
            val yaw=SceneGeometryTransforms.normalizedYaw(rotationDeg)
            val dx=x.coerceIn(-200f,200f)-center.x.toFloat()
            val dz=z.coerceIn(-200f,200f)-center.z.toFloat()
            val dy=y.coerceIn(0f,40f)-area.baseHeightM
            if(dx==0f && dy==0f && dz==0f && yaw==area.rotationDeg) return current
            val transformed=SceneGeometryTransforms.transform(surface,dx,dy,dz,yaw-area.rotationDeg)
            area.copy(vertices=transformed.vertices().map {it.x.toFloat() to it.z.toFloat()},
                baseHeightM=transformed.baseHeightM,rakeDirectionDeg=transformed.rakeDirectionDeg,
                rotationDeg=yaw,holes=transformed.holes().map {ring -> ring.map {it.x.toFloat() to it.z.toFloat()}},
                exclusions=transformed.exclusions().map {ring -> ring.map {it.x.toFloat() to it.z.toFloat()}},
                elevationPoints=transformed.elevationPoints(),importedMesh=transformed.importedMesh
            ).also {it.sharedSurface().validateGeometry()}
        }.getOrNull() ?: return current
        return current.map {if(it.id==areaId) updated else it}
    }

    fun commitAudienceAreaManipulation(areaId: Int, x: Float, y: Float, z: Float,
        rotationDeg: Float): Boolean {
        val updated=previewAudienceAreaManipulation(areaId,x,y,z,rotationDeg)
        if(updated==_audienceAreas.value) return false
        pushUndoCheckpoint()
        _audienceAreas.value=updated
        _audienceGeometryError.value=null
        rebuildAudiencePointsForArea(areaId)
        return true
    }

    fun setVenueBlockCenter(blockId: Int, x: Float, z: Float) {
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = _venueGeometry.value.blocks.map { b ->
                if (b.id != blockId) b else b.copy(
                    centerX = x.coerceIn(-200f, 200f),
                    centerZ = z.coerceIn(-200f, 200f)
                )
            }
        )
        refreshHeatmap()
    }

    fun setVenueBlockSize(blockId: Int, widthM: Float, depthM: Float) {
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = _venueGeometry.value.blocks.map { b ->
                if (b.id != blockId) b else b.copy(
                    widthM = widthM.coerceIn(0.2f, 120f),
                    depthM = depthM.coerceIn(0.2f, 120f)
                )
            }
        )
        refreshHeatmap()
    }

    fun setVenueBlockHeight(blockId: Int, heightM: Float) {
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = _venueGeometry.value.blocks.map { b ->
                if (b.id != blockId) b else b.copy(heightM = heightM.coerceIn(0f, 40f))
            }
        )
        refreshHeatmap()
    }

    fun setVenueBlockThickness(blockId: Int, blockHeightM: Float) {
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = _venueGeometry.value.blocks.map { b ->
                if (b.id != blockId) b else b.copy(blockHeightM = blockHeightM.coerceIn(0.1f, 40f))
            }
        )
        refreshHeatmap()
    }

    fun setVenueBlockSlope(blockId: Int, slopeDeg: Float) {
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = _venueGeometry.value.blocks.map { b ->
                if (b.id != blockId) b else b.copy(slopeDeg = slopeDeg.coerceIn(-30f, 30f))
            }
        )
        refreshHeatmap()
    }

    fun setVenueBlockRotation(blockId: Int, rotationDeg: Float) {
        pushUndoCheckpoint()
        val normalized = ((rotationDeg % 360f) + 360f) % 360f
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = _venueGeometry.value.blocks.map { b ->
                if (b.id != blockId) b else b.copy(rotationDeg = normalized)
            }
        )
        refreshHeatmap()
    }

    fun setVenueBlockLabel(blockId: Int, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = _venueGeometry.value.blocks.map { b ->
                if (b.id != blockId) b else b.copy(label = trimmed)
            }
        )
        refreshHeatmap()
    }

    fun duplicateVenueBlock(blockId: Int) {
        val src = _venueGeometry.value.blocks.firstOrNull { it.id == blockId } ?: return
        pushUndoCheckpoint()
        val nextId = ((_venueGeometry.value.blocks.maxOfOrNull { it.id } ?: -1) + 1)
        val copy = src.copy(
            id = nextId,
            centerX = (src.centerX + 1.0f).coerceIn(-200f, 200f),
            centerZ = (src.centerZ + 1.0f).coerceIn(-200f, 200f),
            label = "${src.label} Copy"
        )
        _venueGeometry.value = _venueGeometry.value.copy(blocks = _venueGeometry.value.blocks + copy)
        refreshHeatmap()
    }

    fun mirrorVenueBlockX(blockId: Int, referenceBlockId: Int? = null) {
        val blocks = _venueGeometry.value.blocks
        if (blocks.none { it.id == blockId }) return
        val refX = referenceBlockId?.let { refId -> blocks.firstOrNull { it.id == refId }?.centerX } ?: 0f
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = blocks.map { b ->
                if (b.id != blockId) b else b.copy(
                    centerX = (2f * refX - b.centerX).coerceIn(-200f, 200f),
                    rotationDeg = ((360f - b.rotationDeg) % 360f + 360f) % 360f
                )
            }
        )
        refreshHeatmap()
    }

    fun mirrorVenueBlockZ(blockId: Int, referenceBlockId: Int? = null) {
        val blocks = _venueGeometry.value.blocks
        if (blocks.none { it.id == blockId }) return
        val refZ = referenceBlockId?.let { refId -> blocks.firstOrNull { it.id == refId }?.centerZ } ?: 0f
        pushUndoCheckpoint()
        _venueGeometry.value = _venueGeometry.value.copy(
            blocks = blocks.map { b ->
                if (b.id != blockId) b else b.copy(
                    centerZ = (2f * refZ - b.centerZ).coerceIn(-200f, 200f),
                    rotationDeg = ((180f - b.rotationDeg) % 360f + 360f) % 360f
                )
            }
        )
        refreshHeatmap()
    }

    // ─── Phase 8: material-aware RT60 setters ────────────────────────────────

    fun setFloorAbsorption(v: Float) {
        pushUndoCheckpoint()
        _roomMaterials.value = _roomMaterials.value.copy(floor = SurfaceMaterial.flat(v))
        recalculate(); refreshHeatmap()
    }

    /** Choose a catalogue surface. Unknown ids are ignored rather than guessed at. */
    fun setSurfaceMaterial(surface: String, materialId: String) {
        val m = SurfaceMaterial.byId(materialId) ?: return
        val current = _roomMaterials.value
        val updated = when (surface) {
            "FLOOR" -> current.copy(floor = m)
            "CEILING" -> current.copy(ceiling = m)
            "WALL" -> current.copy(wall = m)
            else -> return
        }
        if (updated == current) return
        pushUndoCheckpoint()
        _roomMaterials.value = updated
        recalculate(); refreshHeatmap()
    }

    fun setCeilingAbsorption(v: Float) {
        pushUndoCheckpoint()
        _roomMaterials.value = _roomMaterials.value.copy(ceiling = SurfaceMaterial.flat(v))
        recalculate(); refreshHeatmap()
    }

    fun setWallAbsorption(v: Float) {
        pushUndoCheckpoint()
        _roomMaterials.value = _roomMaterials.value.copy(wall = SurfaceMaterial.flat(v))
        recalculate(); refreshHeatmap()
    }

    fun setRoomHeight(v: Float) {
        pushUndoCheckpoint()
        val h = v.coerceIn(3f, 30f)
        _roomMaterials.value = _roomMaterials.value.copy(roomHeightM = h)
        _venueGeometry.value = _venueGeometry.value.copy(wallHeightM = h)
        recalculate(); refreshHeatmap()
    }

    fun addAudiencePoint(x: Float, z: Float) {
        pushUndoCheckpoint()
        val id = nextAudienceId++
        _audience.value = _audience.value + AudiencePoint(id = id, x = x, z = z, earHeightM = 1.2f)
        recalculate()
        refreshHeatmap()
    }

    fun removeAudiencePoint(id: Int) {
        pushUndoCheckpoint()
        _audience.value = _audience.value.filter { it.id != id }
        recalculate()
        refreshHeatmap()
    }

    fun clearAudience() {
        _cutoutTarget.value = null
        _elevationTarget.value = null
        _audienceGeometryError.value = null
        _importedFaceSelection.value = null
        pushUndoCheckpoint()
        _audience.value = emptyList()
        _audienceAreas.value = emptyList()
        _areaDraft.value = emptyList()
        recalculate()
        refreshHeatmap()
    }

    fun addAreaVertex(x: Float, z: Float) {
        val draft = _areaDraft.value
        var sx = snapToGrid(x)
        var sz = snapToGrid(z)

        if (draft.isNotEmpty()) {
            val prev = draft.last()
            val rawDx = sx - prev.first
            val rawDz = sz - prev.second
            val rawLen = sqrt(rawDx * rawDx + rawDz * rawDz)
            if (rawLen < AREA_MIN_SEGMENT_M) return

            val angleRad = atan2(rawDz, rawDx)
            val stepRad = Math.toRadians(AREA_ANGLE_SNAP_DEG.toDouble()).toFloat()
            val snappedAngle = (round(angleRad / stepRad) * stepRad)
            sx = snapToGrid(prev.first + cos(snappedAngle) * rawLen)
            sz = snapToGrid(prev.second + sin(snappedAngle) * rawLen)

            val dx = sx - prev.first
            val dz = sz - prev.second
            val len = sqrt(dx * dx + dz * dz)
            if (len < AREA_MIN_SEGMENT_M) return

            if (draft.size >= 2 && wouldSelfIntersectDraft(draft, sx to sz)) return
        }

        pushUndoCheckpoint()
        _areaDraft.value = draft + (sx to sz)
    }

    fun undoAreaVertex() {
        val d = _areaDraft.value
        if (d.isNotEmpty()) {
            pushUndoCheckpoint()
            _areaDraft.value = d.dropLast(1)
        }
    }

    fun clearAreaDraft() {
        pushUndoCheckpoint()
        _areaDraft.value = emptyList()
        _cutoutTarget.value = null
        _elevationTarget.value = null
        _audienceGeometryError.value = null
        _importedFaceSelection.value = null
    }

    fun beginAudienceCutout(areaId: Int, hole: Boolean) {
        if (_audienceAreas.value.none { it.id == areaId }) return
        _areaDraft.value = emptyList()
        _audienceGeometryError.value = null
        _elevationTarget.value = null
        _cutoutTarget.value = AudienceCutoutTarget(areaId,hole)
    }

    fun beginAudienceElevationPoint(areaId: Int) {
        if (_audienceAreas.value.none { it.id == areaId }) return
        _areaDraft.value=emptyList(); _cutoutTarget.value=null
        _audienceGeometryError.value=null; _elevationTarget.value=areaId
    }

    private fun updateAudienceElevation(area: AudienceArea): Boolean {
        val validation=runCatching { area.sharedSurface().validateGeometry() }
        if (validation.isFailure) {
            _audienceGeometryError.value=validation.exceptionOrNull()?.message ?: "Invalid surface elevation"
            return false
        }
        pushUndoCheckpoint()
        _audienceAreas.value=_audienceAreas.value.map { if (it.id==area.id) area else it }
        _audienceGeometryError.value=null
        rebuildAudiencePointsForArea(area.id)
        return true
    }

    fun setAudienceVertexOffset(areaId: Int,index: Int,offsetM: Float): Boolean {
        val area=_audienceAreas.value.firstOrNull { it.id==areaId } ?: return false
        if (index !in area.vertices.indices) return false
        val offsets=area.vertices.indices.map { area.vertexOffsetsM.getOrElse(it) {0f} }.toMutableList()
        offsets[index]=offsetM
        return updateAudienceElevation(area.copy(vertexOffsetsM=offsets))
    }

    fun addAudienceElevationPoint(areaId: Int,x: Float,z: Float,offsetM: Float? = null): Boolean {
        val area=_audienceAreas.value.firstOrNull { it.id==areaId } ?: return false
        val offset=offsetM ?: area.sharedSurface().elevationOffsetAt(x,z)
        val success=updateAudienceElevation(area.copy(elevationPoints=area.elevationPoints+
            AudienceElevationPoint(x.toDouble(),z.toDouble(),offset)))
        if (success) _elevationTarget.value=null
        return success
    }

    fun setAudienceElevationPointOffset(areaId: Int,index: Int,offsetM: Float): Boolean {
        val area=_audienceAreas.value.firstOrNull { it.id==areaId } ?: return false
        if (index !in area.elevationPoints.indices) return false
        return updateAudienceElevation(area.copy(elevationPoints=area.elevationPoints.mapIndexed { i,p ->
            if (i==index) p.copy(offsetM=offsetM) else p }))
    }

    fun removeAudienceElevationPoint(areaId: Int,index: Int) {
        val area=_audienceAreas.value.firstOrNull { it.id==areaId } ?: return
        if (index !in area.elevationPoints.indices) return
        updateAudienceElevation(area.copy(elevationPoints=area.elevationPoints.filterIndexed { i,_ -> i!=index },
            breaklines=area.sharedSurface().breaklinesAfterRemovingInterior(index)))
    }

    fun addAudienceBreakline(areaId: Int,start: AudienceVertexRef,end: AudienceVertexRef): Boolean {
        val area=_audienceAreas.value.find { it.id==areaId } ?: return false
        if (area.breaklines.any { (it.start==start && it.end==end)||(it.start==end && it.end==start) }) return false
        return updateAudienceElevation(area.copy(breaklines=area.breaklines+AudienceBreakline(start,end)))
    }

    fun removeAudienceBreakline(areaId: Int,index: Int) {
        val area=_audienceAreas.value.find { it.id==areaId } ?: return
        if (index !in area.breaklines.indices) return
        updateAudienceElevation(area.copy(breaklines=area.breaklines.filterIndexed { i,_ -> i!=index }))
    }

    fun resetAudienceElevations(areaId: Int) {
        val area=_audienceAreas.value.firstOrNull { it.id==areaId } ?: return
        updateAudienceElevation(area.copy(vertexOffsetsM=emptyList(),elevationPoints=emptyList(),breaklines=emptyList()))
    }

    fun addAudienceCutout(areaId: Int, vertices: List<Pair<Float,Float>>, hole: Boolean): Boolean {
        val area=_audienceAreas.value.firstOrNull { it.id == areaId } ?: return false
        val updated=if (hole) area.copy(holes=area.holes+listOf(vertices.toList()))
            else area.copy(exclusions=area.exclusions+listOf(vertices.toList()))
        val validation=runCatching { updated.sharedSurface().validateGeometry() }
        if (validation.isFailure) {
            _audienceGeometryError.value=validation.exceptionOrNull()?.message ?: "Invalid audience cutout"
            return false
        }
        pushUndoCheckpoint()
        _audienceAreas.value=_audienceAreas.value.map { if (it.id == areaId) updated else it }
        _audienceGeometryError.value=null
        recalculate(); refreshHeatmap()
        return true
    }

    fun removeAudienceCutout(areaId: Int, index: Int, hole: Boolean) {
        val area=_audienceAreas.value.firstOrNull { it.id == areaId } ?: return
        val rings=if (hole) area.holes else area.exclusions
        if (index !in rings.indices) return
        pushUndoCheckpoint()
        val updated=if (hole) area.copy(holes=rings.filterIndexed { i,_ -> i != index })
            else area.copy(exclusions=rings.filterIndexed { i,_ -> i != index })
        _audienceAreas.value=_audienceAreas.value.map { if (it.id == areaId) updated else it }
        _audienceGeometryError.value=null
        recalculate(); refreshHeatmap()
    }

    /** Geometry is parsed and validated in shared code before publishing a new zone. */
    fun addImportedAudienceMesh(mesh: AudienceMesh,name: String="Imported audience"): Int {
        val id=nextAreaId
        val area=AudienceArea(id=id,name=name.trim().ifEmpty { "Imported audience" },zoneType="AUDIENCE_SEATED",
            vertices=mesh.boundary().map { it.x.toFloat() to it.z.toFloat() },importedMesh=mesh)
        area.sharedSurface().validateGeometry()
        pushUndoCheckpoint(); nextAreaId++
        _audienceAreas.value=_audienceAreas.value+area
        _audienceGeometryError.value=null
        recalculate(); refreshHeatmap()
        return id
    }

    fun importAudienceObj(text: String,options: AudienceMeshImportOptions=AudienceMeshImportOptions()): Boolean {
        val parsed=runCatching { AudienceMeshObj.parse(text,options) }
        if (parsed.isFailure) { _audienceGeometryError.value=parsed.exceptionOrNull()?.message; return false }
        addImportedAudienceMesh(parsed.getOrThrow()); return true
    }

    fun closeAreaFromDraft() {
        val target=_cutoutTarget.value
        if (target != null) {
            if (addAudienceCutout(target.areaId,_areaDraft.value,target.hole)) {
                _areaDraft.value=emptyList(); _cutoutTarget.value=null
            }
            return
        }
        val poly = _areaDraft.value
        if (poly.size < 3) return
        if (polygonAreaAbs(poly) < AREA_MIN_POLYGON_AREA_M2) return
        if (polygonHasSelfIntersection(poly)) return
        pushUndoCheckpoint()

        val zoneType = _activeZoneType.value

        val areaId = nextAreaId++
        val area = AudienceArea(
            id = areaId,
            name = "ZONE ${areaId + 1}",
            zoneType = zoneType,
            baseHeightM = _activeZoneBaseHeightM.value,
            rakeDeg = _activeZoneRakeDeg.value,
            rakeDirectionDeg = _activeZoneRakeDirectionDeg.value,
            vertices = poly
        )
        _audienceAreas.value = _audienceAreas.value + area
        _areaDraft.value = emptyList()
        recalculate()
        refreshHeatmap()
    }

    fun removeAudienceArea(areaId: Int) {
        val current = _audienceAreas.value
        if (current.none { it.id == areaId }) return
        pushUndoCheckpoint()
        if (_elevationTarget.value == areaId) _elevationTarget.value=null
        if (_importedFaceSelection.value?.areaId==areaId) _importedFaceSelection.value=null
        if (_cutoutTarget.value?.areaId == areaId) { _cutoutTarget.value=null; _areaDraft.value=emptyList() }
        _audienceAreas.value = current
            .filter { it.id != areaId }
            .map { area -> if (area.linkedZoneId == areaId) area.copy(linkedZoneId = null) else area }
        _audience.value = _audience.value.filter { it.sourceAreaId != areaId }
        recalculate()
        refreshHeatmap()
    }

    fun setAudienceAreaName(areaId: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (_audienceAreas.value.none { it.id == areaId }) return
        pushUndoCheckpoint()
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.id != areaId) area else area.copy(name = trimmed)
        }
        recalculate()
        refreshHeatmap()
    }

    fun setAudienceAreaType(areaId: Int, zoneType: String) {
        if (!ZONE_TYPES.contains(zoneType)) return
        if (_audienceAreas.value.none { it.id == areaId }) return
        val linkedIds = _audienceAreas.value.filter { it.linkedZoneId == areaId }.map { it.id }
        pushUndoCheckpoint()
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.id != areaId) area else area.copy(zoneType = zoneType)
        }
        propagateLinkedAudienceAreasFrom(areaId)
        rebuildAudiencePointsForArea(areaId)
        linkedIds.forEach { linkedId -> rebuildAudiencePointsForArea(linkedId) }
    }

    fun setAudienceAreaBaseHeight(areaId: Int, heightM: Float) {
        val h = heightM.coerceIn(0f, 40f)
        if (_audienceAreas.value.none { it.id == areaId }) return
        pushUndoCheckpoint()
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.id != areaId) area else area.copy(baseHeightM = h)
        }
        propagateLinkedAudienceAreasFrom(areaId)
        applyAreaGeometryToAudiencePoints(areaId)
    }

    fun setAudienceAreaRake(areaId: Int, rakeDeg: Float) {
        val r = rakeDeg.coerceIn(-30f, 30f)
        if (_audienceAreas.value.none { it.id == areaId }) return
        pushUndoCheckpoint()
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.id != areaId) area else area.copy(rakeDeg = r)
        }
        propagateLinkedAudienceAreasFrom(areaId)
        applyAreaGeometryToAudiencePoints(areaId)
    }

    fun setAudienceAreaRakeDirection(areaId: Int, directionDeg: Float) {
        val d = ((directionDeg + 180f) % 360f + 360f) % 360f - 180f
        if (_audienceAreas.value.none { it.id == areaId }) return
        pushUndoCheckpoint()
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.id != areaId) area else area.copy(rakeDirectionDeg = d)
        }
        propagateLinkedAudienceAreasFrom(areaId)
        applyAreaGeometryToAudiencePoints(areaId)
    }

    fun setAudienceAreaRotation(areaId: Int, rotationDeg: Float) {
        val area = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
        if (area.vertices.size < 3) return
        val normalizedTarget = ((rotationDeg % 360f) + 360f) % 360f
        val delta = normalizedTarget - area.rotationDeg
        if (kotlin.math.abs(delta) < 0.0001f) return
        pushUndoCheckpoint()

        val cx = area.vertices.sumOf { it.first.toDouble() }.toFloat() / area.vertices.size
        val cz = area.vertices.sumOf { it.second.toDouble() }.toFloat() / area.vertices.size
        val rad = Math.toRadians(delta.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        fun rotate(p: Pair<Float,Float>): Pair<Float,Float> {
            val dx=p.first-cx; val dz=p.second-cz
            return (cx+dx*c-dz*s) to (cz+dx*s+dz*c)
        }
        _audienceAreas.value = _audienceAreas.value.map { a ->
            if (a.id != areaId) a else a.mapAudienceCoordinates(::rotate).copy(rotationDeg=normalizedTarget,
                rakeDirectionDeg=((a.rakeDirectionDeg-delta+180f)%360f+360f)%360f-180f)
        }
        rebuildAudiencePointsForArea(areaId)
    }

    fun setAudienceAreaEdgeLength(areaId: Int, edgeIndex: Int, lengthM: Float) {
        val area = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
        if (area.importedMesh!=null) { _audienceGeometryError.value="Edit imported triangle geometry in its source file"; return }
        val verts = area.vertices
        if (verts.size < 3) return
        if (edgeIndex !in verts.indices) return

        val clamped = lengthM.coerceIn(0.2f, 400f)
        val start = verts[edgeIndex]
        val endIndex = (edgeIndex + 1) % verts.size
        val end = verts[endIndex]
        val vx = end.first - start.first
        val vz = end.second - start.second
        val mag = sqrt(vx * vx + vz * vz)
        if (mag < 0.0001f) return

        val ux = vx / mag
        val uz = vz / mag
        val updated = verts.toMutableList()
        updated[endIndex] = (start.first + ux * clamped) to (start.second + uz * clamped)

        val candidate=area.copy(vertices=updated)
        val validation=runCatching { candidate.sharedSurface().validateGeometry() }
        if (validation.isFailure) { _audienceGeometryError.value=validation.exceptionOrNull()?.message; return }
        _audienceGeometryError.value=null
        pushUndoCheckpoint()
        _audienceAreas.value = _audienceAreas.value.map { a ->
            if (a.id != areaId) a else candidate
        }
        rebuildAudiencePointsForArea(areaId)
    }

    fun duplicateAudienceArea(areaId: Int) {
        val src = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
        pushUndoCheckpoint()
        val id = nextAreaId++
        val offset = 1.0f
        val clone = src.mapAudienceCoordinates { (x,z) -> (x+offset) to (z+offset) }.copy(
            id = id,
            name = "${src.name} Copy",
            linkedZoneId = null
        )
        _audienceAreas.value = _audienceAreas.value + clone
        rebuildAudiencePointsForArea(id)
    }

    fun mirrorAudienceAreaX(areaId: Int, referenceAreaId: Int?) {
        val src = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
        val refX = referenceAreaId
            ?.let { refId -> _audienceAreas.value.firstOrNull { it.id == refId } }
            ?.let { ref -> ref.vertices.map { it.first }.average().toFloat() }
            ?: 0f
        pushUndoCheckpoint()
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.id != areaId) area else area.mapAudienceCoordinates { (x,z) -> (2f*refX-x) to z }.copy(
                rakeDirectionDeg = ((-src.rakeDirectionDeg+180f)%360f+360f)%360f-180f,
                rotationDeg = ((360f - src.rotationDeg) % 360f + 360f) % 360f
            )
        }
        rebuildAudiencePointsForArea(areaId)
    }

    fun mirrorAudienceAreaZ(areaId: Int, referenceAreaId: Int?) {
        val src = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
        val refZ = referenceAreaId
            ?.let { refId -> _audienceAreas.value.firstOrNull { it.id == refId } }
            ?.let { ref -> ref.vertices.map { it.second }.average().toFloat() }
            ?: 0f
        pushUndoCheckpoint()
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.id != areaId) area else area.mapAudienceCoordinates { (x,z) -> x to (2f*refZ-z) }.copy(
                rakeDirectionDeg = ((360f-src.rakeDirectionDeg)%360f+360f)%360f-180f,
                rotationDeg = ((180f - src.rotationDeg) % 360f + 360f) % 360f
            )
        }
        rebuildAudiencePointsForArea(areaId)
    }

    fun setAudienceAreaLink(areaId: Int, linkedZoneId: Int?) {
        if (_audienceAreas.value.none { it.id == areaId }) return
        if (linkedZoneId != null && _audienceAreas.value.none { it.id == linkedZoneId }) return
        if (linkedZoneId == areaId) return
        pushUndoCheckpoint()
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.id != areaId) area else area.copy(linkedZoneId = linkedZoneId)
        }
        linkedZoneId?.let { sourceId ->
            syncLinkedAudienceArea(areaId, sourceId)
            applyAreaGeometryToAudiencePoints(areaId)
        }
    }

    fun createVenueBlockFromArea(areaId: Int, removeAreaAfter: Boolean) {
        val area = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
        if (area.vertices.size < 3) return

        pushUndoCheckpoint()

        val minX = area.vertices.minOf { it.first }
        val maxX = area.vertices.maxOf { it.first }
        val minZ = area.vertices.minOf { it.second }
        val maxZ = area.vertices.maxOf { it.second }

        val blockType = when (area.zoneType) {
            "AUDIENCE_SEATED", "AUDIENCE_STANDING" -> "SEATING_BANK"
            "STAGE" -> "STAGE"
            "WALL" -> "WALL"
            else -> "OBSTACLE"
        }

        val nextId = ((_venueGeometry.value.blocks.maxOfOrNull { it.id } ?: -1) + 1)
        val block = VenueBlock(
            id = nextId,
            type = blockType,
            centerX = (minX + maxX) * 0.5f,
            centerZ = (minZ + maxZ) * 0.5f,
            widthM = (maxX - minX).coerceAtLeast(0.2f),
            depthM = (maxZ - minZ).coerceAtLeast(0.2f),
            heightM = area.baseHeightM.coerceIn(0f, 40f),
            blockHeightM = 1f,
            slopeDeg = area.rakeDeg.coerceIn(-30f, 30f),
            rotationDeg = ((area.rakeDirectionDeg % 360f) + 360f) % 360f,
            label = "${area.name} Block"
        )

        _venueGeometry.value = _venueGeometry.value.copy(blocks = _venueGeometry.value.blocks + block)

        if (removeAreaAfter) {
            _audienceAreas.value = _audienceAreas.value.filter { it.id != areaId }
            _audience.value = _audience.value.map { p ->
                if (p.sourceAreaId == areaId) p.copy(sourceAreaId = null) else p
            }
        }

        recalculate()
        refreshHeatmap()
    }

    private fun audienceEarHeightForArea(x: Float, z: Float, area: AudienceArea): Float =
        area.sharedSurface().receiverY(x,z)

    private fun applyAreaGeometryToAudiencePoints(areaId: Int) {
        val area = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
        if (area.importedMesh!=null) { rebuildAudiencePointsForArea(areaId); return }
        _audience.value = _audience.value.map { p ->
            if (p.sourceAreaId != areaId) p else p.copy(earHeightM = audienceEarHeightForArea(p.x, p.z, area))
        }
        recalculate()
        refreshHeatmap()
    }

    private fun syncLinkedAudienceArea(targetAreaId: Int, sourceAreaId: Int) {
        val source = _audienceAreas.value.firstOrNull { it.id == sourceAreaId } ?: return
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.id != targetAreaId) area else area.copy(
                zoneType = source.zoneType,
                baseHeightM = source.baseHeightM,
                rakeDeg = source.rakeDeg,
                rakeDirectionDeg = source.rakeDirectionDeg
            )
        }
    }

    private fun propagateLinkedAudienceAreasFrom(sourceAreaId: Int) {
        val source = _audienceAreas.value.firstOrNull { it.id == sourceAreaId } ?: return
        var changedIds = emptyList<Int>()
        _audienceAreas.value = _audienceAreas.value.map { area ->
            if (area.linkedZoneId != sourceAreaId) return@map area
            changedIds = changedIds + area.id
            area.copy(
                zoneType = source.zoneType,
                baseHeightM = source.baseHeightM,
                rakeDeg = source.rakeDeg,
                rakeDirectionDeg = source.rakeDirectionDeg
            )
        }
        if (changedIds.isNotEmpty()) {
            changedIds.forEach { linkedId -> applyAreaGeometryToAudiencePoints(linkedId) }
        }
    }

    private fun rebuildAudiencePointsForArea(areaId: Int) {
        _audience.value = _audience.value.filter { it.sourceAreaId != areaId }
        recalculate()
        refreshHeatmap()
    }

    internal fun isLineOfSightBlocked(sx: Float, sy: Float, sz: Float, tx: Float, ty: Float, tz: Float): Boolean =
        BoundaryGeometry.prepare(_audienceAreas.value.map {it.sharedSurface()},venue=_venueGeometry.value)
            .blocked(BoundaryPoint(sx.toDouble(),sy.toDouble(),sz.toDouble()),BoundaryPoint(tx.toDouble(),ty.toDouble(),tz.toDouble()))

    // ─── GPU distance test (Phase 0 regression) ──────────────────────────────

    private fun snapToGrid(v: Float): Float {
        val s = AREA_GRID_SNAP_M
        return (round(v / s) * s)
    }

    private fun wouldSelfIntersectDraft(draft: List<Pair<Float, Float>>, candidate: Pair<Float, Float>): Boolean {
        if (draft.size < 2) return false
        val a = draft.last()
        val b = candidate
        for (i in 0 until draft.lastIndex - 1) {
            val c = draft[i]
            val d = draft[i + 1]
            if (segmentsIntersect(a, b, c, d)) return true
        }
        return false
    }

    private fun polygonAreaAbs(poly: List<Pair<Float, Float>>): Float {
        if (poly.size < 3) return 0f
        var twiceArea = 0f
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            twiceArea += a.first * b.second - b.first * a.second
        }
        return kotlin.math.abs(twiceArea) * 0.5f
    }

    private fun polygonHasSelfIntersection(poly: List<Pair<Float, Float>>): Boolean {
        if (poly.size < 4) return false
        for (i in poly.indices) {
            val a1 = poly[i]
            val a2 = poly[(i + 1) % poly.size]
            for (j in i + 1 until poly.size) {
                val b1 = poly[j]
                val b2 = poly[(j + 1) % poly.size]

                if (i == j) continue
                if ((i + 1) % poly.size == j) continue
                if (i == (j + 1) % poly.size) continue

                if (segmentsIntersect(a1, a2, b1, b2)) return true
            }
        }
        return false
    }

    private fun segmentsIntersect(
        p1: Pair<Float, Float>,
        p2: Pair<Float, Float>,
        q1: Pair<Float, Float>,
        q2: Pair<Float, Float>
    ): Boolean {
        fun orient(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>): Float {
            return (b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)
        }
        fun onSegment(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>): Boolean {
            return c.first >= minOf(a.first, b.first) - 1e-5f &&
                c.first <= maxOf(a.first, b.first) + 1e-5f &&
                c.second >= minOf(a.second, b.second) - 1e-5f &&
                c.second <= maxOf(a.second, b.second) + 1e-5f
        }

        val o1 = orient(p1, p2, q1)
        val o2 = orient(p1, p2, q2)
        val o3 = orient(q1, q2, p1)
        val o4 = orient(q1, q2, p2)

        if ((o1 > 0f && o2 < 0f || o1 < 0f && o2 > 0f) &&
            (o3 > 0f && o4 < 0f || o3 < 0f && o4 > 0f)
        ) return true

        if (kotlin.math.abs(o1) < 1e-5f && onSegment(p1, p2, q1)) return true
        if (kotlin.math.abs(o2) < 1e-5f && onSegment(p1, p2, q2)) return true
        if (kotlin.math.abs(o3) < 1e-5f && onSegment(q1, q2, p1)) return true
        if (kotlin.math.abs(o4) < 1e-5f && onSegment(q1, q2, p2)) return true

        return false
    }

    fun runGpuDistanceTest(onLog: (String) -> Unit, onDone: () -> Unit) {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.Default) {
                val speakers = floatArrayOf(
                    -5f, 6f, 0f,
                     5f, 6f, 0f,
                    -2f, 4f, 8f,
                     2f, 4f, 8f,
                )
                val gridPoints = buildList {
                    for (row in 0 until 10) for (col in 0 until 10) {
                        add(col - 4.5f); add(0f); add(row + 2f)
                    }
                }.toFloatArray()

                val distances = engine.computeDistances(speakers, gridPoints)
                buildList {
                    add("[GPU] Results (first row of grid):")
                    for (s in 0 until 4) {
                        add("  Grid[0] → Speaker[$s] = ${"%.2f".format(distances[s])} m")
                    }
                }
            }
            // Back on main thread — safe to update Compose state
            lines.forEach { onLog(it) }
            onDone()
        }
    }

    // ─── Private: SPL recalculation ───────────────────────────────────────────

    /**
     * Drops every derived analysis value. In-flight work is cancelled first so a
     * job that is already running cannot publish its results after the clear.
     */
    private fun clearAnalysisResults(state: PredictionState = PredictionState.EMPTY) {
        listenerValidity=null;coverageValidity=null;_predictionValidity.value=null
        autoRecomputeJob?.cancel()
        predictionGate.invalidate(state)
        _predictionState.value = state
        _currentTrace.value=null
        listenerResultTicket = null
        coverageResultTicket = null
        recalcJob?.cancel()
        heatmapJob?.cancel()
        _optimizerIsRunning.value = false
        _heatmap.value = emptyList()
        _coverageGeometry.value = null
        _coverageStatistics.value = null
        _results.value = emptyList()
        _combinedSplDb.value = null
        _earlyReflections.value = emptyList()
        _rt60Estimate.value = null
        _stiEstimate.value = null
        _highestSplDb.value = null
    }

    private fun recalculate(force: Boolean = false) {
        if(snapshotOnly) return
        val spks = _speakers.value.toList()
        if (spks.isEmpty() || !_signalSplEnabled.value) { clearAnalysisResults(); return }
        if (!force && !_signalAutoCalculate.value) return
        recalcJob?.cancel()
        val ticket = predictionGate.begin(PredictionChannel.LISTENER, _revision.value)
        val lis = _listener.value
        val selected = _selectedBandHz.value
        val air = Atmosphere(_temperatureC.value, _humidityPct.value)
        val request = capturePredictionRequest(ticket, spks,
            listOf(HeatCell(lis.x, lis.z, Float.NaN, renderY = lis.earHeightM)), true)
        val traceInputs=JSONObject(exportSceneJson(false,includeDesignLibrary=false)).apply {remove("savedTraces");remove("referenceTraceSelection");remove("receiverAngularOverlayEnabled");remove("receiverAngularSelection");remove("receiverCameraFocus")}.toString()
        val traceMetadata=TraceMetadata(BoundaryPoint(lis.x.toDouble(),lis.earHeightM.toDouble(),lis.z.toDouble()),
            if(_signalType.value=="SPECTRUM") 1f else _signalBandwidthOct.value,_temperatureC.value,_humidityPct.value,
            TraceComparisons.fingerprint(traceInputs),System.currentTimeMillis(),predictionDataWarnings().joinToString("\n"))
        refreshPredictionState()
        recalcJob = viewModelScope.launch {
            try {
                val output = withContext(predictionDispatcher) { predictionRunner.run(request) }
                if (output.ticket != ticket || !predictionGate.complete(ticket, _revision.value)) return@launch
                val results = spks.mapIndexed { i, spk ->
                    val d = sqrt((lis.x-spk.x).pow(2) + (lis.z-spk.z).pow(2) + (lis.earHeightM-spk.heightM).pow(2)).coerceAtLeast(0.1f)
                    SpeakerResult(spk, d, output.sourceLevelAt(0, i), (air.absorptionDbPerMetre(selected.toDouble()) * d).toFloat())
                }
                listenerResultTicket = ticket
                listenerValidity=output.validity
                val powers=output.bandPowersAt(0)
                _currentTrace.value=if(powers.isEmpty()) null else SavedTrace(0,"Current listener",traceMetadata,powers,output.phasesAt(0),delayReferenceS=output.delayReferenceAt(0))
                _results.value = results
                _combinedSplDb.value = output.levelAt(0).takeIf { it.isFinite() }
                // Direct/first-order predictions do not establish room decay or STI.
                _rt60Estimate.value = null
                _stiEstimate.value = null
                _highestSplDb.value = _heatmap.value.map { it.splDb }.maxOrNull()
                    ?: results.map { it.splDb }.filter { it.isFinite() }.maxOrNull()
                refreshPredictionState()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                android.util.Log.e("Prediction", "Calculation failed", failure)
                if (predictionGate.fail(ticket, _revision.value)) refreshPredictionState()
            }
        }
    }

    private fun refreshHeatmap(force: Boolean = false) {
        if(snapshotOnly) return
        if (_speakers.value.isEmpty() || !_signalSplEnabled.value) { clearAnalysisResults(); return }
        if (!_signalCoverageEnabled.value) {
            heatmapJob?.cancel()
            _heatmap.value = emptyList()
            _coverageGeometry.value = null
            _coverageStatistics.value = null
            coverageResultTicket = null
            coverageValidity=null
            refreshPredictionState()
            return
        }
        if (!force && !_signalAutoCalculate.value) return
        heatmapJob?.cancel()
        val ticket = predictionGate.begin(PredictionChannel.COVERAGE, _revision.value)
        val request = capturePredictionRequest(ticket, _speakers.value.toList(), emptyList(),
            coveragePlan = captureCoveragePlan())
        refreshPredictionState()
        heatmapJob = viewModelScope.launch {
            try {
                val output = withContext(predictionDispatcher) { predictionRunner.run(request) }
                if (output.ticket != ticket || !predictionGate.isLatest(ticket, _revision.value)) return@launch
                val geometry = output.geometry ?: error("Coverage result is missing its surface geometry")
                val cells = geometry.receivers().mapIndexed { i, point -> HeatCell(
                    point.x, point.z, output.levelAt(i), point.areaId, point.name, point.zoneType,
                    point.rakeDeg, point.rakeDirectionDeg, point.surfaceY, i, point.y, point.surfaceKey)
                }.filter { it.splDb.isFinite() }
                if (!predictionGate.complete(ticket, _revision.value)) return@launch
                coverageResultTicket = ticket
                coverageValidity=output.validity
                _coverageGeometry.value = geometry
                _coverageStatistics.value = output.statistics
                _heatmap.value = cells
                _highestSplDb.value = _heatmap.value.map { it.splDb }.maxOrNull()
                    ?: _results.value.map { it.splDb }.filter { it.isFinite() }.maxOrNull()
                refreshPredictionState()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                android.util.Log.e("Prediction", "Calculation failed", failure)
                if (predictionGate.fail(ticket, _revision.value)) refreshPredictionState()
            }
        }
    }

    internal fun captureCoveragePlan(): CoveragePlan {
        val areas = _audienceAreas.value.map { it.sharedSurface() }
        // Older projects may contain generated area points; the new mesh replaces those samples.
        val areaIds = areas.map { it.id }.toSet()
        val probes = _audience.value.filter { it.sourceAreaId !in areaIds }.map { p -> SurfaceReceiver(p.x, p.earHeightM, p.z, p.earHeightM,
            "probe:${p.id}", name = p.name) }
        val venue = _venueGeometry.value
        val step = PredictionModelScope.coverageGridSpacingM(_analysisProfile.value)
        return CoveragePlan(areas, probes, venue.widthM, venue.depthM, step)
    }

    private suspend fun computeDistancesSafe(
        speakerPositions: FloatArray,
        samplePoints: FloatArray
    ): FloatArray {
        if (_engineReady.value) {
            runCatching {
                return engine.computeDistances(speakerPositions, samplePoints)
            }
        }
        return computeDistancesCpuFallback(speakerPositions, samplePoints)
    }

    internal fun computeDistancesCpuFallback(
        speakerPositions: FloatArray,
        samplePoints: FloatArray
    ): FloatArray {
        val speakerCount = speakerPositions.size / 3
        val pointCount = samplePoints.size / 3
        val out = FloatArray(pointCount * speakerCount)
        for (pi in 0 until pointCount) {
            val px = samplePoints[pi * 3]
            val py = samplePoints[pi * 3 + 1]
            val pz = samplePoints[pi * 3 + 2]
            for (si in 0 until speakerCount) {
                val sx = speakerPositions[si * 3]
                val sy = speakerPositions[si * 3 + 1]
                val sz = speakerPositions[si * 3 + 2]
                val dx = px - sx
                val dy = py - sy
                val dz = pz - sz
                out[pi * speakerCount + si] = sqrt(dx * dx + dy * dy + dz * dz)
            }
        }
        return out
    }

    private fun predictionSources(spk: PlacedSpeaker, dsp: SpeakerDsp, air: Atmosphere,
        clf: ClfData? = clfDataFor(spk), extraGainDb: Float = 0f): List<SoundSource> {
        val synthetic = SyntheticRadiator(0f, SyntheticRadiator.horizontalHalfDeg(spk.modelPackageId)) {
            if (spk.arrayElements > 1) SyntheticRadiator.arrayElementVerticalHalfDeg(it)
            else SyntheticRadiator.pointSourceVerticalHalfDeg(spk.modelPackageId)
        }
        val radiator: Radiator = when {
            clf != null -> clf.measuredRadiator(_signalDispersionEnabled.value)
            requiresMeasuredData(spk) -> Radiator { _, _, _ -> Float.NaN }
            else -> Radiator { hz, az, el -> spk.sensitivity -
                if (_signalDispersionEnabled.value) synthetic.lossDb(hz, az, el) else 0f }
        }
        return ArrayGeometry.sources(spk.elementPoses(), spk.id, spk.x, spk.heightM, spk.z,
            spk.panDeg, spk.arraySteerDeg, air.speedOfSound, radiator,
            dsp.gainDb + extraGainDb, dsp.delayMs / 1000.0, dsp.polarity, spk.arrayEdgeTaperDb,
            { hz -> DiscreteBandEq.gainDb(dsp.eqBands,hz) }, dsp.filters)
    }

    /** Freeze every input before dispatch. Obstruction preparation retains its existing approximation. */
    internal fun capturePredictionRequest(ticket: PredictionTicket, speakers: List<PlacedSpeaker>,
        cells: List<HeatCell>, includeSourceLevels: Boolean = false,
        heights: List<Float> = cells.map { it.renderY },
        bands: List<Int> = analysisBands(), selected: Int = _selectedBandHz.value,
        dsps: Map<Int, SpeakerDsp> = _dspMap.value,
        coveragePlan: CoveragePlan? = null, retainBandPowers: Boolean = includeSourceLevels,
        forComparison: Boolean = false): PredictionRequest {
        val dispersion = _signalDispersionEnabled.value
        val responses = mutableMapOf<String, FrozenResponse>()
        val sources = speakers.map { spk ->
            val data = clfDataFor(spk)
            val response = when {
                data != null -> responses.getOrPut(spk.presetId) { FrozenResponse.measured(data, dispersion) }
                requiresMeasuredData(spk) -> FrozenResponse.missing()
                else -> FrozenResponse.generic(spk.sensitivity, spk.modelPackageId, spk.arrayElements > 1, dispersion)
            }
            val dsp = dsps[spk.id] ?: SpeakerDsp(spk.id)
            SourceSnapshot(spk.id, spk.x, spk.heightM, spk.z, spk.panDeg, spk.elementPoses(),
                spk.arraySteerDeg, spk.arrayEdgeTaperDb, response, dsp.gainDb + _signalLevelDbu.value,
                dsp.delayMs / 1000.0, dsp.polarity, dsp.eqBands, dsp.filters,
                if(!forComparison) null else if(data!=null) ComparisonSourceData(
                    projectAssets.capture(data).reference.hash!!,data.calibration,data.provenance.origin==SpeakerDataOrigin.GENERIC_ESTIMATE,dispersion)
                else if(requiresMeasuredData(spk)) ComparisonSourceData("missing:"+spk.presetId,null,false,dispersion)
                else ComparisonSourceData.generic(spk.presetId,spk.sensitivity,spk.modelPackageId,spk.arrayElements>1,dispersion))
        }
        val receivers = cells.mapIndexed { i, cell -> ReceiverSnapshot(cell.x, heights[i], cell.z) }
        val floor = if (_groundReflectionEnabled.value)
            Bands.THIRD_OCTAVE_HZ.associateWith { _roomMaterials.value.floor.alphaAt(it) } else null
        return PredictionRequest(ticket, sources, receivers, selected, bands,
            if (_signalType.value == "SPECTRUM") 1f else _signalBandwidthOct.value,
            WEIGHTING_DB[_weighting.value].orEmpty(), _signalInterferenceEnabled.value,
            Atmosphere(_temperatureC.value, _humidityPct.value), floor, includeSourceLevels, coveragePlan,
            _venueGeometry.value, geometryCache,
            if (_splScaleMode.value == SPL_SCALE_TARGET)
                CoverageTarget(_splTargetDb.value.toDouble()-3.0,_splTargetDb.value.toDouble()+3.0) else null,
            boundarySurfaces=_audienceAreas.value.map {it.sharedSurface()},retainBandPowers=retainBandPowers,groundPhase=_groundReflectionPhase.value,
            comparisonWeighting=if(forComparison) _weighting.value else null,
            comparisonSpectrum=if(forComparison) _comparisonSourceSpectrum.value else null,
            broadbandDrive=if(!forComparison && bands.size>1 && _signalType.value=="SPECTRUM") _broadbandDrive.value else null)
    }

    internal fun predictBandDb(speakers: List<PlacedSpeaker>, x: Float, y: Float, z: Float,
        band: Int, dspOverride: Map<Int, SpeakerDsp>? = null): Float = capturePredictionRequest(
            PredictionTicket(_revision.value, 0, PredictionChannel.LISTENER), speakers,
            listOf(HeatCell(x, z, Float.NaN, renderY = y)), bands = listOf(band), selected = band,
            dsps = dspOverride ?: _dspMap.value).calculate().levelAt(0)

    private fun clfDataFor(spk: PlacedSpeaker): ClfData? =
        _clfRegistry.value[spk.presetId]

    private fun validateAssetRecovery(data: ClfData) {
        if (_assetRecoveryProblems.value.any { it.speakerId == data.speakerId })
            ProjectArchive.requireRecoveryMatch(projectAssetReferences[data.speakerId], data)
    }

    private fun requiresMeasuredData(spk: PlacedSpeaker): Boolean =
        spk.requiresMeasuredData || _clfRegistry.value.containsKey(spk.presetId) ||
            _clfSourceStatus.value.containsKey(spk.presetId) ||
            _speakerPresets.value.any { it.id == spk.presetId && it.series == "CLF" }

    /** Presentation adapter; calibration and supported-band decisions stay in shared-core. */
    fun predictionDataWarnings(): List<String> = _assetRecoveryProblems.value.map {
        "${it.speakerId}: ${it.reason}. Reimport the exact speaker data to recover."
    } + _speakers.value.flatMap { spk ->
        val data = clfDataFor(spk)
        if (data == null) listOf(if (requiresMeasuredData(spk))
            "${spk.label}: measured speaker data unavailable. Reimport the model."
            else "${spk.label}: generic approximation; electrical reference unverified.")
        else buildList {
            addAll(SpeakerDataChecks.warnings(data).map { "${spk.label}: $it" })
            val radiator = data.measuredRadiator(_signalDispersionEnabled.value)
            val width = if (_signalType.value == "SPECTRUM") 1f else _signalBandwidthOct.value
            if(data.complexResponse!=null) add(if(radiator.hasComplexBands())
                "${spk.label}: measured complex band response (declared Cartesian interpolation). Requires complete frequency/direction data, compatible coherent drives and specular material phase. Mixed data requires Energy mode; compatible measured emitters interfere at scattering surfaces; outgoing diffuse sound remains energy-only, without a coherent phase trace."
                else "${spk.label}: exact-tone complex samples only; coverage uses magnitude data until band interpolation is explicitly declared.")
            val missing = analysisBands().flatMap { Bands.segments(it, width) }
                .filter {seg -> if(radiator.hasComplexBands()) listOf(seg.f1,seg.centreHz,seg.f2).any {radiator.complexBandAt(it,0f,0f)==null}
                    else !radiator.levelDb(seg.dataHz,0f,0f).isFinite()}.map {it.dataHz}.distinct()
            if (missing.isNotEmpty()) add("${spk.label}: missing or invalid response/directivity at ${missing.joinToString()} Hz. Combined prediction unavailable.")
        }
    }

    /**
     * Bands the current analysis covers: one, or the whole spectrum.
     *
     * "Spectrum" used to mean only that summation was treated as less coherent -
     * the level was still computed at the selected band alone, so it produced no
     * broadband figure at all despite the name. It now sums every band.
     */
    internal fun analysisBands(): List<Int> =
        if (_signalType.value == "SPECTRUM") SUPPORTED_BANDS_HZ else listOf(_selectedBandHz.value)

    /**
     * Vertical coverage edges for a box at the band being analysed.
     *
     * Measured data is walked outward from the axis until the response has
     * fallen 6 dB, up and down separately. Without measured data this reports
     * the angle the synthetic model itself would put the -6 dB point at, so the
     * overlay never draws a coverage the calculation does not believe in.
     *
     * For an array this describes one element. The array's own coverage is
     * narrower, because summation does the rest, but each box really does
     * radiate this and it is each box the rays are drawn from.
     */
    internal fun coverageEdgesFor(spk: PlacedSpeaker): CoverageEdges {
        clfDataFor(spk)?.let { clf ->
            val up = minusSixDeg(clf) { a -> 0f to a }
            val down = minusSixDeg(clf) { a -> 0f to -a }
            val right = minusSixDeg(clf) { a -> a to 0f }
            val left = minusSixDeg(clf) { a -> -a to 0f }
            if (up != null && down != null && left != null && right != null) {
                return CoverageEdges(up, down, left, right, measured = true)
            }
        }
        return CoverageEdges(
            upDeg = syntheticVerticalMinusSixDeg(spk),
            downDeg = syntheticVerticalMinusSixDeg(spk),
            leftDeg = syntheticHorizontalMinusSixDeg(spk),
            rightDeg = syntheticHorizontalMinusSixDeg(spk),
            measured = false
        )
    }

    /**
     * Angle off axis at which a measured balloon has fallen 6 dB.
     *
     * [direction] turns a distance from the axis into the azimuth and elevation
     * to sample, which is what lets the same walk find all four edges - up,
     * down, left and right - without four copies of it.
     *
     * Returns 90 for a pattern that never falls that far: an omnidirectional
     * box has no coverage edge, and pretending otherwise would draw a boundary
     * that is not there.
     */
    private fun minusSixDeg(clf: ClfData, direction: (Float) -> Pair<Float, Float>): Float? {
        val band = _selectedBandHz.value
        val onAxis = clf.splAtDirection(band, 0f, 0f) ?: return null
        var previousAngle = 0f
        var previousRel = 0f
        var angle = 0.5f
        while (angle <= 90f) {
            val (az, el) = direction(angle)
            val here = clf.splAtDirection(band, az, el) ?: return null
            val rel = here - onAxis
            if (rel <= -6f) {
                // Interpolate across the step that crossed the threshold.
                val span = previousRel - rel
                val t = if (span > 1e-6f) (previousRel + 6f) / span else 0f
                return previousAngle + (angle - previousAngle) * t.coerceIn(0f, 1f)
            }
            previousAngle = angle
            previousRel = rel
            angle += 0.5f
        }
        return 90f
    }

    /** Where the synthetic horizontal model puts its own -6 dB point. */
    private fun syntheticHorizontalMinusSixDeg(spk: PlacedSpeaker): Float =
        // Generic horizontal parabola: -6 dB at its half-angle.
        when (spk.modelPackageId) {
            "line_array" -> 45f
            "point_source" -> 60f
            else -> 50f
        }

    /** Where the synthetic vertical model puts its own -6 dB point, so the two agree. */
    private fun syntheticVerticalMinusSixDeg(spk: PlacedSpeaker): Float {
        if (spk.arrayElements > 1) {
            // Existing approximate viewport footprint; shared prediction sums physical elements.
            val freqK = (_selectedBandHz.value.toDouble() / 1000.0).coerceAtLeast(0.063)
            val halfPower = (65.0 / (sqrt(spk.arrayElements.toDouble()) * Math.pow(freqK, 0.35)))
                .coerceIn(4.0, 80.0)
            return (halfPower / sqrt(2.0)).toFloat()
        }
        // Generic point-source parabola: -6 dB at its vertical half-angle.
        return when (spk.modelPackageId) {
            "point_source" -> 35f
            "line_array" -> 28f
            else -> 40f
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Phase 9: ArrayCalc Optimizer public API
    // ─────────────────────────────────────────────────────────────────────

    /** Compatibility state only; enabling is gated until a shared optimizer is validated. */
    fun setOptimizerEnabled(enabled: Boolean) {
        if (enabled && !PredictionModelScope.automaticOptimizationAvailable) return
        _optimizerEnabled.value = enabled

    }

    /**
     * Set optimizer mode: MINIMIZE_VARIANCE or MAXIMIZE_COVERAGE
     */
    fun setOptimizerMode(mode: String) {
        if (mode != "MINIMIZE_VARIANCE" && mode != "MAXIMIZE_COVERAGE") return
        if (_optimizerMode.value == mode) return
        touch()
        _optimizerMode.value = mode
    }

    /** Unsupported until a validated shared-engine objective replaces the retired model. */
    fun runOptimization() = Unit

    /**
     * Clear/reset optimizer parameters and disable optimizer.
     */
    fun clearOptimizer() {
        pushUndoCheckpoint()
        _optimizerEnabled.value = false
        _optimizerParams.value = emptyList()
        _optimizerResult.value = null
        _optimizerIsRunning.value = false

        // Reset DSP map to zero gains/delays
        val resetDsp = _speakers.value.associate { it.id to SpeakerDsp(it.id) }
        _dspMap.value = resetDsp
        recalculate(force = true)
        refreshHeatmap(force = true)
    }}
