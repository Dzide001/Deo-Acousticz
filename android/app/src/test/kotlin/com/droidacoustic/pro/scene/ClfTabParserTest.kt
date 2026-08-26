package com.droidacoustic.pro.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The CLF TAB reader.
 *
 * TAB is the published half of the format, so unlike the CF2 work this needs no
 * reverse engineering and carries no licensing question - which is exactly why
 * it is the route in. The tests come in two halves:
 *
 * - Synthetic documents built in-memory, which run everywhere and pin the
 *   parsing rules and the coordinate conversion.
 * - A check against the real `CLF2_XD12.tab` when the corpus happens to be
 *   present. The corpus is third-party data and deliberately not in the repo,
 *   so that half skips rather than fails when it is missing.
 */
class ClfTabParserTest {

    // ── Synthetic fixtures ───────────────────────────────────────────────────

    /**
     * A CLF2 document whose balloon is a cosine bowl: 0 dB on axis falling to
     * -40 dB at the back, identical in every arc. Rotationally symmetric, so
     * the answer at any direction depends only on the polar angle - which makes
     * the coordinate conversion easy to check independently.
     */
    private fun syntheticClf2(
        bands: List<Int> = listOf(1000, 2000),
        arcOrder: String = "<normal>",
        arcCount: Int = 72
    ): String = buildString {
        appendLine("<CLF2>")
        appendLine("<VERSION>\t1")
        appendLine("<MODELNAME>\tTest Box 90x50")
        appendLine("<MANUFACTURER>\tSynthetic Audio")
        appendLine("<DESCRIPTION>\tFixture, not a real loudspeaker")
        appendLine("<SENSITIVITY>\t96.0\t98.0")
        appendLine("<BALLOON-SYMMETRY>\t<none>")
        appendLine("<BALLOON-ARC-ORDER>\t$arcOrder")
        appendLine("<CABINET-SYSTEM>\t<on-axis>\t<+x>\t<up>\t<+z>")
        bands.forEach { hz ->
            appendLine("<BAND>\t$hz")
            repeat(arcCount) {
                appendLine((0..36).joinToString("\t") { t ->
                    "%.2f".format(-40.0 * (t * 5) / 180.0)
                })
            }
        }
    }

    @Test
    fun `a well-formed CLF2 document parses to its declared shape`() {
        val s = ClfTabParser.parse(syntheticClf2())
        assertEquals("Test Box 90x50", s.model)
        assertEquals("Synthetic Audio", s.manufacturer)
        assertEquals(5, s.resolutionDeg)
        assertEquals(2, s.bands.size)
        assertEquals(72, s.bands[0].arcCount)
        assertEquals(37, s.bands[0].samplesPerArc)
    }

    @Test
    fun `on axis is the reference and costs nothing`() {
        val band = ClfTabParser.parse(syntheticClf2()).bands.first()
        assertEquals(0f, ClfTabParser.attenuationDb(band, 0f, 0f), 1e-3f)
    }

    @Test
    fun `directly behind the box is the far end of the balloon`() {
        val band = ClfTabParser.parse(syntheticClf2()).bands.first()
        assertEquals(-40f, ClfTabParser.attenuationDb(band, 180f, 0f), 1e-2f)
    }

    @Test
    fun `a rotationally symmetric balloon reads the same at equal polar angles`() {
        // The fixture depends only on theta, so 30 degrees horizontally off axis
        // and 30 degrees vertically off axis must agree. If the azimuth and
        // elevation conversion were transposed or mis-signed, they would not.
        val band = ClfTabParser.parse(syntheticClf2()).bands.first()
        val horizontal = ClfTabParser.attenuationDb(band, 30f, 0f)
        val vertical = ClfTabParser.attenuationDb(band, 0f, 30f)
        val diagonalUp = ClfTabParser.attenuationDb(band, 0f, -30f)
        assertEquals(horizontal, vertical, 1e-2f)
        assertEquals(horizontal, diagonalUp, 1e-2f)
        assertEquals(-40f * 30f / 180f, horizontal, 1e-2f)
    }

    @Test
    fun `the coordinate conversion puts phi zero straight up`() {
        // Measured data says phi 0 and 180 are the vertical plane and phi 90 the
        // horizontal one. Straight up must therefore land on phi 0.
        val (thetaUp, phiUp) = ClfTabParser.toAxisRelative(0f, 90f)
        assertEquals(90f, thetaUp, 1e-3f)
        assertEquals(0f, phiUp, 1e-3f)

        val (thetaSide, phiSide) = ClfTabParser.toAxisRelative(90f, 0f)
        assertEquals(90f, thetaSide, 1e-3f)
        assertEquals(90f, phiSide, 1e-3f)

        val (thetaAhead, _) = ClfTabParser.toAxisRelative(0f, 0f)
        assertEquals(0f, thetaAhead, 1e-3f)
    }

    @Test
    fun `reversed arc order is normalised back to ascending phi`() {
        val normal = ClfTabParser.parse(syntheticClf2(arcOrder = "<normal>")).bands.first()
        val reversed = ClfTabParser.parse(syntheticClf2(arcOrder = "<reversed>")).bands.first()
        // The fixture is rotationally symmetric, so ordering cannot change the
        // values - what this pins is that reordering keeps arc 0 in place and
        // does not drop or duplicate an arc.
        assertEquals(normal.arcCount, reversed.arcCount)
        assertEquals(
            normal.attenuationDb[0].toList(),
            reversed.attenuationDb[0].toList()
        )
    }

    @Test
    fun `half and quarter symmetric balloons expand to the full circle`() {
        listOf(37, 19).forEach { stored ->
            val s = ClfTabParser.parse(syntheticClf2(arcCount = stored))
            assertEquals("$stored stored arcs", 72, s.bands.first().arcCount)
        }
    }

    // ── Integrity ────────────────────────────────────────────────────────────

    @Test
    fun `a consistent balloon passes the integrity check`() {
        val band = ClfTabParser.parse(syntheticClf2()).bands.first()
        assertNull(ClfTabParser.balloonIntegrityError(band))
    }

    @Test
    fun `an arc that disagrees about the on-axis direction is caught`() {
        // Dead ahead is one point in space. If two arcs disagree about its
        // level, the grid was read wrong.
        val band = ClfTabParser.parse(syntheticClf2()).bands.first()
        val broken = band.copy(
            attenuationDb = Array(band.arcCount) { i ->
                band.attenuationDb[i].copyOf().also { if (i == 7) it[0] = -9f }
            }
        )
        val err = ClfTabParser.balloonIntegrityError(broken)
        assertNotNull("a disagreeing on-axis value should be reported", err)
        assertTrue(err!!, err.contains("on-axis"))
    }

    // ── Rejections ───────────────────────────────────────────────────────────

    @Test
    fun `a CF2 binary is rejected with a useful message rather than a crash`() {
        val binary = String(byteArrayOf(0x41, 0xBD.toByte(), 0x0A, 0x00, 0x01, 0x00))
        val err = runCatching { ClfTabParser.parse(binary) }.exceptionOrNull()
        assertTrue("expected a parse failure", err is ClfTabParser.ParseException)
        assertTrue(err!!.message!!, err.message!!.contains("no <CLF1> or <CLF2>"))
    }

    @Test
    fun `an arc of the wrong length names the band and the arc`() {
        val doc = syntheticClf2().replace(
            "<BAND>\t1000\n" + (0..36).joinToString("\t") { "%.2f".format(-40.0 * (it * 5) / 180.0) },
            "<BAND>\t1000\n0.00\t-1.00\t-2.00"
        )
        val err = runCatching { ClfTabParser.parse(doc) }.exceptionOrNull()
        assertTrue("expected a parse failure", err is ClfTabParser.ParseException)
        assertTrue(err!!.message!!, err.message!!.contains("1000 Hz"))
    }

    @Test
    fun `an empty document is rejected, not silently accepted`() {
        assertNull(ClfTabParser.parseOrNull(""))
        assertNull(ClfTabParser.parseOrNull("just some text\nwith no tags"))
    }

    // ── Resampling into the app's own convention ─────────────────────────────

    @Test
    fun `resampling preserves the on-axis reference and the band list`() {
        val s = ClfTabParser.parse(syntheticClf2(bands = listOf(500, 1000, 2000)))
        val data = ClfTabParser.toClfData(s)
        assertEquals(3, data.patterns.size)
        assertEquals(listOf(500, 1000, 2000), data.patterns.map { it.frequencyHz })
        assertEquals(0f, data.splAtDirection(1000, 0f, 0f)!!, 1e-2f)
        assertEquals(72, data.patterns[0].azimuths.size)
        assertEquals(37, data.patterns[0].elevations.size)
    }

    // ── Against the real published file, when it is available ────────────────

    private fun xd12(): File? = listOf(
        "../corpus/clf/martin_audio/CLF2_XD12.tab",
        "../../corpus/clf/martin_audio/CLF2_XD12.tab"
    ).map(::File).firstOrNull { it.exists() }

    @Test
    fun `the published Martin Audio XD12 file parses and holds together`() {
        val f = xd12()
        assumeTrue("corpus not present - third-party data, not in the repo", f != null)
        val s = ClfTabParser.parse(f!!.readText(Charsets.ISO_8859_1))
        assertEquals("XD12", s.model)
        assertEquals("Martin Audio", s.manufacturer)
        assertEquals(27, s.bands.size)
        s.bands.forEach { band ->
            assertEquals("band ${band.frequencyHz} Hz arcs", 72, band.arcCount)
            assertEquals("band ${band.frequencyHz} Hz samples", 37, band.samplesPerArc)
            assertNull(ClfTabParser.balloonIntegrityError(band))
        }
    }

    @Test
    fun `the XD12 measures its published 90 degree horizontal pattern`() {
        val f = xd12()
        assumeTrue("corpus not present", f != null)
        val s = ClfTabParser.parse(f!!.readText(Charsets.ISO_8859_1))
        val band = s.bandNearest(4000)!!
        // Coverage angle is where the response falls 6 dB, doubled. The XD12 is
        // published as 90 degrees horizontal; this is the real check that the
        // coordinate conversion is right, because a transposed axis would
        // return the 50 degree vertical figure instead.
        fun minusSix(azSign: Float): Float {
            var a = 0f
            while (a <= 90f) {
                if (ClfTabParser.attenuationDb(band, azSign * a, 0f) <= -6f) return a
                a += 1f
            }
            return 90f
        }
        val full = minusSix(1f) + minusSix(-1f)
        assertTrue("horizontal coverage measured $full deg, expected about 90", full in 75f..105f)
    }
}

/**
 * The import path, from a document a user supplies to directivity the app can use.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClfTabImportTest {

    @org.junit.Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @org.junit.After fun tearDown() = Dispatchers.resetMain()

    private fun doc(): String = buildString {
        appendLine("<CLF2>")
        appendLine("<MODELNAME>\tImported Box")
        appendLine("<MANUFACTURER>\tSynthetic Audio")
        appendLine("<SENSITIVITY>\t97.0\t99.0")
        appendLine("<BALLOON-SYMMETRY>\t<none>")
        appendLine("<BALLOON-ARC-ORDER>\t<normal>")
        listOf(1000, 2000).forEach { hz ->
            appendLine("<BAND>\t$hz")
            repeat(72) {
                appendLine((0..36).joinToString("\t") { t -> "%.2f".format(-40.0 * (t * 5) / 180.0) })
            }
        }
    }

    @Test
    fun `a TAB document imports and registers real directivity`() {
        val v = SceneViewModel()
        assertTrue("import should succeed", v.importClfTabText(doc()))
        val id = v.selectedPresetId.value
        assertTrue("CLF data should be registered for $id", v.hasParsedClfData(id))
        assertEquals(2, v.getClfData(id)!!.patterns.size)
        assertNull(v.lastImportError.value)
    }

    @Test
    fun `importClfText routes a TAB document to the TAB reader`() {
        // The older key=value sketch would have silently produced a preset with
        // no directivity at all, which is the failure mode worth preventing.
        val v = SceneViewModel()
        assertTrue(v.importClfText(doc()))
        assertTrue(v.hasParsedClfData(v.selectedPresetId.value))
    }

    @Test
    fun `the legacy key-value sketch still works for non-TAB text`() {
        val v = SceneViewModel()
        assertTrue(v.importClfText("modelname = Simple Box\nsensitivity = 101"))
        assertTrue(v.speakerPresets.value.any { it.name == "Simple Box" })
    }

    @Test
    fun `a rejected file reports why and registers nothing`() {
        val v = SceneViewModel()
        val before = v.speakerPresets.value.size
        assertFalse(v.importClfTabText("<CLF2>\n<MODELNAME>\tBroken\n<BAND>\t1000\n0.0\t-1.0"))
        assertNotNull("the user needs to know what was wrong", v.lastImportError.value)
        assertEquals(before, v.speakerPresets.value.size)
    }

    @Test
    fun `a CF2 binary handed to the text importer fails cleanly`() {
        val v = SceneViewModel()
        assertFalse(v.importClfTabText(String(byteArrayOf(0x41, 0xBD.toByte(), 0x0A, 0x00))))
        assertNotNull(v.lastImportError.value)
    }
}
