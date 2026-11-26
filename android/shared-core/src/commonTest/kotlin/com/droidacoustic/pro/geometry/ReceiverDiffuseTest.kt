package com.droidacoustic.pro.geometry

import com.droidacoustic.pro.acoustics.*
import kotlin.math.*
import kotlin.test.*

class ReceiverDiffuseTest {
    private val material=BoundaryMaterial.IDEAL_RIGID.withScattering(MaterialScattering.create(listOf(0f to listOf(125 to 1f,4000 to 1f))))
    private fun panel(x: Double,y0: Double,y1: Double,z0: Double,z1: Double,opaque: Boolean=false,split: Boolean=false): AudienceMesh {
        val v=mutableListOf(AudienceMeshVertex(x,y0.toFloat(),z0),AudienceMeshVertex(x,y1.toFloat(),z0),
            AudienceMeshVertex(x,y1.toFloat(),z1),AudienceMeshVertex(x,y0.toFloat(),z1))
        val t=if(!split) listOf(SurfaceTriangle(0,1,2),SurfaceTriangle(0,2,3)) else {
            v+=AudienceMeshVertex(x,((y0+y1)/2).toFloat(),(z0+z1)/2)
            listOf(SurfaceTriangle(0,1,4),SurfaceTriangle(1,2,4),SurfaceTriangle(2,3,4),SurfaceTriangle(3,0,4))
        }
        return AudienceMesh.create(v,t).withBoundaryRoles(List(t.size) {if(opaque) AcousticBoundaryRole.OCCLUDING else AcousticBoundaryRole.RIGID_REFLECTOR})
            .withBoundaryMaterials(List(t.size) {material})
    }
    private fun geometry(vararg meshes: AudienceMesh)=BoundaryGeometry.prepare(meshes.mapIndexed {i,m->AudienceSurface(i,"Wall","STAGE",emptyList(),importedMesh=m)})
    private val source=BoundaryPoint(2.0,0.0,0.0)
    private val receiver=BoundaryPoint(4.0,0.0,0.0)
    private fun omega(d: Double,a: Double,b: Double)=4*atan(a*b/(d*sqrt(d*d+a*a+b*b)))
    @Test fun narrowReceiverShadowIsClippedWithoutChangingSourceVisibility() {
        val wall=panel(0.0,-1.0,1.0,-1.0,1.0)
        // Behind source, between receiver and wall: a 4 cm strip casts an 8 cm shadow.
        val blocker=panel(3.0,-.01,.01,-.25,.25,opaque=true)
        val g=geometry(wall,blocker)
        val all=g.diffuseSamples(source).sumOf {it.solidAngle}
        assertEquals(omega(2.0,1.0,1.0),all,1e-10)
        val visible=g.diffuseSamples(source,receiver=receiver)
        assertTrue(visible.none {abs(it.point.y)<.03999})
        assertEquals(all-omega(2.0,.04,1.0),visible.sumOf {it.solidAngle},1e-8)
        assertTrue(visible.all {!g.blocked(it.point,receiver)})
        val moved=g.diffuseSamples(source,receiver=BoundaryPoint(4.0,2.0,0.0))
        assertNotEquals(visible,moved)
        assertEquals(all,g.diffuseSamples(source).sumOf {it.solidAngle},1e-12)
    }
    @Test fun receiverAngularRefinementConvergesNearSurface() {
        val g=geometry(panel(0.0,-1.0,1.0,-1.0,1.0));val close=BoundaryPoint(.2,0.0,0.0)
        val plain=g.diffuseSamples(source,rayCount=128)
        val coarse=g.diffuseSamples(source,rayCount=128,receiver=close)
        val fine=g.diffuseSamples(source,rayCount=32768,receiver=close)
        assertTrue(coarse.size>plain.size)
        assertEquals(plain.sumOf {it.solidAngle},fine.sumOf {it.solidAngle},1e-10)
        val n=600;val step=2.0/n;var reference=0.0
        for(i in 0 until n) for(j in 0 until n) {
            val y=-1+(i+.5)*step;val z=-1+(j+.5)*step
            val r1=sqrt(4+y*y+z*z);val r2=sqrt(.04+y*y+z*z)
            reference+=2/(r1*r1*r1)*.2/(PI*r2*r2*r2)*step*step
        }
        fun value(samples: List<DiffuseSurfaceSample>)=samples.sumOf {it.receiverFactor(close,.1)}
        assertTrue(abs(value(fine)-reference)<abs(value(coarse)-reference))
        assertEquals(reference,value(fine),reference*.001)
    }
    @Test fun sharpProfileRefinementConservesWeightsAndConverges() {
        val g=geometry(panel(0.0,-1.0,1.0,-1.0,1.0))
        fun level(p: BoundaryPoint): Float = (40/(1+exp(-80*(p.y-.17)))-40).toFloat()
        val baseline=g.diffuseSamples(source,rayCount=128,receiver=receiver)
        fun sample(tolerance: Float)=g.diffuseSamples(source,rayCount=128,receiver=receiver,
            levelAt={p,_,_->listOf(level(p))},directivityToleranceDb=tolerance)
        val coarse=sample(6f);val fine=sample(.5f)
        assertTrue(fine.size>coarse.size && coarse.size>baseline.size)
        assertEquals(baseline.sumOf {it.solidAngle},fine.sumOf {it.solidAngle},1e-10)
        val n=600;val step=2.0/n;var reference=0.0
        for(i in 0 until n) for(j in 0 until n) {
            val y=-1+(i+.5)*step;val z=-1+(j+.5)*step
            val r1=sqrt(4+y*y+z*z);val r2=sqrt(16+y*y+z*z)
            reference+=2/(r1*r1*r1)*4/(PI*r2*r2*r2)*10.0.pow(level(BoundaryPoint(0.0,y,z))/10.0)*step*step
        }
        fun value(samples: List<DiffuseSurfaceSample>)=samples.sumOf {it.receiverFactor(receiver)*10.0.pow(level(it.point)/10.0)}
        assertTrue(abs(value(fine)-reference)<abs(value(coarse)-reference))
        assertEquals(reference,value(fine),reference*.005)
        // A subsequent profile is evaluated anew, never reused by position-only caching.
        val flat=g.diffuseSamples(source,rayCount=128,receiver=receiver,levelAt={_,_,_->listOf(0f)})
        assertEquals(baseline,flat)
    }
    @Test fun duplicateAndRetriangulatedShadowsCannotMultiplyOrRemoveExtraEnergy() {
        val wall=panel(0.0,-1.0,1.0,-1.0,1.0);val split=panel(0.0,-1.0,1.0,-1.0,1.0,split=true)
        val blocker=panel(3.0,-.1,.1,-.25,.25,opaque=true)
        val expected=geometry(wall,blocker).diffuseSamples(source,receiver=receiver).sumOf {it.solidAngle}
        for(g in listOf(geometry(wall,wall,blocker,blocker),geometry(split,wall,blocker),geometry(blocker,wall,split))) {
            val samples=g.diffuseSamples(source,receiver=receiver,levelAt={p,_,_->listOf((10*p.y).toFloat())})
            assertEquals(expected,samples.sumOf {it.solidAngle},1e-9)
        }
    }
    @Test fun discontinuousProfilesHaveBoundedRefinementWithoutDuplicatingEnergy() {
        val g=geometry(panel(0.0,-1.0,1.0,-1.0,1.0))
        val samples=g.diffuseSamples(source,receiver=receiver,levelAt={p,_,_->listOf(if(p.y>.17) 0f else -100f)})
        assertTrue(samples.size<65536)
        assertEquals(omega(2.0,1.0,1.0),samples.sumOf {it.solidAngle},1e-9)
        assertTrue(samples.any {it.point.y>.17} && samples.any {it.point.y<.17})
    }
    @Test fun predictionUsesActualRadiatorProfileAndAirLoss() {
        val g=geometry(panel(0.0,-1.0,1.0,-1.0,1.0))
        fun level(az: Double)=60+40/(1+exp(-2*(az-5)))
        val radiator=Radiator {_,az,_->level(az.toDouble()).toFloat()}
        val engine=DirectField()
        val prediction=engine.predictBand(listOf(SoundSource(0,2f,0f,0f,180f,0f,radiator)),
            4f,0f,0f,listOf(Bands.Segment(1000,990.0,1010.0)),boundaries=g)
        val alpha=engine.atmosphere.absorptionDbPerMetre(1000.0)
        val n=400;val step=2.0/n;var reference=0.0
        for(i in 0 until n) for(j in 0 until n) {
            val y=-1+(i+.5)*step;val z=-1+(j+.5)*step
            val r1=sqrt(4+y*y+z*z);val r2=sqrt(16+y*y+z*z)
            val power=10.0.pow((level(-atan(z/2)*180/PI)-alpha*(r1+r2))/10)
            reference+=power*2/(r1*r1*r1)*4/(PI*r2*r2*r2)*step*step
        }
        assertEquals(reference,prediction.diffuseMeanSquarePressure!!,reference*.005)
    }
    @Test fun frozenBandReuseKeepsMissingBandsAndEmitterIdentitiesSeparate() {
        val g=geometry(panel(0.0,-1.0,1.0,-1.0,1.0))
        val engine=DirectField(frozenDiffuseBands=listOf(1000,2000))
        val missing=SoundSource(0,2f,0f,0f,180f,0f,Radiator {hz,_,_->if(hz==2000) Float.NaN else 90f})
        fun predict(source: SoundSource,hz: Int)=engine.predictBand(listOf(source),4f,0f,0f,
            listOf(Bands.Segment(hz,hz-10.0,hz+10.0)),boundaries=g)
        val valid=predict(missing,1000).diffuseMeanSquarePressure
        assertNotNull(valid);assertTrue(valid>0)
        assertNull(predict(missing,2000).diffuseMeanSquarePressure)
        assertEquals(valid,predict(missing,1000).diffuseMeanSquarePressure)
        val complete=SoundSource(0,2f,0f,0f,180f,0f,Radiator {_,_,_->90f})
        assertNotNull(predict(complete,2000).diffuseMeanSquarePressure)
        assertFailsWith<IllegalArgumentException> {predict(complete,4000)}
    }
    @Test fun narrowLobeBetweenProbesConvergesWithoutAddingEnergyOrDuplicateFaces() {
        // A compact lobe displaced from all coarse grid probes. Exact range metadata
        // detects its support even when sampled values all equal the background.
        val wall=panel(0.0,-1.0,1.0,-1.0,1.0)
        val g=geometry(wall)
        fun level(p: BoundaryPoint): Float = (-40+40*(1-abs(p.y-.173)/.018).coerceAtLeast(0.0)*
            (1-abs(p.z-.317)/.018).coerceAtLeast(0.0)).toFloat()
        val bound: (List<BoundaryPoint>)->Float = {points ->
            fun peak(lo: Double,hi: Double,c: Double)= (1-(if(c<lo) lo-c else if(c>hi) c-hi else 0.0)/.018).coerceAtLeast(0.0)
            fun floor(lo: Double,hi: Double,c: Double)=(1-max(abs(lo-c),abs(hi-c))/.018).coerceAtLeast(0.0)
            val ymin=points.minOf {it.y};val ymax=points.maxOf {it.y};val zmin=points.minOf {it.z};val zmax=points.maxOf {it.z}
            (40*(peak(ymin,ymax,.173)*peak(zmin,zmax,.317)-floor(ymin,ymax,.173)*floor(zmin,zmax,.317))).toFloat()
        }
        fun samples(geometry: BoundaryGeometry,tolerance: Float,aware: Boolean)=geometry.diffuseSamples(source,rayCount=128,
            receiver=receiver,levelAt={p,_,_->listOf(level(p))},directivityToleranceDb=tolerance,
            variationBound=if(aware) bound else null)
        fun energy(samples: List<DiffuseSurfaceSample>)=samples.sumOf {it.receiverFactor(receiver)*10.0.pow(level(it.point)/10.0)}
        val blind=samples(g,1f,false);val coarse=samples(g,4f,true);val fine=samples(g,1f,true)
        val n=1200;val step=2.0/n;var reference=0.0
        for(i in 0 until n) for(j in 0 until n) {
            val y=-1+(i+.5)*step;val z=-1+(j+.5)*step
            val r1=sqrt(4+y*y+z*z);val r2=sqrt(16+y*y+z*z)
            reference+=2/(r1*r1*r1)*4/(PI*r2*r2*r2)*10.0.pow(level(BoundaryPoint(0.0,y,z))/10.0)*step*step
        }
        assertTrue(blind.all {level(it.point)==-40f}, "Coarse probes must miss the lobe")
        assertTrue(energy(fine)>energy(blind)*1.01)
        assertTrue(abs(energy(fine)-reference)<abs(energy(coarse)-reference))
        assertEquals(reference,energy(fine),reference*.01)
        assertEquals(omega(2.0,1.0,1.0),fine.sumOf {it.solidAngle},1e-9)
        val duplicate=samples(geometry(wall,wall),1f,true)
        assertEquals(energy(fine),energy(duplicate),reference*1e-8)
        assertEquals(fine.sumOf {it.solidAngle},duplicate.sumOf {it.solidAngle},1e-9)
    }
    @Test fun measuredGridBoundsFindOffProbeLobesAcrossTheAzimuthSeam() {
        val az=listOf(0f,5f,5.2f,5.4f,180f,354.6f,354.8f,355f)
        val el=listOf(-90f,3f,3.2f,3.4f,90f)
        val grid=PolarGrid(az,el,az.map {a->el.map {e->if((a==5.2f || a==354.8f) && e==3.2f) 0f else -40f}})
        assertEquals(40f,grid.variationDb(0f,0f,10f),.001f)
        assertEquals(0f,grid.variationDb(40f,0f,1f),.001f)
        assertTrue(grid.variationDb(5.2f,3.2f,.001f)<1f)
        val measured=MeasuredRadiator(mapOf(1000 to 90f),mapOf(1000 to grid::at),
            variationBounds=mapOf(1000 to grid::variationDb))
        assertTrue(measured.hasVariationBounds())
        assertEquals(40f,measured.variationDb(1000,0f,0f,10f)!!,.001f)
        assertNull(measured.variationDb(2000,0f,0f,10f))
    }
    @Test fun frozenMeasuredLobePredictionMatchesIndependentIntegral() {
        val az=listOf(0f,5f,5.3f,5.6f,180f)
        val el=listOf(-90f,3f,3.3f,3.6f,90f)
        val rows=az.map {a->el.map {e->if(a==5.3f && e==3.3f) 0f else -40f}.toFloatArray()}.toTypedArray()
        val data=com.droidacoustic.pro.scene.ClfData("narrow",patterns=listOf(
            com.droidacoustic.pro.scene.ClfPolarPattern(1000,az,el,rows)),sensitivityDbByBand=mapOf(1000 to 90f))
        val frozen=com.droidacoustic.pro.prediction.FrozenResponse.measured(data,true).radiator()
        assertTrue(data.measuredRadiator().hasVariationBounds())
        rows[2][2]=-40f // Snapshot owns both its response and its bounds.
        assertEquals(40f,frozen.variationDb(1000,5.3f,3.3f,1f)!!,.001f)
        val engine=DirectField()
        val emitter=SoundSource(0,2f,0f,0f,180f,0f,frozen)
        val wall=panel(0.0,-1.0,1.0,-1.0,1.0)
        fun result(g: BoundaryGeometry)=engine.predictBand(listOf(emitter),4f,0f,0f,
            listOf(Bands.Segment(1000,990.0,1010.0)),boundaries=g).diffuseMeanSquarePressure!!
        val predicted=result(geometry(wall))
        val n=1200;val step=2.0/n;var reference=0.0
        val loss=engine.atmosphere.absorptionDbPerMetre(1000.0)
        for(i in 0 until n) for(j in 0 until n) {
            val y=-1+(i+.5)*step;val z=-1+(j+.5)*step
            val r1=sqrt(4+y*y+z*z);val r2=sqrt(16+y*y+z*z)
            val db=frozen.levelDb(1000,(-atan(z/2)*180/PI).toFloat(),(asin(y/r1)*180/PI).toFloat())
            reference+=2/(r1*r1*r1)*4/(PI*r2*r2*r2)*10.0.pow((db-loss*(r1+r2))/10)*step*step
        }
        assertEquals(reference,predicted,reference*.015)
        assertEquals(predicted,result(geometry(wall,wall)),predicted*1e-8)
    }
    @Test fun customNarrowLobeConvergesConservesWeightsAndCountsDuplicateFacesOnce() {
        val radial=RadialDirectivity(5.3f,3.3f,mapOf(1000 to listOf(0f to 90f,.3f to 50f,180f to 50f)))
        val custom=BoundedRadiator(radial,radial::rangeDb)
        val emitter=SoundSource(0,2f,0f,0f,180f,0f,custom)
        val wall=panel(0.0,-1.0,1.0,-1.0,1.0);val g=geometry(wall)
        fun angles(p: BoundaryPoint): Pair<Float,Float> {
            val d=sqrt(4+p.y*p.y+p.z*p.z)
            return (-atan(p.z/2)*180/PI).toFloat() to (asin(p.y/d)*180/PI).toFloat()
        }
        fun level(p: BoundaryPoint): Float {val (az,el)=angles(p);return custom.levelDb(1000,az,el)}
        val bound: (List<BoundaryPoint>)->Float? = {points ->
            val centre=BoundaryPoint(0.0,points.sumOf {it.y}/3,points.sumOf {it.z}/3)
            val length=sqrt(4+centre.y*centre.y+centre.z*centre.z)
            val radius=points.maxOf {p->acos(((4+p.y*centre.y+p.z*centre.z)/
                (sqrt(4+p.y*p.y+p.z*p.z)*length)).coerceIn(-1.0,1.0))*180/PI}.toFloat()+1e-5f
            val (az,el)=angles(centre);custom.variationDb(1000,az,el,radius)
        }
        fun samples(tolerance: Float)=g.diffuseSamples(source,receiver=receiver,rayCount=128,
            levelAt={p,_,_->listOf(level(p))},variationBound=bound,directivityToleranceDb=tolerance)
        val coarse=samples(4f);val fine=samples(1f)
        val engine=DirectField();val alpha=engine.atmosphere.absorptionDbPerMetre(1000.0)
        fun energy(samples: List<DiffuseSurfaceSample>)=samples.sumOf {
            val p=it.point;val distance=sqrt(4+p.y*p.y+p.z*p.z)+sqrt(16+p.y*p.y+p.z*p.z)
            it.receiverFactor(receiver)*10.0.pow((level(p)-alpha*distance)/10)
        }
        val n=1400;val step=2.0/n;var reference=0.0
        for(i in 0 until n) for(j in 0 until n) {
            val y=-1+(i+.5)*step;val z=-1+(j+.5)*step
            val r1=sqrt(4+y*y+z*z);val r2=sqrt(16+y*y+z*z)
            reference+=2/(r1*r1*r1)*4/(PI*r2*r2*r2)*10.0.pow((level(BoundaryPoint(0.0,y,z))-alpha*(r1+r2))/10)*step*step
        }
        assertTrue(abs(energy(fine)-reference)<abs(energy(coarse)-reference))
        assertEquals(reference,energy(fine),reference*.01)
        assertEquals(omega(2.0,1.0,1.0),fine.sumOf {it.solidAngle},1e-9)
        fun predict(geometry: BoundaryGeometry)=engine.predictBand(listOf(emitter),4f,0f,0f,
            listOf(Bands.Segment(1000,990.0,1010.0)),boundaries=geometry).diffuseMeanSquarePressure!!
        val actual=predict(g)
        assertEquals(reference,actual,reference*.01)
        assertEquals(actual,predict(geometry(wall,wall)),actual*1e-8)
    }
    @Test fun budgetAndProfileValidationFailExplicitly() {
        val g=geometry(panel(0.0,-1.0,1.0,-1.0,1.0))
        assertFails {g.diffuseSamples(source,receiver=receiver,directivityToleranceDb=0f)}
        assertFails {g.diffuseSamples(source,BoundaryWork(limit=2),receiver=receiver)}
    }
}
