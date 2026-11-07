package local.papernotes.hinote

import local.papernotes.hinote.HinoteTestData.P
import local.papernotes.hinote.HinoteTestData.S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HinoteBinParserTest {
    private fun err(bytes: ByteArray) = HinoteBinParser.parse(bytes).errorOrNull()

    private val sample = HinoteTestData.bin(
        listOf(
            S(listOf(P(1f, 2f, 0, 0.25f), P(3f, 4f, 7, 0.5f), P(5f, 6f, 9, 1f)), brush = 3, r = 1f, g = 0.5f, b = 0f, alpha = 0.5f, width = 3.5f),
            S(listOf(P(10f, 20f), P(30f, 40f)), uuid = ByteArray(16) { 0x40 }),
        ),
    )

    @Test
    fun validFileIsParsedExactly() {
        val bin = HinoteBinParser.parse(sample).valueOrNull()!!
        assertEquals(1000, bin.width)
        assertEquals(1195, bin.height)
        assertEquals(2, bin.strokes.size)
        val s = bin.strokes[0]
        assertEquals(3, s.brushType)
        assertEquals(1f, s.red, 0f)
        assertEquals(0.5f, s.green, 0f)
        assertEquals(0.5f, s.alpha, 0f)
        assertEquals(3.5f, s.width, 0f)
        assertEquals(3, s.points.size)
        assertEquals(7, s.points[1].timeMs)
        assertEquals(0.25f, s.points[0].pressure, 0f)
        assertEquals(40f, bin.strokes[1].points[1].y, 0f)
        assertEquals("40".repeat(16), bin.strokes[1].uuidHex)
        assertEquals(2, bin.visibleStrokes().size)
    }

    @Test
    fun emptyPageIsValid() {
        assertEquals(0, HinoteBinParser.parse(HinoteTestData.bin(emptyList())).valueOrNull()!!.strokes.size)
    }

    @Test
    fun truncatedFileIsRejectedAtEveryLength() {
        for (n in listOf(0, 5, 11, 12, 40, 76, 100, 135, 200, sample.size - 41, sample.size - 1)) {
            val e = err(sample.copyOf(n))
            assertTrue("Länge $n: $e", e != null)
        }
    }

    @Test
    fun appendedByteIsRejected() {
        assertEquals(HinoteError.TrailingBytes, err(sample + byteArrayOf(0)))
    }

    @Test
    fun wrongMagicIsRejected() {
        val bad = sample.copyOf().also { it[0] = 'X'.code.toByte() }
        assertEquals(HinoteError.BadMagic, err(bad))
        assertEquals(HinoteError.BadMagic, err(ByteArray(3)))
    }

    @Test
    fun strokeCountLimitIsEnforcedBeforeAllocation() {
        val bad = sample.copyOf()
        ByteBuffer.wrap(bad).putInt(76 + 36, HinoteBinParser.MAX_STROKES + 1)
        assertEquals(HinoteError.LimitExceeded, err(bad))
        // Anzahl passt nicht zur Dateilänge (aber unter dem Limit): Abbruch ohne große Allokation
        val big = sample.copyOf()
        ByteBuffer.wrap(big).putInt(76 + 36, 150_000)
        assertEquals(HinoteError.Truncated, err(big))
    }

    @Test
    fun pointCountMismatchIsRejected() {
        val bad = sample.copyOf()
        // Punktzahl des ersten Strichs: Strichanfang 136, +48 Kopf +72 -> Punktblock, +4
        ByteBuffer.wrap(bad).putInt(136 + 48 + 72 + 4, Int.MAX_VALUE)
        assertEquals(HinoteError.Inconsistent, err(bad))
    }

    @Test
    fun nonFiniteCoordinateIsRejected() {
        val bad = sample.copyOf()
        ByteBuffer.wrap(bad).putFloat(136 + 48 + 72 + 20, Float.NaN)
        assertEquals(HinoteError.Inconsistent, err(bad))
    }

    @Test
    fun linkedAndGroupedStrokesAreHidden() {
        val grouped = ByteArray(16) { 0x22 }
        val bin = HinoteTestData.bin(
            listOf(
                S(listOf(P(1f, 1f), P(2f, 2f))),
                S(listOf(P(1f, 1f), P(2f, 2f)), link = 3, uuid = ByteArray(16) { 0x11 }),
                S(listOf(P(1f, 1f), P(2f, 2f)), uuid = grouped),
            ),
            groups = listOf(listOf(grouped)),
        )
        val parsed = HinoteBinParser.parse(bin).valueOrNull()!!
        assertEquals(3, parsed.strokes.size)
        assertEquals(1, parsed.visibleStrokes().size)
    }
}
