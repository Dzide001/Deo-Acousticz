package com.droidacoustic.pro.acoustics

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.scene.*
import kotlin.math.*
import kotlin.test.*

class MixedComplexDiffuseTest {
    private val segments=listOf(Bands.Segment(1000,990.0,1010.0))
    private fun response(amplitude:Double=100.0,phase:Double=0.0,slope:Double=0.0):ComplexSpeakerResponse {
        val rows=listOf(900,1100).joinToString {hz ->
            val magnitude=amplitude+slope*(hz-900)
            val r=magnitude*cos(phase);val i=magnitude*sin(phase)
            """{"frequency":$hz,"azimuths":[0],"elevations":[-90,90],"real":[[$r,$r]],"imaginary":[[$i,$i]]}"""
        }
        return ComplexSpeakerResponse.fromJsonString("""{"version":2,"convention":"${ComplexSpeakerResponse.CONVENTION}","provenance":"Diffuse fixture","referenceDrive":"1 V rms","frequencyInterpolation":"cartesian-linear-hz","maximumInterpolationGapHz":200,"patterns":[$rows]}""")
    }
    private fun radiator(r:ComplexSpeakerResponse=response())=MeasuredRadiator(emptyMap(),emptyMap(),complexResponse=r)
    private fun source(r:Radiator=radiator(),id:Int=0,delay:Double=0.0,inverted:Boolean=false,filters:DspFilterChain=DspFilterChain())=
        SoundSource(id,3f,0f,0f,180f,0f,r,delayS=delay,inverted=inverted,filters=filters)
    private fun material(scattering:Float=1f,absorption:Float=0f)=BoundaryMaterial.custom("Measured surface",listOf(900 to absorption,1100 to absorption))
        .withScattering(MaterialScattering.create(listOf(0f to listOf(900 to scattering,1100 to scattering))))
        .withReflectionPhase(ReflectionPhase.create(listOf(0f to listOf(900 to 0.0,1100 to 0.0))))
    private fun wall(m:BoundaryMaterial=material())=VenueBlock(0,widthM=2f,depthM=4f,heightM=2f,blockHeightM=4f,boundaryRole=AcousticBoundaryRole.RIGID_REFLECTOR,boundaryMaterial=m)
    private fun geometry(blocks:List<VenueBlock>)=BoundaryGeometry.prepare(emptyList(),venue=VenueGeometry(stageHeightM=0f,blocks=blocks))
    private fun predict(s:List<SoundSource>,g:BoundaryGeometry?=null,coherent:Boolean=true,field:DirectField=DirectField())=
        field.predictBand(s,4f,0f,0f,segments,coherent=coherent,correlateByGroup=true,boundaries=g)
    @Test fun mixedSourcesAddIndependentPowersButNeverInventCrossSourcePhase() {
        val complex=source();val magnitude=source(Radiator {_,_,_->40f},1)
        val a=predict(listOf(complex));val b=predict(listOf(magnitude))
        val mixed=predict(listOf(complex,magnitude),coherent=false)
        assertEquals(a.meanSquarePressure!!+b.meanSquarePressure!!,mixed.meanSquarePressure!!,mixed.meanSquarePressure!!*2e-5)
        assertNull(predict(listOf(complex,magnitude)).meanSquarePressure)
        assertNull(predict(listOf(complex,source(Radiator {_,_,_->40f},0)),coherent=false).meanSquarePressure)
        assertEquals(mixed.meanSquarePressure!!,predict(listOf(source(delay=.014,inverted=true),magnitude),coherent=false).meanSquarePressure!!,mixed.meanSquarePressure!!*1e-8)
        assertNull(predict(listOf(complex,source(Radiator {_,_,_->Float.NaN},1)),coherent=false).meanSquarePressure)
    }
    @Test fun complexDiffuseMatchesMagnitudeReferenceAndIgnoresArbitrarySourcePhase() {
        val g=geometry(listOf(wall()))
        val a=predict(listOf(source()),g)
        val reference=predict(listOf(source(Radiator {_,_,_->40f})),g)
        assertTrue(a.diffuseMeanSquarePressure!!>0)
        assertEquals(reference.diffuseMeanSquarePressure!!,a.diffuseMeanSquarePressure!!,a.diffuseMeanSquarePressure!!*2e-5)
        val shifted=predict(listOf(source(radiator(response(phase=1.2)),delay=.05,inverted=true)),g)
        assertEquals(a.diffuseMeanSquarePressure!!,shifted.diffuseMeanSquarePressure!!,a.diffuseMeanSquarePressure!!*2e-5)
        assertNull(PhaseResponse.at(listOf(source()),4f,0f,0f,1000,Atmosphere(),g).degrees)
    }
    @Test fun absorptionScatteringPartitionAndCoincidentBlocksDoNotDuplicateEnergy() {
        val b=wall();val full=predict(listOf(source()),geometry(listOf(b)))
        val half=predict(listOf(source()),geometry(listOf(wall(material(.5f,.2f)))))
        assertEquals(full.diffuseMeanSquarePressure!!*.4,half.diffuseMeanSquarePressure!!,full.diffuseMeanSquarePressure!!*2e-5)
        val duplicate=predict(listOf(source()),geometry(listOf(b,b.copy(id=1))))
        assertEquals(full.meanSquarePressure!!,duplicate.meanSquarePressure!!,full.meanSquarePressure!!*2e-5)
        assertEquals(0.0,predict(listOf(source()),geometry(listOf(wall(material(1f,1f))))).diffuseMeanSquarePressure)
        assertEquals(0.0,predict(listOf(source()),geometry(listOf(wall(material(0f))))).diffuseMeanSquarePressure)
    }
    @Test fun filtersAndComplexFrequencySlopeAffectDiffuseEnergyAndCachedResultsAgree() {
        val g=geometry(listOf(wall()))
        val base=predict(listOf(source()),g)
        val gain=DspFilterChain(listOf(DspFilter(FilterKind.PEAK,1000.0,1.0,6.0)))
        val filtered=predict(listOf(source(filters=gain)),g)
        assertEquals(10.0.pow(.6),filtered.diffuseMeanSquarePressure!!/base.diffuseMeanSquarePressure!!,.002)
        val s=source(radiator(response(slope=.1)))
        val uncached=predict(listOf(s),g)
        val field=DirectField(frozenDiffuseBands=listOf(1000))
        val cached=predict(listOf(s),g,field=field)
        assertEquals(uncached.meanSquarePressure!!,cached.meanSquarePressure!!,uncached.meanSquarePressure!!*1e-10)
        assertEquals(cached.diffuseMeanSquarePressure,predict(listOf(s),g,field=field).diffuseMeanSquarePressure)
        assertTrue(uncached.diffuseMeanSquarePressure!!>base.diffuseMeanSquarePressure!!*1.20)
    }
    @Test fun angularBoundsCoverHiddenLobesAndSpectralCancellations() {
        val json=response().toJsonString().replace("\"azimuths\":[0]","\"azimuths\":[0,1,2,180]")
            .replace("\"real\":[[100.0,100.0]]","\"real\":[[1,1],[100,100],[1,1],[1,1]]")
            .replace("\"imaginary\":[[0.0,0.0]]","\"imaginary\":[[0,0],[0,0],[0,0],[0,0]]")
        val narrow=ComplexSpeakerResponse.fromJsonString(json)
        assertTrue(narrow.variationDb(990.0,1010.0,0f,0f,3f)>30)
        assertEquals(0f,response().variationDb(900.0,1100.0,0f,0f,180f))
        // A frequency-only phase/zero crossing must not force spatial refinement of an omnidirectional source.
        assertEquals(0f,response(100.0,slope=-1.0).variationDb(900.0,1100.0,0f,0f,180f))
    }
    @Test fun diffuseBroadBandMatchesDenseIntegralAndMeshOverlapCountsOnce() {
        val b=wall();val g=geometry(listOf(b));val r=response(slope=.2);val src=source(radiator(r))
        val band=listOf(Bands.Segment(1000,900.0,1100.0));val air=Atmosphere()
        val result=DirectField(air).predictBand(listOf(src),4f,0f,0f,band,boundaries=g)
        val receiver=BoundaryPoint(4.0,0.0,0.0)
        val samples=g.diffuseSamples(BoundaryPoint(3.0,0.0,0.0),receiver=receiver)
        val dense=samples.sumOf {sample ->
            val p=sample.point;val distance=sample.sourceDistance+sqrt((p.x-4).pow(2)+p.y*p.y+p.z*p.z)
            val mean=(0 until 1000).sumOf {i ->val f=900+(i+.5)*.2
                (100+.2*(f-900)).pow(2)*10.0.pow(-air.absorptionDbPerMetre(f)*distance/10)}/1000
            mean*sample.receiverFactor(receiver)
        }
        assertEquals(dense,result.diffuseMeanSquarePressure!!,dense*3e-5)
        val shape=VenueBoundaryGeometry.block(b)
        val mesh=AudienceMesh.create(shape.vertices.map {AudienceMeshVertex(it.x,it.y.toFloat(),it.z)},shape.triangles)
            .withBoundaryRoles(List(shape.triangles.size) {AcousticBoundaryRole.RIGID_REFLECTOR})
            .withBoundaryMaterials(List(shape.triangles.size) {b.boundaryMaterial})
        val overlap=BoundaryGeometry.prepare(listOf(AudienceSurface(1,"Imported duplicate","STAGE",emptyList(),importedMesh=mesh)),venue=VenueGeometry(stageHeightM=0f,blocks=listOf(b)))
        val duplicate=DirectField(air).predictBand(listOf(src),4f,0f,0f,band,boundaries=overlap)
        assertEquals(result.meanSquarePressure!!,duplicate.meanSquarePressure!!,result.meanSquarePressure!!*2e-5)
    }
    @Test fun missingDiffuseDirectionsAndCancelledWorkCannotPublishPartialPower() {
        val incomplete=ComplexSpeakerResponse.fromJsonString(response().toJsonString().replace("[-90,90]","[-10,10]"))
        val result=predict(listOf(source(radiator(incomplete))),geometry(listOf(wall())))
        assertNull(result.meanSquarePressure);assertNull(result.diffuseMeanSquarePressure)
        val cachedField=DirectField(frozenDiffuseBands=listOf(1000))
        assertNotNull(predict(listOf(source(),source(id=1)),geometry(listOf(wall())),field=cachedField).meanSquarePressure)
        assertNull(predict(listOf(source(radiator(incomplete)),source(id=1)),geometry(listOf(wall())),field=cachedField).meanSquarePressure)
        assertFailsWith<IllegalStateException> {DirectField().predictBand(listOf(source()),4f,0f,0f,segments,
            boundaries=geometry(listOf(wall())),boundaryWork=BoundaryWork({error("cancelled")}))}
        assertEquals(0.0,predict(listOf(source(radiator(response(0.0)))),geometry(listOf(wall()))).diffuseMeanSquarePressure)
    }

    @Test fun coherentSurfaceIlluminationReinforcesCancelsAndRetainsIndependentGroups() {
        val g=geometry(listOf(wall()));val a=source();val b=source(id=1)
        val single=predict(listOf(a),g).diffuseMeanSquarePressure!!
        val together=predict(listOf(a,b),g).diffuseMeanSquarePressure!!
        assertEquals(4*single,together,single*.006)
        assertEquals(0.0,predict(listOf(a,source(id=1,inverted=true)),g).diffuseMeanSquarePressure!!,1e-10)
        assertEquals(2*single,predict(listOf(a,b),g,coherent=false).diffuseMeanSquarePressure!!,single*1e-8)
        assertEquals(together,predict(listOf(a,source()),g,coherent=false).diffuseMeanSquarePressure!!,1e-8)
    }
    @Test fun coherentIncidentPhaseAndBandwidthDelayControlScatteredPower() {
        val g=geometry(listOf(wall()))
        val full=predict(listOf(source(),source(id=1)),g).diffuseMeanSquarePressure!!
        val quarter=predict(listOf(source(),source(radiator(response(phase=PI/2)),id=1)),g).diffuseMeanSquarePressure!!
        assertEquals(full/2,quarter,full*1e-6)
        val delay=.0005
        val delayed=predict(listOf(source(),source(id=1,delay=delay)),g).diffuseMeanSquarePressure!!
        val x=PI*20*delay
        val expected=full*(2+2*cos(2*PI*1000*delay)*sin(x)/x)/4
        assertEquals(expected,delayed,full*2e-5)
    }
    @Test fun coherentIlluminationRespectsMaterialEnergyPartitionAndUniqueSurfaceOwnership() {
        val src=listOf(source(),source(id=1));val b=wall()
        val full=predict(src,geometry(listOf(b))).diffuseMeanSquarePressure!!
        val partial=predict(src,geometry(listOf(wall(material(.5f,.2f))))).diffuseMeanSquarePressure!!
        assertEquals(full*.4,partial,full*1e-6)
        assertEquals(full,predict(src,geometry(listOf(b,b.copy(id=1)))).diffuseMeanSquarePressure!!,full*1e-8)
        assertEquals(0.0,predict(src,geometry(listOf(wall(material(1f,1f))))).diffuseMeanSquarePressure!!)
        val p=material(.5f,.2f).energyPartition(1000,0f)
        assertEquals(1.0,p.absorbed+p.specular+p.scattered,1e-8)
    }
    @Test fun coherentIlluminationMissingDirectionsNeverPublishPartialPower() {
        val incomplete=ComplexSpeakerResponse.fromJsonString(response().toJsonString().replace("[-90,90]","[-10,10]"))
        val result=predict(listOf(source(),source(radiator(incomplete),id=1)),geometry(listOf(wall())))
        assertNull(result.meanSquarePressure);assertNull(result.diffuseMeanSquarePressure)
    }

    @Test fun separatedEmittersMatchIndependentDenseSurfaceInterferenceIntegral() {
        val r=radiator();val a=source(r)
        val b=SoundSource(1,3f,.5f,.25f,180f,0f,r)
        val air=Atmosphere();val n=400;var dense=0.0
        for(iy in 0 until n) for(iz in 0 until n) {
            val y=-2+(iy+.5)*4/n;val z=-2+(iz+.5)*4/n
            val da=sqrt(4+y*y+z*z);val db=sqrt(4+(y-.5).pow(2)+(z-.25).pow(2))
            val dr=sqrt(9+y*y+z*z)
            val aa=100/da*sqrt(2/da)*10.0.pow(-air.absorptionDbPerMetre(1000.0)*(da+dr)/20)
            val ab=100/db*sqrt(2/db)*10.0.pow(-air.absorptionDbPerMetre(1000.0)*(db+dr)/20)
            val delay=(da-db)/air.speedOfSound;val x=PI*20*delay
            val sinc=if(abs(x)<1e-10) 1.0 else sin(x)/x
            dense+=(aa*aa+ab*ab+2*aa*ab*cos(2*PI*1000*delay)*sinc)*3/(PI*dr.pow(3))*16/(n*n)
        }
        val result=predict(listOf(a,b),geometry(listOf(wall()))).diffuseMeanSquarePressure!!
        assertEquals(dense,result,dense*.002)
    }

    @Test fun commonSurfaceSamplesRespectEveryEmitterShadowAndReceiverVisibility() {
        val obstacle=VenueBlock(1,centerX=2f,centerZ=0f,widthM=.2f,depthM=.5f,heightM=1f,blockHeightM=.2f,
            boundaryRole=AcousticBoundaryRole.OCCLUDING)
        val g=geometry(listOf(wall(),obstacle))
        val origins=listOf(BoundaryPoint(3.0,1.0,0.0),BoundaryPoint(3.0,-1.0,0.0),BoundaryPoint(-3.0,0.0,0.0))
        val receiver=BoundaryPoint(4.0,0.0,0.0)
        val samples=g.illuminationSamples(origins,receiver,BoundaryWork(),false,{_,_,_,_->listOf(0f)},{_,_,_->0f})
        assertTrue(samples.isNotEmpty());assertTrue(samples.any {0 !in it.visibleEmitters && 1 in it.visibleEmitters})
        for(cell in samples) {
            assertFalse(g.blocked(receiver,cell.point));assertFalse(2 in cell.visibleEmitters)
            for(i in 0..1) assertEquals(!g.blocked(origins[i],cell.point),i in cell.visibleEmitters)
        }
    }
    @Test fun magnitudeOnlyDiffuseRetainsIndependentEnergyWithoutInventedCabinetPhase() {
        val g=geometry(listOf(wall()));val r=Radiator {_,_,_->40f}
        val single=predict(listOf(source(r)),g).diffuseMeanSquarePressure!!
        assertEquals(2*single,predict(listOf(source(r),source(r,id=1,inverted=true)),g).diffuseMeanSquarePressure!!,1e-8)
    }

    @Test fun largeMeasuredArrayReusesBoundedGeometryAndBandChangesStayAccurate() {
        val r=radiator();val src=List(32) {i->SoundSource(0,3f,(i-15.5f)*.01f,0f,180f,0f,r,delayS=i*.00001)}
        val g=geometry(listOf(wall(material(.6f,.2f))))
        fun engine(capacity:Int)=CoherentDiffuse(src,BoundaryPoint(4.0,0.0,0.0),g,Atmosphere(),segments,true,true,false,BoundaryWork(),capacity)
        val cached=engine(65536);val uncached=engine(0)
        val missing=mutableSetOf<MissingBand>();val seg=segments.single()
        val cold=cached.bandPower(seg,missing,null,null);val first=cached.geometryEvaluations
        val warm=cached.bandPower(seg,missing,null,null);val repeated=cached.geometryEvaluations-first
        assertEquals(cold,warm,abs(cold)*1e-12);assertTrue(first>0);assertTrue(repeated<first)
        assertEquals(cold,uncached.bandPower(seg,missing,null,null),abs(cold)*1e-12)
        for(band in listOf(900,1000)) {
            val next=Bands.Segment(band,990.0,1010.0)
            assertEquals(uncached.bandPower(next,missing,null,null),cached.bandPower(next,missing,null,null),abs(cold)*1e-12)
        }
        assertTrue(missing.isEmpty())
        println("32-emitter surface geometry evaluations: cold=$first repeated=$repeated (cache cap 65536)")
    }

    @Test fun batchedSurfaceIntegrationMatchesScalarEvaluationAcrossKnotsFiltersAndChunkBoundaries() {
        val rows=listOf(Triple(900,100,0),Triple(1000,130,40),Triple(1100,160,10)).joinToString { (hz,r,i)->
            """{"frequency":$hz,"azimuths":[0],"elevations":[-90,90],"real":[[$r,$r]],"imaginary":[[$i,$i]]}"""
        }
        val measured=radiator(ComplexSpeakerResponse.fromJsonString("""{"version":2,"convention":"${ComplexSpeakerResponse.CONVENTION}","referenceDrive":"1 V rms","provenance":"Nonlinear knot fixture","frequencyInterpolation":"cartesian-linear-hz","maximumInterpolationGapHz":200,"patterns":[$rows]}"""))
        var evaluations=0
        fun wrapped(linear:Boolean)=object:Radiator by measured {
            override fun supportsLinearComplexBand(lowHz:Double,highHz:Double)=linear && measured.supportsLinearComplexBand(lowHz,highHz)
            override fun complexBandAt(hz:Double,azimuthDeg:Float,elevationDeg:Float):ComplexPressure? {
                evaluations++;return measured.complexBandAt(hz,azimuthDeg,elevationDeg)
            }
        }
        val g=geometry(listOf(wall()))
        val requested=(0 until 80).map {Bands.Segment(1000,990.0+it*.25,990.25+it*.25)}
        fun engine(r:Radiator)=CoherentDiffuse(listOf(source(r),source(r,id=1,delay=.0007,
            filters=DspFilterChain(listOf(DspFilter(FilterKind.LOW_PASS,1000.0))))),BoundaryPoint(4.0,0.0,0.0),g,Atmosphere(),segments,true,true,false,BoundaryWork())
        val optimized=engine(wrapped(true));val scalar=engine(wrapped(false));val missing=mutableSetOf<MissingBand>()
        // Warm identical spatial preparation before counting directional response evaluations.
        optimized.bandPower(requested.first(),missing,null,null);scalar.bandPower(requested.first(),missing,null,null)
        evaluations=0;val actual=optimized.bandPowers(requested,missing,null,null);val fast=evaluations
        evaluations=0;val expected=requested.map {scalar.bandPower(it,missing,null,null)};val slow=evaluations
        for(i in actual.indices) assertEquals(expected[i],actual[i],max(1e-10,expected[i]*1e-11))
        assertTrue(fast*5<slow,"endpoint evaluations=$fast scalar evaluations=$slow")
        assertTrue(missing.isEmpty())
    }
    @Test fun declaredLinearIntervalNeverBridgesMissingFrequencyData() {
        val complete=response();assertTrue(complete.supportsLinearBand(990.0,1010.0))
        val gap=ComplexSpeakerResponse.fromJsonString(complete.toJsonString().replace("\"maximumInterpolationGapHz\":200.0","\"maximumInterpolationGapHz\":50.0").replace("\"maximumInterpolationGapHz\":200,","\"maximumInterpolationGapHz\":50,"))
        assertFalse(gap.supportsLinearBand(990.0,1010.0))
        assertFalse(complete.supportsLinearBand(800.0,1000.0));assertFalse(complete.supportsLinearBand(1000.0,1200.0))
        assertFalse(complete.supportsLinearBand(Double.NaN,1100.0))
    }

    @Test fun independentlyConvergedDiffuseSpectrumMatchesDenseFrequencyReference() {
        val r=radiator(response(slope=.2,phase=.4))
        val sources=listOf(source(r),source(r,id=1,delay=.01,
            filters=DspFilterChain(listOf(DspFilter(FilterKind.PEAK,1003.0,20.0,12.0)))))
        val band=listOf(Bands.Segment(1000,900.0,1100.0));val g=geometry(listOf(wall()))
        fun engine()=CoherentDiffuse(sources,BoundaryPoint(4.0,0.0,0.0),g,Atmosphere(),band,true,true,false,BoundaryWork())
        val missing=mutableSetOf<MissingBand>()
        val integrated=engine().integratedPower(band,missing,null,null)!!
        val n=800;val tones=List(n) {val hz=900+(it+.5)*200/n;Bands.Segment(1000,hz,hz)}
        val dense=engine().bandPowers(tones,missing,null,null).average()
        assertEquals(dense,integrated,dense*2e-5);assertTrue(missing.isEmpty())
    }

    @Test fun independentGroupsDoNotPayForUnrelatedLargeDelayOffsets() {
        val g=geometry(listOf(wall()));val band=listOf(Bands.Segment(1000,990.0,1010.0))
        fun integrated(delay:Double):Double {
            val src=listOf(source(),source(),source(id=1,delay=delay))
            val missing=mutableSetOf<MissingBand>()
            val power=CoherentDiffuse(src,BoundaryPoint(4.0,0.0,0.0),g,Atmosphere(),band,false,true,false,BoundaryWork()).integratedPower(band,missing,null,null)
            assertTrue(missing.isEmpty());return power!!
        }
        assertEquals(integrated(0.0),integrated(1000.0),1e-8)
    }


    @Test fun sharedIncidentVectorsDoNotConsumeTheKeyBudgetRepeatedly() {
        val src=List(64) {source()};val g=geometry(listOf(wall()));val air=Atmosphere()
        val cache=DiffuseIncidentCache(128,maxEntries=16,maxKeyBytes=192*3)
        val context=cache.context(DiffuseIncidentCache.Context(src,g,air,true,true,null))
        val visible=cache.vector(IntArray(64) {it});val losses=cache.vector(IntArray(64))
        val materials=cache.materials(emptySet())
        val spectrum=cache.spectrum(listOf(Bands.Segment(1000,1000.0,1000.0)))
        fun key(i:Int)=DiffuseIncidentCache.Key(context,BoundaryPoint(i.toDouble(),0.0,0.0),BoundaryPoint(1.0,0.0,0.0),materials,visible,losses,spectrum)
        repeat(4) {cache.put(key(it),doubleArrayOf(it.toDouble()))}
        repeat(3) {assertEquals(it.toDouble(),cache.get(key(it))!![0])}
        assertNull(cache.get(key(3)));assertEquals(3,cache.storedValues)
        val unshared=DiffuseIncidentCache.Key(context,BoundaryPoint(9.0,0.0,0.0),BoundaryPoint(1.0,0.0,0.0),materials,
            DiffuseIncidentCache.PackedInts(IntArray(64) {it}),DiffuseIncidentCache.PackedInts(IntArray(64)),spectrum)
        val bounded=DiffuseIncidentCache(128,maxKeyBytes=192*3)
        bounded.put(unshared,doubleArrayOf(1.0));assertNull(bounded.get(unshared))
    }

    @Test fun batchedDiffuseIntegrationMatchesScalarWithCacheOverflowAndReceiverLosses() {
        val r=radiator();val src=List(64) {i->SoundSource(i/16,30f,(i%16-7.5f)*.05f,(i/16)*.2f,180f,0f,r,delayS=(i/16)*.0003)}
        val g=geometry(listOf(wall()));val air=Atmosphere()
        val bands=listOf(Bands.Segment(1000,990.0,1010.0))
        val cache=DiffuseIncidentCache(128)
        for(receiver in listOf(40.0,50.0)) {
            fun calculate(batch:Boolean,c:DiffuseIncidentCache?):Double {
                val missing=mutableSetOf<MissingBand>()
                val engine=CoherentDiffuse(src,BoundaryPoint(receiver,0.0,0.0),g,air,bands,true,true,false,BoundaryWork(),incidentCache=c,batchTonePhases=batch)
                val value=engine.integratedPower(bands,missing,floatArrayOf(1f,2f,3f,4f),ReceiverPathLoss {_,_->2f})
                assertTrue(missing.isEmpty());return value!!
            }
            val scalar=calculate(false,null)
            assertEquals(scalar,calculate(true,null),max(1e-10,scalar*1e-10))
            assertEquals(scalar,calculate(true,cache),max(1e-10,scalar*1e-10))
        }
        assertTrue(cache.storedValues in 1..128)
    }

    @Test fun incidentSpectrumCachePreservesReceiverAirLossPathLossesAndBoundedOverflow() {
        val r=radiator();val src=List(32) {i->SoundSource(0,30f,(i-15.5f)*.05f,0f,180f,0f,r)}
        val g=geometry(listOf(wall()));val air=Atmosphere();val cache=DiffuseIncidentCache(128)
        val tones=List(48) {val f=990+(it+.5)*20/48;Bands.Segment(1000,f,f)}
        val missing=mutableSetOf<MissingBand>()
        fun run(receiver:Double,c:DiffuseIncidentCache?,loss:Float=0f,groupLoss:Float=0f,a:Atmosphere=air,constant:Boolean=false):DoubleArray {
            val engine=CoherentDiffuse(src,BoundaryPoint(receiver,0.0,0.0),g,a,segments,true,true,false,BoundaryWork(),incidentCache=c)
            val path:(SoundSource,BoundaryPoint?,Int)->Float=if(constant) ReceiverPathLoss {_,_->loss} else {_,_,_->loss}
            return engine.bandPowers(tones,missing,floatArrayOf(groupLoss),path)
        }
        for(receiver in listOf(40.0,50.0)) {
            val actual=run(receiver,cache);val expected=run(receiver,null)
            for(i in actual.indices) assertEquals(expected[i],actual[i],max(1e-10,expected[i]*1e-11))
        }
        assertTrue(cache.hits>0);assertTrue(cache.storedValues in 1..128)
        for((loss,group) in listOf(6f to 0f,0f to 3f)) {
            val expected=run(40.0,null,loss,group)
            for(constant in listOf(false,true)) {
                val actual=run(40.0,cache,loss,group,constant=constant)
                for(i in actual.indices) assertEquals(expected[i],actual[i],max(1e-10,expected[i]*1e-11))
            }
        }
        val dry=Atmosphere(humidityPct=10f)
        val dryExpected=run(50.0,null,a=dry);val dryActual=run(50.0,cache,a=dry)
        for(i in dryActual.indices) assertEquals(dryExpected[i],dryActual[i],max(1e-10,dryExpected[i]*1e-11))
        assertTrue(missing.isEmpty());assertTrue(cache.storedValues<=128)
    }

}
