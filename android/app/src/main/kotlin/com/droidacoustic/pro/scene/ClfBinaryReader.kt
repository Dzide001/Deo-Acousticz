package com.droidacoustic.pro.scene

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Reader for the CF1 and CF2 binary distribution formats.
 *
 * CF2 is the closed half of the Common Loudspeaker Format: the CLF Group
 * publishes the TAB text definition but not this one. The layout below was
 * established by measurement against the bundled corpus and verified against
 * ground truth - `CLF2_XD12.tab` is the published text source for
 * `Martin Audio-XD12.CF2`, and this reader reproduces all 27 of its bands to
 * within float32 quantisation. See `docs/clf_format_notes.md` for how each
 * field was pinned down.
 *
 * It reads files the user already holds, on their own device, and produces the
 * same [ClfTabParser.Speaker] the TAB reader does, so everything downstream -
 * the registry, the per-element lookup, the coverage edges - is unchanged.
 *
 * ## Layout
 *
 * | Offset | Meaning |
 * |---|---|
 * | `0x0000` | magic `0x000ABD41` |
 * | `0x0014` | version string (`v1.0h`, `v2.0c`, `v2.1a`, …) |
 * | `0x0034` | measuring organisation - *not* the manufacturer |
 * | `0x0138` | model name |
 * | `0x0238` | manufacturer |
 * | `0x0338` | description |
 * | `0x1000` | 30 × f32 axial spectrum, dB @ 1 W / 1 m |
 * | `0x1210` | u32 MINBAND, u32 MAXBAND - indices into the third-octave table |
 * | `0x3798` | balloon: 30 × 72 × 37 f32, dB relative to on axis |
 *
 * The balloon offset is stored nowhere - every u32 in every file was searched -
 * so it is positional. It sits at `0x3798` in all 669 CF2 files of the corpus,
 * and [findBalloon] falls back to a scan rather than assuming it.
 */
object ClfBinaryReader {

    const val MAGIC_CF2 = 0x000ABD41
    const val MAGIC_CF1 = 0x000ABD40

    /**
     * What separates the two formats, beyond the grid: CF2 lays out all 30
     * third-octave slots and leaves the ones outside MIN/MAXBAND as zeros or
     * NaN, while CF1 stores only the declared bands back to back. Reading a CF1
     * as though it held all ten is what made it look undecodable.
     */
    private data class Layout(
        val arcs: Int,
        val samples: Int,
        val resolutionDeg: Int,
        val slots: Int,
        val bandTable: List<Int>,
        val storesEverySlot: Boolean,
        val usualOffsets: List<Int>
    ) {
        val perBand: Int get() = arcs * samples
    }

    private val CF2 = Layout(
        arcs = 72, samples = 37, resolutionDeg = 5, slots = 30,
        bandTable = ClfTabParser.THIRD_OCTAVE_HZ,
        storesEverySlot = true,
        usualOffsets = listOf(0x3798)
    )

    private val CF1 = Layout(
        arcs = 36, samples = 19, resolutionDeg = 10, slots = 10,
        bandTable = ClfTabParser.OCTAVE_HZ,
        storesEverySlot = false,
        usualOffsets = listOf(0x23a8, 0x2e58)
    )

    private const val OFF_VERSION = 0x0014
    private const val OFF_MODEL = 0x0138
    private const val OFF_MANUFACTURER = 0x0238
    private const val OFF_DESCRIPTION = 0x0338
    private const val OFF_AXIAL = 0x1000
    private const val OFF_BAND_RANGE = 0x1210
    private const val USUAL_BALLOON = 0x3798

    /** Poles must agree to better than this across all 72 arcs. */
    private const val POLE_TOL = 0.01f

    class ParseException(message: String) : IllegalArgumentException(message)

    fun parseOrNull(bytes: ByteArray, speakerId: String = ""): ClfTabParser.Speaker? =
        runCatching { parse(bytes, speakerId) }.getOrNull()

    fun parse(bytes: ByteArray, speakerId: String = ""): ClfTabParser.Speaker {
        // Magic before size: a CF1 file is smaller than any CF2 and would
        // otherwise be turned away as "too small" instead of being named.
        if (bytes.size < 8) throw ParseException("too small to be a CLF file (${bytes.size} bytes)")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val layout = when (val magic = buf.getInt(0)) {
            MAGIC_CF2 -> CF2
            MAGIC_CF1 -> CF1
            else -> throw ParseException("not a CLF binary: magic 0x%08X".format(magic))
        }

        val lo = buf.getInt(OFF_BAND_RANGE)
        val hi = buf.getInt(OFF_BAND_RANGE + 4)
        if (lo < 0 || hi <= lo || hi > layout.slots - 1) {
            throw ParseException("${describe(bytes)}: band range $lo..$hi is not usable")
        }

        val storedBands = if (layout.storesEverySlot) layout.slots else hi - lo + 1
        val payload = storedBands * layout.perBand * 4
        if (bytes.size < payload) {
            throw ParseException("too small to hold its balloon (${bytes.size} bytes)")
        }

        val offset = findBalloon(buf, bytes.size, layout, lo, hi, storedBands)
            ?: throw ParseException(
                "${describe(bytes)}: could not find the directivity balloon - " +
                    "the file may be a variant this reader has not seen"
            )

        val bands = (lo..hi).mapNotNull { slot ->
            // CF2 indexes the slot directly; CF1 packs the declared bands from
            // the start, so the same band sits at a different place in the block.
            val stored = if (layout.storesEverySlot) slot else slot - lo
            readBand(buf, offset, layout, stored)?.let { arcs ->
                ClfTabParser.Band(
                    frequencyHz = layout.bandTable[slot],
                    attenuationDb = arcs,
                    resolutionDeg = layout.resolutionDeg
                )
            }
        }
        if (bands.isEmpty()) throw ParseException("no usable bands in $lo..$hi")

        val sensitivity = (lo..hi)
            .map { buf.getFloat(OFF_AXIAL + it * 4) }
            .filter { it.isFinite() && it > 0f }
            .takeIf { it.isNotEmpty() }?.average()?.toFloat()

        val model = readString(bytes, OFF_MODEL)
        return ClfTabParser.Speaker(
            speakerId = speakerId.ifBlank { slug(model) },
            manufacturer = readString(bytes, OFF_MANUFACTURER),
            model = model,
            description = readString(bytes, OFF_DESCRIPTION),
            resolutionDeg = layout.resolutionDeg,
            sensitivityDb = sensitivity,
            maxInputW = null,
            bands = bands,
            tags = mapOf(
                "<FORMAT>" to listOf(if (layout === CF2) "CF2" else "CF1"),
                "<VERSION>" to listOf(readString(bytes, OFF_VERSION))
            )
        )
    }

    /**
     * One band as `[arc][sample]`, or null when the slot holds no usable data.
     *
     * Arcs are reversed on the way out. The binary stores them in the same order
     * as the rows of the TAB source, and the XD12's TAB declares
     * `<BALLOON-ARC-ORDER> <reversed>`, so the same normalisation the TAB reader
     * applies has to happen here for the two paths to agree. That flag was not
     * found anywhere in the binary header, so this is calibrated on the one file
     * where both formats are available. It mirrors phi, which swaps left and
     * right and leaves up and down alone - so a box with an asymmetric
     * horizontal pattern is the only case where getting it wrong would show.
     */
    private fun readBand(
        buf: ByteBuffer,
        offset: Int,
        layout: Layout,
        stored: Int
    ): Array<FloatArray>? {
        val base = offset + stored * layout.perBand * 4
        val arcs = Array(layout.arcs) { arc ->
            FloatArray(layout.samples) { s ->
                buf.getFloat(base + (arc * layout.samples + s) * 4)
            }
        }
        if (arcs.any { row -> row.any { !it.isFinite() } }) return null
        return (listOf(arcs[0]) + arcs.drop(1).reversed()).toTypedArray()
    }

    /**
     * Locate the balloon: the usual place first, then a scan.
     *
     * The judge in both cases is the geometry. Sample 0 and sample 36 of every
     * arc are the same two directions in space - dead ahead and dead behind - so
     * all 72 arcs must agree on them. That single property is what separates the
     * balloon from the other float arrays in the file, and it caught two
     * plausible-looking false positives while this was being worked out.
     */
    private fun findBalloon(
        buf: ByteBuffer,
        size: Int,
        layout: Layout,
        lo: Int,
        hi: Int,
        storedBands: Int
    ): Int? {
        val payload = storedBands * layout.perBand * 4
        layout.usualOffsets.forEach { candidate ->
            if (candidate + payload <= size &&
                isBalloon(buf, candidate, layout, lo, hi, storedBands, requireShape = false)
            ) {
                return candidate
            }
        }
        val lastOffset = size - payload
        val lastStored = if (layout.storesEverySlot) hi else storedBands - 1
        val poleStride = lastStored * layout.perBand * 4
        var offset = 0x100
        while (offset <= lastOffset) {
            // Cheap gate before the full check: the first two arcs of the last
            // band have to agree about the on-axis direction.
            val a = buf.getFloat(offset + poleStride)
            val b = buf.getFloat(offset + poleStride + layout.samples * 4)
            if (a.isFinite() && b.isFinite() && abs(a - b) <= POLE_TOL &&
                isBalloon(buf, offset, layout, lo, hi, storedBands, requireShape = true)
            ) {
                return offset
            }
            offset += 4
        }
        return null
    }

    private fun isBalloon(
        buf: ByteBuffer,
        offset: Int,
        layout: Layout,
        lo: Int,
        hi: Int,
        storedBands: Int,
        requireShape: Boolean
    ): Boolean {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        val range = if (layout.storesEverySlot) lo..hi else 0 until storedBands
        for (stored in range) {
            val base = offset + stored * layout.perBand * 4
            var frontLo = Float.MAX_VALUE; var frontHi = -Float.MAX_VALUE
            var rearLo = Float.MAX_VALUE; var rearHi = -Float.MAX_VALUE
            var flat = true
            var first = Float.NaN
            for (arc in 0 until layout.arcs) {
                val rowBase = base + arc * layout.samples * 4
                val front = buf.getFloat(rowBase)
                val rear = buf.getFloat(rowBase + (layout.samples - 1) * 4)
                if (!front.isFinite() || !rear.isFinite()) return false
                frontLo = minOf(frontLo, front); frontHi = maxOf(frontHi, front)
                rearLo = minOf(rearLo, rear); rearHi = maxOf(rearHi, rear)
                for (s in 0 until layout.samples) {
                    val v = buf.getFloat(rowBase + s * 4)
                    if (!v.isFinite()) return false
                    if (v < -200f || v > 80f) return false
                    if (first.isNaN()) first = v else if (v != first) flat = false
                    min = minOf(min, v); max = maxOf(max, v)
                }
            }
            // An untouched slot inside the declared range is allowed to be flat;
            // a populated one must agree with itself at both poles.
            if (!flat) {
                if (frontHi - frontLo > POLE_TOL) return false
                if (rearHi - rearLo > POLE_TOL) return false
            }
        }
        // A blind scan needs this - a flat block of numbers is not a balloon. At
        // the known offset it must not be applied: a subwoofer really is almost
        // omnidirectional, and d&b's T-SUB spans only 4.9 dB across its range.
        if (requireShape && max - min < 5f) return false
        return true
    }

    /**
     * Name a CLF binary from its header, without decoding any acoustics.
     *
     * CF1 shares CF2's string table - the offsets below read correctly out of
     * both - so a file that cannot be decoded can still be identified. Telling
     * someone "Martin Audio C4,8T is a CF1 file" is worth a great deal more than
     * "unsupported format".
     */
    fun describe(bytes: ByteArray): String {
        if (bytes.size < OFF_DESCRIPTION) return "this file"
        val model = readString(bytes, OFF_MODEL)
        val manufacturer = readString(bytes, OFF_MANUFACTURER)
        return listOf(manufacturer, model).filter { it.isNotBlank() }
            .joinToString(" ").ifBlank { "this file" }
    }

    /** NUL-terminated Latin-1, the encoding these files use throughout. */
    private fun readString(bytes: ByteArray, offset: Int, max: Int = 96): String {
        if (offset >= bytes.size) return ""
        var end = offset
        val limit = minOf(bytes.size, offset + max)
        while (end < limit && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.ISO_8859_1)
            .filter { it.code in 32..126 || it.code in 160..255 }
            .trim()
    }

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "cf2_import" }
}
