package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.statistics.*
import com.droidacoustic.pro.scene.ClfData
import com.droidacoustic.pro.scene.VenueGeometry
import kotlin.math.*
import kotlinx.serialization.json.*

/** Factory-owned response: no caller collections, mutable polar arrays or live callbacks escape into a job. */
class FrozenResponse private constructor(private val radiator: Radiator,private val angular: FrozenAngularData?=null,private val angularIssue:String="CUSTOM_UNASSESSED") {
    internal fun angularProbes(segment:Bands.Segment,az:Float,el:Float)=angular?.probes(segment,az,el) ?: listOf(AngularProbe("source",segment.dataHz,null,angularIssue))
    internal fun radiator(): Radiator = radiator
    companion object {
        fun measured(data: ClfData, dispersion: Boolean): FrozenResponse {
            val bounds = mutableMapOf<Int,(Float,Float,Float)->Float>()
            val grids=mutableMapOf<Int,PolarGrid>()
            val patterns = data.patterns.associate { p ->
                val grid = PolarGrid(p.azimuths.toList(), p.elevations.toList(), p.spl.map { it.toList() },p.angularDomain)
                grids[p.frequencyHz]=grid
                bounds[p.frequencyHz] = grid::variationDb
                p.frequencyHz to { az: Float, el: Float -> grid.at(az, el) }
            }
            val radiator=MeasuredRadiator(data.sensitivityDbByBand.toMap(), patterns,
                data.measurementGrid, data.calibration, dispersion, bounds, data.complexResponse)
            return FrozenResponse(radiator,FrozenAngularData(grids.toMap(),data.measurementGrid,data.complexResponse,radiator,dispersion))
        }
        /** RadialDirectivity owns copied, validated curves; no live callback enters a job. */
        fun custom(response: RadialDirectivity) = FrozenResponse(response)
        fun missing() = FrozenResponse(Radiator { _, _, _ -> Float.NaN },angularIssue="MISSING_SPEAKER_ASSET")
        fun generic(level: Float, packageId: String, array: Boolean, dispersion: Boolean): FrozenResponse {
            val synthetic = SyntheticRadiator(level, SyntheticRadiator.horizontalHalfDeg(packageId)) {
                if (array) SyntheticRadiator.arrayElementVerticalHalfDeg(it)
                else SyntheticRadiator.pointSourceVerticalHalfDeg(packageId)
            }
            return FrozenResponse(if (dispersion) synthetic else Radiator { _, _, _ -> level },angularIssue=if(dispersion) "GENERIC" else "BYPASSED")
        }
    }
}

class SourceSnapshot(
    val id: Int, val x: Float, val y: Float, val z: Float,
    private val yaw: Float, poses: List<ArrayGeometry.Pose>,
    private val steer: Float, private val taper: Float,
    private val response: FrozenResponse, private val gainDb: Float = 0f,
    private val delayS: Double = 0.0, private val inverted: Boolean = false,
    eqDb: Map<Int, Float> = emptyMap(), private val filters: DspFilterChain = DspFilterChain(),
    internal val comparisonData: ComparisonSourceData? = null
) {
    private val poses = poses.toList()
    private val eq = eqDb.toMap()
    internal fun angularProbes(segment:Bands.Segment,az:Float,el:Float)=response.angularProbes(segment,az,el)
    internal fun comparisonGeometry() = buildJsonObject {
        put("x",x);put("y",y);put("z",z);put("yawDeg",yaw);put("steeringDeg",steer)
        put("poses",JsonArray(poses.map {p -> buildJsonArray {add(p.forwardM);add(p.upM);add(p.tiltDownDeg);add(p.alongM)} }))
    }
    internal fun comparisonPreset() = buildJsonObject {
        put("gainDbRelativeToModelReference",gainDb);put("delayS",delayS);put("inverted",inverted);put("taperDb",taper)
        put("eqDb",buildJsonObject {eq.entries.sortedBy {it.key}.forEach {(f,g)->put(f.toString(),g)}})
        put("filters",Json.parseToJsonElement(filters.toJsonString()))
    }
    internal fun effectiveDrive(): Pair<DriveUnit,Double>? = comparisonData?.calibration?.let {c ->
        c.driveValue?.let {value -> c.driveUnit to value*10.0.pow(gainDb/if(c.driveUnit==DriveUnit.WATTS) 10.0 else 20.0)}
    }
    internal fun emitters(groupIndex: Int, air: Atmosphere): List<SoundSource> = ArrayGeometry.sources(
        poses, groupIndex, x, y, z, yaw, steer, air.speedOfSound, response.radiator(), gainDb,
        delayS, inverted, taper, { hz -> DiscreteBandEq.gainDb(eq,hz) }, filters)
}

/** Fixed probes can carry additional frozen per-source losses. */
class ReceiverSnapshot(val x: Float, val y: Float, val z: Float,
    lossesByBand: Map<Int, Map<Int, Float>> = emptyMap()) {
    private val losses = lossesByBand.mapValues { it.value.toMap() }
    internal fun loss(band: Int, sourceId: Int) = losses[band]?.get(sourceId) ?: 0f
}

class PredictionRequest(
    val ticket: PredictionTicket,
    sources: List<SourceSnapshot>, receivers: List<ReceiverSnapshot>,
    val selectedBand: Int, bands: List<Int>, private val widthOct: Float,
    weightingDb: Map<Int, Float>, private val interference: Boolean,
    private val atmosphere: Atmosphere, floorAbsorption: Map<Int, Float>? = null,
    private val includeSourceLevels: Boolean = false,
    private val coveragePlan: CoveragePlan? = null,
    venue: VenueGeometry? = null,
    private val geometryCache: GeometryCache? = null,
    private val coverageTarget: CoverageTarget? = null,
    boundarySurfaces: List<AudienceSurface> = emptyList(),
    private val retainBandPowers: Boolean = includeSourceLevels,
    private val groundPhase: ReflectionPhase? = null,
    private val comparisonWeighting: String? = null, private val retainPhase: Boolean = retainBandPowers,
    private val comparisonSpectrum: BroadbandSourceSpectrum? = null, bandDriveFractions: Map<Int,Double> = emptyMap(),
    broadbandDrive: BroadbandDrive? = null
) {
    private val venue=venue?.copy(blocks=venue.blocks.toList())
    private val boundarySurfaces=boundarySurfaces.toList()
    private val sources = sources.toList()
    private val receivers = receivers.toList()
    private val bands = bands.toList()
    private val weighting = weightingDb.toMap()
    private val driveFractions=if(broadbandDrive!=null) {
        require(bandDriveFractions.isEmpty()) {"Choose one drive convention"}
        broadbandDrive.fractions(this.bands)
    } else bandDriveFractions.toMap()
    init {require(driveFractions.isEmpty() || ((!retainPhase || broadbandDrive!=null) && driveFractions.keys==bands.toSet() && driveFractions.values.all {it.isFinite() && it>=0} && driveFractions.values.any {it>0})) {"Explicit band drive requires complete finite factors and compatible phase capture"}}
    private val floor = floorAbsorption?.toMap()
    private val emitters = this.sources.flatMapIndexed { i, s -> s.emitters(i, atmosphere) }
    private val segments = (this.bands + selectedBand).distinct().associateWith { Bands.segments(it, widthOct) }
    /** Coverage count is known only after worker-side preprocessing; reading this never builds a mesh. */
    val receiverCount: Int? get() = if (coveragePlan == null) receivers.size else null
    val sourceCount get() = sources.size

    fun coverageGeometry(checkCancellation: () -> Unit = {}): PreparedAudienceGeometry =
        coveragePlan?.let { geometryCache?.prepare(it,checkCancellation) ?: it.prepare(checkCancellation) }
            ?: error("Coverage comparison requires an audience mesh")
    fun coverageConvention() = CoverageConvention(bands.toList(), widthOct,
        if (bands.size > 1) bands.associateWith { weighting[it] ?: 0f } else emptyMap())

    /** Metadata-only direct-ray inspection using the same mechanical frames as prediction. */
    fun receiverAngularSupport(targets:List<AngularReceiver>,checkCancellation:()->Unit={}):ReceiverAngularReport {
        val frozen=targets.toList()
        require(frozen.size<=65 && frozen.map {it.id}.distinct().size==frozen.size && frozen.all {it.id.isNotBlank() && it.name.isNotBlank()}) {"Support overlay allows listener plus 64 saved receivers"}
        require(frozen.all {p->listOf(p.position.x,p.position.y,p.position.z).all {it.isFinite() && kotlin.math.abs(it-it.toFloat().toDouble())<=1e-5}}) {"Invalid receiver or Float coordinate conversion exceeds 10 micrometres"}
        val groups=sources.mapIndexed {i,s->s to s.emitters(i,atmosphere)}
        var work=0
        val output=frozen.map {r->
            val rows=mutableListOf<ReceiverAngularRow>()
            groups.forEach {(s,elements)->elements.forEachIndexed {index,e->
                checkCancellation()
                val direction=DoubleArray(3);e.toward(r.position.x.toFloat(),r.position.y.toFloat(),r.position.z.toFloat(),direction)
                require(direction.all {it.isFinite()}) {"Invalid source geometry in support overlay"}
                for(segment in segments.getValue(selectedBand)) {
                    val probes=if(direction[0]<1e-6) listOf(AngularProbe("source",segment.dataHz,null,"COINCIDENT_RECEIVER"))
                        else s.angularProbes(segment,direction[1].toFloat(),direction[2].toFloat())
                    probes.forEach {p->
                        checkCancellation();require(++work<=20000) {"Receiver support overlay exceeds 20,000 rows; reduce receivers, emitters or band width"}
                        rows+=ReceiverAngularRow(s.id,index,segment.dataHz,direction[1].toFloat(),direction[2].toFloat(),direction[0],p)
                    }
                }
            }}
            ReceiverAngularAggregation.summary(r,rows)
        }
        return ReceiverAngularReport(ticket.sceneRevision,output,selectedBand,widthOct)
    }

    /** Capture targets supply locations/frequencies/IDs only, never source levels or scene conditions. */
    fun captureComparison(requestedSamples: List<SpeakerComparisonSample>, sceneRevision: Long,
        normalization: RelativeResponseNormalization? = null, levelConvention: String = "ABSOLUTE_SPL",
        broadbandCapture: Boolean = false,
        checkCancellation: () -> Unit = {}): ImportedComparison {
        val targets=requestedSamples.toList()
        val broadband=if(broadbandCapture) BroadbandDefinition.create(comparisonWeighting ?: error("Missing scene weighting label"),bands,widthOct,weighting,comparisonSpectrum) else null
        require(sources.isNotEmpty() && sources.all {it.comparisonData!=null}) {"Capture requires explicit scene source identities"}
        require(targets.isNotEmpty() && targets.size<=512 && targets.map {it.hz}.distinct().size<=64) {"Capture supports 1–512 samples and at most 64 frequencies"}
        require(targets.map {it.id}.distinct().size==targets.size && targets.all {it.id.isNotBlank() && it.hz.isFinite() && (if(broadband!=null) it.hz==0.0 else it.hz in 25.0..20000.0 && it.hz==it.hz.toInt().toDouble())}) {"Capture requires unique IDs and integer band centres from 25–20000 Hz"}
        targets.forEach {s -> listOf(s.x,s.y,s.z).forEach {v -> require(v.isFinite() && abs(v-v.toFloat().toDouble())<=1e-5) {"Receiver coordinate exceeds the 10 micrometre Float conversion bound"}} }
        require(levelConvention in listOf("ABSOLUTE_SPL","RELATIVE_TO_REFERENCE","RELATIVE_TO_ON_AXIS","BROADBAND_SPL","RELATIVE_BROADBAND_TO_REFERENCE")) {"Unsupported capture level convention"}
        require((levelConvention in listOf("BROADBAND_SPL","RELATIVE_BROADBAND_TO_REFERENCE"))== (broadband!=null)) {"Broadband capture mode and level convention disagree"}
        require((levelConvention in listOf("ABSOLUTE_SPL","BROADBAND_SPL"))== (normalization==null)) {"Choose an explicit reference receiver for relative capture"}
        normalization?.let {n ->
            require(n.method==if(broadband!=null) "weighted-broadband-level-difference" else "per-frequency-level-difference") {"Normalization method conflicts with capture metric"}
            listOf(n.xM,n.yM,n.zM).forEach {v -> require(abs(v-v.toFloat().toDouble())<=1e-5) {"Normalization receiver exceeds Float conversion bound"}}
            if(levelConvention=="RELATIVE_TO_ON_AXIS") {
                require(emitters.size==1) {"On-axis normalization requires one physical emitter; use RELATIVE_TO_REFERENCE for arrays"}
                val direction=DoubleArray(3);emitters.single().toward(n.xM.toFloat(),n.yM.toFloat(),n.zM.toFloat(),direction)
                require(direction[0]>=DirectField.MIN_DISTANCE_M && abs(direction[1])<=0.001 && abs(direction[2])<=0.001) {"Normalization receiver is not on the emitter's mechanical acoustic axis"}
            }
        }
        val boundaries=BoundaryGeometry.prepare(boundarySurfaces,BoundaryWork(checkCancellation),venue)
        val drives=sources.map {it.effectiveDrive()}
        val commonDrive=drives.firstOrNull()?.takeIf {drive -> drives.all {it==drive}}
        val model=JsonArray(sources.map {it.comparisonData!!.json()}).toString()
        val preset=buildJsonObject {
            put("sources",JsonArray(sources.map {it.comparisonPreset()}));put("coherentBetweenGroups",interference)
            put("arrayAndSelfPathsCoherent",true);put("driveBasis","effective flat gain; EQ and filters recorded separately")
        }.toString()
        val geometry=buildJsonObject {
            put("frame","world metres; X/Z plan, Y up; source yaw 0 along +X toward +Z")
            put("sources",JsonArray(sources.map {it.comparisonGeometry()}));put("boundarySha256",boundaries.comparisonFingerprint(checkCancellation))
        }.toString()
        val frequency=buildJsonObject {
            put("mode",if(broadband==null) "unweighted-band-mean-square; fixed-total-drive-flat-PSD" else "weighted-sum-independent-band-powers");put("widthOct",widthOct)
            put("bands",JsonArray((if(broadband==null) targets.map {it.hz.toInt()}.distinct().sorted() else broadband.bandCentersHz).map {hz -> buildJsonObject {
                put("hz",hz);put("segments",JsonArray(Bands.segments(hz,widthOct).map {seg -> buildJsonArray {add(seg.dataHz);add(seg.f1);add(seg.f2)} }))
            }}))
        }.toString()
        val air=buildJsonObject {put("temperatureC",atmosphere.temperatureC);put("humidityPct",atmosphere.humidityPct);put("pressureKPa",atmosphere.pressureKPa)}.toString()
        val ground=buildJsonObject {
            put("enabled",floor!=null);floor?.let {put("absorption",buildJsonObject {it.entries.sortedBy {it.key}.forEach {(hz,a)->put(hz.toString(),a)}})}
            groundPhase?.let {put("phase",Json.parseToJsonElement(it.toJsonString()))}
            put("model","first-order; no diffraction/transmission; invalid below-ground geometry disables global image per receiver")
        }.toString()
        val conditions=SpeakerComparisonConditions(model,preset,geometry,frequency,levelConvention,air,ground,
            commonDrive?.first ?: DriveUnit.UNKNOWN,commonDrive?.second,normalization?.canonical(),broadband?.canonical())
        val levels=mutableMapOf<String,Double?>()
        val referenceLevels=mutableMapOf<Int,Double?>()
        val bandDiagnostics=mutableListOf<JsonObject>()
        var referenceBandDiagnostics: JsonObject?=null
        (if(broadband==null) targets.groupBy {it.hz.toInt()} else mapOf(0 to targets)).forEach {(hz,points) ->
            checkCancellation()
            val receivers=points.map {ReceiverSnapshot(it.x.toFloat(),it.y.toFloat(),it.z.toFloat())} +
                (normalization?.let {listOf(ReceiverSnapshot(it.xM.toFloat(),it.yM.toFloat(),it.zM.toFloat()))} ?: emptyList())
            val request=PredictionRequest(ticket,sources,receivers,
                if(broadband==null) hz else bands.first(),if(broadband==null) listOf(hz) else bands,widthOct,
                if(broadband==null) emptyMap() else weighting,interference,atmosphere,floor,venue=venue,geometryCache=geometryCache,
                boundarySurfaces=boundarySurfaces,groundPhase=groundPhase,retainBandPowers=broadband!=null,retainPhase=false,
                bandDriveFractions=if(broadband?.sourceSpectrum!=null) broadband.driveFractions else emptyMap())
            val output=request.calculate(checkCancellation)
            val referenceDb=normalization?.let {
                output.totalPowerAt(points.size).takeIf {p -> p.isFinite() && p>0}?.let {p -> 10*log10(p)}
            }
            if(normalization!=null) referenceLevels[hz]=referenceDb
            if(normalization!=null && broadband!=null) referenceBandDiagnostics=buildJsonObject {
                output.bandPowersAt(points.size).forEach {(f,v)->put(f.toString(),v?.let(::JsonPrimitive) ?: JsonNull)}
            }
            points.forEachIndexed {i,p -> val power=output.totalPowerAt(i)
                if(broadband!=null) bandDiagnostics+=buildJsonObject {
                    put("sampleId",p.id);put("bands",buildJsonObject {output.bandPowersAt(i).forEach {(f,v)->put(f.toString(),v?.let(::JsonPrimitive) ?: JsonNull)}})
                }
                val absolute=power.takeIf {it.isFinite() && it>0}?.let {10*log10(it)}
                levels[p.id]=if(normalization==null) absolute else
                    if(absolute!=null && referenceDb!=null && referenceDb>=normalization.minimumReferenceLevelDb) absolute-referenceDb else null
            }
        }
        val samples=targets.map {it.copy(levelDb=levels[it.id])}
        return EngineComparisonDocuments.encode(conditions,samples,sources.any {it.comparisonData!!.synthetic},sceneRevision,checkCancellation,normalization,referenceLevels,broadband,bandDiagnostics,referenceBandDiagnostics)
    }

    fun calculate(checkCancellation: () -> Unit = {}): PredictionOutput {
        val geometryWork=BoundaryWork(checkCancellation)
        val boundaries=BoundaryGeometry.prepare(boundarySurfaces,geometryWork,venue).takeUnless {it.isEmpty}
        val geometry = coveragePlan?.let { geometryCache?.prepare(it,checkCancellation) ?: it.prepare(checkCancellation) }
        val receivers = geometry?.receivers()?.map { ReceiverSnapshot(it.x,it.y,it.z) } ?: this.receivers
        val field = DirectField(atmosphere,segments.filterKeys {driveFractions[it]!=0.0}.values.flatten().map {it.dataHz}.distinct())
        val missing=mutableSetOf<MissingSourceBand>()
        val unavailableBands=mutableSetOf<Int>()
        fun bandPower(receiver: ReceiverSnapshot, band: Int, boundaryWork:BoundaryWork, group: Int? = null): Double {
            val fraction=driveFractions[band] ?: 1.0
            if(fraction==0.0) return 0.0 // Explicitly unexcited; no missing response or diffuse work is needed.
            val selected = if (group == null) emitters else emitters.filter { it.group == group }
            val floorModel = floor?.takeIf { receiver.y >= 0f && selected.all { it.y >= 0f } }
                ?.let { alpha -> FloorReflection(groundPhase) { alpha[it] ?: 0f } }
            val prediction=field.predictBand(selected, receiver.x, receiver.y, receiver.z, segments.getValue(band),
                interference, floor=floorModel, correlateByGroup=true,
                boundaries=boundaries, boundaryWork=boundaryWork,
                pathLossDb=ReceiverPathLoss {emitter,hz ->
                    checkCancellation()
                    receiver.loss(hz,sources[emitter.group].id)
                })
            val power=prediction.meanSquarePressure ?: Double.NaN
            if(group==null) {
                if(!power.isFinite()) unavailableBands+=band
                prediction.missingBands.forEach {m->missing+=MissingSourceBand(sources[m.group].id,m.dataHz)}
            }
            return fraction*power
        }
        val bandPowers=if(retainBandPowers) bands.associateWith {DoubleArray(receivers.size)} else emptyMap()
        // Keep actual measured frequencies; never synthesize complex frequency interpolation.
        val phaseFrequencies=if(retainPhase) (bands + emitters.flatMap {it.radiator.complexFrequencies()}).distinct().sorted() else emptyList()
        require(!retainBandPowers || phaseFrequencies.size<=4096) {"Too many complex trace frequencies"}
        val phaseSamples=if(retainBandPowers) phaseFrequencies.associateWith {mutableListOf<PhaseSample>()} else emptyMap()
        val totalPowers = DoubleArray(receivers.size)
        val levels = FloatArray(receivers.size)
        val perSource = if (includeSourceLevels) Array(receivers.size) { FloatArray(sources.size) } else emptyArray()
        receivers.forEachIndexed { i, receiver ->
            checkCancellation()
            // Bound one receiver's complete spectrum (including source-level/phase traces).
            // Adding valid audience receivers must not exhaust an unrelated global counter.
            // Geometry preparation has its own budget; publication remains all-or-nothing.
            val boundaryWork=BoundaryWork(checkCancellation)
            if(retainBandPowers) phaseFrequencies.forEach {hz ->
                checkCancellation()
                phaseSamples.getValue(hz).add(when {
                    !interference && sources.size>1 -> PhaseSample(null,"Coherent source summation is disabled")
                    else -> PhaseResponse.at(emitters,receiver.x,receiver.y,receiver.z,hz,atmosphere,boundaries,boundaryWork,floor?.let {alpha -> FloorReflection(groundPhase) {alpha[it] ?: alpha.keys.minByOrNull {key -> abs(ln(key.toDouble()/it))}?.let {key -> alpha[key]} ?: Float.NaN}})
                })
            }
            val perBand = bands.map { band -> checkCancellation(); band to bandPower(receiver, band, boundaryWork) }
            perBand.forEach {(hz,power)->bandPowers[hz]?.set(i,power)}
            totalPowers[i] = when {
                perBand.any { !it.second.isFinite() } -> Double.NaN
                perBand.size == 1 -> perBand.single().second
                else -> perBand.sumOf { (hz, power) -> power*10.0.pow((weighting[hz] ?: 0f)/10.0) }
            }
            // Display flooring never enters coverage differences, which use totalPowers.
            levels[i] = if (totalPowers[i].isFinite()) DirectField.toDb(totalPowers[i]) else Float.NaN
            if (includeSourceLevels) sources.indices.forEach { group ->
                checkCancellation()
                perSource[i][group] = DirectField.toDb(bandPower(receiver, selectedBand, boundaryWork, group))
            }
        }
        val statistics = geometry?.let { CoverageStatisticsCalculator.calculate(it,levels,coverageTarget,checkCancellation) }
        val references=if(retainBandPowers) DoubleArray(receivers.size) {i ->
            val r=receivers[i]
            emitters.minOfOrNull {s ->
                sqrt((s.x-r.x).toDouble().pow(2)+(s.y-r.y).toDouble().pow(2)+(s.z-r.z).toDouble().pow(2))/atmosphere.speedOfSound+s.delayS
            } ?: 0.0
        } else doubleArrayOf()
        return PredictionOutput(ticket, levels, perSource, geometry, statistics,bandPowers,totalPowers,phaseSamples,references,
            PredictionValidity.summarize(levels,unavailableBands,missing,statistics))
    }
}

class PredictionOutput(val ticket: PredictionTicket, levels: FloatArray, sourceLevels: Array<FloatArray>,
    val geometry: PreparedAudienceGeometry? = null, val statistics: CoverageStatistics? = null,
    bandPowers: Map<Int,DoubleArray> = emptyMap(), totalPowers: DoubleArray = doubleArrayOf(), phases: Map<Int,List<PhaseSample>> = emptyMap(), delayReferencesS: DoubleArray = doubleArrayOf(),
    val validity: PredictionValidity = PredictionValidity.summarize(levels,statistics=statistics)) {
    private val references=delayReferencesS.copyOf()
    fun delayReferenceAt(receiver:Int):Double? = references.getOrNull(receiver)
    private val phaseSamples=phases.mapValues {it.value.toList()}
    fun phasesAt(receiver:Int):Map<Int,PhaseSample> = phaseSamples.mapValues {it.value[receiver]}
    private val totals=totalPowers.copyOf()
    fun totalPowerAt(receiver: Int): Double = totals[receiver]
    private val powers=bandPowers.mapValues {it.value.copyOf()}
    fun bandPowersAt(receiver: Int): Map<Int,Double?> = powers.mapValues {it.value[receiver].takeIf {p->p.isFinite()}}
    val receiverCount: Int get()=levels.size
    private val levels = levels.copyOf()
    private val sourceLevels = sourceLevels.map { it.copyOf() }
    fun levelAt(receiver: Int) = levels[receiver]
    fun sourceLevelAt(receiver: Int, source: Int) = sourceLevels[receiver][source]
}

fun interface PredictionRunner { suspend fun run(request: PredictionRequest): PredictionOutput }
