package com.droidacoustic.pro.acoustics

import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class PolarGridPerformanceTest {
    @Test fun primitiveLookupMatchesIndependentBilinearReferenceAcrossKnotsAndSeam() {
        val az=listOf(0f,17f,91f,185f,310f);val el=listOf(-80f,-30f,0f,23f,80f)
        val rows=az.map {a->el.map {e->(sin(a*.1)+cos(e*.07)).toFloat()*40}}
        val grid=PolarGrid(az,el,rows)
        fun reference(a:Float,e:Float):Float {
            val angle=((a%360)+360)%360
            val ai=az.indexOfLast {it<=angle}.let {if(it<0) az.lastIndex else it};val aj=(ai+1)%az.size
            val ei=el.indexOfLast {it<=e};val ej=(ei+1).coerceAtMost(el.lastIndex)
            val width=(az[aj]-az[ai]).let {if(it<=0) it+360 else it}
            val offset=(angle-az[ai]).let {if(it<0) it+360 else it}
            val ta=offset/width;val te=if(ei==ej) 0f else (e-el[ei])/(el[ej]-el[ei])
            return (rows[ai][ei]*(1-te)+rows[ai][ej]*te)*(1-ta)+(rows[aj][ei]*(1-te)+rows[aj][ej]*te)*ta
        }
        val random=Random(38)
        val points=az.flatMap {a->el.map {a to it}}+List(2000) {random.nextDouble(-720.0,720.0).toFloat() to random.nextDouble(-80.0,80.0).toFloat()}
        for((a,e) in points) assertEquals(reference(a,e),grid.at(a,e),1e-5f)
        assertTrue(grid.at(0f,81f).isNaN());assertTrue(grid.at(Float.NaN,0f).isNaN())
        assertTrue(grid.at(Float.POSITIVE_INFINITY,0f).isNaN());assertTrue(grid.at(0f,Float.NaN).isNaN())
    }
    @Test fun constantOptimizationPreservesMissingAngularCoverageAndFrozenInput() {
        val row=mutableListOf(3f,3f);val grid=PolarGrid(listOf(0f),listOf(-10f,10f),listOf(row))
        row[0]=9f
        assertEquals(3f,grid.at(359f,0f));assertEquals(3f to 3f,grid.componentRange(359f,0f,9f))
        assertTrue(grid.at(0f,11f).isNaN());assertNull(grid.componentRange(0f,0f,11f))
        assertNull(grid.componentRange(Float.NaN,0f,1f))
        val invalid=PolarGrid(listOf(0f),listOf(-90f,90f),listOf(listOf(Float.NaN,0f)))
        assertTrue(invalid.at(0f,0f).isNaN())
    }

    @Test fun componentBoundsMatchKnotAndEdgeReference() {
        val az=listOf(0f,17f,91f,185f,310f);val el=listOf(-90f,-30f,0f,23f,90f)
        val grid=PolarGrid(az,el,az.map {a->el.map {e->(sin(a*.1)+cos(e*.07)).toFloat()*40}})
        val random=Random(91)
        repeat(2000) {
            val a=random.nextDouble(-360.0,720.0).toFloat()
            val e=random.nextDouble(-80.0,80.0).toFloat()
            val radius=random.nextDouble(0.0,10.0).toFloat()
            val low=(e-radius).coerceAtLeast(-90f);val high=(e+radius).coerceAtMost(90f)
            val span=if(low<=-90f || high>=90f) 180f else
                (asin((sin(radius*PI/180)/cos(e*PI/180)).coerceIn(-1.0,1.0))*180/PI).toFloat()
            val angle=((a%360)+360)%360;val left=angle-span;val right=angle+span
            val aa=listOf(left,right)+az.flatMap {k->listOf(k-360f,k,k+360f)}.filter {it>left && it<right}
            val ee=listOf(low,high)+el.filter {it>low && it<high}
            val values=aa.flatMap {x->ee.map {y->grid.at(x,y)}}
            assertEquals(values.min() to values.max(),grid.componentRange(a,e,radius))
        }
    }
}
