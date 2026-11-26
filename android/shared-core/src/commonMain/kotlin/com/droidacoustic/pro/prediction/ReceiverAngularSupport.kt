package com.droidacoustic.pro.prediction

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.geometry.BoundaryPoint
import kotlinx.serialization.json.*

/** World positions are frozen before worker dispatch. IDs distinguish colocated receivers. */
data class AngularReceiver(val id:String,val name:String,val position:BoundaryPoint)
data class AngularProbe(val response:String,val frequencyHz:Int,val point:AngularDomainPoint?,val issue:String?=null,val required:Boolean=true)
internal class FrozenAngularData(private val grids:Map<Int,PolarGrid>,grid:MeasurementGrid,
    private val complex:ComplexSpeakerResponse?,private val radiator:Radiator,private val dispersion:Boolean) {
    private val table=MeasuredBandTable(grids.mapValues {it.key to it.value},grid)
    private val complexKeys=complex?.frequencies()?.sorted().orEmpty()
    fun probes(segment:Bands.Segment,az:Float,el:Float):List<AngularProbe> {
        if(!dispersion) return listOf(AngularProbe("directivity disabled",segment.dataHz,null,"BYPASSED"))
        val result=mutableListOf<AngularProbe>()
        val value=table.valueAt(segment.dataHz) {(hz,g)->
            result+=AngularProbe("magnitude",hz,g.angularDomainAt(az,el),required=complex?.maximumInterpolationGapHz==null)
            g.at(az,el)
        }
        if(value==null || !radiator.levelDb(segment.dataHz,az,el).isFinite())
            result+=AngularProbe("magnitude",segment.dataHz,null,"MISSING_MAGNITUDE_RESPONSE",required=complex?.maximumInterpolationGapHz==null)
        complex?.let {c ->
            if(c.maximumInterpolationGapHz==null) {
                c.angularDomainAt(segment.dataHz,az,el)?.let {result+=AngularProbe("complex exact tone only",segment.dataHz,it,required=false)}
            } else {
                val keys=complexKeys
                val lo=keys.indexOfLast {it<=segment.f1};val hi=keys.indexOfFirst {it>=segment.f2}
                if(lo<0 || hi<lo || (lo until hi).any {keys[it+1]-keys[it]>c.maximumInterpolationGapHz!!})
                    result+=AngularProbe("complex band",segment.dataHz,null,"MISSING_COMPLEX_FREQUENCY_SUPPORT")
                else for(i in lo..hi) result+=AngularProbe("complex band endpoint",keys[i],c.angularDomainAt(keys[i],az,el))
            }
        }
        return result
    }
}

data class ReceiverAngularRow(val sourceId:Int,val elementIndex:Int,val dataHz:Int,val azimuthDeg:Float,val elevationDeg:Float,
    val distanceM:Double,val probe:AngularProbe)
data class ReceiverAngularSummary(val receiver:AngularReceiver,val support:String,val origin:String,val rows:List<ReceiverAngularRow>) {
    val supportColorRgb:Int get()=when(support) {"UNAVAILABLE"->0xe45b54;"EXTRAPOLATION_WARNING","SPARSE"->0xe6ab47;"STORED"->0x4dbbb2;"INTERPOLATED"->0x6ca5e6;else->0x999999}
    val originColorRgb:Int get()=when(origin) {"MEASURED"->0x4dbbb2;"SYMMETRY"->0xb896e8;"INTERPOLATED"->0xe58ac4;"MIXED"->0xd0bd78;else->0x999999}
}
data class AngularOverlayLine(val from:BoundaryPoint,val to:BoundaryPoint,val colorRgb:Int)
class ReceiverAngularReport(val sceneRevision:Long,results:List<ReceiverAngularSummary>,val selectedBandHz:Int?=null,val widthOct:Float?=null) {
    val receivers=results.toList()
    /** Three orthogonal diamonds keep receiver markers visible in plan, section and perspective. */
    fun markerLines(selectedId:String?=null):List<AngularOverlayLine> = receivers.flatMap {r ->
        val p=r.receiver.position
        (listOf(.46 to r.supportColorRgb,.25 to r.originColorRgb)+if(r.receiver.id==selectedId) listOf(.62 to 0xffffff) else emptyList()).flatMap {(radius,color)->
            (0..2).flatMap {plane ->
                fun point(a:Double,b:Double)=when(plane) {
                    0->BoundaryPoint(p.x+a,p.y,p.z+b);1->BoundaryPoint(p.x+a,p.y+b,p.z);else->BoundaryPoint(p.x,p.y+a,p.z+b)
                }
                val ring=listOf(point(radius,0.0),point(0.0,radius),point(-radius,0.0),point(0.0,-radius))
                ring.indices.map {i->AngularOverlayLine(ring[i],ring[(i+1)%4],color)}
            }
        }
    }
    fun toJsonString():String=buildJsonObject {
        put("schema",1);put("sceneRevision",sceneRevision)
        selectedBandHz?.let {put("selectedBandHz",it)};widthOct?.let {put("widthOct",it)}
        put("scope","Direct rays only; all configured emitters, no level weighting. Does not assess reflection/scattering paths, SPL/phase accuracy or independent measurement provenance. Exact-tone complex data do not establish band phase support.")
        put("receivers",JsonArray(receivers.map {r->buildJsonObject {
            put("id",r.receiver.id);put("name",r.receiver.name);put("position",buildJsonArray {add(r.receiver.position.x);add(r.receiver.position.y);add(r.receiver.position.z)})
            put("support",r.support);put("origin",r.origin)
            put("rows",JsonArray(r.rows.map {v->buildJsonObject {
                put("sourceId",v.sourceId);put("elementIndex",v.elementIndex);put("dataHz",v.dataHz);put("response",v.probe.response);put("sampleHz",v.probe.frequencyHz);put("requiredForBand",v.probe.required)
                put("azimuthDeg",v.azimuthDeg);put("elevationDeg",v.elevationDeg);put("distanceM",v.distanceM)
                v.probe.issue?.let {put("issue",it)};v.probe.point?.let {p->
                    put("sampling",p.samplingStatus);put("origin",p.origin);put("runtimeInterpolation",p.runtimeInterpolation)
                    put("contributors",JsonArray(p.contributors.map {JsonPrimitive(it.name)}))
                }
            }}))
        }}))
    }.toString()
}

internal object ReceiverAngularAggregation {
    fun summary(receiver:AngularReceiver,rows:List<ReceiverAngularRow>):ReceiverAngularSummary {
        val active=rows.filter {it.probe.required}
        val statuses=active.map {it.probe.point?.samplingStatus ?: it.probe.issue ?: "UNASSESSED"}
        val support=when {
            statuses.any {it in listOf("OUTSIDE_ELEVATION","INVALID_QUERY","INVALID_GRID","COINCIDENT_RECEIVER") || it.startsWith("MISSING_")} -> "UNAVAILABLE"
            statuses.any {it in listOf("SINGLE_AZIMUTH_EXTRAPOLATION","POSSIBLE_MISSING_SECTOR")} -> "EXTRAPOLATION_WARNING"
            active.isEmpty() || statuses.any {it in listOf("GENERIC","CUSTOM_UNASSESSED","BYPASSED")} -> "UNASSESSED"
            "SPARSE_INTERPOLATION" in statuses -> "SPARSE"
            "INTERPOLATED" in statuses -> "INTERPOLATED"
            else -> "STORED"
        }
        val origins=active.map {it.probe.point?.origin ?: "UNDECLARED"}.distinct()
        val origin=if(active.isEmpty() || origins.any {it in listOf("UNDECLARED","UNAVAILABLE","AZIMUTH_REPLICATION")} ||
            active.any {AngularSampleOrigin.UNDECLARED in it.probe.point?.contributors.orEmpty()}) "UNDECLARED"
            else origins.singleOrNull() ?: "MIXED"
        return ReceiverAngularSummary(receiver,support,origin,rows.toList())
    }
}
