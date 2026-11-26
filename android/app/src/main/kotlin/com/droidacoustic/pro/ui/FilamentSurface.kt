package com.droidacoustic.pro.ui

import android.content.Context
import android.widget.FrameLayout
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.droidacoustic.pro.geometry.SpeakerGizmoTransform
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Manipulator
import java.nio.ByteBuffer
import android.view.GestureDetector
import androidx.compose.runtime.LaunchedEffect
import com.droidacoustic.pro.scene.AudienceArea
import com.droidacoustic.pro.scene.AudiencePoint
import com.droidacoustic.pro.geometry.PreparedAudienceGeometry
import com.droidacoustic.pro.geometry.ViewportRay
import com.droidacoustic.pro.geometry.PickPoint3D
import com.droidacoustic.pro.geometry.GizmoCamera
import com.droidacoustic.pro.geometry.GizmoPoint3
import com.droidacoustic.pro.scene.HeatCell
import com.droidacoustic.pro.scene.ListenerPos
import com.droidacoustic.pro.scene.CoverageEdges
import com.droidacoustic.pro.scene.PlacedSpeaker
import com.droidacoustic.pro.scene.SpeakerModelPackage
import com.droidacoustic.pro.scene.VenueGeometry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// =============================================================================
// FilamentSurface — 3D viewport + tap-to-place
// =============================================================================

/** Standard camera framings. A design tool needs orthographic-feeling plan and
 *  section views, not just a free orbit. */
enum class ViewPreset(val label: String) {
    PLAN("Plan"),
    SECTION("Section"),
    PERSPECTIVE("3D")
}

/** A tappable object in world space. [radius] is its approximate half-extent. */
data class PickTarget(
    val id: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val radius: Float = 0.6f
)

/** Stable selected-object snapshot; preview geometry never changes its identity. */
data class SceneTransformTarget(val kind: String, val id: Int, val transform: SpeakerGizmoTransform, val snapshot: Any)
data class SceneTransformPreview(val venue: VenueGeometry? = null, val areas: List<AudienceArea>? = null)

@Composable
fun FilamentSurface(
    modifier   : Modifier = Modifier,
    viewPreset : ViewPreset = ViewPreset.PERSPECTIVE,
    frameAllToken: Int = 0,
    receiverCameraFocus: com.droidacoustic.pro.geometry.ReceiverCameraFocus?=null,
    receiverFrameTarget: com.droidacoustic.pro.prediction.AngularReceiver?=null,
    receiverFrameToken:Long=0,
    receiverFrameObstructions:List<com.droidacoustic.pro.geometry.ReceiverLabelRect> = emptyList(),
    onReceiverFrameMessage:(String?)->Unit={},
    venueGeometry: VenueGeometry = VenueGeometry(),
    audienceAreas: List<AudienceArea> = emptyList(),
    selectedMeshAreaId: Int? = null, selectedMeshFaces: Set<Int> = emptySet(),
    areaDraft    : List<Pair<Float, Float>> = emptyList(),
    draftSurface: AudienceArea? = null,
    activeZoneType: String = "AUDIENCE_SEATED",
    activeZoneBaseHeightM: Float = 0f,
    activeZoneRakeDeg: Float = 0f,
    activeZoneRakeDirectionDeg: Float = 0f,
    audience   : List<AudiencePoint> = emptyList(),
    speakers   : List<PlacedSpeaker> = emptyList(),
    speakerModelPackages: List<SpeakerModelPackage> = emptyList(),
    heatmap    : List<HeatCell> = emptyList(),
    coverageGeometry: PreparedAudienceGeometry? = null,
    // Null keeps the old behaviour: scale to whatever the cells contain.
    splScaleMinDb: Float? = null,
    splScaleMaxDb: Float? = null,
    listener   : ListenerPos? = null,
    aimRaysEnabled: Boolean = false,
    receiverAngularReport: com.droidacoustic.pro.prediction.ReceiverAngularReport? = null,
    selectedReceiverMarkerId:String="listener",
    receiverMarkerPickingEnabled:Boolean=false,
    onReceiverMarkerSelected:(String)->Boolean={false},
    coverageEdges: Map<Int, CoverageEdges> = emptyMap(),
    contourThresholds: List<Float> = emptyList(),
    bandStepDb: Float? = null,
    bandReferenceDb: Float? = null,
    contourEmphasisDb: Float? = null,
    onSpeakerMeshStatsChanged: (loaded: Int, total: Int) -> Unit = { _, _ -> },
    selectedObject: SceneTransformTarget? = null,
    previewObject: (SceneTransformTarget, SpeakerGizmoTransform) -> SceneTransformPreview = { _, _ -> SceneTransformPreview() },
    commitObject: (SceneTransformTarget, SpeakerGizmoTransform) -> Unit = { _, _ -> },
    selectedSpeaker: PlacedSpeaker? = null,
    previewSpeaker: (PlacedSpeaker) -> List<PlacedSpeaker> = { listOf(it) },
    onMoveSpeaker: (Int, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onRotateSpeaker: (Int, Float) -> Unit = { _, _ -> },
    onTiltSpeaker: (Int, Float) -> Unit = { _, _ -> },
    pickTargets: List<PickTarget> = emptyList(),
    onPickTarget: (id: Int) -> Unit = { },
    onSceneRayTap: (ViewportRay) -> Boolean = { false },
    onFloorTap : (x: Float, z: Float) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val ctx = remember { FilamentContext(context) }
    var objectPreview by remember { mutableStateOf<SceneTransformPreview?>(null) }
    val renderedVenue = objectPreview?.venue ?: venueGeometry
    val renderedAreas = objectPreview?.areas ?: audienceAreas
    ctx.receiverMarkerReport=receiverAngularReport
    ctx.selectedReceiverMarkerId=selectedReceiverMarkerId
    ctx.receiverMarkerPickingEnabled=receiverMarkerPickingEnabled
    ctx.onReceiverMarkerSelected=onReceiverMarkerSelected
    ctx.onReceiverFrameMessage=onReceiverFrameMessage
    ctx.gizmoObject = selectedObject
    ctx.onObjectPreview = { target, transform -> objectPreview = if(target != null && transform != null) previewObject(target,transform) else null }
    ctx.onObjectCommit = commitObject

    ctx.gizmoSpeaker = selectedSpeaker
    ctx.previewSpeaker = previewSpeaker
    ctx.gizmoPreset = viewPreset
    ctx.onGizmoMove = onMoveSpeaker
    ctx.onGizmoRotate = onRotateSpeaker
    ctx.onGizmoTilt = onTiltSpeaker
    ctx.onFloorTap = onFloorTap                      // refresh on each recompose
    ctx.onSpeakerMeshStatsChanged = onSpeakerMeshStatsChanged
    ctx.pickTargets = pickTargets
    ctx.onPickTarget = onPickTarget
    ctx.onSceneRayTap = onSceneRayTap
    LaunchedEffect(renderedVenue) { ctx.updateVenueGeometry(renderedVenue) }
    LaunchedEffect(viewPreset, frameAllToken) { ctx.setViewPreset(viewPreset, venueGeometry) }
    LaunchedEffect(receiverCameraFocus,receiverFrameTarget,receiverFrameToken,receiverFrameObstructions) {
        ctx.setReceiverFraming(receiverCameraFocus,receiverFrameTarget,receiverFrameObstructions)
    }
    LaunchedEffect(renderedAreas, selectedMeshAreaId, selectedMeshFaces, areaDraft, draftSurface, activeZoneType, activeZoneBaseHeightM, activeZoneRakeDeg, activeZoneRakeDirectionDeg) {
        ctx.updateAudienceAreas(
            areas = renderedAreas,
            draft = areaDraft,
            draftZoneType = activeZoneType,
            draftBaseHeightM = activeZoneBaseHeightM,
            draftRakeDeg = activeZoneRakeDeg,
            draftRakeDirectionDeg = activeZoneRakeDirectionDeg,
            draftSurface = draftSurface,
            selectedAreaId = selectedMeshAreaId, selectedFaces = selectedMeshFaces
        )
    }
    LaunchedEffect(audience) { ctx.updateAudience(audience) }
    LaunchedEffect(speakers, speakerModelPackages) { ctx.updateSpeakers(speakers, speakerModelPackages) }
    LaunchedEffect(heatmap, coverageGeometry, splScaleMinDb, splScaleMaxDb, bandStepDb, bandReferenceDb) {
        ctx.updateHeatmap(heatmap, splScaleMinDb, splScaleMaxDb, bandStepDb, bandReferenceDb, coverageGeometry)
    }
    LaunchedEffect(listener) { ctx.updateListener(listener) }
    LaunchedEffect(receiverAngularReport,selectedReceiverMarkerId) { ctx.updateReceiverAngular(receiverAngularReport,selectedReceiverMarkerId) }
    LaunchedEffect(heatmap, coverageGeometry, contourThresholds, contourEmphasisDb) {
        ctx.updateContours(heatmap, contourThresholds, contourEmphasisDb, coverageGeometry)
    }
    LaunchedEffect(speakers, aimRaysEnabled, venueGeometry, coverageEdges) {
        ctx.updateAimRays(speakers, aimRaysEnabled, venueGeometry, coverageEdges)
    }
    DisposableEffect(Unit) { onDispose { ctx.destroy() } }

    AndroidView(
        factory = { c -> FrameLayout(c).also { frame ->
            val sv = SurfaceView(c); frame.addView(sv); ctx.attach(sv, venueGeometry)
            frame.addView(ReceiverMarkerView(c,ctx), FrameLayout.LayoutParams(-1,-1))
            frame.addView(SpeakerTransformOverlay(c, ctx), FrameLayout.LayoutParams(-1,-1))
        } },
        modifier = modifier,
        update   = { frame ->
            ctx.gizmoSpeaker = selectedSpeaker
            ctx.gizmoObject = selectedObject
            ctx.gizmoPreset = viewPreset
            (frame.getChildAt(1) as? ReceiverMarkerView)?.refresh()
            (frame.getChildAt(2) as? SpeakerTransformOverlay)?.refresh()
        }
    )
}

// =============================================================================
// FilamentContext — owns all Filament objects for the 3D viewport
// =============================================================================

class FilamentContext(private val context: Context) {

    // ── Core Filament objects ─────────────────────────────────────────────────
    private val engine        = Engine.create()
    private val renderer      = engine.createRenderer()
    private val scene         = engine.createScene()
    private val view          = engine.createView()
    private val camEntity     = EntityManager.get().create()
    private val camera        = engine.createCamera(camEntity)

    // ── glTF / asset pipeline ─────────────────────────────────────────────────
    private val materials      = UbershaderProvider(engine)
    private val assetLoader    = AssetLoader(engine, materials, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)

    // ── Runtime state ─────────────────────────────────────────────────────────
    private var swapChain    : SwapChain?     = null
    private var gridAsset    : FilamentAsset? = null
    private var venueAsset   : FilamentAsset? = null
    private var audienceAreasAsset: FilamentAsset? = null
    private var audienceAsset: FilamentAsset? = null
    private var speakersAsset: FilamentAsset? = null
    private var receiverAngularAsset: FilamentAsset?=null
    private var aimRaysAsset : FilamentAsset? = null
    private var contourAsset : FilamentAsset? = null
    private val speakerMeshAssetsById = mutableMapOf<Int, FilamentAsset>()
    private val speakerMeshKindsById = mutableMapOf<Int, String>()
    private val speakerAssetBytesCache = mutableMapOf<String, ByteArray>()
    private var heatmapAsset : FilamentAsset? = null
    private var listenerAsset: FilamentAsset? = null
    private var currentVenueGeometry: VenueGeometry = VenueGeometry()

    // ── Viewport dims (for ray casting) ──────────────────────────────────────
    private var viewportW  = 1
    private var viewportH  = 1
    private val fovDegrees = 45.0

    // ── Public callback set by composable each recomposition ──────────────────
    var onFloorTap: (Float, Float) -> Unit = { _, _ -> }
    var onSpeakerMeshStatsChanged: (Int, Int) -> Unit = { _, _ -> }

    /** Objects a tap can select, in world space. */
    var receiverMarkerReport: com.droidacoustic.pro.prediction.ReceiverAngularReport?=null
    var invalidateReceiverMarkers:()->Unit={}
    var selectedReceiverMarkerId:String="listener"
    var receiverMarkerPickingEnabled=false
    var onReceiverMarkerSelected:(String)->Boolean={false}
    var receiverMarkerLayoutProvider:(()->com.droidacoustic.pro.geometry.ReceiverMarkerLayout)?=null
    var pickTargets: List<PickTarget> = emptyList()

    /** Called instead of [onFloorTap] when a tap lands on a pick target. */
    var gizmoObject: SceneTransformTarget? = null
    var onObjectPreview: (SceneTransformTarget?, SpeakerGizmoTransform?) -> Unit = { _, _ -> }
    var onObjectCommit: (SceneTransformTarget, SpeakerGizmoTransform) -> Unit = { _, _ -> }
    fun previewObjectGizmo(target: SceneTransformTarget?, transform: SpeakerGizmoTransform?) {
        if (!destroyed) onObjectPreview(target,transform)
    }
    var gizmoSpeaker: PlacedSpeaker? = null
    var gizmoPreset: ViewPreset = ViewPreset.PERSPECTIVE
    var onGizmoMove: (Int, Float, Float, Float) -> Unit = { _, _, _, _ -> }
    var onGizmoRotate: (Int, Float) -> Unit = { _, _ -> }
    var onGizmoTilt: (Int, Float) -> Unit = { _, _ -> }
    var previewSpeaker: (PlacedSpeaker) -> List<PlacedSpeaker> = { listOf(it) }
    private var savedSpeakers: List<PlacedSpeaker> = emptyList()
    private var savedPackages: List<SpeakerModelPackage> = emptyList()
    private var previewSpeakers: List<PlacedSpeaker>? = null
    private var destroyed = false
    fun previewGizmo(speaker: PlacedSpeaker?) {
        if (destroyed) return
        val next = speaker?.let(previewSpeaker)
        if (next == previewSpeakers) return
        previewSpeakers = next
        renderSpeakers(next ?: savedSpeakers, savedPackages)
        updateAimRays(next ?: savedSpeakers, savedAimEnabled, savedAimVenue, savedAimEdges)
    }
    private var savedAimEnabled = false
    private var savedAimVenue = VenueGeometry()
    private var savedAimEdges: Map<Int,CoverageEdges> = emptyMap()
    fun gizmoCamera() = GizmoCamera(
        GizmoPoint3(eyeArr[0],eyeArr[1],eyeArr[2]),
        GizmoPoint3(targetArr[0],targetArr[1],targetArr[2]),
        GizmoPoint3(upArr[0],upArr[1],upArr[2]),
        viewportW,viewportH,fovDegrees.toFloat())
    var onPickTarget: (Int) -> Unit = { }
    var onSceneRayTap: (ViewportRay) -> Boolean = { false }

    // ── Camera orbit controller ───────────────────────────────────────────────
    //
    // The camera is rebuilt per preset rather than tweened, because Filament's
    // Manipulator fixes its orbit home at build time.
    //
    // The previous single home position — (0, 10, 18) looking at the origin —
    // is a ~29° elevation, which is close to eye level across a 28 m room. That
    // is a poor default for a coverage tool: it foreshortens the audience plane
    // to almost nothing and makes a 0.4 m cabinet near the origin very easy to
    // miss entirely. PERSPECTIVE now sits much higher, and the framing scales
    // with the venue instead of being a constant.
    private var manipulator: Manipulator = buildManipulator(ViewPreset.PERSPECTIVE, VenueGeometry())
    private var currentPreset: ViewPreset = ViewPreset.PERSPECTIVE
    private var receiverFocus:com.droidacoustic.pro.geometry.ReceiverCameraFocus?=null
    private var receiverTarget:com.droidacoustic.pro.prediction.AngularReceiver?=null
    private var receiverObstructions:List<com.droidacoustic.pro.geometry.ReceiverLabelRect> = emptyList()
    var receiverLabelSafeRect:com.droidacoustic.pro.geometry.ReceiverLabelRect?=null
        private set
    var onReceiverFrameMessage:(String?)->Unit={}
    fun setReceiverFraming(focus:com.droidacoustic.pro.geometry.ReceiverCameraFocus?,target:com.droidacoustic.pro.prediction.AngularReceiver?,obstructions:List<com.droidacoustic.pro.geometry.ReceiverLabelRect>) {
        val wasFocused=receiverFocus!=null
        receiverFocus=focus;receiverTarget=target;receiverObstructions=obstructions.toList()
        if(focus!=null) applyReceiverFrame() else {
            receiverLabelSafeRect=null
            if(wasFocused) setViewPreset(currentPreset,currentVenueGeometry)
            onReceiverFrameMessage(null)
        }
    }
    private fun applyReceiverFrame() {
        val focus=receiverFocus ?: return
        if(viewportW<2 || viewportH<2) return // surfaceChanged will retry with real dimensions.
        val target=receiverTarget
        val frame=target?.let {com.droidacoustic.pro.geometry.ReceiverCameraFraming.fit(focus,it,viewportW,viewportH,context.resources.displayMetrics.density,fovDegrees.toFloat(),receiverObstructions)}
        if(frame==null) {
            receiverLabelSafeRect=null
            onReceiverFrameMessage("Unable to frame: hide details, narrow context or enlarge the viewport.");return
        }
        receiverLabelSafeRect=frame.usable
        val c=frame.camera
        manipulator=Manipulator.Builder().targetPosition(c.target.x,c.target.y,c.target.z)
            .upVector(c.up.x,c.up.y,c.up.z).orbitHomePosition(c.eye.x,c.eye.y,c.eye.z).build(Manipulator.Mode.ORBIT)
        manipulator.setViewport(viewportW,viewportH)
        manipulator.getLookAt(eyeArr,targetArr,upArr)
        onReceiverFrameMessage(null)
    }

    private fun buildManipulator(preset: ViewPreset, venue: VenueGeometry): Manipulator {
        // Frame the whole venue with a margin, never closer than a small room.
        val extent = maxOf(venue.widthM, venue.depthM).coerceAtLeast(12f)
        val d = extent * 1.15f
        val b = Manipulator.Builder()
            .targetPosition(0f, 1.5f, 0f)
            .upVector(0f, 1f, 0f)
        when (preset) {
            // Near-nadir. Not exactly vertical: a true top-down eye is collinear
            // with the up vector and the orbit basis degenerates.
            ViewPreset.PLAN ->
                b.orbitHomePosition(0f, d * 1.35f, d * 0.06f)
            // Looking along the room from stage-left, for checking array aim
            // and audience rake in elevation.
            ViewPreset.SECTION ->
                b.orbitHomePosition(d * 1.25f, extent * 0.28f, 0.01f)
            // Elevated three-quarter view — the working default.
            ViewPreset.PERSPECTIVE ->
                b.orbitHomePosition(d * 0.55f, extent * 0.62f, d * 0.80f)
        }
        return b.build(Manipulator.Mode.ORBIT)
    }

    /** Switch camera preset, reframing for the current venue. */
    fun setViewPreset(preset: ViewPreset, venue: VenueGeometry) {
        currentPreset = preset
        manipulator = buildManipulator(preset, venue)
        if (viewportW > 1 && viewportH > 1) manipulator.setViewport(viewportW, viewportH)
        applyReceiverFrame()
    }

    /** Re-frame the current preset — "zoom to fit". */
    fun frameAll(venue: VenueGeometry) = setViewPreset(currentPreset, venue)

    // ── Render loop ───────────────────────────────────────────────────────────
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) { choreographer.postFrameCallback(this); renderFrame(t) }
    }
    private val eyeArr    = FloatArray(3)
    private val targetArr = FloatArray(3)
    private val upArr     = FloatArray(3)

    // ── Touch helpers ─────────────────────────────────────────────────────────
    private var scaleDetector         : ScaleGestureDetector? = null
    private var gestureDetector       : GestureDetector?      = null
    private var isScaling             = false   // true while a pinch is in progress
    private var isTwoFingerGrabActive = false   // true while strafe grab is live

    // =========================================================================
    // Public API
    // =========================================================================

    fun attach(surfaceView: SurfaceView, venueGeometry: VenueGeometry = VenueGeometry()) {
        currentVenueGeometry = venueGeometry
        configureScene()
        configureView()
        attachSurfaceCallback(surfaceView)
        attachTouchListener(surfaceView)
        loadFloorGrid()
        choreographer.postFrameCallback(frameCallback)
    }

    fun destroy() {
        destroyed = true
        choreographer.removeFrameCallback(frameCallback)
        receiverMarkerLayoutProvider=null
        receiverAngularAsset?.let {destroyAsset(it)}
        listenerAsset?.let { destroyAsset(it) }
        heatmapAsset?.let { destroyAsset(it) }
        speakersAsset?.let { destroyAsset(it) }
        speakerMeshAssetsById.values.forEach { destroyAsset(it) }
        speakerMeshAssetsById.clear()
        speakerMeshKindsById.clear()
        audienceAsset?.let { destroyAsset(it) }
        audienceAreasAsset?.let { destroyAsset(it) }
        venueAsset?.let { destroyAsset(it) }
        gridAsset?.let    { destroyAsset(it)  }
        resourceLoader.destroy()
        assetLoader.destroy()
        swapChain?.let { engine.destroySwapChain(it) }
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camEntity)
        EntityManager.get().destroy(camEntity)
        engine.flushAndWait()
        engine.destroy()
    }

    /** Swap speaker markers whenever the ViewModel list changes. */
    fun updateAudienceAreas(
        areas: List<AudienceArea>,
        draft: List<Pair<Float, Float>>,
        draftZoneType: String,
        draftBaseHeightM: Float,
        draftRakeDeg: Float,
        draftRakeDirectionDeg: Float,
        draftSurface: AudienceArea? = null,
        selectedAreaId: Int? = null, selectedFaces: Set<Int> = emptySet()
    ) {
        audienceAreasAsset?.let { destroyAsset(it) }
        audienceAreasAsset = null
        val data = AudienceAreasGlb.build(
            areas = areas,
            draft = draft,
            draftZoneType = draftZoneType,
            draftBaseHeightM = draftBaseHeightM,
            draftRakeDeg = draftRakeDeg,
            draftRakeDirectionDeg = draftRakeDirectionDeg,
            draftSurface = draftSurface,
            selectedAreaId = selectedAreaId, selectedFaces = selectedFaces
        ) ?: return
        audienceAreasAsset = assetLoader.createAsset(ByteBuffer.wrap(data))?.also { asset ->
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
        }
    }

    fun updateVenueGeometry(venue: VenueGeometry) {
        val gridChanged = currentVenueGeometry.widthM != venue.widthM || currentVenueGeometry.depthM != venue.depthM
        currentVenueGeometry = venue
        venueAsset?.let { destroyAsset(it) }
        venueAsset = null
        val data = VenueGeometryGlb.build(venue) ?: return
        venueAsset = assetLoader.createAsset(ByteBuffer.wrap(data))?.also { asset ->
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
        }
        // Reload floor grid to match new venue dimensions
        if(gridChanged) loadFloorGrid()
    }

    /** Swap speaker markers whenever the ViewModel list changes. */
    fun updateAudience(points: List<AudiencePoint>) {
        audienceAsset?.let { destroyAsset(it) }
        audienceAsset = null
        val data = AudienceGlb.build(points) ?: return
        audienceAsset = assetLoader.createAsset(ByteBuffer.wrap(data))?.also { asset ->
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
        }
    }

    /** Swap speaker markers whenever the ViewModel list changes. */
    fun updateSpeakers(speakers: List<PlacedSpeaker>, modelPackages: List<SpeakerModelPackage>) {
        savedSpeakers = speakers
        savedPackages = modelPackages
        renderSpeakers(previewSpeakers ?: speakers, modelPackages)
    }
    private fun renderSpeakers(speakers: List<PlacedSpeaker>, modelPackages: List<SpeakerModelPackage>) {
        val packageById = modelPackages.associateBy { it.id }

        val activeIds = speakers.mapTo(mutableSetOf()) { it.id }
        val removedIds = speakerMeshAssetsById.keys.filterNot { it in activeIds }
        removedIds.forEach { speakerId ->
            speakerMeshAssetsById.remove(speakerId)?.let { destroyAsset(it) }
            speakerMeshKindsById.remove(speakerId)
        }

        var loadedCount = 0
        speakers.forEach { spk ->
            val pkg = packageById[spk.modelPackageId] ?: packageById["generic"]
            val packagePath = pkg?.modelAssetPath ?: "speaker_models/${spk.modelPackageId}.glb"
            val existingKind = speakerMeshKindsById[spk.id]

            if (pkg != null) {
                val existingAsset = speakerMeshAssetsById[spk.id]
                if (existingAsset != null && existingKind == packagePath) {
                    applySpeakerMeshTransform(existingAsset, spk, pkg)
                    loadedCount++
                    return@forEach
                }

                speakerMeshAssetsById.remove(spk.id)?.let { destroyAsset(it) }
                val meshLoaded = loadSpeakerMesh(spk, pkg, packagePath)
                if (meshLoaded) {
                    speakerMeshKindsById[spk.id] = packagePath
                    loadedCount++
                } else {
                    // Mesh missing/unreadable -> draw fallback wireframe speaker for this item.
                    val fallbackData = SpeakersGlb.build(listOf(spk), modelPackages)
                    if (fallbackData != null) {
                        speakerMeshAssetsById[spk.id] = assetLoader.createAsset(ByteBuffer.wrap(fallbackData))?.also { asset ->
                            resourceLoader.loadResources(asset)
                            asset.releaseSourceData()
                            scene.addEntities(asset.entities)
                        } ?: run {
                            speakerMeshKindsById.remove(spk.id)
                            return@forEach
                        }
                        speakerMeshKindsById[spk.id] = "fallback"
                        loadedCount++
                    } else {
                        speakerMeshKindsById.remove(spk.id)
                    }
                }
            } else {
                speakerMeshAssetsById.remove(spk.id)?.let { destroyAsset(it) }
                val fallbackData = SpeakersGlb.build(listOf(spk), modelPackages)
                if (fallbackData != null) {
                    speakerMeshAssetsById[spk.id] = assetLoader.createAsset(ByteBuffer.wrap(fallbackData))?.also { asset ->
                        resourceLoader.loadResources(asset)
                        asset.releaseSourceData()
                        scene.addEntities(asset.entities)
                    } ?: run {
                        speakerMeshKindsById.remove(spk.id)
                        return@forEach
                    }
                    speakerMeshKindsById[spk.id] = "fallback"
                loadedCount++
                } else {
                    speakerMeshKindsById.remove(spk.id)
                }
            }
        }

        onSpeakerMeshStatsChanged(loadedCount, speakers.size)
    }

    private fun loadSpeakerMesh(spk: PlacedSpeaker, pkg: SpeakerModelPackage?, packagePath: String): Boolean {
        val explicitPath = pkg?.modelAssetPath
        val finalPath = explicitPath ?: packagePath

        val bytes = speakerAssetBytesCache.getOrPut(finalPath) {
            runCatching {
                context.assets.open(finalPath).use { it.readBytes() }
            }.getOrNull() ?: return false
        }

        val asset = assetLoader.createAsset(ByteBuffer.wrap(bytes)) ?: return false
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)
        applySpeakerMeshTransform(asset, spk, pkg)
        speakerMeshAssetsById[spk.id] = asset
        return true
    }

    private fun applySpeakerMeshTransform(asset: FilamentAsset, spk: PlacedSpeaker, pkg: SpeakerModelPackage?) {
        val tm = engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return

        val yaw = Math.toRadians(spk.panDeg.toDouble())
        val pitch = Math.toRadians(-spk.arrayAimDeg.toDouble())
        val cy = cos(yaw).toFloat()
        val syaw = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()

        val sx = (pkg?.cabinetWidthM ?: 1f).coerceAtLeast(0.1f)
        val sy = (pkg?.cabinetHeightM ?: 1f).coerceAtLeast(0.1f)
        val sz = (pkg?.cabinetDepthM ?: 1f).coerceAtLeast(0.1f)

        val tx = spk.x
        val ty = spk.heightM
        val tz = spk.z

        // Rotation = yaw(Y) * pitch(local Z), then non-uniform scale by package dimensions.
        val c0x = cy * cp
        val c0y = sp
        val c0z = syaw * cp

        val c1x = -cy * sp
        val c1y = cp
        val c1z = -syaw * sp

        val c2x = -syaw
        val c2y = 0f
        val c2z = cy

        // Column-major 4x4 transform matrix.
        val m = floatArrayOf(
            sx * c0x, sx * c0y, sx * c0z, 0f,
            sy * c1x, sy * c1y, sy * c1z, 0f,
            sz * c2x, sz * c2y, sz * c2z, 0f,
            tx,     ty,      tz,     1f
        )
        tm.setTransform(instance, m)
    }

    /** Move the gold listener marker whenever the ViewModel position changes. */
    fun updateListener(listener: ListenerPos?) {
        listenerAsset?.let { destroyAsset(it) }
        listenerAsset = null
        val data = ListenerGlb.build(listener) ?: return
        listenerAsset = assetLoader.createAsset(ByteBuffer.wrap(data))?.also { asset ->
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
        }
    }

    /** Draw or clear the aim rays. Cheap enough to rebuild whenever aiming changes. */
    fun updateReceiverAngular(report:com.droidacoustic.pro.prediction.ReceiverAngularReport?,selectedId:String="listener") {
        invalidateReceiverMarkers()
        receiverAngularAsset?.let {destroyAsset(it)};receiverAngularAsset=null
        val data=ReceiverAngularGlb.build(report,selectedId) ?: return
        receiverAngularAsset=assetLoader.createAsset(ByteBuffer.wrap(data))?.also {asset->
            resourceLoader.loadResources(asset);asset.releaseSourceData();scene.addEntities(asset.entities)
        }
    }

    fun updateAimRays(
        speakers: List<PlacedSpeaker>,
        enabled: Boolean,
        venue: VenueGeometry,
        edges: Map<Int, CoverageEdges>
    ) {
        savedAimEnabled = enabled; savedAimVenue = venue; savedAimEdges = edges
        aimRaysAsset?.let { destroyAsset(it) }
        aimRaysAsset = null
        if (!enabled) return
        val data = AimRaysGlb.build(
            speakers,
            edges = edges,
            venueWidthM = venue.widthM,
            venueDepthM = venue.depthM,
            venueHeightM = venue.wallHeightM
        ) ?: return
        aimRaysAsset = assetLoader.createAsset(ByteBuffer.wrap(data))?.also { asset ->
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
        }
    }

    /** Iso-level lines over the coverage map. */
    fun updateContours(cells: List<HeatCell>, thresholds: List<Float>, emphasisDb: Float?, geometry: PreparedAudienceGeometry?) {
        contourAsset?.let { destroyAsset(it) }
        contourAsset = null
        if (thresholds.isEmpty() || cells.isEmpty()) return
        val data = ContoursGlb.build(cells, thresholds, emphasisDb, geometry) ?: return
        contourAsset = assetLoader.createAsset(ByteBuffer.wrap(data))?.also { asset ->
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
        }
    }

    /** Swap the surface mesh whenever computed coverage changes. */
    fun updateHeatmap(
        cells: List<HeatCell>,
        minDb: Float? = null,
        maxDb: Float? = null,
        bandStepDb: Float? = null,
        bandReferenceDb: Float? = null,
        geometry: PreparedAudienceGeometry? = null
    ) {
        heatmapAsset?.let { destroyAsset(it) }
        heatmapAsset = null
        val renderCells = cells
        val lo = minDb ?: renderCells.minOfOrNull { it.splDb } ?: return
        val hi = maxDb ?: renderCells.maxOfOrNull { it.splDb } ?: return
        val data = HeatmapGlb.build(renderCells, lo, hi, bandStepDb, bandReferenceDb, geometry) ?: return
        heatmapAsset = assetLoader.createAsset(ByteBuffer.wrap(data))?.also { asset ->
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private fun destroyAsset(asset: FilamentAsset) {
        asset.entities.forEach { scene.removeEntity(it) }
        assetLoader.destroyAsset(asset)
    }

    private fun configureScene() {
        scene.skybox = Skybox.Builder().color(0.04f, 0.04f, 0.09f, 1.0f).build(engine)
    }

    private fun configureView() {
        view.scene  = scene
        view.camera = camera
        view.multiSampleAntiAliasingOptions =
            com.google.android.filament.View.MultiSampleAntiAliasingOptions().apply { enabled = true }
    }

    private fun attachSurfaceCallback(surfaceView: SurfaceView) {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                viewportW = width; viewportH = height
                view.viewport = Viewport(0, 0, width, height)
                camera.setProjection(fovDegrees, width.toDouble() / height.toDouble(), 0.1, 200.0, Camera.Fov.VERTICAL)
                manipulator.setViewport(width, height)
                applyReceiverFrame()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = null
            }
        })
    }

    private fun attachTouchListener(surfaceView: SurfaceView) {
        // Single tap → ray cast to floor → place speaker
        gestureDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                // Exactly ONE tap callback may be wired here.
                //
                // GestureDetector fires onSingleTapUp the moment a finger
                // lifts, and then fires onSingleTapConfirmed again roughly
                // 300 ms later once it knows no second tap is coming. Both
                // were previously routed to the same handler, so every single
                // tap placed two speakers stacked at the same coordinate —
                // visible as one marker but counted twice.
                //
                // onSingleTapUp is the one kept: placement should feel
                // immediate, and nothing here handles double taps.
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if(receiverMarkerPickingEnabled && receiverMarkerReport!=null) {
                        val receiver=receiverMarkerLayoutProvider?.invoke()?.pick(com.droidacoustic.pro.geometry.GizmoPoint2(e.x,e.y),selectedReceiverMarkerId)
                        if(receiver!=null) {
                            if(onReceiverMarkerSelected(receiver)) selectedReceiverMarkerId=receiver
                            return true
                        }
                    }
                    val hit = pickTarget(e.x, e.y)
                    if (hit != null) {
                        onPickTarget(hit)
                    } else {
                        val ray=viewportRay(e.x,e.y)
                        if (ray==null || !onSceneRayTap(ray))
                            rayFloorIntersect(e.x, e.y)?.let { (wx, wz) -> onFloorTap(wx, wz) }
                    }
                    return true
                }
            })

        scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    // Kill any active strafe grab so it doesn't fight the zoom
                    isScaling = true
                    if (isTwoFingerGrabActive) {
                        manipulator.grabEnd()
                        isTwoFingerGrabActive = false
                    }
                    return true
                }
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    // Use the actual pinch focus point, not the screen centre
                    manipulator.scroll(
                        detector.focusX.toInt(), detector.focusY.toInt(),
                        (1f - detector.scaleFactor) * 600f
                    )
                    return true
                }
                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isScaling = false
                }
            })

        surfaceView.setOnTouchListener { _, event ->
            gestureDetector!!.onTouchEvent(event)
            scaleDetector!!.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    manipulator.grabBegin(event.x.toInt(), event.y.toInt(), false)
                    isTwoFingerGrabActive = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // End the single-finger orbit; two-finger pan/pinch starts lazily
                    manipulator.grabEnd()
                    isTwoFingerGrabActive = false
                }
                MotionEvent.ACTION_MOVE -> when {
                    event.pointerCount == 1 -> {
                        if (!isScaling) manipulator.grabUpdate(event.x.toInt(), event.y.toInt())
                    }
                    else -> if (!isScaling) {
                        // Two-finger pan (strafe) — start grab lazily on first MOVE
                        val mx = ((event.getX(0) + event.getX(1)) / 2f).toInt()
                        val my = ((event.getY(0) + event.getY(1)) / 2f).toInt()
                        if (!isTwoFingerGrabActive) {
                            manipulator.grabBegin(mx, my, true)
                            isTwoFingerGrabActive = true
                        }
                        manipulator.grabUpdate(mx, my)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    manipulator.grabEnd()
                    isTwoFingerGrabActive = false
                    if (event.pointerCount == 2) {
                        val ri = if (event.actionIndex == 0) 1 else 0
                        manipulator.grabBegin(event.getX(ri).toInt(), event.getY(ri).toInt(), false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    manipulator.grabEnd()
                    isTwoFingerGrabActive = false
                    isScaling = false
                }
            }
            true
        }
    }

    private fun loadFloorGrid() {
        gridAsset?.let { destroyAsset(it) }
        gridAsset = null
        gridAsset = assetLoader.createAsset(ByteBuffer.wrap(FloorGridGlb.build(currentVenueGeometry.widthM, currentVenueGeometry.depthM)))?.also { asset ->
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
        }
    }

    /** Shared projection uses the same camera pose, field of view and clipping planes as Filament. */
    private fun viewportRay(sx: Float,sy: Float): ViewportRay? = ViewportRay.perspective(
        sx.toDouble(),sy.toDouble(),viewportW.toDouble(),viewportH.toDouble(),
        PickPoint3D(eyeArr[0].toDouble(),eyeArr[1].toDouble(),eyeArr[2].toDouble()),
        PickPoint3D(targetArr[0].toDouble(),targetArr[1].toDouble(),targetArr[2].toDouble()),
        PickPoint3D(upArr[0].toDouble(),upArr[1].toDouble(),upArr[2].toDouble()),fovDegrees)

    /** Same shared projection for face, object and floor taps. */
    private fun screenRay(sx: Float,sy: Float): DoubleArray? = viewportRay(sx,sy)?.let {
        doubleArrayOf(it.origin.x,it.origin.y,it.origin.z,it.direction.x,it.direction.y,it.direction.z)
    }

    /**
     * Nearest pick target under a screen point, or null.
     *
     * A floor-plane hit test cannot select anything mounted above the floor:
     * in perspective, the ray through a flown cabinet lands on the floor well
     * beyond the position that cabinet actually occupies. This tests the ray
     * against each target's 3D position instead.
     */
    private fun pickTarget(sx: Float, sy: Float): Int? {
        if (pickTargets.isEmpty()) return null
        val ray = screenRay(sx, sy) ?: return null
        val ox = ray[0]; val oy = ray[1]; val oz = ray[2]
        val dx = ray[3]; val dy = ray[4]; val dz = ray[5]

        var bestId: Int? = null
        var bestT = Double.MAX_VALUE

        pickTargets.forEach { t ->
            val vx = t.x - ox; val vy = t.y - oy; val vz = t.z - oz
            val along = vx * dx + vy * dy + vz * dz       // projection onto ray
            if (along <= 0) return@forEach                 // behind the camera
            val cx = vx - along * dx
            val cy = vy - along * dy
            val cz = vz - along * dz
            val perp = sqrt(cx * cx + cy * cy + cz * cz)   // distance to ray
            // Scale tolerance with distance so far-away boxes stay tappable.
            val tolerance = t.radius + along * 0.02
            if (perp <= tolerance && along < bestT) {
                bestT = along
                bestId = t.id
            }
        }
        return bestId
    }

    private fun rayFloorIntersect(sx: Float, sy: Float): Pair<Float, Float>? {
        val ray=viewportRay(sx,sy) ?: return null
        if (abs(ray.direction.y)<1e-6) return null
        val t=-ray.origin.y/ray.direction.y
        if (t<0) return null
        val hX=(ray.origin.x+t*ray.direction.x).toFloat()
        val hZ=(ray.origin.z+t*ray.direction.z).toFloat()
        val halfW = (currentVenueGeometry.widthM * 0.5f).coerceAtLeast(4f)
        val halfD = (currentVenueGeometry.depthM * 0.5f).coerceAtLeast(4f)
        val clampedX = hX.coerceIn(-halfW, halfW)
        val clampedZ = hZ.coerceIn(-halfD, halfD)
        return clampedX to clampedZ
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val sc = swapChain ?: return
        manipulator.getLookAt(eyeArr, targetArr, upArr)
        camera.lookAt(
            eyeArr[0].toDouble(),    eyeArr[1].toDouble(),    eyeArr[2].toDouble(),
            targetArr[0].toDouble(), targetArr[1].toDouble(), targetArr[2].toDouble(),
            upArr[0].toDouble(),     upArr[1].toDouble(),     upArr[2].toDouble()
        )
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }
}

