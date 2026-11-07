package local.papernotes.hinote

import local.papernotes.hinote.HinoteTestData.P
import local.papernotes.hinote.HinoteTestData.S
import local.papernotes.model.NoteFactory
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteCodec
import local.papernotes.storage.PnoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Random

class HinoteImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun read(bytes: ByteArray) = HinoteContainerReader.read(ByteArrayInputStream(bytes))

    private fun source(name: String, bytes: ByteArray) = HinoteImportSource(name) { ByteArrayInputStream(bytes) }

    @Test
    fun roundtripMapsNoteAndPages() {
        val container = read(HinoteTestData.hinote()).valueOrNull()!!
        assertEquals(HinoteTestData.NOTE_ID, container.noteId)
        assertEquals(listOf(HinoteTestData.PAGE1, HinoteTestData.PAGE2, HinoteTestData.PAGE3), container.pages.map { it.id })

        val imported = HinoteImporter.importNote(container, "HiNote-Import", "Fallback", now = 123L)
        val note = imported.note
        assertEquals(0, imported.warnings)
        assertEquals("Testnotiz", note.title)
        assertEquals("HiNote-Import", note.folderPath)
        assertEquals("hinote:" + HinoteTestData.NOTE_ID, note.importSource)
        assertTrue(note.favorite)
        assertEquals(1000L, note.created) // Minimum der Seiten
        assertEquals(9000L, note.modified) // Maximum der Seiten
        val pages = note.pageIds.map { note.pages.getValue(it) }
        assertEquals(listOf("blank", "todo", "dotted"), pages.map { it.template })
        assertEquals(listOf(true, false, false), pages.map { it.bookmarked })
        assertTrue(pages.all { it.width == 1404 && it.height == 1872 })
        assertEquals(0, pages[1].strokes.size) // Seite ohne .bin
        val stroke = pages[0].strokes.single()
        assertEquals(2, stroke.penType)
        assertEquals(0xFF000000.toInt(), stroke.color)
        assertEquals(2f * HinoteImporter.STROKE_WIDTH_FACTOR, stroke.width, 0f)
        assertEquals(0f, stroke.points[0].x, 0f)
        assertEquals(702f, stroke.points[1].x, 0.01f) // 500 * 1,404
        assertEquals(140.4f, stroke.points[1].y, 0.01f) // y mit demselben Maßstab, oben ausgerichtet
        assertEquals(10L, stroke.points[1].time)
        assertEquals(0.5f, stroke.points[1].pressure, 0f)
    }

    @Test
    fun importedNoteSurvivesCodecRoundtripWithImportSource() {
        val container = read(HinoteTestData.hinote()).valueOrNull()!!
        val note = HinoteImporter.importNote(container, "HiNote-Import", "x", 1L).note
        val file = File(tmp.root, "n.pnote")
        assertTrue(PnoteCodec.write(note, file) is PnoteResult.Ok)
        assertEquals(note, PnoteCodec.read(file).valueOrNull())
        assertEquals("hinote:" + HinoteTestData.NOTE_ID, PnoteCodec.readSummary(file).valueOrNull()!!.importSource)
    }

    @Test
    fun pnoteWithoutImportSourceStaysReadable() {
        val note = NoteFactory.newNote("T")
        val file = File(tmp.root, "a.pnote")
        PnoteCodec.write(note, file)
        assertNull(PnoteCodec.read(file).valueOrNull()!!.importSource)
    }

    @Test
    fun colorAndPenMapping() {
        assertEquals(0xFFFF8000.toInt(), HinoteImporter.argb(1f, 1f, 0.5f, 0f))
        assertEquals(0x80FF0000.toInt(), HinoteImporter.argb(0.5f, 1f, 0f, 0f))
        val expected = mapOf(1 to 4, 2 to 2, 3 to 3, 4 to 6, 5 to 6, 11 to 3, 12 to 4, 13 to 4, 99 to 2)
        for ((brush, framework) in expected) assertEquals("Pinsel $brush", framework, HinoteImporter.penTypeFor(brush))
    }

    @Test
    fun blueGreenRedOrderOfTheFileIsConverted() {
        // Datei: B, G, R. Rot (r=1) muss ARGB-Rot ergeben.
        val bin = HinoteTestData.bin(listOf(S(listOf(P(1f, 1f), P(2f, 2f)), r = 1f, g = 0f, b = 0f)))
        val s = HinoteBinParser.parse(bin).valueOrNull()!!.strokes.single()
        assertEquals(0xFFFF0000.toInt(), HinoteImporter.argb(s.alpha, s.red, s.green, s.blue))
    }

    @Test
    fun templateMapping() {
        assertEquals("blank", HinoteImporter.templateFor("base1"))
        assertEquals("dotted", HinoteImporter.templateFor("base6"))
        assertEquals("todo", HinoteImporter.templateFor("plan2"))
        assertEquals("blank", HinoteImporter.templateFor("learning5"))
        assertEquals("blank", HinoteImporter.templateFor("../x"))
    }

    @Test
    fun brokenBinBecomesEmptyPageWithWarning() {
        val valid = HinoteTestData.bin(listOf(S(listOf(P(1f, 1f), P(2f, 2f)))))
        val container = read(HinoteTestData.hinote(bin1 = valid.copyOf(valid.size - 1))).valueOrNull()!!
        val imported = HinoteImporter.importNote(container, "HiNote-Import", "x", 1L)
        assertEquals(1, imported.warnings)
        assertEquals(3, imported.note.pageIds.size)
        assertTrue(imported.note.pages.values.all { it.strokes.isEmpty() })
    }

    @Test
    fun brokenPageJsonBecomesEmptyPageWithWarning() {
        val bytes = HinoteTestData.hinote(extra = listOf("pages/${"a".repeat(32)}.jhinote" to byteArrayOf(1, 2, 3)))
        val container = read(bytes).valueOrNull()!!
        val imported = HinoteImporter.importNote(container, "HiNote-Import", "x", 1L)
        assertEquals(1, imported.warnings)
        assertEquals(4, imported.note.pageIds.size)
    }

    @Test
    fun zipSlipNameIsRejected() {
        for (name in listOf("../evil.bin", "files/../../x", "/abs", "pages\\x.jhinote")) {
            val bytes = HinoteTestData.hinote(extra = listOf(name to byteArrayOf(1)))
            assertEquals(name, HinoteError.InvalidEntryName, read(bytes).errorOrNull())
        }
    }

    @Test
    fun oversizedEntryIsRejected() {
        val big = ByteArray((PnoteCodec.MAX_ENTRY_BYTES + 1).toInt())
        val bytes = HinoteTestData.hinote(extra = listOf("files/${"b".repeat(32)}.bin" to big))
        assertEquals(HinoteError.LimitExceeded, read(bytes).errorOrNull())
    }

    @Test
    fun highCompressionRatioIsRejected() {
        // 8 MiB Nullen packen auf wenige KiB: Rate weit über der Grenze, Größe unter dem Einzellimit
        val zeros = ByteArray(8 * 1024 * 1024)
        val bytes = HinoteTestData.hinote(extra = listOf("files/${"c".repeat(32)}.bin" to zeros))
        assertEquals(HinoteError.LimitExceeded, read(bytes).errorOrNull())
    }

    @Test
    fun notAZipAndMissingNoteAreCorrupt() {
        assertEquals(HinoteError.Corrupt, read(byteArrayOf(1, 2, 3, 4)).errorOrNull())
        val noNote = HinoteTestData.zip(listOf("custom_md.jhinote" to HinoteTestData.gzip("{}")))
        assertEquals(HinoteError.Corrupt, read(noNote).errorOrNull())
    }

    @Test
    fun strokeCountLimitInContainerBecomesWarning() {
        val bad = HinoteTestData.bin(listOf(S(listOf(P(1f, 1f), P(2f, 2f))))).also {
            ByteBuffer.wrap(it).putInt(76 + 36, HinoteBinParser.MAX_STROKES + 1)
        }
        val container = read(HinoteTestData.hinote(bin1 = bad)).valueOrNull()!!
        assertEquals(1, HinoteImporter.importNote(container, "HiNote-Import", "x", 1L).warnings)
    }

    @Test
    fun runnerImportsOnceAndSkipsDuplicates() {
        val repo = NoteRepository(tmp.newFolder("notes"))
        val a = HinoteTestData.hinote()
        val b = HinoteTestData.hinote(noteId = "e".repeat(32), title = "Zweite")
        val broken = byteArrayOf(0, 1, 2)
        val progress = ArrayList<Int>()
        val first = HinoteImportRunner.run(
            listOf(source("a", a), source("a-again", a), source("b", b), source("broken", broken)),
            repo, "Fallback", onProgress = { done, _ -> progress.add(done) },
        )
        assertEquals(HinoteImportReport(imported = 2, skipped = 1, withWarnings = 0, failed = 1), first)
        assertEquals(listOf(1, 2, 3, 4), progress)
        assertEquals(2, repo.list(HinoteImporter.TARGET_FOLDER).valueOrNull()!!.size)
        // zweiter Lauf: alles bereits vorhanden
        val second = HinoteImportRunner.run(listOf(source("a", a), source("b", b)), repo, "Fallback")
        assertEquals(HinoteImportReport(imported = 0, skipped = 2, withWarnings = 0, failed = 0), second)
        assertEquals(2, repo.listAll("").valueOrNull()!!.size)
    }

    @Test
    fun randomGarbageNeverThrows() {
        val rnd = Random(7)
        repeat(200) {
            val bytes = ByteArray(rnd.nextInt(400)).also { rnd.nextBytes(it) }
            HinoteBinParser.parse(bytes)
            read(bytes)
        }
        // Zufällige Verfälschung einer gültigen Datei
        val valid = HinoteTestData.bin(listOf(S(listOf(P(1f, 1f), P(2f, 2f), P(3f, 3f)))))
        repeat(500) {
            val copy = valid.copyOf()
            copy[rnd.nextInt(copy.size)] = rnd.nextInt().toByte()
            HinoteBinParser.parse(copy)
        }
    }

    /** Alle echten Exporte (git-ignoriert); ohne die Dateien wird der Test übersprungen. */
    @Test
    fun realExportsMatchPythonCount() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tmp/device/exp/custom") }.firstOrNull { it.isDirectory }
        assumeTrue("tmp/device/exp/custom fehlt", dir != null)
        val files = dir!!.listFiles { f -> f.name.endsWith(".hinote") }!!.sortedBy { it.name }
        assumeTrue("keine Exporte", files.isNotEmpty())
        var bins = 0
        var pageCount = 0
        var strokes = 0
        var points = 0
        var warnings = 0
        for (f in files) {
            val container = FileInputStream(f).use { HinoteContainerReader.read(it) }.valueOrNull()
            assertNotNull(f.name, container)
            val imported = HinoteImporter.importNote(container!!, "HiNote-Import", "x", 1L)
            warnings += imported.warnings
            bins += container.pages.count { it.bin != null }
            pageCount += container.pages.size
            for (page in imported.note.pages.values) {
                strokes += page.strokes.size
                points += page.strokes.sumOf { it.points.size }
            }
        }
        assertEquals(0, warnings)
        if (files.size == 14) {
            assertEquals(19, pageCount) // 19 Seiten, davon 18 mit .bin (API Gateway, Seite 2, ist leer)
            assertEquals(18, bins)
            assertEquals(4607, strokes) // Python: tools/hinote_bin.py check
            assertEquals(78_119, points)
        } else {
            assertTrue(strokes > 0)
        }
    }
}
