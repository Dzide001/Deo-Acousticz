package com.droidacoustic.pro.scene

import com.droidacoustic.pro.acoustics.*
import kotlin.test.*

class ClfReferenceSlotsTest {
    private fun document(row:String,format:Int=2)=buildString {
        appendLine("<CLF$format>");appendLine(row)
        appendLine("<BALLOON-SYMMETRY>\t<rotational>")
        for(hz in listOf(500,1000,2000)) {
            appendLine("<BAND>\t$hz");appendLine(List(if(format==2) 37 else 19){"0"}.joinToString("\t"))
        }
    }
    @Test fun explicitSensitivityHolesKeepTheirBandIdentityAndDoNotBorrowAxialLevels() {
        for(format in listOf(1,2)) {
            val axial=List(30){"120"}.joinToString("\t")
            val data=ClfTabParser.toClfData(ClfTabParser.parse(document("<SENSITIVITY>\t80\t<nodata>\t96\n<AXIAL-SPECTRUM>\t$axial",format)))
            assertEquals(mapOf(500 to 80f,2000 to 96f),data.sensitivityDbByBand)
            assertTrue(data.measuredRadiator().levelDb(1000,0f,0f).isNaN())
            val restored=assertNotNull(ClfParser.parseJson(ClfParser.toJson(data)))
            assertEquals(data.sensitivityDbByBand,restored.sensitivityDbByBand)
            assertTrue(restored.measuredRadiator().levelDb(1000,0f,0f).isNaN())
        }
    }
    @Test fun thirdOctaveAxialFallbackPreservesNodataSlotsAndCf1DoesNotInferOctaveDrive() {
        val tokens=ClfTabParser.THIRD_OCTAVE_HZ.map {when(it) {500->"80";2000->"96";else->"<nodata>"}}
        val text=document("<AXIAL-SPECTRUM>\t"+tokens.joinToString("\t"))
        assertEquals(mapOf(500 to 80f,2000 to 96f),ClfTabParser.parse(text).sensitivityByBand)
        assertTrue(ClfTabParser.parse(text.replace("<CLF2>","<CLF1>").replace(List(37){"0"}.joinToString("\t"),List(19){"0"}.joinToString("\t"))).sensitivityByBand.isEmpty())
    }
    @Test fun malformedReferenceRowsFailInsteadOfShiftingOrFallingBack() {
        for(row in listOf("80\t96","80\tNaN\t96","80\toops\t96","80\tInfinity\t96"))
            assertFailsWith<ClfTabParser.ParseException> {ClfTabParser.parse(document("<SENSITIVITY>\t$row"))}
        assertTrue(ClfTabParser.parse(document("<SENSITIVITY>\t<nodata>\t<nodata>\t<nodata>")).sensitivityByBand.isEmpty())
    }
}
