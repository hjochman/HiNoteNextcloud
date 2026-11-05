package local.papernotes.storage

import local.papernotes.model.NoteFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class PnoteCodecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun decode(bytes: ByteArray) = PnoteCodec.decode(ByteArrayInputStream(bytes))

    private fun assertErr(expected: PnoteError, bytes: ByteArray) {
        val r = decode(bytes)
        assertEquals(expected, r.errorOrNull())
    }

    @Test
    fun roundtripKeepsEverythingExactly() {
        val note = TestData.bigNote(pages = 3, strokesPerPage = 4, points = 500) // 12 Striche... plus Seiten
        val file = File(tmp.root, "a.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val back = PnoteCodec.read(file).valueOrNull()
        assertEquals(note, back)
        assertEquals(note.pageIds, back!!.pageIds)
        val p = note.pages.getValue(note.pageIds[2]).strokes[3].points[499]
        val q = back.pages.getValue(note.pageIds[2]).strokes[3].points[499]
        assertEquals(p.x.toRawBits(), q.x.toRawBits())
        assertEquals(p.pressure.toRawBits(), q.pressure.toRawBits())
        assertEquals(p.time, q.time)
    }

    @Test
    fun roundtripTenStrokesOnThreePages() {
        val note = TestData.bigNote(pages = 3, strokesPerPage = 10, points = 2000)
        assertEquals(note, decode(TestData.encode(note)).valueOrNull())
    }

    @Test
    fun roundtripOfExtremeFloatsAndTimes() {
        val base = TestData.simpleNote()
        val pid = base.pageIds[0]
        val pts = listOf(
            local.papernotes.model.Point(Float.MIN_VALUE, Float.MAX_VALUE, Long.MAX_VALUE, 0.1f),
            local.papernotes.model.Point(-0f, 1.17549435E-38f, 0L, 1f / 3f),
        )
        val page = base.pages.getValue(pid).copy(strokes = listOf(local.papernotes.model.Stroke(7, 0.3f, -1, pts)))
        val note = base.copy(pages = mapOf(pid to page))
        val back = decode(TestData.encode(note)).valueOrNull()!!
        assertEquals(pts[0], back.pages.getValue(pid).strokes[0].points[0])
        assertEquals(pts[1].x.toRawBits(), back.pages.getValue(pid).strokes[0].points[1].x.toRawBits())
        assertEquals(pts[1].pressure, back.pages.getValue(pid).strokes[0].points[1].pressure, 0f)
    }

    @Test
    fun newNoteHasOneBlankPage() {
        val n = NoteFactory.newNote("Titel")
        assertEquals(1, n.pageIds.size)
        val page = n.pages.getValue(n.pageIds[0])
        assertEquals("blank", page.template)
        assertEquals(1404, page.width)
        assertEquals(1872, page.height)
        assertTrue(PnoteCodec.isValidId(n.id))
    }

    // ------------------------------------------------------------ Atomares Schreiben

    @Test
    fun failedWriteKeepsExistingFileAndLeavesNoTmp() {
        val note = TestData.bigNote(pages = 1, strokesPerPage = 1, points = 10)
        val file = File(tmp.root, "keep.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val before = file.readBytes()

        val r = PnoteCodec.writeAtomic(file) { out ->
            out.write(ByteArray(1000) { 1 })
            throw IOException("simuliert")
        }
        assertEquals(PnoteError.Io, r.errorOrNull())
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(tmp.root, "keep.pnote.tmp").exists())
        assertEquals(listOf("keep.pnote"), tmp.root.list()!!.toList())
    }

    @Test
    fun runtimeExceptionInEncoderAlsoCleansUp() {
        val file = File(tmp.root, "x.pnote")
        val r = PnoteCodec.writeAtomic(file) { throw IllegalStateException("boom") }
        assertEquals(PnoteError.Io, r.errorOrNull())
        assertFalse(file.exists())
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test
    fun unwritableDirectoryKeepsOldFile() {
        val dir = tmp.newFolder("ro")
        val file = File(dir, "n.pnote")
        val note = TestData.simpleNote()
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        val before = file.readBytes()
        assertTrue(dir.setWritable(false))
        try {
            // Als root (z. B. CI) greift die Rechteprüfung nicht; dann ist nichts zu prüfen.
            val reallyReadOnly = try {
                File(dir, "probe").createNewFile().not()
            } catch (e: IOException) {
                true
            }
            if (reallyReadOnly) {
                val r = PnoteCodec.write(note.copy(title = "neu"), file)
                assertEquals(PnoteError.Io, r.errorOrNull())
                assertArrayEquals(before, file.readBytes())
            }
        } finally {
            dir.setWritable(true)
        }
        assertEquals(listOf("n.pnote"), dir.list()!!.toList())
    }

    @Test
    fun invalidNoteIsRejectedBeforeAnythingIsWritten() {
        val file = File(tmp.root, "bad.pnote")
        val n = TestData.simpleNote().copy(id = "../evil")
        assertEquals(PnoteError.InvalidId, PnoteCodec.write(n, file).errorOrNull())
        val n2 = TestData.simpleNote().copy(folderPath = "../x")
        assertEquals(PnoteError.InvalidFolder, PnoteCodec.write(n2, file).errorOrNull())
        assertEquals(0, tmp.root.list()!!.size)
    }

    // ------------------------------------------------------------ Sicherheit beim Lesen

    private fun okZip(extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val pid = TestData.uuid()
        return TestData.zip(
            listOf("manifest.json" to TestData.manifest(pageIds = listOf(pid)), "pages/$pid.json" to TestData.pageJson()) + extra,
        )
    }

    @Test
    fun wellFormedHandwrittenZipIsAccepted() {
        assertTrue(decode(okZip()) is PnoteResult.Ok)
    }

    @Test
    fun optionalPreviewAndMediaAreTolerated() {
        val extra = listOf("preview.png" to ByteArray(10), "media/${TestData.uuid()}.png" to ByteArray(5))
        assertTrue(decode(okZip(extra)) is PnoteResult.Ok)
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5)

    @Test
    fun previewRoundtripsAndIsReadableWithoutPages() {
        val note = TestData.bigNote(pages = 2, strokesPerPage = 2, points = 50).copy(preview = jpeg)
        val file = File(tmp.root, "p.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        assertArrayEquals(jpeg, PnoteCodec.read(file).valueOrNull()!!.preview)
        assertArrayEquals(jpeg, PnoteCodec.readPreview(file))
        assertEquals(note, PnoteCodec.read(file).valueOrNull())
    }

    @Test
    fun noteWithoutPreviewHasNone() {
        val note = TestData.simpleNote()
        val file = File(tmp.root, "n.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        assertEquals(null, PnoteCodec.read(file).valueOrNull()!!.preview)
        assertEquals(null, PnoteCodec.readPreview(file))
    }

    @Test
    fun readPreviewToleratesMissingAndBrokenFiles() {
        assertEquals(null, PnoteCodec.readPreview(File(tmp.root, "gibt-es-nicht.pnote")))
        val bad = File(tmp.root, "bad.pnote").apply { writeBytes(ByteArray(64) { 9 }) }
        assertEquals(null, PnoteCodec.readPreview(bad))
    }

    @Test
    fun writeRejectsPreviewThatIsNotJpegOrTooLarge() {
        val file = File(tmp.root, "x.pnote")
        val notJpeg = TestData.simpleNote().copy(preview = ByteArray(20) { 1 })
        assertEquals(PnoteError.InvalidNote, PnoteCodec.write(notJpeg, file).errorOrNull())
        val big = ByteArray(PnoteCodec.MAX_PREVIEW_BYTES + 1).also { System.arraycopy(jpeg, 0, it, 0, jpeg.size) }
        assertEquals(PnoteError.LimitExceeded, PnoteCodec.write(TestData.simpleNote().copy(preview = big), file).errorOrNull())
        assertFalse(file.exists())
    }

    @Test
    fun readDropsInvalidPreviewButKeepsNote() {
        val pageId = TestData.uuid()
        val entries = listOf(
            "manifest.json" to TestData.manifest(pageIds = listOf(pageId)),
            "preview.jpg" to ByteArray(30) { 1 },
            "pages/$pageId.json" to TestData.pageJson(),
        )
        val note = decode(TestData.zip(entries)).valueOrNull()!!
        assertEquals(null, note.preview)
        assertEquals(1, note.pageIds.size)
    }

    @Test
    fun readPreviewIgnoresOversizedEntry() {
        val big = ByteArray(PnoteCodec.MAX_PREVIEW_BYTES + 10).also { System.arraycopy(jpeg, 0, it, 0, jpeg.size) }
        val file = File(tmp.root, "big.pnote").apply {
            writeBytes(TestData.zip(listOf("manifest.json" to TestData.manifest(), "preview.jpg" to big)))
        }
        assertEquals(null, PnoteCodec.readPreview(file))
    }

    @Test
    fun rejectsPathTraversalEntry() {
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("../evil" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("pages/../../evil.json" to ByteArray(1))))
    }

    @Test
    fun rejectsAbsolutePath() {
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("/etc/passwd" to ByteArray(1))))
    }

    @Test
    fun rejectsBackslash() {
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("pages\\x.json" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("..\\evil" to ByteArray(1))))
    }

    @Test
    fun rejectsUnexpectedNames() {
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("evil.txt" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("pages/not-a-uuid.json" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("media/${TestData.uuid()}.p/ng" to ByteArray(1))))
        assertErr(PnoteError.InvalidEntryName, okZip(listOf("a".repeat(300) to ByteArray(1))))
    }

    @Test
    fun rejectsZipBombEntryOverEntryLimit() {
        // 17 MB Nullen komprimieren auf wenige KB, entpackt liegen sie über dem Limit von 16 MB je Eintrag.
        val bomb = ByteArray((PnoteCodec.MAX_ENTRY_BYTES + 1024 * 1024).toInt())
        val bytes = okZip(listOf("media/${TestData.uuid()}.bin" to bomb))
        assertTrue("sollte klein komprimiert sein", bytes.size < 200_000)
        assertErr(PnoteError.LimitExceeded, bytes)
    }

    @Test
    fun rejectsTotalSizeOverLimit() {
        val chunk = ByteArray(15 * 1024 * 1024)
        val entries = List(5) { "media/${TestData.uuid()}.bin" to chunk } // 75 MB > 64 MB
        assertErr(PnoteError.LimitExceeded, okZip(entries))
    }

    @Test
    fun rejectsTooManyEntries() {
        val entries = List(PnoteCodec.MAX_ENTRIES + 1) { "media/${TestData.uuid()}.png" to ByteArray(1) }
        assertErr(PnoteError.LimitExceeded, okZip(entries))
    }

    @Test
    fun rejectsUnknownNewerVersion() {
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(version = 2)))
        assertEquals(PnoteError.UnsupportedVersion(2), decode(z).errorOrNull())
    }

    @Test
    fun rejectsVersionZero() {
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(version = 0)))
        assertEquals(PnoteError.Corrupt, decode(z).errorOrNull())
    }

    @Test
    fun rejectsNonUuidPageId() {
        val z = TestData.zip(
            listOf(
                "manifest.json" to TestData.manifest(pageIds = listOf("../../evil")),
            ),
        )
        assertEquals(PnoteError.InvalidId, decode(z).errorOrNull())
    }

    @Test
    fun rejectsNonUuidNoteId() {
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(id = "../../x")))
        assertEquals(PnoteError.InvalidId, decode(z).errorOrNull())
    }

    @Test
    fun rejectsEvilFolderPathInManifest() {
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(folder = "../x")))
        assertEquals(PnoteError.InvalidFolder, decode(z).errorOrNull())
    }

    @Test
    fun rejectsMissingManifestAndMissingPage() {
        assertEquals(PnoteError.MissingManifest, decode(TestData.zip(listOf("preview.png" to ByteArray(1)))).errorOrNull())
        val z = TestData.zip(listOf("manifest.json" to TestData.manifest(pageIds = listOf(TestData.uuid()))))
        assertEquals(PnoteError.Corrupt, decode(z).errorOrNull())
    }

    @Test
    fun rejectsGarbage() {
        assertEquals(PnoteError.MissingManifest, decode(ByteArray(100) { 7 }).errorOrNull())
        val z = TestData.zip(listOf("manifest.json" to "{kaputt".toByteArray()))
        assertEquals(PnoteError.Corrupt, decode(z).errorOrNull())
    }

    @Test
    fun rejectsTooManyPointsPerStroke() {
        val pid = TestData.uuid()
        val pts = (0..PnoteCodec.MAX_POINTS_PER_STROKE).joinToString(",") { "[1,2,3,0.5]" }
        val page = """{"template":"blank","width":10,"height":20,"bookmarked":false,"strokes":[{"t":1,"w":1,"c":0,"p":[$pts]}]}"""
        val z = TestData.zip(
            listOf("manifest.json" to TestData.manifest(pageIds = listOf(pid)), "pages/$pid.json" to page.toByteArray()),
        )
        assertEquals(PnoteError.LimitExceeded, decode(z).errorOrNull())
    }

    // ------------------------------------------------------------ Vorwärtskompatibilität

    @Test
    fun unknownFieldsAreIgnored() {
        val pid = TestData.uuid()
        val page = """{"textboxes":[{"x":1}],"template":"grid","width":10,"height":20,"bookmarked":true,""" +
            """"images":["a"],"strokes":[{"t":2,"w":1.5,"c":-16777216,"future":{"a":[1]},"p":[[1.5,2.5,3,0.25]]}],"zzz":null}"""
        val z = TestData.zip(
            listOf(
                "manifest.json" to TestData.manifest(pageIds = listOf(pid), extra = ""","syncHash":"abc","tags":["a"]"""),
                "pages/$pid.json" to page.toByteArray(),
            ),
        )
        val note = decode(z).valueOrNull()!!
        val p = note.pages.getValue(pid)
        assertEquals("grid", p.template)
        assertTrue(p.bookmarked)
        assertEquals(1, p.strokes.size)
        assertEquals(local.papernotes.model.Point(1.5f, 2.5f, 3L, 0.25f), p.strokes[0].points[0])
        assertEquals(-16777216, p.strokes[0].color)
    }

    @Test
    fun readSummaryReadsOnlyManifest() {
        val note = TestData.bigNote(pages = 2, strokesPerPage = 1, points = 5, folder = "A/B")
        val file = File(tmp.root, "s.pnote")
        PnoteCodec.write(note, file)
        val s = PnoteCodec.readSummary(file).valueOrNull()!!
        assertEquals(note.id, s.id)
        assertEquals(note.title, s.title)
        assertEquals("A/B", s.folderPath)
        assertEquals(2, s.pageCount)
        assertTrue(s.favorite)
    }
}
