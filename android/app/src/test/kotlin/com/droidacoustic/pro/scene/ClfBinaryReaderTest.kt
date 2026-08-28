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
class ClfBinaryReaderTest {

    private fun corpus(): File? = listOf("../corpus/clf", "../../corpus/clf")
        .map(::File).firstOrNull { it.isDirectory }

    private fun xd12Cf2() = corpus()?.resolve("martin_audio/Martin Audio-XD12.CF2")?.takeIf { it.exists() }
    private fun xd12Tab() = corpus()?.resolve("martin_audio/CLF2_XD12.tab")?.takeIf { it.exists() }

    // ── Rejections, which need no corpus ─────────────────────────────────────

    @Test
    fun `a CF2 file truncated mid-balloon is rejected as too small`() {
        // Valid magic and a believable band range, but not enough file behind
        // them - which is what a half-copied download actually looks like.
        val stub = ByteArray(60_000)
        stub[0] = 0x41; stub[1] = 0xBD.toByte(); stub[2] = 0x0A; stub[3] = 0x00
        stub[0x1210] = 3                      // MINBAND
        stub[0x1214] = 29                     // MAXBAND
        val e = runCatching { ClfBinaryReader.parse(stub) }.exceptionOrNull()
        assertTrue(e is ClfBinaryReader.ParseException)
        assertTrue(e!!.message!!, e.message!!.contains("too small"))
    }

    @Test
    fun `a handful of bytes is rejected without reading past the end`() {
        listOf(0, 1, 4, 7, 64).forEach { n ->
            val e = runCatching { ClfBinaryReader.parse(ByteArray(n)) }.exceptionOrNull()
            assertTrue("$n bytes should be refused", e is ClfBinaryReader.ParseException)
        }
    }

    @Test
    fun `a file with the wrong magic is rejected by name`() {
        val junk = ByteArray(2_000_000)
        val e = runCatching { ClfBinaryReader.parse(junk) }.exceptionOrNull()
        assertTrue(e is ClfBinaryReader.ParseException)
        assertTrue(e!!.message!!, e.message!!.contains("not a CLF binary"))
    }

    @Test
    fun `a CF1 file decodes on its own grid`() {
        // CF1 is 10 degrees and octave bands, and unlike CF2 it stores only the
        // bands it declares rather than all ten slots. Reading it as though it
        // held every slot is what made it look undecodable.
        val root = corpus()
        assumeTrue("corpus not present", root != null)
        val cf1 = root!!.walkTopDown()
            .firstOrNull { it.isFile && it.extension.equals("cf1", ignoreCase = true) }
        assumeTrue("corpus present but holds no CF1 files", cf1 != null)

        val s = ClfBinaryReader.parse(cf1!!.readBytes())
        assertEquals(10, s.resolutionDeg)
        assertEquals("CF1", s.tags["<FORMAT>"]?.firstOrNull())
        assertTrue(
            "should carry octave bands, got ${s.bands.map { it.frequencyHz }}",
            s.bands.all { it.frequencyHz in ClfTabParser.OCTAVE_HZ }
        )
        s.bands.forEach { b ->
            assertEquals("arcs at ${b.frequencyHz} Hz", 36, b.arcCount)
            assertEquals("samples at ${b.frequencyHz} Hz", 19, b.samplesPerArc)
            assertNull(
                "pole geometry at ${b.frequencyHz} Hz",
                ClfTabParser.balloonIntegrityError(b)
            )
        }
    }

    @Test
    fun `a CF1 file identifies itself from the same string table as CF2`() {
        val root = corpus()
        assumeTrue("corpus not present", root != null)
        val cf1 = root!!.walkTopDown()
            .firstOrNull { it.isFile && it.extension.equals("cf1", ignoreCase = true) }
        assumeTrue("corpus present but holds no CF1 files", cf1 != null)
        val described = ClfBinaryReader.describe(cf1!!.readBytes())
        assertTrue("should name the speaker, got '$described'", described.isNotBlank())
        assertTrue("should not fall back to the placeholder", described != "this file")
    }

    @Test
    fun `describe never throws on rubbish`() {
        assertEquals("this file", ClfBinaryReader.describe(ByteArray(0)))
        assertEquals("this file", ClfBinaryReader.describe(ByteArray(16)))
        assertTrue(ClfBinaryReader.describe(ByteArray(5000)).isNotBlank())
    }

    @Test
    fun `parseOrNull swallows the failure`() {
        assertNull(ClfBinaryReader.parseOrNull(ByteArray(64)))
    }

    // ── Against the published text source ────────────────────────────────────

    @Test
    fun `the binary reader agrees with the text reader, band for band`() {
        val cf2 = xd12Cf2(); val tab = xd12Tab()
        assumeTrue("corpus not present - third-party data, not in the repo", cf2 != null && tab != null)

        val fromBinary = ClfBinaryReader.parse(cf2!!.readBytes())
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
        val s = ClfBinaryReader.parse(cf2!!.readBytes())
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
        val binary = ClfTabParser.toClfData(ClfBinaryReader.parse(cf2!!.readBytes()))
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
            val s = ClfBinaryReader.parseOrNull(f.readBytes())
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
    fun `every CF1 file in the corpus decodes`() {
        val root = corpus()
        assumeTrue("corpus not present", root != null)
        val files = root!!.walkTopDown()
            .filter { it.isFile && it.extension.equals("cf1", ignoreCase = true) }
            .toList()
        assumeTrue("corpus present but holds no CF1 files", files.isNotEmpty())

        val failures = mutableListOf<String>()
        var poleFailures = 0
        files.forEach { f ->
            val s = ClfBinaryReader.parseOrNull(f.readBytes())
            if (s == null || s.bands.isEmpty()) {
                failures += f.name
            } else {
                s.bands.forEach { b ->
                    if (ClfTabParser.balloonIntegrityError(b) != null) poleFailures++
                }
            }
        }
        assertEquals("files that would not decode: $failures", emptyList<String>(), failures)
        assertEquals("decoded bands failing the pole check", 0, poleFailures)
    }

    @Test
    fun `the two formats are told apart by magic, not by extension`() {
        val root = corpus()
        assumeTrue("corpus not present", root != null)
        val cf1 = root!!.walkTopDown().firstOrNull { it.isFile && it.extension.equals("cf1", true) }
        val cf2 = root.walkTopDown().firstOrNull { it.isFile && it.extension.equals("cf2", true) }
        assumeTrue("need one of each", cf1 != null && cf2 != null)
        assertEquals("CF1", ClfBinaryReader.parse(cf1!!.readBytes()).tags["<FORMAT>"]?.first())
        assertEquals("CF2", ClfBinaryReader.parse(cf2!!.readBytes()).tags["<FORMAT>"]?.first())
        assertEquals(10, ClfBinaryReader.parse(cf1.readBytes()).resolutionDeg)
        assertEquals(5, ClfBinaryReader.parse(cf2.readBytes()).resolutionDeg)
    }

    @Test
    fun `decoded balloons are normalised relative to the on-axis direction`() {
        val root = corpus()
        assumeTrue("corpus not present", root != null)
        val files = root!!.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("cf1", "cf2") }
            .take(40).toList()
        assumeTrue("no CLF binaries", files.isNotEmpty())
        files.forEach { f ->
            val s = ClfBinaryReader.parse(f.readBytes())
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
