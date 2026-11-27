package com.droidacoustic.pro.acoustics

import kotlin.math.*

/** Electrical quantities are deliberately not interchangeable without a measured transfer. */
enum class DriveUnit { UNKNOWN, VOLTS_RMS, WATTS }

/** Levels must be free-field values at this distance (measurement air loss already removed). */
data class ReferenceCalibration(
    val distanceM: Double = 1.0,
    val driveUnit: DriveUnit = DriveUnit.UNKNOWN,
    val driveValue: Double? = null,
    val provenance: String = "Unverified imported reference"
) {
    init {
        require(distanceM.isFinite() && distanceM > 0)
        require(if (driveUnit == DriveUnit.UNKNOWN) driveValue == null
            else driveValue != null && driveValue.isFinite() && driveValue > 0)
    }
    val electricalReferenceKnown: Boolean get() = driveUnit != DriveUnit.UNKNOWN
    fun atOneMetre(levelDb: Float): Float = (levelDb + 20 * log10(distanceM)).toFloat()
    /** null means unsupported conversion, never an assumed 1 W or 2.83 V reference. */
    fun gainForDrive(unit: DriveUnit, value: Double): Float? {
        if (!electricalReferenceKnown || unit != driveUnit || !value.isFinite() || value <= 0) return null
        val factor = if (unit == DriveUnit.WATTS) 10 else 20
        return (factor * log10(value / driveValue!!)).toFloat()
    }
}

enum class MeasurementGrid(val thirdOctaveSteps: Int) { THIRD_OCTAVE(1), OCTAVE(3) }

/** Interpolate dB in log frequency only between adjacent declared measurement cells.
 * An absent interior cell, non-finite value or out-of-range frequency stays unavailable.
 */
class MeasuredBandTable<T>(samples: Map<Int, T>, private val grid: MeasurementGrid) {
    private val samples = samples.toList().sortedBy { it.first }.toMap()
    fun valueAt(hz: Int, evaluate: (T) -> Float): Float? {
        if (hz <= 0) return null
        samples[hz]?.let { return evaluate(it).takeIf(Float::isFinite) }
        // Nominal 31/32 and 63/64 aliases share a standardized frequency cell.
        fun cell(f: Int) = (3 * log2(f / 1000.0)).roundToInt()
        fun onGrid(f: Int): Boolean = f==31 || f==32 || f==63 || f==64 || (f > 0 && abs(3 * log2(f / 1000.0) - cell(f)) < 0.09)
        // Integer import aliases use the engine's existing base-two cell centre, consistently
        // for interpolation as well as exact alias lookup. Other measured frequencies stay intact.
        fun frequency(f: Int)=when(f) {31,32->31.25;63,64->62.5;else->f.toDouble()}
        val alias = samples.entries.firstOrNull { onGrid(it.key) && onGrid(hz) && cell(it.key) == cell(hz) }
        alias?.let { return evaluate(it.value).takeIf(Float::isFinite) }
        val low = samples.entries.lastOrNull { it.key < hz } ?: return null
        val high = samples.entries.firstOrNull { it.key > hz } ?: return null
        if (!onGrid(low.key) || !onGrid(high.key) || cell(high.key) - cell(low.key) != grid.thirdOctaveSteps) return null
        val a = evaluate(low.value).takeIf(Float::isFinite) ?: return null
        val b = evaluate(high.value).takeIf(Float::isFinite) ?: return null
        val t = ln(frequency(hz) / frequency(low.key)) / ln(frequency(high.key) / frequency(low.key))
        return (a + t * (b - a)).toFloat()
    }
}

/** Magnitude-only measured source. Neither missing sensitivity nor missing direction is generic. */
class MeasuredRadiator(
    levelsDb: Map<Int, Float>,
    patterns: Map<Int, (Float, Float) -> Float>,
    grid: MeasurementGrid = MeasurementGrid.THIRD_OCTAVE,
    val calibration: ReferenceCalibration = ReferenceCalibration(),
    private val useDirectivity: Boolean = true,
    variationBounds: Map<Int, (Float, Float, Float) -> Float> = emptyMap(),
    private val complexResponse: ComplexSpeakerResponse? = null
) : Radiator {
    override fun complexFrequencies()=if(useDirectivity) complexResponse?.frequencies().orEmpty() else emptySet()
    override fun supportsLinearComplexBand(lowHz:Double,highHz:Double)=useDirectivity && complexResponse?.supportsLinearBand(lowHz,highHz)==true
    override fun hasComplexBands()=complexResponse?.maximumInterpolationGapHz!=null
    override fun complexBandVariationDb(lowHz:Double,highHz:Double,az:Float,el:Float,radius:Float)=
        if(useDirectivity) complexResponse?.variationDb(lowHz,highHz,az,el,radius) else Float.NaN
    override fun complexBandKnots()=complexResponse?.frequencies().orEmpty()
    override fun complexBandAt(hz:Double,azimuthDeg:Float,elevationDeg:Float)=if(useDirectivity) complexResponse?.bandAt(hz,azimuthDeg,elevationDeg) else null
    override fun complexBandBasis(lowHz:Double,highHz:Double,az:Float,el:Float,out:DoubleArray,offset:Int)=
        useDirectivity && complexResponse?.linearBandBasis(lowHz,highHz,az,el,out,offset)==true
    override fun complexReferenceDrive()=if(useDirectivity) complexResponse?.referenceDrive else null
    override fun complexAt(hz:Int,azimuthDeg:Float,elevationDeg:Float)=if(useDirectivity) complexResponse?.at(hz,azimuthDeg,elevationDeg) else null
    private val bounded = useDirectivity && variationBounds.isNotEmpty()
    override fun hasVariationBounds() = bounded
    private val bounds = MeasuredBandTable(variationBounds, grid)
    override fun variationDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float, radiusDeg: Float): Float? =
        if (!useDirectivity) 0f else bounds.valueAt(dataHz) { it(azimuthDeg, elevationDeg, radiusDeg) }
    private val levels = MeasuredBandTable(levelsDb, grid)
    private val directions = MeasuredBandTable(patterns, grid)
    fun referenceLevelDb(hz: Int): Float? = levels.valueAt(hz) { it }?.let(calibration::atOneMetre)
    override fun levelDb(dataHz: Int, azimuthDeg: Float, elevationDeg: Float): Float {
        val level = referenceLevelDb(dataHz) ?: return Float.NaN
        val direction = if (useDirectivity) directions.valueAt(dataHz) { it(azimuthDeg, elevationDeg) }
            ?: return Float.NaN else 0f
        return level + direction
    }
}

/** Validated regular angular grid, cyclic azimuth and bounded elevation, shared by platforms. */
class PolarGrid(azimuths: List<Float>, elevations: List<Float>, rows: List<List<Float>>, private val declaredDomain: AngularDomain? = null) {
    private val elevations = elevations.toFloatArray()
    private val azimuths: FloatArray
    private val rows: Array<FloatArray>
    private val constantValue: Float?
    val valid: Boolean
    init {
        val shapeValid = azimuths.isNotEmpty() && elevations.isNotEmpty() &&
            rows.size == azimuths.size && rows.all { it.size == elevations.size && it.all(Float::isFinite) } &&
            azimuths.all(Float::isFinite) && elevations.all { it.isFinite() && it in -90f..90f } &&
            elevations.zipWithNext().all { (a, b) -> a < b }
        val sorted = if (shapeValid) azimuths.indices.map { i ->
            (((azimuths[i] % 360f) + 360f) % 360f) to rows[i].toList()
        }.sortedBy { it.first } else emptyList()
        valid = shapeValid && sorted.zipWithNext().all { (a,b) -> a.first != b.first || a.second == b.second }
        val unique = sorted.distinctBy { it.first }
        this.azimuths = unique.map { it.first }.toFloatArray()
        this.rows = unique.map { it.second.toFloatArray() }.toTypedArray()
        require(declaredDomain==null || declaredDomain.matches(this.azimuths.toList(),this.elevations.toList())) {"Angular declaration uses different grid axes"}
        constantValue=if(valid && this.rows.all {row->row.all {it==this.rows[0][0]}}) this.rows[0][0] else null
    }
    private val domain: AngularDomain by lazy {declaredDomain ?: AngularDomain.undeclared(this.azimuths.toList(),this.elevations.toList())}
    fun angularDomainAt(azimuth: Float,elevation: Float)=domain.at(azimuth,elevation,angularSupport.at(azimuth,elevation))
    fun angularDomainSummary()=domain.summary()
    /** Cached diagnostics stay outside the prediction hot path. */
    val angularSupport: AngularGridSupport by lazy {AngularGridSupport(this.azimuths.toList(),this.elevations.toList(),valid)}
    /** Bound bilinear interpolation over a rectangle enclosing a spherical cap.
     * Include clipped cell corners and internal knots, including the cyclic seam. */
    fun variationDb(azimuthDeg: Float, elevationDeg: Float, radiusDeg: Float): Float =
        componentRange(azimuthDeg,elevationDeg,radiusDeg)?.let {it.second-it.first} ?: Float.NaN
    fun componentRange(azimuthDeg: Float, elevationDeg: Float, radiusDeg: Float): Pair<Float,Float>? {
        if (!valid || !azimuthDeg.isFinite() || !elevationDeg.isFinite() || !radiusDeg.isFinite() || radiusDeg<0) return null
        val low=(elevationDeg-radiusDeg).coerceAtLeast(-90f)
        val high=(elevationDeg+radiusDeg).coerceAtMost(90f)
        if(low<elevations.first() || high>elevations.last()) return null
        constantValue?.let {return it to it}
        val span=if(radiusDeg>=90 || low<=-90 || high>=90) 180f else
            MathCap.longitudeRadius(elevationDeg,radiusDeg)
        val az=((azimuthDeg%360)+360)%360
        val left=az-span;val right=az+span
        var min=Float.POSITIVE_INFINITY;var max=Float.NEGATIVE_INFINITY
        fun sample(a:Float,e:Float) {val v=at(a,e);min=minOf(min,v);max=maxOf(max,v)}
        fun sampleAzimuth(a:Float) {
            sample(a,low);sample(a,high)
            for(e in elevations) if(e>low && e<high) sample(a,e)
        }
        sampleAzimuth(left);sampleAzimuth(right)
        for(a in azimuths) for(offset in -1..1) {
            val candidate=a+offset*360f
            if(candidate>left && candidate<right) sampleAzimuth(candidate)
        }
        return min to max
    }
    private fun floorIndex(axis:FloatArray,value:Float):Int {
        var lo=0;var hi=axis.size
        while(lo<hi) {val mid=(lo+hi) ushr 1;if(axis[mid]<=value) lo=mid+1 else hi=mid}
        return lo-1
    }
    fun at(azimuthDeg: Float, elevationDeg: Float): Float {
        if (!valid || !(azimuthDeg>=-Float.MAX_VALUE && azimuthDeg<=Float.MAX_VALUE) ||
            !(elevationDeg>=elevations.first() && elevationDeg<=elevations.last())) return Float.NaN
        constantValue?.let {return it}
        val az = ((azimuthDeg % 360f) + 360f) % 360f
        val a0 = floorIndex(azimuths,az).let { if (it < 0) azimuths.lastIndex else it }
        val a1 = (a0 + 1) % azimuths.size
        val span = (azimuths[a1] - azimuths[a0]).let { if (it <= 0) it + 360 else it }
        val into = (az - azimuths[a0]).let { if (it < 0) it + 360 else it }
        val ta = if (azimuths.size == 1) 0f else into / span
        val e0 = floorIndex(elevations,elevationDeg)
        val e1 = (e0 + 1).coerceAtMost(elevations.lastIndex)
        val te = if (e0 == e1) 0f else (elevationDeg - elevations[e0]) / (elevations[e1] - elevations[e0])
        val lo = rows[a0][e0] * (1 - te) + rows[a0][e1] * te
        val hi = rows[a1][e0] * (1 - te) + rows[a1][e1] * te
        return lo * (1 - ta) + hi * ta
    }
}

private object MathCap {
    fun longitudeRadius(elevation: Float,radius: Float): Float =
        (asin((sin(radius*PI/180)/cos(elevation*PI/180)).coerceIn(-1.0,1.0))*180/PI).toFloat()
}
