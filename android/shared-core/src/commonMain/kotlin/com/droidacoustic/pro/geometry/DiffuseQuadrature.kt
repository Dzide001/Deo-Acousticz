package com.droidacoustic.pro.geometry

import kotlin.math.*

/** Convex planar patch operations. All clipping planes are normalized in metres. */
internal object DiffuseQuadrature {
    data class Plane(val origin: MeshPoint,val normal: MeshPoint)
    fun split(polygon: List<MeshPoint>,plane: Plane): Pair<List<MeshPoint>,List<MeshPoint>> {
        val inside=mutableListOf<MeshPoint>();val outside=mutableListOf<MeshPoint>()
        if(polygon.isEmpty()) return inside to outside
        var a=polygon.last();var da=(a-plane.origin).dot(plane.normal)
        for(b in polygon) {
            val db=(b-plane.origin).dot(plane.normal)
            if((da>=0)!=(db>=0)) {
                val cross=a+(b-a)*(da/(da-db));inside+=cross;outside+=cross
            }
            if(db>=0) inside+=b else outside+=b
            a=b;da=db
        }
        fun clean(p: List<MeshPoint>): List<MeshPoint> {
            val result=mutableListOf<MeshPoint>()
            for(v in p) if(result.isEmpty() || (v-result.last()).length()>1e-12) result+=v
            if(result.size>1 && (result.first()-result.last()).length()<=1e-12) result.removeAt(result.lastIndex)
            return if(result.size>=3) result else emptyList()
        }
        return clean(inside) to clean(outside)
    }
    /** Partition into the intersection with a convex shadow and its disjoint outside pieces. */
    fun partition(polygon: List<MeshPoint>,planes: List<Plane>,work: BoundaryWork): Pair<List<MeshPoint>,List<List<MeshPoint>>> {
        var inside=polygon;val outside=mutableListOf<List<MeshPoint>>()
        for(plane in planes) {
            work.check();if(inside.isEmpty()) break
            val (a,b)=split(inside,plane);inside=a;if(b.isNotEmpty()) outside+=b
        }
        return if(inside.isEmpty()) emptyList<MeshPoint>() to listOf(polygon) else inside to outside
    }
    fun solidAngle(a: MeshPoint,b: MeshPoint,c: MeshPoint,source: MeshPoint): Double {
        fun unit(p: MeshPoint): MeshPoint {val d=p-source;return d*(1/d.length())}
        val u=unit(a);val v=unit(b);val w=unit(c)
        return 2*atan2(abs(u.dot(v.cross(w))),1+u.dot(v)+v.dot(w)+w.dot(u))
    }
    /** Exact angular weights, adaptive longest-angular-edge bisection. Even tiny patches get a node. */
    fun integrate(polygon: List<MeshPoint>,source: MeshPoint,maxSolidAngle: Double,work: BoundaryWork,
        receiver: MeshPoint?=null,profile: ((MeshPoint)->List<Float>)?=null,toleranceDb: Float=1f,
        variationBound: ((List<MeshPoint>)->Float?)?=null,
        highOrder: Boolean=false,
        canonicalRefinement:Boolean=false,
        refinementOrigins: List<MeshPoint> = emptyList(),
        emit: (MeshPoint,Double)->Unit) {
        fun triangle(a: MeshPoint,b: MeshPoint,c: MeshPoint,depth: Int) {
            work.check()
            val omega=solidAngle(a,b,c,source)
            if(!omega.isFinite() || omega<=0) return
            fun angularLength(p: MeshPoint,q: MeshPoint,origin: MeshPoint): Double {
                val u=p-origin;val v=q-origin
                val length=u.length()*v.length()
                return if(length<=1e-18) 2.0 else (1-u.dot(v)/length).coerceIn(0.0,2.0)
            }
            fun edge(p: MeshPoint,q: MeshPoint)=max(max(angularLength(p,q,source),receiver?.let {angularLength(p,q,it)} ?: 0.0),refinementOrigins.maxOfOrNull {angularLength(p,q,it)} ?: 0.0)
            val ab=edge(a,b);val bc=edge(b,c);val ca=edge(c,a)
            val geometricResolved=omega<=maxSolidAngle && maxOf(ab,bc,ca)<=2*maxSolidAngle
            val centre=(a+b+c)*(1.0/3)
            var profileResolved=true
            val profileEdges=DoubleArray(3)
            // Probe vertices, edge midpoints and centroid. A 0.1-degree angular floor bounds
            // refinement at genuine discontinuities; arbitrary unprobed narrow lobes remain possible.
            val sourceSpan=maxOf(angularLength(a,b,source),angularLength(b,c,source),angularLength(c,a,source))
            if(profile!=null && geometricResolved && sourceSpan>1-cos(PI/1800)) {
                val values=listOf(a,b,c,(a+b)*.5,(b+c)*.5,(c+a)*.5,centre).map(profile)
                require(values.all {it.size==values.first().size}) {"Inconsistent diffuse directivity profile"}
                for(i in values.first().indices) {
                    // Seven scalar probes; avoid constructing boxed level/edge lists for
                    // every emitter/band at every receiver's adaptive node.
                    val v0=values[0][i];val v1=values[1][i];val v2=values[2][i]
                    val v3=values[3][i];val v4=values[4][i];val v5=values[5][i];val v6=values[6][i]
                    if(!(v0.isFinite() && v1.isFinite() && v2.isFinite() && v3.isFinite() &&
                        v4.isFinite() && v5.isFinite() && v6.isFinite())) {profileResolved=false;continue}
                    val low=minOf(minOf(v0,v1,v2),minOf(v3,v4,v5),v6)
                    val high=maxOf(maxOf(v0,v1,v2),maxOf(v3,v4,v5),v6)
                    if(high-low>toleranceDb) profileResolved=false
                    profileEdges[0]=max(profileEdges[0],(maxOf(v0,v1,v3)-minOf(v0,v1,v3)).toDouble())
                    profileEdges[1]=max(profileEdges[1],(maxOf(v1,v2,v4)-minOf(v1,v2,v4)).toDouble())
                    profileEdges[2]=max(profileEdges[2],(maxOf(v2,v0,v5)-minOf(v2,v0,v5)).toDouble())
                }
            }
            // Thin slivers can subtend little area but span large directional changes.
            // A discontinuity has no finite dB span. Bound its unresolved angular energy
            // instead of endlessly bisecting a vanishingly thin strip.
            if(omega<=maxSolidAngle*1e-6) profileResolved=true
            val bound=if(geometricResolved) variationBound?.invoke(listOf(a,b,c)) else null
            val boundResolved=bound==null || (bound.isFinite() && bound<=toleranceDb)
            if(geometricResolved && profileResolved && boundResolved) {
                if(!highOrder) emit(centre,omega) else {
                    // Degree-five triangle rule, reweighted to the exact solid angle. This
                    // resolves smooth interference without forcing wavelength-sized centroid cells.
                    val nodes=mutableListOf(centre to .225)
                    for((u,v,w) in listOf(Triple(.059715871789770,.470142064105115,.132394152788506),
                        Triple(.797426985353087,.101286507323456,.125939180544827))) {
                        nodes+=a*u+b*v+c*v to w;nodes+=a*v+b*u+c*v to w;nodes+=a*v+b*v+c*u to w
                    }
                    val weights=nodes.map {(p,w)->w/(p-source).length().pow(3)}
                    val sum=weights.sum()
                    nodes.forEachIndexed {i,(p,_)->emit(p,omega*weights[i]/sum)}
                }
                return
            }
            require(depth<48) {"Diffuse refinement exceeded depth limit"}
            // Prefer changing directions, but cap anisotropy so a discontinuity cannot
            // generate vanishingly thin strips with an unresolved long edge.
            // Coherent scene transport uses a receiver-independent subdivision tree.
            // Receiver geometry still controls acceptance/depth, but not the split direction:
            // shared nodes then carry exactly reusable incident illumination across receivers.
            val scores=if(canonicalRefinement) doubleArrayOf((a-b).dot(a-b),(b-c).dot(b-c),(c-a).dot(c-a))
                else doubleArrayOf(ab,bc,ca)
            if(!canonicalRefinement && geometricResolved && boundResolved) for(i in 0..2) if(profileEdges[i]>toleranceDb) scores[i]*=16
            if(scores[0]>=scores[1] && scores[0]>=scores[2]) {val m=(a+b)*.5;triangle(a,m,c,depth+1);triangle(m,b,c,depth+1)}
            else if(scores[1]>=scores[2]) {val m=(b+c)*.5;triangle(a,b,m,depth+1);triangle(a,m,c,depth+1)}
            else {val m=(c+a)*.5;triangle(a,b,m,depth+1);triangle(m,b,c,depth+1)}
        }
        for(i in 1 until polygon.size-1) triangle(polygon[0],polygon[i],polygon[i+1],0)
    }
}
