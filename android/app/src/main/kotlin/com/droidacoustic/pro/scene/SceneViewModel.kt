package com.droidacoustic.pro.scene

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidacoustic.pro.engine.AcousticEngine
import com.droidacoustic.pro.optimizer.ArrayCalcOptimizer
import com.droidacoustic.pro.optimizer.CoverageGridBuilder
import com.droidacoustic.pro.optimizer.CoveragePoint
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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// =============================================================================
// Domain types
// =============================================================================

data class PlacedSpeaker(
    val id         : Int,
    val x          : Float,           // world-space X (metres)
    val z          : Float,           // world-space Z (metres)
    val heightM    : Float = 1.2f,    // cabinet height above floor
    val sensitivity: Float = 100f,   // dBSPL @ 1W/1m
    val arrayElements: Int = 1,      // 1 = point source, >1 = vertical line-array
    val arraySpacingM: Float = 0.2f, // inter-element spacing (metres)
    val arrayInterBoxSplayDeg: Float = 0f, // uniform inter-box splay angle (deg)
    val arraySplayByBoxDeg: List<Float> = emptyList(), // per-joint splay profile (N-1 joints)
    val arrayAimDeg: Float = 0f,     // mechanical aim (down-positive)
    val panDeg     : Float = 0f,     // horizontal aiming / yaw
    val arraySteerDeg: Float = 0f,   // electronic steering angle (vertical plane)
    val arrayEdgeTaperDb: Float = 0f,// attenuation at top/bottom element edges
    val modelPackageId: String = "generic",
    val sourceId   : Int? = null,
    val label      : String = "SPK ${id + 1}"
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
    val renderY: Float = 0f
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
    val vertices : List<Pair<Float, Float>>
)

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
    val eqBands   : Map<Int, Float> = emptyMap()  // bandHz -> +/-6 dB offset
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
    val floorAlpha   : Float = 0.15f,   // carpet / sealed concrete
    val ceilingAlpha : Float = 0.55f,   // acoustic tile / open-truss
    val wallAlpha    : Float = 0.25f,   // gypsum / acoustic panels
    val roomHeightM  : Float = 8f       // ceiling height (metres)
)

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
    val blocks       : List<VenueBlock> = emptyList()
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
    val label        : String = "Block"
)

internal data class RoomBounds(
    val minX    : Float,
    val maxX    : Float,
    val minZ    : Float,
    val maxZ    : Float,
    val heightM : Float
)

private data class AcousticAnalysis(
    val speakerResults    : List<SpeakerResult>,
    val combinedSplDb     : Float,
    val roomBounds        : RoomBounds?,
    val rt60Estimate      : Rt60Estimate?
)

// =============================================================================
// ViewModel
// =============================================================================


class SceneViewModel : ViewModel() {

    companion object {
        const val SCENE_SCHEMA_VERSION = 7
        private const val MIN_SUPPORTED_SCENE_VERSION = 1
        private const val MAX_HISTORY_ENTRIES = 80
        private const val AREA_GRID_SNAP_M = 0.25f
        private const val AREA_ANGLE_SNAP_DEG = 15f
        private const val AREA_MIN_SEGMENT_M = 0.35f
        private const val AREA_MIN_POLYGON_AREA_M2 = 0.25f
        val SUPPORTED_BANDS_HZ = listOf(63, 125, 250, 500, 1000, 2000, 4000, 8000)
        val ANALYSIS_PROFILES = listOf("Fast", "Balanced", "Precision")

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
                val hi = cells.maxOfOrNull { it.splDb } ?: 100f
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

    private val _signalType = MutableStateFlow("BAND")
    val signalType: StateFlow<String> = _signalType.asStateFlow()

    private val _signalBandwidthOct = MutableStateFlow(1f / 3f)
    val signalBandwidthOct: StateFlow<Float> = _signalBandwidthOct.asStateFlow()

    private val _signalResolution = MutableStateFlow(24)
    val signalResolution: StateFlow<Int> = _signalResolution.asStateFlow()

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

    private val _earlyReflections = MutableStateFlow<List<EarlyReflection>>(emptyList())
    val earlyReflections: StateFlow<List<EarlyReflection>> = _earlyReflections.asStateFlow()

    private val _rt60Estimate = MutableStateFlow<Rt60Estimate?>(null)
    val rt60Estimate: StateFlow<Rt60Estimate?> = _rt60Estimate.asStateFlow()

    private val _stiEstimate = MutableStateFlow<StiEstimate?>(null)
    val stiEstimate: StateFlow<StiEstimate?> = _stiEstimate.asStateFlow()

    // CLF (Common Loudspeaker Format) polar pattern registry: maps speaker ID to parsed CLF data.
    // NOTE: This now starts empty and is populated by real-data ingestion paths (JSON imports or bundled assets).
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

    private var optimizerJob: Job? = null

    private var recalcJob: Job? = null
    private var heatmapJob: Job? = null
    private var earlyReflectionJob: Job? = null

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

    private fun pushUndoCheckpoint() {
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

            _clfRegistry.value = parsedRegistry
            _clfBinaryAssets.value = binaryMap.mapValues { it.value.toList() }
            _clfExternalAssets.value = externalMap.mapValues { it.value.toList() }

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
        val root = sources.firstOrNull { it.id == sourceId } ?: return setOf(sourceId)
        val set = linkedSetOf(sourceId)
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
        val cabinetWidthM = obj.optDouble("cabinetWidthM", 0.42).toFloat().coerceIn(0.1f, 6f)
        val cabinetDepthM = obj.optDouble("cabinetDepthM", 0.40).toFloat().coerceIn(0.1f, 6f)
        val cabinetHeightM = obj.optDouble("cabinetHeightM", 0.65).toFloat().coerceIn(0.1f, 6f)

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
    fun importClfText(text: String): Boolean {
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
            
            // Register CLF data in registry
            val newRegistry = _clfRegistry.value.toMutableMap()
            newRegistry[clfData.speakerId] = clfData
            _clfRegistry.value = newRegistry
            val status = _clfSourceStatus.value.toMutableMap()
            status[clfData.speakerId] = "JSON"
            _clfSourceStatus.value = status
            
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
        val newRegistry = _clfRegistry.value.toMutableMap()
        newRegistry.remove(speakerId)
        _clfRegistry.value = newRegistry
        val newStatus = _clfSourceStatus.value.toMutableMap()
        newStatus.remove(speakerId)
        _clfSourceStatus.value = newStatus
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
        pushUndoCheckpoint()
        _speakers.value = emptyList()
        _speakerSources.value = emptyList()
        _activeSpeakerSourceId.value = null
        _results.value  = emptyList()
        _heatmap.value  = emptyList()
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
        _signalInterferenceEnabled.value = true
        _signalAutoCalculate.value = false
        _signalSplEnabled.value = true
        _signalDispersionEnabled.value = true
        _signalCoverageEnabled.value = true
        _highestSplDb.value = null
    }

    /**
     * Export full scene + acoustics state to JSON.
     * Includes geometry, DSP, audience, atmosphere, and room material settings.
     */
    fun exportSceneJson(includeClfRegistry: Boolean = true): String {
        val root = JSONObject()
        root.put("version", SCENE_SCHEMA_VERSION)
        root.put("schema", "droidacoustic.scene")
        root.put("selectedPresetId", _selectedPresetId.value)
        root.put("activeZoneType", _activeZoneType.value)
        root.put("activeZoneBaseHeightM", _activeZoneBaseHeightM.value)
        root.put("activeZoneRakeDeg", _activeZoneRakeDeg.value)
        root.put("activeZoneRakeDirectionDeg", _activeZoneRakeDirectionDeg.value)
        root.put("selectedSpeakerModelPackageId", _selectedSpeakerModelPackageId.value)
        root.put("selectedBandHz", _selectedBandHz.value)
        root.put("signalLevelDbu", _signalLevelDbu.value)
        root.put("signalType", _signalType.value)
        root.put("signalBandwidthOct", _signalBandwidthOct.value)
        root.put("signalResolution", _signalResolution.value)
        root.put("signalInterferenceEnabled", _signalInterferenceEnabled.value)
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
                            )
                        }
                    }
                )
        )

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
                .put("floorAlpha", _roomMaterials.value.floorAlpha)
                .put("ceilingAlpha", _roomMaterials.value.ceilingAlpha)
                .put("wallAlpha", _roomMaterials.value.wallAlpha)
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

        if (includeClfRegistry) {
            root.put(
                "clfRegistry",
                JSONObject().apply {
                    _clfRegistry.value.forEach { (speakerId, clfData) ->
                        put(speakerId, JSONObject(ClfParser.toJson(clfData)))
                    }
                }
            )
        }

        return root.toString()
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
        root.put("version", 1)
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
                            )
                        }
                    }
                )
        )
        root.put(
            "roomMaterials",
            JSONObject()
                .put("floorAlpha", _roomMaterials.value.floorAlpha)
                .put("ceilingAlpha", _roomMaterials.value.ceilingAlpha)
                .put("wallAlpha", _roomMaterials.value.wallAlpha)
                .put("roomHeightM", _roomMaterials.value.roomHeightM)
        )
        return root.toString()
    }

    /** Phase 2 — import venue-only package without replacing full scene. */
    fun importVenueJson(json: String): Boolean {
        return runCatching {
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
                        label = o.optString("label", "$type ${i + 1}")
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
                blocks = loadedBlocks
            )
            root.optJSONObject("roomMaterials")?.let { rm ->
                _roomMaterials.value = RoomMaterials(
                    floorAlpha = rm.optDouble("floorAlpha", _roomMaterials.value.floorAlpha.toDouble()).toFloat().coerceIn(0.01f, 1f),
                    ceilingAlpha = rm.optDouble("ceilingAlpha", _roomMaterials.value.ceilingAlpha.toDouble()).toFloat().coerceIn(0.01f, 1f),
                    wallAlpha = rm.optDouble("wallAlpha", _roomMaterials.value.wallAlpha.toDouble()).toFloat().coerceIn(0.01f, 1f),
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
        root.put("version", 1)
        root.put("speakerCount", _speakers.value.size)
        root.put("audiencePointCount", _audience.value.size)
        root.put("audienceAreaCount", _audienceAreas.value.size)
        _combinedSplDb.value?.let { root.put("combinedSplDb", it) }
        _rt60Estimate.value?.let {
            root.put("rt60S", it.rt60S)
            root.put("roomVolumeM3", it.volumeM3)
        }
        _stiEstimate.value?.let {
            root.put("sti", it.sti)
            root.put("stiQuality", it.quality)
            root.put("alconsPct", it.alconsPct)
        }
        root.put("venue", JSONObject(exportVenueJson()).optJSONObject("venueGeometry"))
        root.put("selectedBandHz", _selectedBandHz.value)
        root.put("signalLevelDbu", _signalLevelDbu.value)
        root.put("signalType", _signalType.value)
        root.put("signalBandwidthOct", _signalBandwidthOct.value)
        root.put("signalResolution", _signalResolution.value)
        root.put("signalInterferenceEnabled", _signalInterferenceEnabled.value)
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
        return runCatching {
            val root = JSONObject(json)
            val incomingVersion = if (root.has("version")) root.optInt("version", -1) else 1
            if (incomingVersion < MIN_SUPPORTED_SCENE_VERSION) {
                throw IllegalArgumentException("Snapshot version $incomingVersion is too old")
            }
            if (incomingVersion > SCENE_SCHEMA_VERSION) {
                throw IllegalArgumentException("Snapshot version $incomingVersion is newer than supported v$SCENE_SCHEMA_VERSION")
            }

            if (recordHistory) pushUndoCheckpoint()

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
            _signalBandwidthOct.value = root.optDouble("signalBandwidthOct", _signalBandwidthOct.value.toDouble()).toFloat().coerceIn(1f / 12f, 1f)
            _signalResolution.value = root.optInt("signalResolution", _signalResolution.value).coerceIn(3, 96)
            _signalInterferenceEnabled.value = root.optBoolean("signalInterferenceEnabled", _signalInterferenceEnabled.value)
            _signalAutoCalculate.value = root.optBoolean("signalAutoCalculate", _signalAutoCalculate.value)
            setSplScaleMode(root.optString("splScaleMode", _splScaleMode.value))
            setSplTargetDb(root.optDouble("splTargetDb", _splTargetDb.value.toDouble()).toFloat())
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
                            label = o.optString("label", "$type ${i + 1}")
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
                    blocks = loadedBlocks
                )
                _roomMaterials.value = _roomMaterials.value.copy(roomHeightM = wallH)
            }

            root.optJSONObject("listener")?.let { l ->
                _listener.value = ListenerPos(
                    x = l.optDouble("x", 0.0).toFloat(),
                    z = l.optDouble("z", 0.0).toFloat(),
                    earHeightM = l.optDouble("earHeightM", 1.2).toFloat().coerceIn(0.5f, 2.5f)
                )
            }

            root.optJSONObject("roomMaterials")?.let { rm ->
                _roomMaterials.value = RoomMaterials(
                    floorAlpha = rm.optDouble("floorAlpha", 0.15).toFloat().coerceIn(0.01f, 1f),
                    ceilingAlpha = rm.optDouble("ceilingAlpha", 0.55).toFloat().coerceIn(0.01f, 1f),
                    wallAlpha = rm.optDouble("wallAlpha", 0.25).toFloat().coerceIn(0.01f, 1f),
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
                        eqBands = eq
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
                        earHeightM = o.optDouble("earHeightM", 1.2).toFloat().coerceIn(0.5f, 30f),
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
                    loadedAreas += AudienceArea(
                        id = o.optInt("id", i),
                        name = o.optString("name", "AREA ${i + 1}"),
                        zoneType = o.optString("zoneType", "AUDIENCE_SEATED").let { if (ZONE_TYPES.contains(it)) it else "AUDIENCE_SEATED" },
                        baseHeightM = o.optDouble("baseHeightM", 0.0).toFloat().coerceIn(0f, 40f),
                        rakeDeg = o.optDouble("rakeDeg", 0.0).toFloat().coerceIn(-30f, 30f),
                        rakeDirectionDeg = o.optDouble("rakeDirectionDeg", 0.0).toFloat().let { ((it + 180f) % 360f + 360f) % 360f - 180f },
                        rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat().let { ((it % 360f) + 360f) % 360f },
                        linkedZoneId = if (o.has("linkedZoneId")) o.optInt("linkedZoneId", -1).takeIf { it >= 0 } else null,
                        vertices = verts
                    )
                }
            }
            _audienceAreas.value = loadedAreas
            _areaDraft.value = emptyList()

            nextId = (_speakers.value.maxOfOrNull { it.id } ?: -1) + 1
            nextAudienceId = (_audience.value.maxOfOrNull { it.id } ?: -1) + 1
            nextAreaId = (_audienceAreas.value.maxOfOrNull { it.id } ?: -1) + 1

            // Import CLF registry
            root.optJSONObject("clfRegistry")?.let { clfObj ->
                val importedClf = mutableMapOf<String, ClfData>()
                clfObj.keys().forEach { speakerId ->
                    try {
                        val clfJson = clfObj.getJSONObject(speakerId).toString()
                        val clfData = ClfParser.parseJson(clfJson)
                        if (clfData != null) {
                            importedClf[speakerId] = clfData
                        }
                    } catch (e: Exception) {
                        // Skip invalid CLF data
                    }
                }
                if (importedClf.isNotEmpty()) {
                    _clfRegistry.value = importedClf
                }
            }

            recalculate(force = true)
            refreshHeatmap(force = true)
            _lastImportError.value = null
        }.onFailure {
            _lastImportError.value = it.message ?: "Invalid scene JSON"
        }.isSuccess
    }

    fun moveListener(x: Float, z: Float) {
        pushUndoCheckpoint()
        _listener.value = ListenerPos(x, z)
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

    fun setSplTargetDb(db: Float) {
        val v = db.coerceIn(40f, 140f)
        if (_splTargetDb.value == v) return
        pushUndoCheckpoint()
        _splTargetDb.value = v
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
        _roomMaterials.value = _roomMaterials.value.copy(floorAlpha = v.coerceIn(0.01f, 1f))
        recalculate(); refreshHeatmap()
    }

    fun setCeilingAbsorption(v: Float) {
        pushUndoCheckpoint()
        _roomMaterials.value = _roomMaterials.value.copy(ceilingAlpha = v.coerceIn(0.01f, 1f))
        recalculate(); refreshHeatmap()
    }

    fun setWallAbsorption(v: Float) {
        pushUndoCheckpoint()
        _roomMaterials.value = _roomMaterials.value.copy(wallAlpha = v.coerceIn(0.01f, 1f))
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
    }

    fun closeAreaFromDraft() {
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

        val rotated = area.vertices.map { (x, z) ->
            val dx = x - cx
            val dz = z - cz
            (cx + (dx * c - dz * s)) to (cz + (dx * s + dz * c))
        }

        _audienceAreas.value = _audienceAreas.value.map { a ->
            if (a.id != areaId) a else a.copy(
                vertices = rotated,
                rotationDeg = normalizedTarget
            )
        }
        rebuildAudiencePointsForArea(areaId)
    }

    fun setAudienceAreaEdgeLength(areaId: Int, edgeIndex: Int, lengthM: Float) {
        val area = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
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

        pushUndoCheckpoint()
        val ux = vx / mag
        val uz = vz / mag
        val updated = verts.toMutableList()
        updated[endIndex] = (start.first + ux * clamped) to (start.second + uz * clamped)

        _audienceAreas.value = _audienceAreas.value.map { a ->
            if (a.id != areaId) a else a.copy(vertices = updated)
        }
        rebuildAudiencePointsForArea(areaId)
    }

    fun duplicateAudienceArea(areaId: Int) {
        val src = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
        pushUndoCheckpoint()
        val id = nextAreaId++
        val offset = 1.0f
        val clone = src.copy(
            id = id,
            name = "${src.name} Copy",
            linkedZoneId = null,
            vertices = src.vertices.map { (x, z) -> (x + offset) to (z + offset) }
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
            if (area.id != areaId) area else area.copy(
                vertices = src.vertices.map { (x, z) -> (2f * refX - x) to z },
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
            if (area.id != areaId) area else area.copy(
                vertices = src.vertices.map { (x, z) -> x to (2f * refZ - z) },
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

    private fun audienceHeadOffsetForZone(zoneType: String): Float {
        return when (zoneType) {
            "AUDIENCE_STANDING" -> 1.6f
            else -> 1.2f
        }
    }

    private fun audienceEarHeightForArea(x: Float, z: Float, area: AudienceArea): Float {
        if (area.vertices.isEmpty()) return audienceHeadOffsetForZone(area.zoneType)
        val cx = area.vertices.sumOf { it.first.toDouble() }.toFloat() / area.vertices.size
        val cz = area.vertices.sumOf { it.second.toDouble() }.toFloat() / area.vertices.size
        val rakeRad = Math.toRadians(area.rakeDeg.toDouble())
        val dirRad = Math.toRadians(area.rakeDirectionDeg.toDouble())
        val dx = kotlin.math.sin(dirRad).toFloat()
        val dz = kotlin.math.cos(dirRad).toFloat()
        val along = (x - cx) * dx + (z - cz) * dz
        val planeY = area.baseHeightM + tan(rakeRad).toFloat() * along
        return (planeY + audienceHeadOffsetForZone(area.zoneType)).coerceIn(0.5f, 30f)
    }

    private fun applyAreaGeometryToAudiencePoints(areaId: Int) {
        val area = _audienceAreas.value.firstOrNull { it.id == areaId } ?: return
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

    private fun stageSurfaceYAt(x: Float, z: Float): Float? {
        val vg = _venueGeometry.value
        val halfW = vg.stageWidthM * 0.5f
        val halfD = vg.stageDepthM * 0.5f
        val lx = x - vg.stageCenterX
        val lz = z - vg.stageCenterZ
        if (kotlin.math.abs(lx) > halfW || kotlin.math.abs(lz) > halfD) return null
        val slope = tan(Math.toRadians(vg.stageSlopeDeg.toDouble())).toFloat()
        return (vg.stageHeightM + slope * lz).coerceAtLeast(0f)
    }

    private fun blockSurfaceYAt(x: Float, z: Float): Float? {
        var best: Float? = null
        _venueGeometry.value.blocks.forEach { b ->
            val theta = Math.toRadians((-b.rotationDeg).toDouble())
            val c = cos(theta).toFloat()
            val s = sin(theta).toFloat()
            val dx = x - b.centerX
            val dz = z - b.centerZ
            val lx = dx * c - dz * s
            val lz = dx * s + dz * c

            if (kotlin.math.abs(lx) <= b.widthM * 0.5f && kotlin.math.abs(lz) <= b.depthM * 0.5f) {
                val slope = tan(Math.toRadians(b.slopeDeg.toDouble())).toFloat()
                val y = (b.heightM + slope * lz).coerceAtLeast(0f)
                best = if (best == null) y else max(best!!, y)
            }
        }
        return best
    }

    private fun isLineOfSightBlocked(
        sx: Float,
        sy: Float,
        sz: Float,
        tx: Float,
        ty: Float,
        tz: Float
    ): Boolean {
        return estimateObstructionAttenuationDb(sx, sy, sz, tx, ty, tz) >= 8f
    }

    private data class PathObstructionStats(
        val blockedSamples: Int,
        val sampleCount: Int,
        val maxPenetrationM: Float,
        val minClearanceToTopM: Float
    ) {
        val blockedFraction: Float
            get() = if (sampleCount <= 0) 0f else blockedSamples.toFloat() / sampleCount.toFloat()
    }

    private data class VerticalProfile(
        val inside: Boolean,
        val penetrationToTopM: Float,
        val clearanceToTopM: Float
    )

    private fun estimateObstructionAttenuationDb(
        sx: Float,
        sy: Float,
        sz: Float,
        tx: Float,
        ty: Float,
        tz: Float
    ): Float {
        val stats = collectPathObstructionStats(sx, sy, sz, tx, ty, tz)
        val distanceM = sqrt((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy) + (tz - sz) * (tz - sz)).coerceAtLeast(0.1f)
        val freqScale = (_selectedBandHz.value.toFloat() / 1000f).coerceAtLeast(0.063f)
        val hfWeight = kotlin.math.sqrt(freqScale).coerceIn(0.25f, 2.2f)

        // No hard block: keep a small grazing-edge diffraction shadow near top edges.
        if (stats.blockedSamples == 0) {
            val grazing = (0.45f - stats.minClearanceToTopM).coerceIn(0f, 0.45f) / 0.45f
            val grazeLoss = 4.5f * grazing * hfWeight
            return grazeLoss.coerceIn(0f, 8f)
        }

        // Hard block: attenuation increases with blocked fraction and penetration depth.
        val blockedTerm = (6f + 24f * stats.blockedFraction).coerceAtLeast(0f)
        val penetrationTerm = (stats.maxPenetrationM / 0.55f).coerceIn(0f, 2f) * 10f
        val distanceTerm = (1f + 0.12f * log10((distanceM + 1f).toDouble()).toFloat()).coerceIn(0.9f, 1.5f)
        var loss = (blockedTerm + penetrationTerm) * (0.58f + 0.52f * hfWeight) * distanceTerm

        // Low frequencies bend around obstacles more (diffraction recovery).
        val lowFreqRecovery = ((1000f / _selectedBandHz.value.toFloat()).coerceAtLeast(1f) - 1f).coerceIn(0f, 1.8f) * 6f
        // Near-edge paths also recover more than deep-shadow paths.
        val shallowShadowRecovery = (1f - (stats.maxPenetrationM / 0.25f).coerceIn(0f, 1f)) * 6f
        val edgeRayRecovery = estimateEdgeDiffractionRecoveryDb(
            sx = sx,
            sy = sy,
            sz = sz,
            tx = tx,
            ty = ty,
            tz = tz,
            stats = stats
        )
        loss -= (lowFreqRecovery + shallowShadowRecovery + edgeRayRecovery)

        return loss.coerceIn(0f, 55f)
    }

    private fun estimateEdgeDiffractionRecoveryDb(
        sx: Float,
        sy: Float,
        sz: Float,
        tx: Float,
        ty: Float,
        tz: Float,
        stats: PathObstructionStats
    ): Float {
        if (stats.blockedSamples <= 0) return 0f

        val steps = when (_analysisProfile.value) {
            "Fast" -> 20
            "Precision" -> 56
            else -> 36
        }

        val edgeCandidates = mutableListOf<Triple<Float, Float, Float>>()
        var prevInside = false
        for (i in 1 until steps) {
            val t = i.toFloat() / steps.toFloat()
            val px = sx + (tx - sx) * t
            val py = sy + (ty - sy) * t
            val pz = sz + (tz - sz) * t
            val inside = verticalObstructionProfile(px, py, pz).inside
            if (inside != prevInside) {
                val topY = obstructionTopSurfaceYAt(px, pz) ?: py
                edgeCandidates += Triple(px, topY, pz)
            }
            prevInside = inside
        }
        if (edgeCandidates.isEmpty()) return 0f

        val directDist = distance3d(sx, sy, sz, tx, ty, tz).coerceAtLeast(0.1f)
        val freqK = (_selectedBandHz.value.toFloat() / 1000f).coerceAtLeast(0.063f)

        var bestRecovery = 0f
        edgeCandidates.forEach { (ex, ey, ez) ->
            val pathDist = distance3d(sx, sy, sz, ex, ey, ez) + distance3d(ex, ey, ez, tx, ty, tz)
            val excess = (pathDist - directDist).coerceAtLeast(0f)
            val edgeOffset = pointToSegmentDistance3d(ex, ey, ez, sx, sy, sz, tx, ty, tz)

            // Approximate knife-edge behavior:
            // - stronger recovery at LF
            // - weaker with larger excess path and larger edge offset
            // - weaker when deep in shadow (large blocked fraction)
            val lfBoost = (1.2f / kotlin.math.sqrt(freqK)).coerceIn(0.6f, 2.2f)
            val shadowPenalty = stats.blockedFraction.coerceIn(0f, 1f) * 4f
            val candidateRecovery =
                15f * lfBoost -
                    (22f * excess) -
                    (2.8f * edgeOffset) -
                    shadowPenalty

            bestRecovery = max(bestRecovery, candidateRecovery)
        }

        return bestRecovery.coerceIn(0f, 14f)
    }

    private fun obstructionTopSurfaceYAt(x: Float, z: Float): Float? {
        var best: Float? = null
        stageVerticalBoundsAt(x, z)?.let { (_, top) ->
            best = top
        }
        _venueGeometry.value.blocks.forEach { b ->
            blockVerticalBoundsAt(b, x, z)?.let { (_, top) ->
                best = if (best == null) top else max(best!!, top)
            }
        }
        return best
    }

    private fun distance3d(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun pointToSegmentDistance3d(
        px: Float,
        py: Float,
        pz: Float,
        ax: Float,
        ay: Float,
        az: Float,
        bx: Float,
        by: Float,
        bz: Float
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val abz = bz - az
        val apx = px - ax
        val apy = py - ay
        val apz = pz - az
        val ab2 = (abx * abx + aby * aby + abz * abz).coerceAtLeast(1e-6f)
        val t = ((apx * abx + apy * aby + apz * abz) / ab2).coerceIn(0f, 1f)
        val cx = ax + abx * t
        val cy = ay + aby * t
        val cz = az + abz * t
        return distance3d(px, py, pz, cx, cy, cz)
    }

    private fun collectPathObstructionStats(
        sx: Float,
        sy: Float,
        sz: Float,
        tx: Float,
        ty: Float,
        tz: Float
    ): PathObstructionStats {
        val steps = when (_analysisProfile.value) {
            "Fast" -> 24
            "Precision" -> 72
            else -> 48
        }
        val dx = tx - sx
        val dy = ty - sy
        val dz = tz - sz
        var blockedCount = 0
        var maxPenetration = 0f
        var minClearance = Float.POSITIVE_INFINITY

        for (i in 1 until steps) {
            val t = i.toFloat() / steps.toFloat()
            val px = sx + dx * t
            val py = sy + dy * t
            val pz = sz + dz * t
            val sample = verticalObstructionProfile(px, py, pz)
            minClearance = minOf(minClearance, sample.clearanceToTopM)
            if (sample.inside) {
                blockedCount += 1
                maxPenetration = max(maxPenetration, sample.penetrationToTopM)
            }
        }

        return PathObstructionStats(
            blockedSamples = blockedCount,
            sampleCount = (steps - 1).coerceAtLeast(1),
            maxPenetrationM = maxPenetration,
            minClearanceToTopM = if (minClearance.isFinite()) minClearance else Float.POSITIVE_INFINITY
        )
    }

    private fun verticalObstructionProfile(x: Float, y: Float, z: Float): VerticalProfile {
        var inside = false
        var maxPenetration = 0f
        var minClearance = Float.POSITIVE_INFINITY

        val stage = stageVerticalBoundsAt(x, z)
        if (stage != null) {
            val (bottom, top) = stage
            minClearance = minOf(minClearance, y - top)
            if (y >= bottom && y <= top) {
                inside = true
                maxPenetration = max(maxPenetration, top - y)
            }
        }

        _venueGeometry.value.blocks.forEach { b ->
            val bounds = blockVerticalBoundsAt(b, x, z) ?: return@forEach
            val (bottom, top) = bounds
            minClearance = minOf(minClearance, y - top)
            if (y >= bottom && y <= top) {
                inside = true
                maxPenetration = max(maxPenetration, top - y)
            }
        }

        return VerticalProfile(
            inside = inside,
            penetrationToTopM = maxPenetration,
            clearanceToTopM = if (minClearance.isFinite()) minClearance else Float.POSITIVE_INFINITY
        )
    }

    private fun stageVerticalBoundsAt(x: Float, z: Float): Pair<Float, Float>? {
        val vg = _venueGeometry.value
        val lx = x - vg.stageCenterX
        val lz = z - vg.stageCenterZ
        val halfW = vg.stageWidthM * 0.5f
        val halfD = vg.stageDepthM * 0.5f
        if (kotlin.math.abs(lx) > halfW || kotlin.math.abs(lz) > halfD) return null
        val slope = tan(Math.toRadians(vg.stageSlopeDeg.toDouble())).toFloat()
        val topY = (vg.stageHeightM + slope * lz).coerceAtLeast(0f)
        return 0f to topY
    }

    private fun blockVerticalBoundsAt(block: VenueBlock, x: Float, z: Float): Pair<Float, Float>? {
        val theta = Math.toRadians((-block.rotationDeg).toDouble())
        val c = cos(theta).toFloat()
        val s = sin(theta).toFloat()
        val dx = x - block.centerX
        val dz = z - block.centerZ
        val lx = dx * c - dz * s
        val lz = dx * s + dz * c

        if (kotlin.math.abs(lx) > block.widthM * 0.5f || kotlin.math.abs(lz) > block.depthM * 0.5f) return null

        val slope = tan(Math.toRadians(block.slopeDeg.toDouble())).toFloat()
        val topY = block.heightM + slope * lz
        val bottomY = topY - block.blockHeightM
        return bottomY to topY
    }

    private fun isInsideStageVolume(x: Float, y: Float, z: Float): Boolean {
        val bounds = stageVerticalBoundsAt(x, z) ?: return false
        return y >= bounds.first && y <= bounds.second
    }

    private fun isInsideAnyBlockVolume(x: Float, y: Float, z: Float): Boolean {
        return _venueGeometry.value.blocks.any { b ->
            val bounds = blockVerticalBoundsAt(b, x, z) ?: return@any false
            y >= bounds.first && y <= bounds.second
        }
    }

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
    private fun clearAnalysisResults() {
        recalcJob?.cancel()
        earlyReflectionJob?.cancel()
        _results.value = emptyList()
        _combinedSplDb.value = null
        _earlyReflections.value = emptyList()
        _rt60Estimate.value = null
        _stiEstimate.value = null
        _highestSplDb.value = null
    }

    private fun recalculate(force: Boolean = false) {
        // An empty scene is invalidated ahead of the auto-calculate gate. That gate
        // defers *recomputation* until the user asks for it, but numbers describing
        // sources that no longer exist are never worth keeping: removing the last
        // speaker used to leave a full set of results sitting on screen.
        val spks = _speakers.value
        if (spks.isEmpty()) {
            clearAnalysisResults()
            return
        }
        if (!force && !_signalAutoCalculate.value) return
        if (!_signalSplEnabled.value) {
            clearAnalysisResults()
            return
        }

        recalcJob?.cancel()
        recalcJob = viewModelScope.launch {
            val analysis = withContext(Dispatchers.Default) {
                val positions = FloatArray(spks.size * 3) { i ->
                    val spk = spks[i / 3]
                    when (i % 3) { 0 -> spk.x; 1 -> spk.heightM; else -> spk.z }
                }
                val lis    = _listener.value
                val lisArr = floatArrayOf(lis.x, lis.earHeightM, lis.z)
                val dists  = computeDistancesSafe(positions, lisArr)
                val results = spks.mapIndexed { i, spk ->
                    val d   = dists[i].coerceAtLeast(0.1f)
                    val dsp    = _dspMap.value[spk.id] ?: SpeakerDsp(spk.id)
                    val occlusionPenalty = estimateObstructionAttenuationDb(
                        sx = spk.x,
                        sy = spk.heightM,
                        sz = spk.z,
                        tx = lis.x,
                        ty = lis.earHeightM,
                        tz = lis.z
                    )
                    // Try CLF-based directivity first; fall back to simplified model if no CLF
                    val clfPenalty = clfDirectivityAttenuationDb(
                        speakerId = spk.id.toString(),
                        fromX = spk.x,
                        fromY = spk.heightM,
                        fromZ = spk.z,
                        toX = lis.x,
                        toY = lis.earHeightM,
                        toZ = lis.z,
                        spk = spk
                    )
                    val directivityPenalty = clfPenalty ?: horizontalDirectivityAttenuationDb(
                        spk = spk,
                        fromX = spk.x,
                        fromZ = spk.z,
                        toX = lis.x,
                        toZ = lis.z,
                        pathOrder = 0
                    )
                    val verticalAimPenalty = if (clfPenalty != null) 0f else verticalAimAttenuationDb(
                        spk = spk,
                        fromX = spk.x,
                        fromY = spk.heightM,
                        fromZ = spk.z,
                        toX = lis.x,
                        toY = lis.earHeightM,
                        toZ = lis.z,
                        pathOrder = 0
                    )
                    val boundaryDelta = floorBoundaryInterferenceDb(
                        srcX = spk.x,
                        srcY = spk.heightM,
                        srcZ = spk.z,
                        dstX = lis.x,
                        dstY = lis.earHeightM,
                        dstZ = lis.z
                    )
                    val beamShadowPenalty = if (_signalDispersionEnabled.value && (directivityPenalty + verticalAimPenalty) > 14f) 12f else 0f
                    val spl    = directSplDb(spk, dsp, d) - occlusionPenalty - directivityPenalty - verticalAimPenalty - beamShadowPenalty + boundaryDelta
                    val air    = atmosphericLossDb(
                        distanceM = d,
                        bandHz = _selectedBandHz.value,
                        temperatureC = _temperatureC.value,
                        humidityPct = _humidityPct.value
                    )
                    SpeakerResult(spk, d, spl, air)
                }
                val coherent = coherentSumDb(
                    results.map { result ->
                        val dsp = _dspMap.value[result.speaker.id] ?: SpeakerDsp(result.speaker.id)
                        CoherentContribution(
                            splDb = result.splDb,
                            distanceM = result.distanceM,
                            delayMs = dsp.delayMs,
                            polarity = dsp.polarity
                        )
                    }
                )
                val room = estimateRoomBounds(spks, _listener.value, _audience.value, _audienceAreas.value)
                AcousticAnalysis(
                    speakerResults = results,
                    combinedSplDb = coherent,
                    roomBounds = room,
                    rt60Estimate = room?.let { estimateRt60(it, _roomMaterials.value) }
                )
            }
            _results.value = analysis.speakerResults
            _combinedSplDb.value = analysis.combinedSplDb
            _rt60Estimate.value = analysis.rt60Estimate
            recomputeStiEstimate()
            _highestSplDb.value = _results.value.maxOfOrNull { it.splDb }

            val listenerSnapshot = _listener.value
            earlyReflectionJob?.cancel()
            earlyReflectionJob = viewModelScope.launch {
                val reflections = withContext(Dispatchers.Default) {
                    buildEarlyReflections(analysis.speakerResults, analysis.roomBounds, listenerSnapshot)
                }
                _earlyReflections.value = reflections
            }
        }
    }

    private fun refreshHeatmap(force: Boolean = false) {
        // Same rule as recalculate(): an empty scene clears the map whether or not
        // auto-calculate is on. A coverage map with no sources behind it is never
        // a reading anyone should trust.
        val spks = _speakers.value
        if (spks.isEmpty()) {
            heatmapJob?.cancel()
            _heatmap.value = emptyList()
            _stiEstimate.value = null
            _highestSplDb.value = null
            return
        }
        if (!force && !_signalAutoCalculate.value) return
        if (!_signalCoverageEnabled.value || !_signalSplEnabled.value) {
            _heatmap.value = emptyList()
            _highestSplDb.value = _results.value.maxOfOrNull { it.splDb }
            _stiEstimate.value = null
            return
        }

        heatmapJob?.cancel()
        heatmapJob = viewModelScope.launch {
            val cells = withContext(Dispatchers.Default) {
                val positions = FloatArray(spks.size * 3) { i ->
                    val spk = spks[i / 3]
                    when (i % 3) { 0 -> spk.x; 1 -> spk.heightM; else -> spk.z }
                }

                val coords = mutableListOf<Pair<Float, Float>>()
                val sampleHeights = mutableListOf<Float>()
                val sampleRenderHeights = mutableListOf<Float>()
                val sourceAreas = mutableListOf<Int?>()
                val sourceAreaNames = mutableListOf<String?>()
                val sourceZoneTypes = mutableListOf<String?>()
                val sourceRakes = mutableListOf<Float>()
                val sourceRakeDirections = mutableListOf<Float>()
                val points = mutableListOf<Float>()
                val aud = _audience.value
                val areaById = _audienceAreas.value.associateBy { it.id }
                if (aud.isNotEmpty()) {
                    aud.forEach { a ->
                        val area = a.sourceAreaId?.let { areaById[it] }
                        coords += a.x to a.z
                        sampleHeights += a.earHeightM
                        sampleRenderHeights += (a.earHeightM - audienceHeadOffsetForZone(area?.zoneType ?: "AUDIENCE_SEATED")).coerceAtLeast(0f)
                        sourceAreas += a.sourceAreaId
                        sourceAreaNames += area?.name
                        sourceZoneTypes += area?.zoneType
                        sourceRakes += area?.rakeDeg ?: 0f
                        sourceRakeDirections += area?.rakeDirectionDeg ?: 0f
                        points += a.x
                        points += a.earHeightM
                        points += a.z
                    }
                } else {
                    val min = -12f
                    val max = 12f
                    val step = when (_analysisProfile.value) {
                        "Fast" -> 3f
                        "Precision" -> 1f
                        else -> 2f
                    }
                    var z = min
                    while (z <= max + 0.001f) {
                        var x = min
                        while (x <= max + 0.001f) {
                            val overlaps = _audienceAreas.value.filter { a ->
                                (a.zoneType == "AUDIENCE_SEATED" || a.zoneType == "AUDIENCE_STANDING") &&
                                    pointInPolygon(x, z, a.vertices)
                            }

                            if (overlaps.isEmpty()) {
                                val blockY = blockSurfaceYAt(x, z)
                                val stageY = stageSurfaceYAt(x, z)
                                val renderY = max(blockY ?: 0f, stageY ?: 0f)
                                coords += x to z
                                sampleHeights += 1.2f
                                sampleRenderHeights += renderY
                                sourceAreas += null
                                sourceAreaNames += null
                                sourceZoneTypes += null
                                sourceRakes += 0f
                                sourceRakeDirections += 0f
                                points += x
                                points += 1.2f
                                points += z
                            } else {
                                overlaps.forEach { area ->
                                    val earHeight = audienceEarHeightForArea(x, z, area)
                                    coords += x to z
                                    sampleHeights += earHeight
                                    sampleRenderHeights += (earHeight - audienceHeadOffsetForZone(area.zoneType)).coerceAtLeast(0f)
                                    sourceAreas += area.id
                                    sourceAreaNames += area.name
                                    sourceZoneTypes += area.zoneType
                                    sourceRakes += area.rakeDeg
                                    sourceRakeDirections += area.rakeDirectionDeg
                                    points += x
                                    points += earHeight
                                    points += z
                                }
                            }
                            x += step
                        }
                        z += step
                    }
                }

                val dists = computeDistancesSafe(positions, points.toFloatArray())
                val speakerCount = spks.size
                coords.mapIndexed { gi, (x, z) ->
                    val listenerY = sampleHeights[gi]
                    val renderY = sampleRenderHeights[gi]
                    val areaId = sourceAreas[gi]
                    val areaName = sourceAreaNames[gi]
                    val zoneType = sourceZoneTypes[gi]
                    val zoneRake = sourceRakes[gi]
                    val zoneRakeDirection = sourceRakeDirections[gi]
                    val contributions = ArrayList<CoherentContribution>(speakerCount)
                    for (si in 0 until speakerCount) {
                        val d = dists[gi * speakerCount + si].coerceAtLeast(0.1f)
                        val dspH = _dspMap.value[spks[si].id] ?: SpeakerDsp(spks[si].id)
                        val occlusionPenalty = estimateObstructionAttenuationDb(
                            sx = spks[si].x,
                            sy = spks[si].heightM,
                            sz = spks[si].z,
                            tx = x,
                            ty = listenerY,
                            tz = z
                        )
                        // Try CLF-based directivity first; fall back to simplified model if no CLF
                        val clfPenalty = clfDirectivityAttenuationDb(
                            speakerId = spks[si].id.toString(),
                            fromX = spks[si].x,
                            fromY = spks[si].heightM,
                            fromZ = spks[si].z,
                            toX = x,
                            toY = listenerY,
                            toZ = z,
                            spk = spks[si]
                        )
                        val directivityPenalty = clfPenalty ?: horizontalDirectivityAttenuationDb(
                            spk = spks[si],
                            fromX = spks[si].x,
                            fromZ = spks[si].z,
                            toX = x,
                            toZ = z,
                            pathOrder = 0
                        )
                        val verticalAimPenalty = if (clfPenalty != null) 0f else verticalAimAttenuationDb(
                            spk = spks[si],
                            fromX = spks[si].x,
                            fromY = spks[si].heightM,
                            fromZ = spks[si].z,
                            toX = x,
                            toY = listenerY,
                            toZ = z,
                            pathOrder = 0
                        )
                        val boundaryDelta = floorBoundaryInterferenceDb(
                            srcX = spks[si].x,
                            srcY = spks[si].heightM,
                            srcZ = spks[si].z,
                            dstX = x,
                            dstY = listenerY,
                            dstZ = z
                        )
                        val beamShadowPenalty = if (_signalDispersionEnabled.value && (directivityPenalty + verticalAimPenalty) > 14f) 12f else 0f
                        contributions += CoherentContribution(
                            splDb = directSplDb(spks[si], dspH, d, listenerY) - occlusionPenalty - directivityPenalty - verticalAimPenalty - beamShadowPenalty + boundaryDelta,
                            distanceM = d,
                            delayMs = dspH.delayMs,
                            polarity = dspH.polarity
                        )
                    }
                    HeatCell(
                        x = x,
                        z = z,
                        splDb = coherentSumDb(contributions),
                        sourceAreaId = areaId,
                        sourceAreaName = areaName,
                        sourceZoneType = zoneType,
                        sourceRakeDeg = zoneRake,
                        sourceRakeDirectionDeg = zoneRakeDirection,
                        renderY = renderY
                    )
                }
            }
            _heatmap.value = cells
            recomputeStiEstimate()
            _highestSplDb.value = cells.maxOfOrNull { it.splDb } ?: _results.value.maxOfOrNull { it.splDb }
        }
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

    /**
     * Phase 9 — lightweight STI estimate from level margin, coverage uniformity and RT60.
     * Returns normalized STI in [0,1] and an ALcons approximation.
     */
    private fun recomputeStiEstimate() {
        val heat = _heatmap.value
        val rt = _rt60Estimate.value
        val combined = _combinedSplDb.value
        if (heat.isEmpty() || combined == null || rt == null) {
            _stiEstimate.value = null
            return
        }

        val avg = heat.map { it.splDb }.average().toFloat()
        val dev = kotlin.math.sqrt(heat.map { (it.splDb - avg) * (it.splDb - avg) }.average().toFloat()).coerceAtLeast(0f)

        val levelScore = ((avg - 58f) / 22f).coerceIn(0f, 1f)
        val uniformityScore = (1f - dev / 10f).coerceIn(0f, 1f)
        val rtTarget = 0.9f
        val rtPenalty = (kotlin.math.abs(rt.rt60S - rtTarget) / 1.6f).coerceIn(0f, 1f)
        val rtScore = (1f - rtPenalty).coerceIn(0f, 1f)

        val sti = (0.45f * levelScore + 0.25f * uniformityScore + 0.30f * rtScore).coerceIn(0f, 1f)
        val quality = when {
            sti >= 0.75f -> "Excellent"
            sti >= 0.60f -> "Good"
            sti >= 0.45f -> "Fair"
            sti >= 0.30f -> "Poor"
            else -> "Bad"
        }
        val alcons = (170f * kotlin.math.exp((-5.4f * sti).toDouble()).toFloat()).coerceIn(0f, 100f)
        _stiEstimate.value = StiEstimate(sti = sti, quality = quality, alconsPct = alcons)
    }

    private data class CoherentContribution(
        val splDb: Float,
        val distanceM: Float,
        val delayMs: Float,
        val polarity: Boolean
    )

    /**
     * Phase 8 — single-element SPL (geometric spreading + atmospheric loss + DSP).
     * Building block for both point sources and individual array elements.
     */
    private fun elementSplDb(sensitivity: Float, dsp: SpeakerDsp, distanceM: Float): Float {
        val geo    = 20f * log10(distanceM.toDouble()).toFloat()
        val air    = atmosphericLossDb(distanceM, _selectedBandHz.value, _temperatureC.value, _humidityPct.value)
        val eqGain = dsp.eqBands[_selectedBandHz.value] ?: 0f
        return sensitivity + dsp.gainDb + eqGain - geo - air
    }

    /**
     * Phase 8 — vertical line-array coherent summation.
     * N elements stacked along Y, centred at [spk.heightM].
     * Each element contributes a complex phasor summed at the selected band.
     */
    private fun lineArraySplDb(
        spk       : PlacedSpeaker,
        dsp       : SpeakerDsp,
        horizDistM: Float,         // XZ-plane distance to listener
        listenerY : Float
    ): Float {
        val n       = spk.arrayElements
        val spacing = spk.arraySpacingM.toDouble()
        val freq    = _selectedBandHz.value.toDouble()
        val c       = (331.3 + 0.606 * _temperatureC.value).coerceAtLeast(300.0)
        val centreY = spk.heightM.toDouble()
        val globalSteerDeg = spk.arraySteerDeg + spk.arrayAimDeg
        val edgeTaperDb = spk.arrayEdgeTaperDb.toDouble()
        val joints = (n - 1).coerceAtLeast(0)
        val splayProfile = if (spk.arraySplayByBoxDeg.size == joints) {
            spk.arraySplayByBoxDeg
        } else {
            List(joints) { spk.arrayInterBoxSplayDeg }
        }

        // Build per-element absolute aim from cumulative per-joint splay.
        val elemAimDeg = DoubleArray(n) { globalSteerDeg.toDouble() }
        for (i in 1 until n) {
            elemAimDeg[i] = elemAimDeg[i - 1] + splayProfile[i - 1].toDouble()
        }
        if (n > 1) {
            val meanAim = elemAimDeg.average()
            val offset = globalSteerDeg.toDouble() - meanAim
            for (i in 0 until n) elemAimDeg[i] += offset
        }

        val edgeNormDen = ((n - 1) * 0.5).coerceAtLeast(1.0)
        var real    = 0.0
        var imag    = 0.0

        fun directivityAttenuationDb(mismatchDeg: Double, elementCount: Int, frequencyHz: Double): Double {
            // Broader beam at LF, narrower at HF and for larger arrays.
            val freqK = (frequencyHz / 1000.0).coerceAtLeast(0.063)
            val halfPowerDeg = (65.0 / (sqrt(elementCount.toDouble()) * Math.pow(freqK, 0.35))).coerceIn(4.0, 80.0)
            val x = kotlin.math.abs(mismatchDeg) / halfPowerDeg
            return (-12.0 * x * x).coerceAtLeast(-24.0)
        }

        for (elem in 0 until n) {
            val relIdx = elem - (n - 1) * 0.5
            val ey  = centreY + relIdx * spacing
            val dy  = ey - listenerY.toDouble()
            val d   = sqrt(horizDistM.toDouble() * horizDistM.toDouble() + dy * dy).coerceAtLeast(0.1)
            val relNorm = kotlin.math.abs(relIdx) / edgeNormDen
            val taper = -edgeTaperDb * relNorm

            val elemAim = elemAimDeg[elem]
            val listenerAngleDeg = Math.toDegrees(atan2((listenerY.toDouble() - ey), horizDistM.toDouble().coerceAtLeast(0.05)))
            val mismatchDeg = listenerAngleDeg - elemAim
            val dirAttenDb = directivityAttenuationDb(mismatchDeg, n, freq)

            val spl = elementSplDb(spk.sensitivity, dsp, d.toFloat()) + taper.toFloat() + dirAttenDb.toFloat()
            val amp = Math.pow(10.0, spl / 20.0)

            val steerRad = Math.toRadians(elemAim)
            // Steering term for a vertical linear array (far-field phase progression).
            val steerPhase = -2.0 * PI * freq * (relIdx * spacing) * kotlin.math.sin(steerRad) / c
            val phi = 2.0 * PI * freq * d / c + steerPhase + if (dsp.polarity) PI else 0.0
            real   += amp * cos(phi)
            imag   += amp * sin(phi)
        }
        val mag = sqrt(real * real + imag * imag).coerceAtLeast(1e-9)
        return (20.0 * log10(mag)).toFloat()
    }

    /**
     * Phase 8 — dispatch: line-array or point-source SPL.
     * A speaker is treated as a line array when `arrayElements > 1`.
     */
    private fun directSplDb(
        spk: PlacedSpeaker,
        dsp: SpeakerDsp,
        distanceM: Float,
        listenerY: Float = _listener.value.earHeightM
    ): Float {
        val base = if (spk.arrayElements > 1) {
            lineArraySplDb(spk, dsp, distanceM, listenerY)
        } else {
            elementSplDb(spk.sensitivity, dsp, distanceM)
        }
        return base + _signalLevelDbu.value
    }

    private fun coherentSumDb(contributions: List<CoherentContribution>): Float {
        if (contributions.isEmpty()) return 0f
        val frequencyHz = _selectedBandHz.value.toDouble().coerceAtLeast(1.0)
        val speedOfSound = (331.3 + 0.606 * _temperatureC.value.toDouble()).coerceAtLeast(300.0)
        var real = 0.0
        var imag = 0.0
        contributions.forEach { c ->
            val amplitude = Math.pow(10.0, c.splDb.toDouble() / 20.0)
            val propagationS = c.distanceM.toDouble() / speedOfSound
            val delayS = c.delayMs.toDouble() / 1000.0
            val phase = 2.0 * PI * frequencyHz * (propagationS + delayS) + if (c.polarity) PI else 0.0
            real += amplitude * cos(phase)
            imag += amplitude * sin(phase)
        }
        val mag = sqrt(real * real + imag * imag).coerceAtLeast(1e-9)
        val coherentDb = (20.0 * log10(mag)).toFloat()

        val incoherentPower = contributions.sumOf { Math.pow(10.0, it.splDb.toDouble() / 10.0) }.coerceAtLeast(1e-12)
        val incoherentDb = (10.0 * log10(incoherentPower)).toFloat()

        val lowFreqForceIncoherent = !_signalInterferenceEnabled.value && _selectedBandHz.value <= 163
        if (lowFreqForceIncoherent) return incoherentDb

        val coherentWeight = if (_signalType.value == "BAND") {
            val detail = ((1f / 3f) / _signalBandwidthOct.value.coerceAtLeast(1f / 12f)).coerceIn(0f, 1f)
            (0.25f + detail * 0.75f).coerceIn(0f, 1f)
        } else {
            val resNorm = ((_signalResolution.value - 3).toFloat() / 93f).coerceIn(0f, 1f)
            (0.05f + 0.35f * resNorm).coerceIn(0f, 0.45f)
        }

        val blendedPower = coherentWeight * Math.pow(10.0, coherentDb.toDouble() / 10.0) +
            (1f - coherentWeight) * Math.pow(10.0, incoherentDb.toDouble() / 10.0)
        return (10.0 * log10(blendedPower.coerceAtLeast(1e-12))).toFloat()
    }

    /**
     * CLF-based directivity attenuation using polar pattern interpolation.
     * Returns negative dB attenuation (loss) from on-axis reference.
     * For frequency-dependent realism, CLF replaces the simpler model.
     */
    private fun clfDirectivityAttenuationDb(
        speakerId: String,
        fromX: Float,
        fromY: Float,
        fromZ: Float,
        toX: Float,
        toY: Float,
        toZ: Float,
        spk: PlacedSpeaker
    ): Float? {
        if (!_signalDispersionEnabled.value) return 0f
        val clf = _clfRegistry.value[speakerId] ?: return null
        val bandHz = _selectedBandHz.value
        
        // Calculate direction from speaker to listener
        val dx = toX - fromX
        val dz = toZ - fromZ
        val dy = toY - fromY
        if (kotlin.math.abs(dx) < 1e-5f && kotlin.math.abs(dz) < 1e-5f && kotlin.math.abs(dy) < 1e-5f) {
            return 0f  // On-axis
        }

        // Horizontal angle (azimuth): relative to speaker's pan direction
        val bearingDeg = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).toFloat()
        val azimuthDeg = angularDeltaDeg(bearingDeg, spk.panDeg)
        
        // Vertical angle (elevation): relative to speaker's aim direction
        val horizDist = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f)
        val targetElevationDeg = Math.toDegrees(atan2(dy.toDouble(), horizDist.toDouble())).toFloat()
        val acousticAxisDeg = -spk.arrayAimDeg  // App convention: down-positive → elevation negative
        val elevationDeg = angularDeltaDeg(targetElevationDeg, acousticAxisDeg)
        
        // Look up SPL at this direction and frequency from CLF
        val splDbRelative = clf.splAtDirection(bandHz, azimuthDeg, elevationDeg) ?: return null
        
        // Return attenuation (negative = loss)
        return -splDbRelative
    }

    private fun horizontalDirectivityAttenuationDb(
        spk: PlacedSpeaker,
        fromX: Float,
        fromZ: Float,
        toX: Float,
        toZ: Float,
        pathOrder: Int
    ): Float {
        if (!_signalDispersionEnabled.value) return 0f
        val dx = toX - fromX
        val dz = toZ - fromZ
        if (kotlin.math.abs(dx) < 1e-5f && kotlin.math.abs(dz) < 1e-5f) return 0f

        val bearingDeg = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())).toFloat()
        val mismatchDeg = angularDeltaDeg(bearingDeg, spk.panDeg)

        val halfPowerDeg = when (spk.modelPackageId) {
            "line_array" -> 45f
            "point_source" -> 60f
            else -> 50f
        }

        val x = (mismatchDeg / halfPowerDeg).coerceAtLeast(0f)
        val baseLoss = (6f * x * x).coerceAtMost(24f)
        val reflectionRelax = (1f - 0.16f * pathOrder.coerceAtLeast(0)).coerceIn(0.5f, 1f)
        return baseLoss * reflectionRelax
    }

    private fun verticalAimAttenuationDb(
        spk: PlacedSpeaker,
        fromX: Float,
        fromY: Float,
        fromZ: Float,
        toX: Float,
        toY: Float,
        toZ: Float,
        pathOrder: Int
    ): Float {
        if (!_signalDispersionEnabled.value) return 0f
        // Vertical steering/directivity for line arrays is already handled in lineArraySplDb.
        if (spk.arrayElements > 1) return 0f

        val dx = toX - fromX
        val dz = toZ - fromZ
        val dy = toY - fromY
        val horizDist = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f)

        val targetElevationDeg = Math.toDegrees(atan2(dy.toDouble(), horizDist.toDouble())).toFloat()
        // App convention: arrayAimDeg is down-positive, so acoustic axis elevation is -arrayAimDeg.
        val acousticAxisDeg = -spk.arrayAimDeg
        val mismatchDeg = angularDeltaDeg(targetElevationDeg, acousticAxisDeg)

        val halfPowerDeg = when (spk.modelPackageId) {
            "point_source" -> 35f
            "line_array" -> 28f
            else -> 40f
        }

        val x = (mismatchDeg / halfPowerDeg).coerceAtLeast(0f)
        val baseLoss = (6f * x * x).coerceAtMost(18f)
        val reflectionRelax = (1f - 0.16f * pathOrder.coerceAtLeast(0)).coerceIn(0.5f, 1f)
        return baseLoss * reflectionRelax
    }

    private fun angularDeltaDeg(aDeg: Float, bDeg: Float): Float {
        val d = (((aDeg - bDeg) + 540f) % 360f) - 180f
        return kotlin.math.abs(d)
    }

    private fun floorBoundaryInterferenceDb(
        srcX: Float,
        srcY: Float,
        srcZ: Float,
        dstX: Float,
        dstY: Float,
        dstZ: Float
    ): Float {
        // Simple two-ray model against floor plane (y = 0).
        val c = (331.3f + 0.606f * _temperatureC.value).coerceAtLeast(300f)
        val f = _selectedBandHz.value.toFloat().coerceAtLeast(1f)

        val direct = distance3d(srcX, srcY, srcZ, dstX, dstY, dstZ).coerceAtLeast(0.1f)
        val imgY = -srcY
        val reflected = distance3d(srcX, imgY, srcZ, dstX, dstY, dstZ).coerceAtLeast(0.1f)
        val delta = (reflected - direct).coerceAtLeast(0f)

        // Reflection magnitude derived from floor absorption coefficient.
        val floorAlpha = _roomMaterials.value.floorAlpha.coerceIn(0.01f, 0.99f)
        val r = kotlin.math.sqrt((1f - floorAlpha).coerceIn(0.01f, 0.99f)).toDouble()

        val phi = 2.0 * PI * f.toDouble() * delta.toDouble() / c.toDouble()
        val real = 1.0 + r * cos(phi)
        val imag = r * sin(phi)
        val mag = kotlin.math.sqrt(real * real + imag * imag).coerceAtLeast(1e-6)
        val db = (20.0 * kotlin.math.log10(mag)).toFloat()

        // Keep contribution conservative to avoid unstable combing in coarse grids.
        return db.coerceIn(-9f, 3f)
    }

    private fun estimateRoomBounds(
        speakers: List<PlacedSpeaker>,
        listener: ListenerPos,
        audience: List<AudiencePoint>,
        audienceAreas: List<AudienceArea>
    ): RoomBounds? {
        val venue = _venueGeometry.value
        val baseMinX = -venue.widthM * 0.5f
        val baseMaxX = venue.widthM * 0.5f
        val baseMinZ = -venue.depthM * 0.5f
        val baseMaxZ = venue.depthM * 0.5f

        val xs = mutableListOf<Float>()
        val zs = mutableListOf<Float>()
        xs += baseMinX; xs += baseMaxX
        zs += baseMinZ; zs += baseMaxZ
        speakers.forEach { xs += it.x; zs += it.z }
        xs += listener.x; zs += listener.z
        audience.forEach { xs += it.x; zs += it.z }
        audienceAreas.forEach { area ->
            area.vertices.forEach { (x, z) ->
                xs += x; zs += z
            }
        }
        if (xs.isEmpty() || zs.isEmpty()) return null

        val centerX = (xs.min() + xs.max()) * 0.5f
        val centerZ = (zs.min() + zs.max()) * 0.5f
        val width = max(xs.max() - xs.min() + 4f, 20f)
        val depth = max(zs.max() - zs.min() + 4f, 20f)
        return RoomBounds(
            minX = centerX - width * 0.5f,
            maxX = centerX + width * 0.5f,
            minZ = centerZ - depth * 0.5f,
            maxZ = centerZ + depth * 0.5f,
            heightM = venue.wallHeightM
        )
    }

    /**
     * Phase 8+ — Image-source early reflections, configurable 1st..3rd order.
     *
     * Per-surface absorption → insertion loss:  loss_dB = -10·log10(1 - α)
     */
    private fun buildEarlyReflections(
        results: List<SpeakerResult>,
        room: RoomBounds?,
        listener: ListenerPos
    ): List<EarlyReflection> {
        if (room == null) return emptyList()
        val mat = _roomMaterials.value
        val speedOfSound = (331.3f + 0.606f * _temperatureC.value).coerceAtLeast(300f)

        fun surfaceLossDb(alpha: Float, surfaceType: String): Float {
            val freqK = (_selectedBandHz.value.toFloat() / 1000f).coerceAtLeast(0.063f)
            val logFreq = kotlin.math.log10(freqK.toDouble()).toFloat()

            val surfaceFreqTilt = when (surfaceType) {
                "FLOOR" -> 0.10f
                "CEILING" -> 0.14f
                else -> 0.12f // WALL
            }
            val profileBias = when (_analysisProfile.value) {
                "Fast" -> -0.02f
                "Precision" -> 0.03f
                else -> 0f
            }

            // Effective absorption rises mildly toward HF and depends on surface family.
            val effectiveAlpha = (alpha + surfaceFreqTilt * logFreq + profileBias).coerceIn(0.01f, 0.99f)
            val a = effectiveAlpha
            return (-10.0 * Math.log10((1.0 - a).toDouble())).toFloat()
        }

        // Reflection transform: image_coord = flip * src_coord + offset
        data class Surf(
            val name  : String,
            val type  : String,
            val flipX : Float, val flipY : Float, val flipZ : Float,
            val offX  : Float, val offY  : Float, val offZ  : Float,
            val loss  : Float,
            val nx    : Float, val ny: Float, val nz: Float
        )
        val surfs = listOf(
            Surf("Floor",      "FLOOR",   1f, -1f,  1f,  0f,              0f,              0f, surfaceLossDb(mat.floorAlpha, "FLOOR"),     0f,  1f,  0f),
            Surf("Ceiling",    "CEILING", 1f, -1f,  1f,  0f, 2f*room.heightM,              0f, surfaceLossDb(mat.ceilingAlpha, "CEILING"), 0f, -1f,  0f),
            Surf("Left wall",  "WALL",   -1f,  1f,  1f, 2f*room.minX,    0f,              0f, surfaceLossDb(mat.wallAlpha, "WALL"),      1f,  0f,  0f),
            Surf("Right wall", "WALL",   -1f,  1f,  1f, 2f*room.maxX,    0f,              0f, surfaceLossDb(mat.wallAlpha, "WALL"),     -1f,  0f,  0f),
            Surf("Front wall", "WALL",    1f,  1f, -1f,  0f,              0f, 2f*room.minZ,   surfaceLossDb(mat.wallAlpha, "WALL"),      0f,  0f,  1f),
            Surf("Back wall",  "WALL",    1f,  1f, -1f,  0f,              0f, 2f*room.maxZ,   surfaceLossDb(mat.wallAlpha, "WALL"),      0f,  0f, -1f)
        )

        fun reflect(x: Float, y: Float, z: Float, s: Surf) =
            Triple(s.flipX*x + s.offX, s.flipY*y + s.offY, s.flipZ*z + s.offZ)

        fun pathDist(ix: Float, iy: Float, iz: Float): Float {
            val dx = ix - listener.x
            val dy = iy - listener.earHeightM
            val dz = iz - listener.z
            return sqrt(dx*dx + dy*dy + dz*dz).coerceAtLeast(0.1f)
        }

        fun incidenceShapingDb(ix: Float, iy: Float, iz: Float, surf: Surf, order: Int): Float {
            val vx = (listener.x - ix)
            val vy = (listener.earHeightM - iy)
            val vz = (listener.z - iz)
            val vmag = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1e-4f)
            val dot = kotlin.math.abs((vx * surf.nx + vy * surf.ny + vz * surf.nz) / vmag).coerceIn(0f, 1f)
            // dot≈1 normal incidence (strong), dot≈0 grazing (weaker, especially at HF and higher orders)
            val grazing = (1f - dot)
            val freqScale = (_selectedBandHz.value.toFloat() / 1000f).coerceAtLeast(0.063f)
            val hfWeight = kotlin.math.sqrt(freqScale).coerceIn(0.25f, 2.3f)
            val orderWeight = (1f + 0.28f * (order - 1).coerceAtLeast(0))
            return (grazing * grazing * 4.0f * hfWeight * orderWeight).coerceIn(0f, 8f)
        }

        val maxDim = max(room.maxX - room.minX, room.maxZ - room.minZ)
        val all = mutableListOf<EarlyReflection>()
        val maxOrder = _reflectionOrder.value.coerceIn(1, 3)

        data class PathState(
            val ix: Float,
            val iy: Float,
            val iz: Float,
            val order: Int,
            val pathName: String,
            val lossDb: Float,
            val prevSurfaceIdx: Int
        )

        results.forEach { result ->
            val spk = result.speaker
            val dsp = _dspMap.value[spk.id] ?: SpeakerDsp(spk.id)

            var frontier = surfs.mapIndexed { idx, s ->
                val (ix1, iy1, iz1) = reflect(spk.x, spk.heightM, spk.z, s)
                PathState(
                    ix = ix1,
                    iy = iy1,
                    iz = iz1,
                    order = 1,
                    pathName = s.name,
                    lossDb = s.loss,
                    prevSurfaceIdx = idx
                )
            }

            while (frontier.isNotEmpty()) {
                val nextFrontier = mutableListOf<PathState>()

                frontier.forEach { st ->
                    val d = pathDist(st.ix, st.iy, st.iz)
                    val distanceGate = maxDim * (2.6f + st.order * 0.9f)
                    if (d <= distanceGate) {
                        val occlusionLoss = estimateObstructionAttenuationDb(
                            sx = st.ix,
                            sy = st.iy,
                            sz = st.iz,
                            tx = listener.x,
                            ty = listener.earHeightM,
                            tz = listener.z
                        )
                        val order = st.pathName.count { it == '→' } + 1
                        val lastSurfaceName = st.pathName.substringAfterLast('→')
                        val lastSurface = surfs.firstOrNull { it.name == lastSurfaceName }
                        val incidenceLoss = lastSurface?.let { incidenceShapingDb(st.ix, st.iy, st.iz, it, order) } ?: 0f
                        val directivityPenalty = horizontalDirectivityAttenuationDb(
                            spk = spk,
                            fromX = st.ix,
                            fromZ = st.iz,
                            toX = listener.x,
                            toZ = listener.z,
                            pathOrder = order
                        )
                        val verticalAimPenalty = verticalAimAttenuationDb(
                            spk = spk,
                            fromX = st.ix,
                            fromY = st.iy,
                            fromZ = st.iz,
                            toX = listener.x,
                            toY = listener.earHeightM,
                            toZ = listener.z,
                            pathOrder = order
                        )
                        val diffuseLossPerBounce = when (_analysisProfile.value) {
                            "Fast" -> 1.1f
                            "Precision" -> 1.8f
                            else -> 1.4f
                        }
                        val diffuseLoss = (order - 1).coerceAtLeast(0) * diffuseLossPerBounce
                        val reflectionAirLoss = atmosphericLossDbForReflection(
                            d,
                            _selectedBandHz.value,
                            _temperatureC.value,
                            _humidityPct.value,
                            order
                        )
                        val spl = directSplDb(spk, dsp, d, listener.earHeightM) - st.lossDb - occlusionLoss - incidenceLoss - diffuseLoss - directivityPenalty - verticalAimPenalty - reflectionAirLoss
                        val dt = ((d - result.distanceM).coerceAtLeast(0f) / speedOfSound) * 1000f
                        all += EarlyReflection(spk.label, st.pathName, dt, d, spl)
                    }

                    if (st.order >= maxOrder) return@forEach

                    surfs.forEachIndexed { idx, s ->
                        if (idx == st.prevSurfaceIdx) return@forEachIndexed
                        val (nix, niy, niz) = reflect(st.ix, st.iy, st.iz, s)
                        nextFrontier += PathState(
                            ix = nix,
                            iy = niy,
                            iz = niz,
                            order = st.order + 1,
                            pathName = "${st.pathName}→${s.name}",
                            lossDb = st.lossDb + s.loss,
                            prevSurfaceIdx = idx
                        )
                    }
                }

                frontier = nextFrontier
            }
        }
        val maxReflections = when (_analysisProfile.value) {
            "Fast" -> 10
            "Precision" -> 24
            else -> 16
        }
        return all.sortedByDescending { it.splDb }.take(maxReflections)
    }

    /**
     * Phase 8 — Sabine RT60 with per-surface absorption from [RoomMaterials].
     * Formula: RT60 = 0.161 × V / Σ(α × S)
     */
    internal fun estimateRt60(room: RoomBounds, mat: RoomMaterials): Rt60Estimate {
        val width  = room.maxX - room.minX
        val depth  = room.maxZ - room.minZ
        val height = room.heightM
        val floorArea   = width * depth
        val ceilingArea = floorArea
        val wallArea    = 2f * height * (width + depth)
        val volume      = width * depth * height

        val sabins =
            floorArea   * mat.floorAlpha   +
            ceilingArea * mat.ceilingAlpha +
            wallArea    * mat.wallAlpha
        val rt60 = if (sabins <= 0.01f) 0f else 0.161f * volume / sabins
        return Rt60Estimate(width, depth, height, volume, rt60)
    }

    internal fun atmosphericLossDb(
        distanceM: Float,
        bandHz: Int,
        temperatureC: Float,
        humidityPct: Float
    ): Float {
        // Coarse one-band approximation at ~20°C / 50% RH (dB per metre).
        val alphaBase = when (bandHz) {
            63   -> 0.0002f
            125  -> 0.0004f
            250  -> 0.0007f
            500  -> 0.0012f
            1000 -> 0.0020f
            2000 -> 0.0045f
            4000 -> 0.0120f
            8000 -> 0.0350f
            else -> 0.0020f
        }

        val tempFactor = 1f + (20f - temperatureC) * 0.01f
        val humFactor  = 1f + (50f - humidityPct) * 0.008f
        val scale = (tempFactor * humFactor).coerceIn(0.5f, 2.0f)

        return alphaBase * scale * distanceM.coerceAtLeast(0f)
    }

    /**
     * Phase 9 — Frequency-dependent reflection air loss (separate from direct path).
     * Reflections travel longer distances, so air absorption is more pronounced.
     * Order-dependent: higher-order reflections lose more HF energy due to cumulative path length.
     */
    private fun atmosphericLossDbForReflection(
        reflectionDistanceM: Float,
        bandHz: Int,
        temperatureC: Float,
        humidityPct: Float,
        reflectionOrder: Int
    ): Float {
        // Enhanced air loss for reflections: longer effective path length.
        val alphaBase = when (bandHz) {
            63   -> 0.00025f
            125  -> 0.00050f
            250  -> 0.00090f
            500  -> 0.00150f
            1000 -> 0.00250f
            2000 -> 0.00580f
            4000 -> 0.01550f
            8000 -> 0.04500f
            else -> 0.00250f
        }

        val tempFactor = 1f + (20f - temperatureC) * 0.01f
        val humFactor  = 1f + (50f - humidityPct) * 0.008f
        val baseScale = (tempFactor * humFactor).coerceIn(0.5f, 2.0f)

        // Order-dependent boost: higher-order reflections accumulate HF loss.
        // 1st order: 1.0×, 2nd: 1.15×, 3rd: 1.28×
        val orderBoost = (1.0f + 0.14f * (reflectionOrder - 1).coerceAtLeast(0)).coerceAtLeast(1.0f)

        return alphaBase * baseScale * orderBoost * reflectionDistanceM.coerceAtLeast(0f)
    }

    private fun pointInPolygon(px: Float, pz: Float, poly: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val xi = poly[i].first; val zi = poly[i].second
            val xj = poly[j].first; val zj = poly[j].second
            val denom = (zj - zi).let { if (kotlin.math.abs(it) < 1e-6f) 1e-6f else it }
            val intersect = ((zi > pz) != (zj > pz)) &&
                (px < (xj - xi) * (pz - zi) / denom + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    // ─────────────────────────────────────────────────────────────────────
    // Phase 9: ArrayCalc Optimizer public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Enable/disable optimizer. When enabled, optimization parameters are applied to DSP map.
     */
    fun setOptimizerEnabled(enabled: Boolean) {
        _optimizerEnabled.value = enabled
        if (enabled && _optimizerParams.value.isNotEmpty()) {
            // Apply optimizer params to DSP map
            applyOptimizerParams(_optimizerParams.value)
            recalculate(force = true)
            refreshHeatmap(force = true)
        }
    }

    /**
     * Set optimizer mode: MINIMIZE_VARIANCE or MAXIMIZE_COVERAGE
     */
    fun setOptimizerMode(mode: String) {
        if (mode != "MINIMIZE_VARIANCE" && mode != "MAXIMIZE_COVERAGE") return
        _optimizerMode.value = mode
    }

    /**
     * Run array optimization with current speakers and coverage points.
     * Uses MINIMIZE_VARIANCE mode to optimize uniformity across audience area.
     */
    fun runOptimization() {
        val spks = _speakers.value
        if (spks.size < 2) return  // Optimization only makes sense for multiple speakers

        _optimizerIsRunning.value = true
        optimizerJob?.cancel()
        optimizerJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val mode = _optimizerMode.value

                // Build coverage grid from audience points and areas
                val coveragePoints = CoverageGridBuilder.buildGrid(
                    _audience.value,
                    _audienceAreas.value,
                    gridSpacingM = 2.0f  // 2-meter grid spacing for fast optimization
                )

                if (coveragePoints.isEmpty()) {
                    // Fall back to listener position if no audience defined
                    val listener = _listener.value
                    val fallbackPoints = listOf(
                        CoveragePoint(listener.x, listener.z, listener.earHeightM, weight = 1f)
                    )
                    if (mode == "MINIMIZE_VARIANCE") {
                        ArrayCalcOptimizer.minimizeVariance(spks, fallbackPoints, ::refSplDb)
                    } else {
                        ArrayCalcOptimizer.maximizeCoverage(spks, listener, ::refSplDb)
                    }
                } else {
                    if (mode == "MINIMIZE_VARIANCE") {
                        ArrayCalcOptimizer.minimizeVariance(spks, coveragePoints, ::refSplDb)
                    } else {
                        ArrayCalcOptimizer.maximizeCoverage(spks, _listener.value, ::refSplDb)
                    }
                }
            }

            _optimizerResult.value = result
            _optimizerParams.value = result.params
            _optimizerIsRunning.value = false

            // Auto-enable and apply if optimizer was disabled
            if (!_optimizerEnabled.value) {
                setOptimizerEnabled(true)
            }
        }
    }

    /**
     * Reference SPL function for optimizer: calculates SPL from speaker to point with DSP applied.
     * Signature required by ArrayCalcOptimizer.
     */
    private fun refSplDb(speakerId: Int, x: Float, z: Float, earHeightM: Float, shadingDb: Float, delayMs: Float): Float {
        val spk = _speakers.value.find { it.id == speakerId } ?: return 0f
        val dx = x - spk.x
        val dz = z - spk.z
        val dy = earHeightM - spk.heightM
        val d = sqrt(dx * dx + dz * dz + dy * dy).coerceAtLeast(0.1f)

        // Base SPL with shading and sensitivity
        val baseSpl = spk.sensitivity + 20f * log10(1f / d) + shadingDb

        // Approximate directivity penalty (simplified model)
        val directivityPenalty = horizontalDirectivityAttenuationDb(
            spk = spk,
            fromX = spk.x,
            fromZ = spk.z,
            toX = x,
            toZ = z,
            pathOrder = 0
        )

        // Air loss (frequency-dependent; use selected band)
        val airLoss = atmosphericLossDb(d, _selectedBandHz.value, _temperatureC.value, _humidityPct.value)

        // Delay is handled in coherent summation, not here
        return baseSpl - directivityPenalty - airLoss
    }

    /**
     * Apply optimizer parameters to DSP map.
     */
    private fun applyOptimizerParams(params: List<OptimizerParam>) {
        val newDspMap = _dspMap.value.toMutableMap()
        for (param in params) {
            val existing = newDspMap[param.speakerId] ?: SpeakerDsp(param.speakerId)
            newDspMap[param.speakerId] = existing.copy(
                gainDb = param.shadingGainDb,
                delayMs = param.delayMs
            )
        }
        _dspMap.value = newDspMap
    }

    /**
     * Clear/reset optimizer parameters and disable optimizer.
     */
    fun clearOptimizer() {
        _optimizerEnabled.value = false
        _optimizerParams.value = emptyList()
        _optimizerResult.value = null
        _optimizerIsRunning.value = false
        optimizerJob?.cancel()

        // Reset DSP map to zero gains/delays
        val resetDsp = _speakers.value.associate { it.id to SpeakerDsp(it.id) }
        _dspMap.value = resetDsp
        recalculate(force = true)
        refreshHeatmap(force = true)
    }}