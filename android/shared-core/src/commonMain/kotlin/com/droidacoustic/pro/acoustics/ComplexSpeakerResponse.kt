package com.droidacoustic.pro.acoustics

import kotlinx.serialization.json.*
import com.droidacoustic.pro.geometry.*
import kotlin.math.*

/** Measured complex pressure / 20 µPa at 1 m for a common reference drive.
 * Positive delay rotates positively, matching the existing engine phasors.
 * Measurement propagation must already be removed; no frequency interpolation is invented. */
class ComplexSpeakerResponse private constructor(val provenance:String, val referenceDrive:String,
    private val patterns: Map<Int,Pair<PolarGrid,PolarGrid>>, private val serialized:String, val maximumInterpolationGapHz: Double?) {
    private val frequencyKeys=patterns.keys.sorted().toIntArray()
    /** Index of the first measured frequency >= hz; immutable and allocation-free. */
    private fun upperIndex(hz:Double):Int {
        var low=0;var high=frequencyKeys.size
        while(low<high) {
            val mid=(low+high) ushr 1
            if(frequencyKeys[mid]<hz) low=mid+1 else high=mid
        }
        return low
    }
    fun at(hz:Int,az:Float,el:Float):ComplexPressure? {
        val pair=patterns[hz] ?: return null
        val r=pair.first.at(az,el);val i=pair.second.at(az,el)
        return if(r.isFinite() && i.isFinite()) ComplexPressure(r.toDouble(),i.toDouble()) else null
    }
    fun supportsLinearBand(lowHz:Double,highHz:Double):Boolean {
        val gap=maximumInterpolationGapHz ?: return false
        if(!lowHz.isFinite() || !highHz.isFinite() || lowHz>=highHz) return false
        val index=upperIndex((lowHz+highHz)*.5)
        if(index<=0 || index>=frequencyKeys.size) return false
        val lo=frequencyKeys[index-1];val hi=frequencyKeys[index]
        return lowHz>=lo && highHz<=hi && hi-lo<=gap
    }
    fun bandAt(hz:Double,az:Float,el:Float):ComplexPressure? {
        val gap=maximumInterpolationGapHz ?: return null
        if(!hz.isFinite()) return null
        val index=upperIndex(hz)
        if(index==frequencyKeys.size) return null
        val hi=frequencyKeys[index]
        val lo=if(hi.toDouble()==hz) hi else if(index>0) frequencyKeys[index-1] else return null
        val a=at(lo,az,el) ?: return null
        if(lo==hi) return a
        if(hi-lo>gap) return null
        val b=at(hi,az,el) ?: return null
        val t=(hz-lo)/(hi-lo)
        return ComplexPressure(a.real+(b.real-a.real)*t,a.imaginary+(b.imaginary-a.imaginary)*t)
    }
    /** Read each angular endpoint once instead of repeating both grids for each
     * interior frequency. Preserve bandAt's exact measured-endpoint behavior. */
    fun linearBandBasis(lowHz:Double,highHz:Double,az:Float,el:Float,out:DoubleArray,offset:Int):Boolean {
        require(offset>=0 && offset<=out.size-4)
        val gap=maximumInterpolationGapHz ?: return false
        if(!lowHz.isFinite() || !highHz.isFinite() || lowHz>=highHz) return false
        val index=upperIndex((lowHz+highHz)*.5)
        if(index<=0 || index>=frequencyKeys.size) return false
        val lo=frequencyKeys[index-1];val hi=frequencyKeys[index]
        if(lowHz<lo || highHz>hi || hi-lo>gap) return false
        val a=at(lo,az,el) ?: return false
        val b=at(hi,az,el) ?: return false
        val t=(lowHz-lo)/(hi-lo);val u=(highHz-lo)/(hi-lo)
        val ar=if(lowHz==lo.toDouble()) a.real else a.real+(b.real-a.real)*t
        val ai=if(lowHz==lo.toDouble()) a.imaginary else a.imaginary+(b.imaginary-a.imaginary)*t
        val br=if(highHz==hi.toDouble()) b.real else a.real+(b.real-a.real)*u
        val bi=if(highHz==hi.toDouble()) b.imaginary else a.imaginary+(b.imaginary-a.imaginary)*u
        out[offset]=ar;out[offset+1]=ai;out[offset+2]=br-ar;out[offset+3]=bi-ai
        return true
    }
    /** Conservative angular variation over every frequency of a declared interpolation interval.
     * Bounds complex deviation from the cap centre, and uses the exact minimum distance of
     * the centre's Cartesian frequency segment from zero. No between-probe lobe is assumed absent. */
    fun variationDb(lowHz:Double,highHz:Double,az:Float,el:Float,radius:Float):Float {
        val edges=(listOf(lowHz,highHz)+patterns.keys.filter {it>lowHz && it<highHz}.map {it.toDouble()}).distinct().sorted()
        fun deviation(hz:Double):Double? {
            val centre=bandAt(hz,az,el) ?: return null
            val index=upperIndex(hz)
            if(index==frequencyKeys.size) return null
            val hi=frequencyKeys[index]
            val lo=if(hi.toDouble()==hz) hi else if(index>0) frequencyKeys[index-1] else return null
            val t=if(lo==hi) 0.0 else (hz-lo)/(hi-lo)
            fun range(real:Boolean):Pair<Double,Double>? {
                fun part(key:Int)=patterns.getValue(key).let {if(real) it.first else it.second}.componentRange(az,el,radius)
                val a=part(lo) ?: return null;val b=part(hi) ?: return null
                return (a.first*(1-t)+b.first*t) to (a.second*(1-t)+b.second*t)
            }
            val r=range(true) ?: return null;val i=range(false) ?: return null
            val dr=max(abs(r.first-centre.real),abs(r.second-centre.real))
            val di=max(abs(i.first-centre.imaginary),abs(i.second-centre.imaginary))
            return sqrt(dr*dr+di*di)
        }
        var bound=0.0
        for((lo,hi) in edges.zipWithNext()) {
            val a=bandAt(lo,az,el) ?: return Float.NaN;val b=bandAt(hi,az,el) ?: return Float.NaN
            val delta=max(deviation(lo) ?: return Float.NaN,deviation(hi) ?: return Float.NaN)
            if(delta==0.0) continue
            val dx=b.real-a.real;val dy=b.imaginary-a.imaginary;val d=dx*dx+dy*dy
            val t=if(d==0.0) 0.0 else (-(a.real*dx+a.imaginary*dy)/d).coerceIn(0.0,1.0)
            val minimum=sqrt((a.real+t*dx).pow(2)+(a.imaginary+t*dy).pow(2))
            if(minimum<=delta) return Float.POSITIVE_INFINITY
            bound=max(bound,20*log10((minimum+delta)/(minimum-delta)))
        }
        return bound.toFloat()
    }
    fun angularDomainAt(hz:Int,az:Float,el:Float)=patterns[hz]?.first?.angularDomainAt(az,el)
    internal fun angularGrids(): Map<Int,PolarGrid> = patterns.mapValues {it.value.first}
    internal fun angularSupports(): Map<Int,AngularGridSupport> = angularGrids().mapValues {it.value.angularSupport}
    fun frequencies(): Set<Int> = patterns.keys.toSet()
    fun toJsonString()=serialized
    companion object {
        const val CONVENTION="positive-delay-propagation-removed-1m-v1"
        fun fromJsonString(text:String):ComplexSpeakerResponse {
            require(text.length<=32*1024*1024)
            val root=Json.parseToJsonElement(text).jsonObject
            val version=root.getValue("version").jsonPrimitive.int
            require(version in 1..2)
            val gap=if(version==2) root.getValue("maximumInterpolationGapHz").jsonPrimitive.double.also {require(it.isFinite() && it>0 && it<=2000)} else null
            if(version==2) require(root.getValue("frequencyInterpolation").jsonPrimitive.content=="cartesian-linear-hz")
            require(root.getValue("convention").jsonPrimitive.content==CONVENTION) {"Unsupported speaker phase reference"}
            val provenance=root.getValue("provenance").jsonPrimitive.content
            val drive=root.getValue("referenceDrive").jsonPrimitive.content
            require(provenance.isNotBlank() && drive.isNotBlank())
            val rows=root.getValue("patterns").jsonArray;require(rows.size in 1..4088)
            val patterns=rows.map {item ->val p=item.jsonObject
                val hz=p.getValue("frequency").jsonPrimitive.int;require(hz>0)
                val az=p.getValue("azimuths").jsonArray.map {it.jsonPrimitive.float}
                val el=p.getValue("elevations").jsonArray.map {it.jsonPrimitive.float}
                val domain=p["angularDomain"]?.let {AngularDomain.parse(it.toString(),az,el,true)}
                fun grid(key:String)=PolarGrid(az,el,p.getValue(key).jsonArray.map {row->row.jsonArray.map {it.jsonPrimitive.float}},domain).also {require(it.valid)}
                hz to (grid("real") to grid("imaginary"))
            }
            require(patterns.map {it.first}.distinct().size==patterns.size)
            return ComplexSpeakerResponse(provenance,drive,patterns.toMap(),root.toString(),gap)
        }
    }
}

data class PhaseSample(val degrees:Double?,val reason:String?=null) {
    init {require(degrees==null || degrees.isFinite() && degrees>=-180 && degrees<=180)}
}

data class PhaseFieldResult(val pressure:ComplexPressure?,val phase:PhaseSample)

object PhaseResponse {
    const val LEGACY_CONVENTION="coherent-direct-tone-positive-delay-v1"
    const val SPECULAR_CONVENTION="coherent-direct-specular-tone-positive-delay-v2"
    const val CONVENTION="coherent-direct-specular-ground-tone-positive-delay-v3"
    fun wrapped(degrees:Double):Double=((degrees+180)%360+360)%360-180
    fun at(sources:List<SoundSource>,x:Float,y:Float,z:Float,hz:Int,air:Atmosphere,
        boundaries:BoundaryGeometry?=null,work:BoundaryWork=BoundaryWork(),floor:FloorReflection?=null):PhaseSample =
        calculate(sources,x,y,z,hz,air,boundaries,work,floor).phase

    fun calculate(sources:List<SoundSource>,x:Float,y:Float,z:Float,hz:Int,air:Atmosphere,
        boundaries:BoundaryGeometry?=null,work:BoundaryWork=BoundaryWork(),floor:FloorReflection?=null):PhaseFieldResult {
        fun missing(reason:String)=PhaseFieldResult(null,PhaseSample(null,reason))
        if(sources.isEmpty()) return missing("No sources")
        if(boundaries?.hasScatteringModel(work,groundOwnsPlane=floor!=null)==true) return missing("Diffuse scattering has no complex phase model")
        if(floor!=null && (y<0f || sources.any {it.y<0f})) return missing("Ground phase requires sources and receiver on or above y=0")
        val paths=SpecularPaths.prepare(sources,BoundaryPoint(x.toDouble(),y.toDouble(),z.toDouble()),air,floor,boundaries,work)
        var sum=ComplexPressure.ZERO;var amplitudeSum=0.0
        var referenceDrive:String?=null
        for(path in paths) {
            work.check()
            val material=BoundaryMaterial.reflectionMaterial(path.materials,hz,path.incidenceDeg)
            val alpha=if(path.floorPath) floor!!.absorptionAt(hz) else null
            if(alpha!=null && (!alpha.isFinite() || alpha !in 0f..1f)) return missing("Invalid ground absorption")
            val reflectionGain=if(alpha!=null) sqrt(1.0-alpha) else sqrt(material?.energyPartition(hz,path.incidenceDeg)?.specular ?: 1.0)
            // Zero-energy arrivals need neither speaker nor material phase samples.
            if(reflectionGain==0.0) continue
            if(path.floorPath && floor!!.reflectionPhase==null && alpha!=0f) return missing("Global ground reflection phase is missing")
            if(material!=null && material.reflectionPhase==null && material!=BoundaryMaterial.IDEAL_RIGID)
                return missing("Reflection phase missing for material: ${material.label}")
            val s=sources[path.emitter]
            val drive=s.radiator.complexReferenceDrive() ?: return missing("Missing complex speaker data")
            if(referenceDrive!=null && referenceDrive!=drive) return missing("Incompatible reference drives")
            referenceDrive=drive
            if(s.eqDb(hz)!=0f) return missing("EQ phase is unavailable")
            val intrinsic=(s.radiator.complexAt(hz,path.az,path.el)
                ?.times(s.filters.at(hz.toDouble()) ?: return missing("Filter frequency exceeds Nyquist")))
                ?: return missing("Complex data missing at this frequency or direct/reflected direction")
            val scale=10.0.pow((s.gainDb-air.absorptionDbPerMetre(hz.toDouble())*path.distance)/20)/path.distance * reflectionGain * if(s.inverted) -1 else 1
            // Materials use exp(+iwt); conjugate their coefficient for positive-delay source phasors.
            val materialPhase=(if(path.floorPath) floor?.reflectionPhase else material?.reflectionPhase)?.degreesAt(hz.toDouble(),path.incidenceDeg) ?: 0.0
            val angle=2*PI*hz*path.time-materialPhase*PI/180
            val real=scale*(intrinsic.real*cos(angle)-intrinsic.imaginary*sin(angle))
            val imaginary=scale*(intrinsic.real*sin(angle)+intrinsic.imaginary*cos(angle))
            sum+=ComplexPressure(real,imaginary);amplitudeSum+=sqrt(real*real+imaginary*imaginary)
        }
        if(!sum.power.isFinite() || amplitudeSum==0.0 || sqrt(sum.power)<=amplitudeSum*1e-9)
            return PhaseFieldResult(sum,PhaseSample(null,"Phase undefined at zero energy or cancellation"))
        return PhaseFieldResult(sum,PhaseSample(wrapped(atan2(sum.imaginary,sum.real)*180/PI)))
    }
}
