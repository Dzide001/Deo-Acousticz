package com.droidacoustic.pro.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The CF2 binary reader.
 *
 * CF2 is the closed half of the format, so unlike the TAB reader this one rests
 * on measurement rather than a published definition. That makes the tests more
 * important, not less, and they are built around the one place where both
 * formats describe the same loudspeaker: `CLF2_XD12.tab` is the published text
 * source for `Martin Audio-XD12.CF2`. If the binary reader and the text reader
 * disagree about that box, the binary reader is wrong.
 *
 * The corpus is third-party data and deliberately not in the repo, so the tests
 * that need it skip rather than fail when it is absent.
 */
class ClfCf2ReaderTest {

    private fun corpus(): File? = listOf("../corpus/clf", "../../corpus/clf")
        .map(::File).firstOrNull { it.isDirectory }

    private fun xd12Cf2() = corpus()?.resolve("martin_audio/Martin Audio-XD12.CF2")?.takeIf { it.exists() }
    private fun xd12Tab() = corpus()?.resolve("martin_audio/CLF2_XD12.tab")?.takeIf { it.exists() }

    // ── Rejections, which need no corpus ─────────────────────────────────────

    @Test
    fun `something far too small is rejected`() {
        val e = runCatching { ClfCf2Reader.parse(ByteArray(64)) }.exceptionOrNull()
        assertTrue(e is ClfCf2Reader.ParseException)
        assertTrue(e!!.message!!, e.message!!.contains("too small"))
    }

    @Test
    fun `a file with the wrong magic is rejected by name`() {
        val junk = ByteArray(2_000_000)
        val e = runCatching { ClfCf2Reader.parse(junk) }.exceptionOrNull()
        assertTrue(e is ClfCf2Reader.ParseException)
        assertTrue(e!!.message!!, e.message!!.contains("not a CLF binary"))
    }

    @Test
    fun `a CF1 file is turned away with something the user can act on`() {
        val cf1 = ByteArray(2_000_000)
        // 0x000ABD40, little endian.
        cf1[0] = 0x40; cf1[1] = 0xBD.toByte(); cf1[2] = 0x0A; cf1[3] = 0x00
        val e = runCatching { ClfCf2Reader.parse(cf1) }.exceptionOrNull()
        assertTrue(e is ClfCf2Reader.ParseException)
        assertTrue(e!!.message!!, e.message!!.contains("CF1"))
        assertTrue("should say what to do instead", e.message!!.contains(".tab"))
    }

    @Test
    fun `parseOrNull swallows the failure`() {
        assertNull(ClfCf2Reader.parseOrNull(ByteArray(64)))
    }

    // ── Against the published text source ────────────────────────────────────

    @Test
    fun `the binary reader agrees with the text reader, band for band`() {
        val cf2 = xd12Cf2(); val tab = xd12Tab()
        assumeTrue("corpus not present - third-party data, not in the repo", cf2 != null && tab != null)

        val fromBinary = ClfCf2Reader.parse(cf2!!.readBytes())
        val fromText = ClfTabParser.parse(tab!!.readText(Charsets.ISO_8859_1))

        assertEquals("band count", fromText.bands.size, fromBinary.bands.size)
        assertEquals(fromText.bands.map { it.frequencyHz }, fromBinary.bands.map { it.frequencyHz })

        var worst = 0f
        fromText.bands.zip(fromBinary.bands).forEach { (t, b) ->
            assertEquals("arcs at ${t.frequencyHz} Hz", t.arcCount, b.arcCount)
            assertEquals("samples at ${t.frequencyHz} Hz", t.samplesPerArc, b.samplesPerArc)
            for (arc in 0 until t.arcCount) {
                for (s in 0 until t.samplesPerArc) {
                    worst = maxOf(worst, kotlin.math.abs(t.attenuationDb[arc][s] - b.attenuationDb[arc][s]))
                }
            }
        }
        // The text carries one decimal place, so float32 quantisation is the
        // only difference there should be.
        assertTrue("worst disagreement $worst dB", worst < 1e-3f)
    }

    @Test
    fun `metadata comes off the binary, not the filename`() {
        val cf2 = xd12Cf2()
        assumeTrue("corpus not present", cf2 != null)
        val s = ClfCf2Reader.parse(cf2!!.readBytes())
        assertEquals("XD12", s.model)
        assertEquals("Martin Audio", s.manufacturer)
        assertEquals(5, s.resolutionDeg)
        assertTrue("description should be read too", s.description.contains("two-way"))
        assertNotNull(s.sensitivityDb)
        assertTrue("sensitivity ${s.sensitivityDb} looks wrong", s.sensitivityDb!! in 85f..105f)
    }

    @Test
    fun `the two readers put the coverage edges in the same place`() {
        val cf2 = xd12Cf2(); val tab = xd12Tab()
        assumeTrue("corpus not present", cf2 != null && tab != null)
        val binary = ClfTabParser.toClfData(ClfCf2Reader.parse(cf2!!.readBytes()))
        val text = ClfTabParser.toClfData(ClfTabParser.parse(tab!!.readText(Charsets.ISO_8859_1)))
        listOf(0f to 0f, 30f to 0f, 0f to 20f, -45f to -10f).forEach { (az, el) ->
            assertEquals(
                "direction ($az, $el)",
                text.splAtDirection(2000, az, el)!!,
                binary.splAtDirection(2000, az, el)!!,
                1e-3f
            )
        }
    }

    // ── The whole corpus ─────────────────────────────────────────────────────

    @Test
    fun `every CF2 file in the corpus decodes`() {
        val root = corpus()
        assumeTrue("corpus not present", root != null)
        val files = root!!.walkTopDown()
            .filter { it.isFile && it.extension.equals("cf2", ignoreCase = true) }
            .toList()
        assumeTrue("corpus present but holds no CF2 files", files.isNotEmpty())

        val failures = mutableListOf<String>()
        var poleFailures = 0
        files.forEach { f ->
            val s = ClfCf2Reader.parseOrNull(f.readBytes())
            if (s == null || s.bands.isEmpty()) {
                failures += f.name
            } else {
                // Every decoded band must still satisfy the geometry that
                // located it in the first place.
                s.bands.forEach { b ->
                    if (ClfTabParser.balloonIntegrityError(b) != null) poleFailures++
                }
            }
        }
        assertEquals("files that would not decode: $failures", emptyList<String>(), failures)
        assertEquals("decoded bands failing the pole check", 0, poleFailures)
    }

    @Test
    fun `decoded balloons are normalised relative to the on-axis direction`() {
        val root = corpus()
        assumeTrue("corpus not present", root != null)
        val files = root!!.walkTopDown()
            .filter { it.isFile && it.extension.equals("cf2", ignoreCase = true) }
            .take(40).toList()
        assumeTrue("no CF2 files", files.isNotEmpty())
        files.forEach { f ->
            val s = ClfCf2Reader.parse(f.readBytes())
            s.bands.forEach { b ->
                val onAxis = b.at(0f, 0f)
                assertTrue("${f.name} at ${b.frequencyHz} Hz: on axis reads $onAxis",
                           onAxis in -1f..3f)
                assertTrue("${f.name} at ${b.frequencyHz} Hz has an absurd value",
                           b.attenuationDb.all { row -> row.all { it > -200f && it < 80f } })
            }
        }
    }
}
