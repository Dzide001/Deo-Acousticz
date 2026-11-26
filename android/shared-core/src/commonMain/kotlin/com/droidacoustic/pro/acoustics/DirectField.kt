package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.*

import kotlin.math.pow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One radiating element, placed and aimed.
 *
 * A single box is one of these. A line array is one per box, each at its own
 * position on the curve and pointing its own way - the array is never treated
 * as a single lumped source, because what an array does is exactly the sum of
 * what its boxes do.
 *
 * @param group which loudspeaker in the scene this element belongs to.
 * @param yawDeg heading in plan: 0 faces +X, positive turns toward +Z.
 * @param axisElevationDeg where the acoustic axis points, above horizontal.
 * @param eqDb gain applied in a given third-octave band, on top of [gainDb].
 */
class SoundSource(
    val group: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    yawDeg: Float,
    axisElevationDeg: Float,
    val radiator: Radiator,
    val gainDb: Float = 0f,
    val delayS: Double = 0.0,
    val inverted: Boolean = false,
    val eqDb: (dataHz: Int) -> Float = { 0f },
    val filters: DspFilterChain = DspFilterChain()
) {
    // Orthonormal frame of the box: forward along the axis, right in plan, up.
    private val fx: Double; private val fy: Double; private val fz: Double
    private val rx: Double; private val rz: Double
    private val ux: Double; private val uy: Double; private val uz: Double

    init {
        val yaw = radians(yawDeg.toDouble())
        val el = radians(axisElevationDeg.toDouble())
        fx = cos(el) * cos(yaw); fy = sin(el); fz = cos(el) * sin(yaw)
        rx = -sin(yaw); rz = cos(yaw)
        ux = -sin(el) * cos(yaw); uy = cos(el); uz = -sin(el) * sin(yaw)
    }

    /**
     * Direction of a point as the box sees it.
     *
     * A true rotation into the box's frame. Subtracting the pan from the
     * bearing and the tilt from the elevation separately - which is what this
     * replaced - is only right for a listener near the axis: for a box tilted
     * well down and a listener well off to the side it reads the balloon in the
     * wrong place.
     *
     * @return distance in metres, azimuth and elevation in degrees.
     */
    internal fun toward(px: Float, py: Float, pz: Float, out: DoubleArray) {
        val dx = (px - x).toDouble()
        val dy = (py - y).toDouble()
        val dz = (pz - z).toDouble()
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-6) {
            out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
            return
        }
        val lf = dx * fx + dy * fy + dz * fz
        val lr = dx * rx + dz * rz
        val lu = dx * ux + dy * uy + dz * uz
        out[0] = d
        out[1] = degrees(atan2(lr, lf))
        out[2] = degrees(asin((lu / d).coerceIn(-1.0, 1.0)))
    }
}

/**
 * A reflecting floor at y = 0.
 *
 * Off by default: a direct-field prediction is free-field unless explicitly
 * enabled. When enabled the
 * floor is a mirror - each element gains an image arrival, travelling the longer
 * path and weakened by whatever the floor absorbed in that band.
 */
class FloorReflection(val reflectionPhase: ReflectionPhase? = null, val absorptionAt: (dataHz: Int) -> Float)

/**
 * Direct-field level at a point: every element's arrival, summed with phase.
 *
 * ## How a band is summed
 *
 * At a single frequency, arrivals add as complex pressures and the result combs
 * violently with position. Nobody listens at a single frequency. The level in a
 * band is the average of that squared sum across the band, and for arrivals of
 * amplitude a and travel time t it has an exact closed form - for each pair,
 *
 *     2 a_i a_j cos(2 pi f_c tau) sinc(pi B tau),    tau = t_i - t_j
 *
 * with f_c the centre and B the width of the band. The sinc is the whole story
 * of coherence: two boxes of the same array, with tau a fraction of a
 * millisecond, sum in pressure; a delay tower forty milliseconds away sums in
 * power; everything in between falls out of the same expression. There is no
 * blend factor to choose, and narrowing the analysis bandwidth brings the comb
 * back, as it should.
 *
 * Declared band-capable complex data and DSP filters use converged frequency subdivision.
 * Legacy magnitude-only data retains geometric/electrical delay phase.
 * Segment integration is exact for constant amplitudes and flat spectral density;
 * directional gain and air absorption are evaluated at each segment's centre.
 */
data class MissingBand(val group: Int, val dataHz: Int)

/** No numerical level is published when any contributing source lacks required data. */
data class BandPrediction(val meanSquarePressure: Double?, val missingBands: Set<MissingBand>,
    val diffuseMeanSquarePressure: Double? = 0.0) {
    val levelDb: Float? get() = meanSquarePressure?.let(DirectField::toDb)
}

/** frozenDiffuseBands enables request-local reuse only for immutable snapshot emitters. */
class DirectField(val atmosphere: Atmosphere = Atmosphere(),frozenDiffuseBands: List<Int>?=null) {
    private val diffuseBands=frozenDiffuseBands?.distinct()?.toList()
    private data class DiffuseKey(val geometry: BoundaryGeometry,val source: SoundSource,val receiver: BoundaryPoint,val ground: Boolean,val complexIntervals: List<Bands.Segment>)
    private data class PreparedDiffuse(val samples: List<DiffuseSurfaceSample>,val missing: Set<MissingBand>)
    private val diffuseLock=GeometryCacheLock()
    private val diffuseCache=linkedMapOf<DiffuseKey,PreparedDiffuse>()
    private data class ProbeKey(val source: SoundSource,val point: BoundaryPoint,val hz: Double)
    private val probeCache=linkedMapOf<ProbeKey,Float>()
    private val incidentCache=if(diffuseBands!=null) DiffuseIncidentCache() else null
    private fun diffuseLevel(source: SoundSource,point: BoundaryPoint,hz: Int,az: Float,el: Float,work: BoundaryWork,frequencyHz:Double=hz.toDouble()): Float {
        val key=ProbeKey(source,point,frequencyHz)
        if(diffuseBands!=null) diffuseLock.withLock {probeCache[key]}?.let {return it}
        work.check()
        val level=if(source.radiator.hasComplexBands()) source.radiator.complexBandAt(frequencyHz,az,el)?.power
            ?.let {if(it==0.0) -300f else (10*log10(it)).toFloat()} ?: Float.NaN
            else source.radiator.levelDb(hz,az,el)
        if(diffuseBands!=null) diffuseLock.withLock {
            if(probeCache.size>=262144) probeCache.remove(probeCache.keys.first())
            probeCache[key]=level
        }
        return level
    }



    /**
     * Mean-square pressure at a point, in the units where 0 dB SPL is 1.
     *
     * @param segments the analysis band, see [Bands.segments]. The result is the
     *   width-weighted mean: fixed total reference drive with flat power spectral
     * density over the requested band. Amplitude is held constant per segment.
     * @param coherent false sums independent emitters (or groups) as power; each emitter
     * remains coherent with its own reflected arrival.
     * @param groupLossDb extra loss per loudspeaker, indexed by [SoundSource.group] -
     *   an obstruction in the path, for instance.
     */
    fun predictBand(
        sources: List<SoundSource>,
        px: Float,
        py: Float,
        pz: Float,
        segments: List<Bands.Segment>,
        coherent: Boolean = true,
        groupLossDb: FloatArray? = null,
        floor: FloorReflection? = null,
        correlateByGroup: Boolean = false,
        boundaries: BoundaryGeometry? = null,
        boundaryWork: BoundaryWork = BoundaryWork(),
        pathLossDb: ((SoundSource, BoundaryPoint?, Int)->Float)? = null
    ): BandPrediction {
        val complex=sources.any {it.radiator.hasComplexBands()}
        val illumination=if(complex && boundaries!=null && sources.indices.any {i -> (0 until i).any {j -> sources[i].radiator.hasComplexBands() && sources[j].radiator.hasComplexBands() && (coherent || correlateByGroup && sources[i].group==sources[j].group)}})
            CoherentDiffuse(sources,BoundaryPoint(px.toDouble(),py.toDouble(),pz.toDouble()),boundaries,atmosphere,segments,coherent,correlateByGroup,floor!=null,boundaryWork,incidentCache=incidentCache) else null
        if(!complex && sources.all {it.filters.isEmpty}) return predictBandSamples(sources,px,py,pz,segments,coherent,groupLossDb,floor,correlateByGroup,boundaries,boundaryWork,pathLossDb,illumination=illumination)
        val missing=sources.flatMap {s->segments.map {MissingBand(s.group,it.dataHz)}}.toSet()
        // Unknown cross-source phase is permitted only when the user explicitly sums independent groups.
        // Elements within one correlated array still require mutually compatible response data.
        for(i in sources.indices) for(j in 0 until i) {
            val a=sources[i];val b=sources[j]
            if((coherent || correlateByGroup && a.group==b.group) && (a.radiator.hasComplexBands() || b.radiator.hasComplexBands()) &&
                (!a.radiator.hasComplexBands() || !b.radiator.hasComplexBands() || a.radiator.complexReferenceDrive()==null ||
                 a.radiator.complexReferenceDrive()!=b.radiator.complexReferenceDrive())) return BandPrediction(null,missing,null)
        }
        val knots=(sources.flatMap {it.radiator.complexBandKnots().map(Int::toDouble)} +
            sources.flatMap {it.filters.filters}.flatMap {f -> listOf(-8,-4,-2,-1,0,1,2,4,8).map {f.frequencyHz+it*f.frequencyHz/f.q}}).distinct().sorted()
        val base=segments.flatMap {seg ->
            val edges=listOf(seg.f1)+knots.filter {it>seg.f1 && it<seg.f2}.map {it.toDouble()}+listOf(seg.f2)
            edges.zipWithNext {lo,hi->Bands.Segment(seg.dataHz,lo,hi)}
        }
        // Use the separate quadrature only with declared measured interpolation. Custom
        // complex responses retain the existing analytic finite-band refinement path.
        val integrateDiffuse=illumination!=null && sources.filter {it.radiator.hasComplexBands()}.all {source->
            base.all {source.radiator.supportsLinearComplexBand(it.f1,it.f2)}
        }
        val diffuseMissing=mutableSetOf<MissingBand>()
        val integratedDiffuse=if(integrateDiffuse) illumination!!.integratedPower(base,diffuseMissing,groupLossDb,pathLossDb) else null
        if(integrateDiffuse && integratedDiffuse==null) return BandPrediction(null,if(diffuseMissing.isEmpty()) missing else diffuseMissing,null)
        // Seed around narrow filter features; propagation-delay oscillations remain analytically integrated.
        val localDiffuseCache=mutableMapOf<DiffuseKey,PreparedDiffuse>()
        var divisions=4
        var previous:Double?=null;var previousDiffuse:Double?=null;var stable=0
        repeat(12) {
            boundaryWork.check()
            val count=base.sumOf {divisions}
            if(count>16384) return BandPrediction(null,missing,null)
            val refined=base.flatMap {seg ->
                val n=divisions
                (0 until n).map {i->Bands.Segment(seg.dataHz,seg.f1+seg.widthHz*i/n,seg.f1+seg.widthHz*(i+1)/n)}
            }
            val result=predictBandSamples(sources,px,py,pz,refined,coherent,groupLossDb,floor,correlateByGroup,boundaries,boundaryWork,pathLossDb,segments,localDiffuseCache,illumination,integratedDiffuse)
            val power=result.meanSquarePressure ?: return result
            val diffuse=result.diffuseMeanSquarePressure ?: return result
            if(previous!=null && previousDiffuse!=null && abs(power-previous!!)<=max(1e-10,abs(power)*1e-5) &&
                abs(diffuse-previousDiffuse!!)<=max(1e-10,abs(diffuse)*1e-5)) stable++ else stable=0
            if(stable>=2) return result
            previous=power;previousDiffuse=diffuse;divisions*=2
        }
        return BandPrediction(null,missing,null)
    }

    private fun predictBandSamples(
        sources: List<SoundSource>,
        px: Float,
        py: Float,
        pz: Float,
        segments: List<Bands.Segment>,
        coherent: Boolean = true,
        groupLossDb: FloatArray? = null,
        floor: FloorReflection? = null,
        correlateByGroup: Boolean = false,
        boundaries: BoundaryGeometry? = null,
        boundaryWork: BoundaryWork = BoundaryWork(),
        pathLossDb: ((SoundSource, BoundaryPoint?, Int)->Float)? = null,
        complexDiffuseIntervals:List<Bands.Segment> = emptyList(),
        localDiffuseCache:MutableMap<DiffuseKey,PreparedDiffuse>? = null,
        illumination:CoherentDiffuse? = null,
        integratedDiffuse:Double? = null
    ): BandPrediction {
        require(segments.all { it.f1 > 0.0 && it.f2 > it.f1 && it.f2.isFinite() })
        require(diffuseBands==null || segments.all {it.dataHz in diffuseBands}) {"Band is outside the frozen diffuse sampling context"}
        require(floor == null || (py >= 0f && sources.all { it.y >= 0f })) {
            "Ground reflection requires sources and receivers on or above y=0"
        }
        val n = sources.size
        if (n == 0 || segments.isEmpty()) return BandPrediction(0.0, emptySet())
        val receiver=BoundaryPoint(px.toDouble(),py.toDouble(),pz.toDouble())
        val scratch=DoubleArray(3)
        fun sourcePoint(s:SoundSource)=BoundaryPoint(s.x.toDouble(),s.y.toDouble(),s.z.toDouble())
        val arrivals=SpecularPaths.prepare(sources,receiver,atmosphere,floor,boundaries,boundaryWork)
        val missing = mutableSetOf<MissingBand>()
        data class DiffuseArrival(val emitter: Int,val sample: DiffuseSurfaceSample,val distance: Double,
            val az: Float,val el: Float,val receiverFactor: Double)
        val diffuseArrivals=mutableListOf<DiffuseArrival>()
        if(boundaries!=null && illumination==null) for(i in sources.indices) {
            val source=sources[i]
            val requestedBands=segments.map {it.dataHz}.toSet()
            val complexSource=source.radiator.hasComplexBands()
            val intervals=if(complexSource) complexDiffuseIntervals.ifEmpty {segments} else emptyList()
            val bands=if(complexSource) requestedBands.toList() else diffuseBands ?: requestedBands.toList()
            val probes=if(complexSource) intervals.flatMap {seg ->
                (listOf(seg.f1,seg.f2)+source.radiator.complexBandKnots().filter {it>seg.f1 && it<seg.f2}.map {it.toDouble()})
                    .map {seg.dataHz to it}
            }.distinct() else bands.map {it to it.toDouble()}
            val probeMissing=mutableSetOf<MissingBand>()
            val probeScratch=DoubleArray(3)
            val profile: (BoundaryPoint,Set<BoundaryMaterial>,Float)->List<Float> = {point,materials,incidence ->
                source.toward(point.x.toFloat(),point.y.toFloat(),point.z.toFloat(),probeScratch)
                probes.map {(hz,frequency) ->
                    val scattered=BoundaryMaterial.reflectionMaterial(materials,hz,incidence)?.energyPartition(hz,incidence)?.scattered ?: 0.0
                    if(scattered==0.0 || MissingBand(source.group,hz) in probeMissing) -300f else {
                        val level=diffuseLevel(source,point,hz,probeScratch[1].toFloat(),probeScratch[2].toFloat(),boundaryWork,frequency)
                        if(!level.isFinite()) {probeMissing+=MissingBand(source.group,hz);-300f}
                        else level+(10*log10(scattered)).toFloat()
                    }
                }
            }
            val cacheKey=DiffuseKey(boundaries,source,receiver,floor!=null,intervals)
            val prepared=localDiffuseCache?.get(cacheKey) ?: (if(diffuseBands!=null) diffuseLock.withLock {diffuseCache[cacheKey]} else null) ?: run {
                val bound: (List<BoundaryPoint>)->Float? = {points ->
                    val centre=BoundaryPoint(points.sumOf {it.x}/3,points.sumOf {it.y}/3,points.sumOf {it.z}/3)
                    val dx=centre.x-source.x;val dy=centre.y-source.y;val dz=centre.z-source.z
                    val length=sqrt(dx*dx+dy*dy+dz*dz)
                    val radius=points.maxOf {p ->
                        val x=p.x-source.x;val y=p.y-source.y;val z=p.z-source.z
                        kotlin.math.acos(((x*dx+y*dy+z*dz)/(sqrt(x*x+y*y+z*z)*length)).coerceIn(-1.0,1.0))*180/PI
                    }.toFloat().let {(it+1e-5f).coerceAtMost(180f)}
                    source.toward(centre.x.toFloat(),centre.y.toFloat(),centre.z.toFloat(),probeScratch)
                    if(complexSource && probeMissing.isNotEmpty()) 0f else if(complexSource) intervals.map {seg ->
                        boundaryWork.check()
                        source.radiator.complexBandVariationDb(seg.f1,seg.f2,probeScratch[1].toFloat(),probeScratch[2].toFloat(),radius)
                            ?: Float.POSITIVE_INFINITY
                    }.maxOrNull() else bands.mapNotNull {hz ->
                        boundaryWork.check()
                        source.radiator.variationDb(hz,probeScratch[1].toFloat(),probeScratch[2].toFloat(),radius)
                    }.maxOrNull()
                }
                val samples=boundaries.diffuseSamples(sourcePoint(source),boundaryWork,receiver=receiver,levelAt=profile,excludeGround=floor!=null,variationBound=if(complexSource || source.radiator.hasVariationBounds()) bound else null)
                PreparedDiffuse(samples,probeMissing.toSet()).also {value ->
                    localDiffuseCache?.set(cacheKey,value)
                    if(diffuseBands!=null) diffuseLock.withLock {
                        while(diffuseCache.isNotEmpty() && (diffuseCache.size>=32 || diffuseCache.values.sumOf {it.samples.size}+samples.size>65536))
                            diffuseCache.remove(diffuseCache.keys.first())
                        diffuseCache[cacheKey]=value
                    }
                }
            }
            missing+=prepared.missing.filter {it.dataHz in requestedBands}
            for(sample in prepared.samples) {
                boundaryWork.check()
                // The global y=0 floor owns its entire response when enabled.
                if(floor!=null && abs(sample.point.y)<1e-6 && abs(sample.normalTowardSource.y)>1-1e-10) continue
                val factor=sample.receiverFactor(receiver,MIN_DISTANCE_M)
                // Receiver shadow clipping already established visibility for the entire patch.
                if(factor==0.0) continue
                source.toward(sample.point.x.toFloat(),sample.point.y.toFloat(),sample.point.z.toFloat(),scratch)
                val dx=receiver.x-sample.point.x;val dy=receiver.y-sample.point.y;val dz=receiver.z-sample.point.z
                diffuseArrivals+=DiffuseArrival(i,sample,sample.sourceDistance+sqrt(dx*dx+dy*dy+dz*dz),
                    scratch[1].toFloat(),scratch[2].toFloat(),factor)
            }
        }
        val m=arrivals.size

        val amp = DoubleArray(m)
        val phase = DoubleArray(m)
        val phaseSlope = DoubleArray(m)
        val correlationIds=mutableMapOf<Int,Int>()
        val arrivalGroups=IntArray(m) {i ->
            val key=if(coherent) 0 else if(correlateByGroup) sources[arrivals[i].emitter].group else arrivals[i].emitter
            correlationIds.getOrPut(key) {correlationIds.size}
        }
        val arrivalSum=ArrivalBandSum(DoubleArray(m) {arrivals[it].time},arrivalGroups)
        val sumWork:((Int)->Unit)?=if(boundaries!=null) boundaryWork::check else null
        // Equal-delay correlated paths can be combined before the finite-band pair sum.
        // This avoids subtracting enormous powers to recover a cancellation null, and
        // prevents roundoff at that null from driving pointless frequency refinement.
        // Only exact delays/phases/slopes qualify; nearby paths retain their sinc term.
        val equalDelayPrevious=IntArray(m) {-1}
        for(i in 0 until m) for(j in i-1 downTo 0) {
            if(boundaries!=null) boundaryWork.check()
            val sameEmitter=arrivals[i].emitter==arrivals[j].emitter
            val sameGroup=correlateByGroup && sources[arrivals[i].emitter].group==sources[arrivals[j].emitter].group
            if(arrivals[i].time==arrivals[j].time && (coherent || sameEmitter || sameGroup)) {
                equalDelayPrevious[i]=j;break
            }
        }
        // Split at measured phase knots so frequency-dependent phase is integrated exactly
        // for the piecewise-linear phase model, rather than frozen at a band's centre.
        val phaseKnots=(arrivals.flatMap {it.materials.flatMap {m -> (m.reflectionPhase?.frequencies() ?: emptyList()).onEach {boundaryWork.check()}}} + (floor?.reflectionPhase?.frequencies() ?: emptyList())).distinct().sorted()
        val phaseSegments=if(phaseKnots.isEmpty()) segments else segments.flatMap {seg ->
            val edges=listOf(seg.f1)+phaseKnots.filter {boundaryWork.check(); it>seg.f1 && it<seg.f2}.map {it.toDouble()}+listOf(seg.f2)
            edges.zipWithNext {lo,hi -> Bands.Segment(seg.dataHz,lo,hi)}
        }
        var total = 0.0
        var diffuseTotal = 0.0
        val coherentDiffusePowers=if(integratedDiffuse==null) illumination?.bandPowers(phaseSegments,missing,groupLossDb,pathLossDb) else null
        for ((segmentIndex,seg) in phaseSegments.withIndex()) {
            val alpha = atmosphere.absorptionDbPerMetre(seg.centreHz)
            val reflected = floor?.let { sqrt((1f - it.absorptionAt(seg.dataHz)).coerceIn(0f, 1f).toDouble()) }

            for (k in 0 until m) {
                if (boundaries!=null) boundaryWork.check()
                val arrival=arrivals[k]; val s=sources[arrival.emitter]
                // A fully absorbed or fully scattered specular path contributes no pressure and needs no polar sample.
                val material=BoundaryMaterial.reflectionMaterial(arrival.materials,seg.dataHz,arrival.incidenceDeg)
                val materialGain=sqrt(material?.energyPartition(seg.dataHz,arrival.incidenceDeg)?.specular ?: 1.0)
                val measuredPhase=if(arrival.floorPath) floor?.reflectionPhase else material?.reflectionPhase
                phase[k]=(measuredPhase?.degreesAt(seg.centreHz,arrival.incidenceDeg) ?: 0.0)*PI/180.0
                phaseSlope[k]=if(measuredPhase==null) 0.0 else
                    (measuredPhase.degreesAt(seg.f2,arrival.incidenceDeg)-measuredPhase.degreesAt(seg.f1,arrival.incidenceDeg))*PI/180.0/seg.widthHz
                if ((arrival.floorPath && reflected == 0.0) || materialGain==0.0) {
                    amp[k] = 0.0
                    continue
                }
                val intrinsic=if(s.radiator.hasComplexBands()) s.radiator.complexBandAt(seg.centreHz,arrival.az,arrival.el) else null
                val filter=s.filters.at(seg.centreHz)
                if(filter==null || (s.radiator.hasComplexBands() && (intrinsic==null || s.eqDb(seg.dataHz)!=0f ||
                    (arrival.floorPath && floor?.reflectionPhase==null && reflected!=1.0) ||
                    (material!=null && material.reflectionPhase==null && material!=BoundaryMaterial.IDEAL_RIGID)))) {
                    missing+=MissingBand(s.group,seg.dataHz);amp[k]=0.0;continue
                }
                val transfer=(intrinsic ?: ComplexPressure(1.0,0.0))*filter
                if(transfer.power==0.0) {amp[k]=0.0;continue}
                phase[k]-=atan2(transfer.imaginary,transfer.real)
                val level = if(intrinsic!=null) (10*log10(transfer.power)).toFloat()
                    else s.radiator.levelDb(seg.dataHz, arrival.az, arrival.el)+(10*log10(filter.power)).toFloat()
                if (!level.isFinite()) {
                    missing += MissingBand(s.group, seg.dataHz)
                    amp[k] = 0.0
                    continue
                }
                var db = level + s.gainDb + s.eqDb(seg.dataHz) -
                    20.0 * log10(arrival.distance) - alpha * arrival.distance
                if (groupLossDb != null && s.group in groupLossDb.indices) db -= groupLossDb[s.group]
                if (pathLossDb!=null) db-=pathLossDb(s,arrival.bounce,seg.dataHz)
                var a = 10.0.pow(db / 20.0)*materialGain
                if (arrival.floorPath) a *= reflected ?: 0.0
                amp[k] = if (s.inverted) -a else a
            }

            for(i in m-1 downTo 0) {
                if(amp[i]==0.0) continue
                var j=equalDelayPrevious[i]
                while(j>=0) {
                    if(phase[i]==phase[j] && phaseSlope[i]==phaseSlope[j]) {
                        amp[j]+=amp[i];amp[i]=0.0;break
                    }
                    j=equalDelayPrevious[j]
                }
            }
            val p=arrivalSum.power(amp,phase,phaseSlope,seg.centreHz,seg.widthHz,
                checkWork=sumWork)
            // Diffuse paths are energy contributions, independent of coherent specular phase.
            var diffusePower=integratedDiffuse ?: coherentDiffusePowers?.get(segmentIndex) ?: 0.0
            for(arrival in diffuseArrivals) {
                boundaryWork.check();val source=sources[arrival.emitter];val sample=arrival.sample
                val material=BoundaryMaterial.reflectionMaterial(sample.materials,seg.dataHz,sample.incidenceDeg) ?: continue
                val scattered=material.energyPartition(seg.dataHz,sample.incidenceDeg).scattered
                if(scattered==0.0) continue
                if(source.radiator.hasComplexBands()) {
                    val intrinsic=source.radiator.complexBandAt(seg.centreHz,arrival.az,arrival.el)
                    if(intrinsic==null || source.eqDb(seg.dataHz)!=0f) {missing+=MissingBand(source.group,seg.dataHz);continue}
                    if(intrinsic.power==0.0) continue
                }
                val level=diffuseLevel(source,sample.point,seg.dataHz,arrival.az,arrival.el,boundaryWork,seg.centreHz)
                if(!level.isFinite()) {missing+=MissingBand(source.group,seg.dataHz);continue}
                val filter=source.filters.at(seg.centreHz)
                if(filter==null) {missing+=MissingBand(source.group,seg.dataHz);continue}
                if(filter.power==0.0) continue
                var db=level+source.gainDb+source.eqDb(seg.dataHz)+10*log10(filter.power)-alpha*arrival.distance
                if(groupLossDb!=null && source.group in groupLossDb.indices) db-=groupLossDb[source.group]
                if(pathLossDb!=null) db-=pathLossDb(source,sample.point,seg.dataHz)
                // Source inverse-square spreading is already in the intercepted solid angle.
                diffusePower+=10.0.pow(db/10.0)*scattered*arrival.receiverFactor
            }
            diffuseTotal += diffusePower * seg.widthHz
            total += (max(p, 0.0)+diffusePower) * seg.widthHz
        }
        return BandPrediction(if (missing.isEmpty()) total / segments.sumOf { it.widthHz } else null, missing,
            if(missing.isEmpty()) diffuseTotal / segments.sumOf {it.widthHz} else null)

    }

    /** Compatibility numeric API: NaN means unavailable data, not zero acoustic power. */
    fun bandPower(
        sources: List<SoundSource>, px: Float, py: Float, pz: Float,
        segments: List<Bands.Segment>, coherent: Boolean = true,
        groupLossDb: FloatArray? = null, floor: FloorReflection? = null,
        correlateByGroup: Boolean = false,
        boundaries: BoundaryGeometry? = null, boundaryWork: BoundaryWork = BoundaryWork(),
        pathLossDb: ((SoundSource, BoundaryPoint?, Int)->Float)? = null
    ): Double = predictBand(sources, px, py, pz, segments, coherent, groupLossDb,
        floor, correlateByGroup, boundaries, boundaryWork, pathLossDb).meanSquarePressure ?: Double.NaN

    /** [bandPower] as a level in dB SPL, floored at [FLOOR_DB]. */
    fun bandLevelDb(
        sources: List<SoundSource>,
        px: Float,
        py: Float,
        pz: Float,
        segments: List<Bands.Segment>,
        coherent: Boolean = true,
        groupLossDb: FloatArray? = null,
        floor: FloorReflection? = null,
        correlateByGroup: Boolean = false,
        boundaries: BoundaryGeometry? = null, boundaryWork: BoundaryWork = BoundaryWork(),
        pathLossDb: ((SoundSource, BoundaryPoint?, Int)->Float)? = null
    ): Float = toDb(bandPower(sources, px, py, pz, segments, coherent, groupLossDb, floor, correlateByGroup,
        boundaries,boundaryWork,pathLossDb))

    companion object {
        /** Inside this the inverse-square law stops meaning anything. */
        const val MIN_DISTANCE_M = 0.1

        /**
         * Display floor for valid, very low power (including exact cancellation).
         * Missing measured data is represented separately by BandPrediction and
         * must never be mapped to this value.
         */
        const val FLOOR_DB = 0f

        fun toDb(power: Double): Float =
            if (power <= 1.0) FLOOR_DB else (10.0 * log10(power)).toFloat()
    }
}
