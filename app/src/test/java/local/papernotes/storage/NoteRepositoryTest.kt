package local.papernotes.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var repo: NoteRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        root = tmp.newFolder("notes")
        repo = NoteRepository(root) { ++now }
    }

    private fun ok(r: PnoteResult<*>) = assertTrue("erwartet Ok, war $r", r is PnoteResult.Ok)

    @Test
    fun createSaveListLoad() {
        val a = TestData.simpleNote("", "Alpha").copy(modified = 1_000L)
        val b = TestData.simpleNote("", "Beta").copy(modified = 5_000L)
        ok(repo.save(a))
        ok(repo.save(b))
        val list = repo.list("").valueOrNull()!!
        assertEquals(2, list.size)
        assertEquals("Beta", list[0].title) // neueste zuerst
        assertEquals(1, list[0].pageCount)
        assertEquals(a, repo.load(a.id).valueOrNull())
        assertTrue(File(root, "${a.id}.pnote").isFile)
    }

    @Test
    fun foldersAndCounts() {
        ok(repo.createFolder("BIT"))
        ok(repo.createFolder("BIT/Sub"))
        ok(repo.save(TestData.simpleNote("BIT", "n1")))
        ok(repo.save(TestData.simpleNote("BIT", "n2")))
        val top = repo.listFolders("").valueOrNull()!!
        assertEquals(listOf(FolderSummary("BIT", "BIT", 2)), top)
        val sub = repo.listFolders("BIT").valueOrNull()!!
        assertEquals(listOf(FolderSummary("Sub", "BIT/Sub", 0)), sub)
        assertEquals(2, repo.list("BIT").valueOrNull()!!.size)
        assertEquals(0, repo.list("").valueOrNull()!!.size)
        assertEquals(PnoteError.AlreadyExists, repo.createFolder("BIT").errorOrNull())
    }

    @Test
    fun moveChangesManifestAndDirectory() {
        ok(repo.createFolder("Ziel"))
        val n = TestData.simpleNote("", "m")
        ok(repo.save(n))
        ok(repo.move(n.id, "Ziel"))
        assertFalse(File(root, "${n.id}.pnote").exists())
        assertTrue(File(root, "Ziel/${n.id}.pnote").isFile)
        assertEquals("Ziel", repo.load(n.id).valueOrNull()!!.folderPath)
        assertEquals(1, repo.list("Ziel").valueOrNull()!!.size)
        ok(repo.move(n.id, ""))
        assertTrue(File(root, "${n.id}.pnote").isFile)
        assertFalse(File(root, "Ziel/${n.id}.pnote").exists())
    }

    @Test
    fun moveToNewNestedFolderCreatesIt() {
        val n = TestData.simpleNote("", "m")
        ok(repo.save(n))
        ok(repo.move(n.id, "A/B"))
        assertTrue(File(root, "A/B/${n.id}.pnote").isFile)
    }

    @Test
    fun renameKeepsFileName() {
        val n = TestData.simpleNote("", "alt").copy(modified = 10L)
        ok(repo.save(n))
        ok(repo.rename(n.id, "Neuer Titel äöü"))
        val back = repo.load(n.id).valueOrNull()!!
        assertEquals("Neuer Titel äöü", back.title)
        assertTrue(back.modified > n.modified - 1)
        assertEquals(listOf("${n.id}.pnote"), root.list()!!.toList())
    }

    @Test
    fun deleteMovesToTrash() {
        val n = TestData.simpleNote("", "weg")
        ok(repo.save(n))
        ok(repo.delete(n.id))
        assertEquals(PnoteError.NotFound, repo.load(n.id).errorOrNull())
        assertTrue(File(root, ".trash/${n.id}.pnote").isFile)
        assertEquals(0, repo.list("").valueOrNull()!!.size)
        assertEquals(PnoteError.NotFound, repo.delete(n.id).errorOrNull())
    }

    @Test
    fun deleteTwiceSameIdKeepsBothInTrash() {
        val n = TestData.simpleNote("", "weg")
        ok(repo.save(n))
        ok(repo.delete(n.id))
        ok(repo.save(n))
        ok(repo.delete(n.id))
        assertEquals(2, File(root, ".trash").list()!!.size)
    }

    @Test
    fun renameFolderUpdatesManifestsOfNotesInside() {
        ok(repo.createFolder("Alt"))
        val n = TestData.simpleNote("Alt", "x")
        ok(repo.save(n))
        val n2 = TestData.simpleNote("Alt/Tief", "y")
        ok(repo.save(n2))
        ok(repo.renameFolder("Alt", "Neu"))
        assertFalse(File(root, "Alt").exists())
        assertEquals("Neu", repo.load(n.id).valueOrNull()!!.folderPath)
        assertEquals("Neu/Tief", repo.load(n2.id).valueOrNull()!!.folderPath)
        assertEquals(PnoteError.NotFound, repo.renameFolder("Gibt es nicht", "X").errorOrNull())
        ok(repo.createFolder("Anderer"))
        assertEquals(PnoteError.AlreadyExists, repo.renameFolder("Neu", "Anderer").errorOrNull())
    }

    @Test
    fun rejectsEvilFolderNames() {
        val evil = listOf(
            "..", "../x", "a/../b", "/abs", "a//b", ".hidden", ".trash", "a\\b", "x/", " lead", "trail ", "a:b", "a*b",
            "a".repeat(81), "a\u0000b", "~", "a/.", "%2e%2e",
        )
        for (name in evil) {
            assertEquals("createFolder $name", PnoteError.InvalidFolder, repo.createFolder(name).errorOrNull())
            assertEquals("list $name", PnoteError.InvalidFolder, repo.list(name).errorOrNull())
            assertEquals("save $name", PnoteError.InvalidFolder, repo.save(TestData.simpleNote(name)).errorOrNull())
        }
        val n = TestData.simpleNote("", "ok")
        ok(repo.save(n))
        assertEquals(PnoteError.InvalidFolder, repo.move(n.id, "../out").errorOrNull())
        ok(repo.createFolder("Gut"))
        assertEquals(PnoteError.InvalidFolder, repo.renameFolder("Gut", "../out").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.renameFolder("Gut", "a/b").errorOrNull())
        assertEquals(PnoteError.InvalidId, repo.load("../../etc/passwd").errorOrNull())
        assertEquals(PnoteError.InvalidId, repo.delete("..").errorOrNull())
        // Nichts außerhalb von root angelegt
        assertEquals(listOf("notes"), tmp.root.list()!!.toList())
    }

    @Test
    fun acceptsUmlautsAndSpacesInFolderNames() {
        ok(repo.createFolder("Übung März-2026_v1.0"))
        assertTrue(File(root, "Übung März-2026_v1.0").isDirectory)
        assertTrue(FolderPath.isValid("A/B"))
        assertTrue(FolderPath.isValid(""))
        assertTrue(FolderPath.isValidName("a".repeat(80)))
    }

    @Test
    fun symlinkOutOfRootIsNotFollowed() {
        val outside = tmp.newFolder("outside")
        TestData.simpleNote().let { PnoteCodec.write(it, File(outside, "${it.id}.pnote")) }
        val link = File(root, "Link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            return // Plattform ohne Symlinks
        }
        assertEquals(PnoteError.InvalidFolder, repo.list("Link").errorOrNull())
        assertEquals(0, repo.listFolders("").valueOrNull()!!.size)
        assertEquals(PnoteError.InvalidFolder, repo.save(TestData.simpleNote("Link")).errorOrNull())
    }

    @Test
    fun foreignFilesAreIgnoredAndNeverOverwritten() {
        val foreign = File(root, "fremd.pnote").apply { writeText("meins") }
        val other = File(root, "readme.txt").apply { writeText("hallo") }
        val n = TestData.simpleNote("", "n")
        ok(repo.save(n))
        assertEquals(1, repo.list("").valueOrNull()!!.size)
        assertEquals("meins", foreign.readText())
        assertEquals("hallo", other.readText())
    }

    @Test
    fun corruptNoteIsSkippedInList() {
        val id = TestData.uuid()
        File(root, "$id.pnote").writeBytes(ByteArray(50) { 3 })
        ok(repo.save(TestData.simpleNote("", "gut")))
        assertEquals(1, repo.list("").valueOrNull()!!.size)
        assertTrue(repo.load(id).errorOrNull() != null)
    }

    @Test
    fun saveOverwritesSameNoteInPlace() {
        val n = TestData.simpleNote("", "v1")
        ok(repo.save(n))
        ok(repo.save(n.copy(title = "v2")))
        assertEquals("v2", repo.load(n.id).valueOrNull()!!.title)
        assertEquals(1, root.list()!!.size)
    }

    @Test
    fun favoriteAndPinnedKeepModified() {
        val n = TestData.simpleNote("", "fav").copy(modified = 4_242L)
        ok(repo.save(n))
        ok(repo.setFavorite(n.id, true))
        ok(repo.setPinned(n.id, true))
        val back = repo.load(n.id).valueOrNull()!!
        assertTrue(back.favorite)
        assertTrue(back.pinned)
        assertEquals(4_242L, back.modified)
        ok(repo.setFavorite(n.id, false))
        ok(repo.setPinned(n.id, false))
        val off = repo.load(n.id).valueOrNull()!!
        assertFalse(off.favorite)
        assertFalse(off.pinned)
        assertEquals(4_242L, off.modified)
        assertEquals(PnoteError.NotFound, repo.setFavorite(TestData.uuid(), true).errorOrNull())
        assertEquals(PnoteError.InvalidId, repo.setPinned("../x", true).errorOrNull())
    }

    @Test
    fun embeddedPreviewSurvivesRenameFavoriteMoveAndIsLoadable() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 8, 7)
        val n = TestData.simpleNote("", "mit Vorschau").copy(preview = jpeg)
        ok(repo.save(n))
        assertArrayEquals(jpeg, repo.loadPreview(n.id))
        ok(repo.rename(n.id, "neu"))
        ok(repo.setFavorite(n.id, true))
        ok(repo.move(n.id, "Ordner"))
        assertArrayEquals(jpeg, repo.loadPreview(n.id))
        assertArrayEquals(jpeg, repo.load(n.id).valueOrNull()!!.preview)
        assertEquals(null, repo.loadPreview(TestData.uuid()))
        assertEquals(null, repo.loadPreview("../x"))
    }

    @Test
    fun favoriteInFolderStaysInFolder() {
        val n = TestData.simpleNote("Ordner", "x")
        ok(repo.save(n))
        ok(repo.setFavorite(n.id, true))
        assertTrue(File(root, "Ordner/${n.id}.pnote").isFile)
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun copyCreatesNewIdsAndTimes() {
        val src = TestData.bigNote(pages = 2, strokesPerPage = 2, points = 5, folder = "Ordner")
            .copy(pinned = true)
        ok(repo.save(src))
        val newId = repo.copy(src.id, "Kopie-Titel").valueOrNull()!!
        assertTrue(newId != src.id)
        val copy = repo.load(newId).valueOrNull()!!
        assertEquals("Kopie-Titel", copy.title)
        assertEquals("Ordner", copy.folderPath)
        assertEquals(copy.created, copy.modified)
        assertTrue(copy.created > 1_000L) // von der Testuhr, nicht vom Original
        assertEquals(src.pageIds.size, copy.pageIds.size)
        assertTrue(copy.pageIds.none { it in src.pageIds })
        copy.pageIds.forEach { assertEquals(it, copy.pages.getValue(it).id) }
        assertEquals(
            src.pageIds.map { src.pages.getValue(it).strokes },
            copy.pageIds.map { copy.pages.getValue(it).strokes },
        )
        // Original unverändert
        assertEquals(src, repo.load(src.id).valueOrNull())
        assertEquals(2, repo.list("Ordner").valueOrNull()!!.size)
    }

    @Test
    fun copyAndRenameRejectInvalidTitles() {
        val n = TestData.simpleNote("", "x")
        ok(repo.save(n))
        assertEquals(PnoteError.InvalidNote, repo.copy(n.id, "  ").errorOrNull())
        assertEquals(PnoteError.InvalidNote, repo.rename(n.id, "").errorOrNull())
        assertEquals(PnoteError.InvalidNote, repo.rename(n.id, "a".repeat(NoteTitles.MAX_LENGTH + 1)).errorOrNull())
        assertEquals(PnoteError.InvalidNote, repo.rename(n.id, "a\nb").errorOrNull())
        assertEquals("x", repo.load(n.id).valueOrNull()!!.title)
        assertEquals(1, root.list()!!.size)
    }

    @Test
    fun deleteFolderOnlyWhenEmpty() {
        ok(repo.createFolder("Leer"))
        ok(repo.createFolder("Voll"))
        ok(repo.createFolder("Eltern/Kind"))
        ok(repo.save(TestData.simpleNote("Voll", "n")))
        ok(repo.deleteFolder("Leer"))
        assertFalse(File(root, "Leer").exists())
        assertEquals(PnoteError.NotEmpty, repo.deleteFolder("Voll").errorOrNull())
        assertEquals(PnoteError.NotEmpty, repo.deleteFolder("Eltern").errorOrNull())
        assertTrue(File(root, "Voll").isDirectory)
        assertEquals(PnoteError.NotFound, repo.deleteFolder("Gibt es nicht").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolder("").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolder("..").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolder("a/../..").errorOrNull())
        assertEquals(PnoteError.InvalidFolder, repo.deleteFolder(".trash").errorOrNull())
        assertTrue(root.isDirectory)
    }

    @Test
    fun searchIsCaseInsensitiveAcrossFoldersWithoutTrash() {
        ok(repo.save(TestData.simpleNote("", "Pipeline Alpha")))
        ok(repo.save(TestData.simpleNote("BIT", "alle PIPELINE")))
        ok(repo.save(TestData.simpleNote("BIT/Tief", "Nichts")))
        val gone = TestData.simpleNote("", "pipeline weg")
        ok(repo.save(gone))
        ok(repo.delete(gone.id))
        val hits = repo.search("pipeLINE").valueOrNull()!!
        assertEquals(setOf("Pipeline Alpha", "alle PIPELINE"), hits.map { it.title }.toSet())
        assertEquals(0, repo.search("tief").valueOrNull()!!.size) // nur Titel, nicht Ordnernamen
        assertEquals(1, repo.search("nichts").valueOrNull()!!.size)
    }

    @Test
    fun searchRejectsEmptyAndTooLongQueries() {
        ok(repo.save(TestData.simpleNote("", "abc")))
        assertEquals(0, repo.search("").valueOrNull()!!.size)
        assertEquals(0, repo.search("   ").valueOrNull()!!.size)
        assertEquals(0, repo.search("a".repeat(NoteRepository.MAX_QUERY_LENGTH + 1)).valueOrNull()!!.size)
        assertEquals(1, repo.search("ab").valueOrNull()!!.size)
    }

    @Test
    fun searchSortsPinnedFirstThenModified() {
        val a = TestData.simpleNote("", "x alt").copy(modified = 10L)
        val b = TestData.simpleNote("", "x neu").copy(modified = 30L)
        val c = TestData.simpleNote("A", "x fix").copy(modified = 5L, pinned = true)
        listOf(a, b, c).forEach { ok(repo.save(it)) }
        assertEquals(listOf("x fix", "x neu", "x alt"), repo.search("x").valueOrNull()!!.map { it.title })
    }

    @Test
    fun listAllIsRecursiveAndSkipsTrash() {
        ok(repo.save(TestData.simpleNote("", "a").copy(modified = 1)))
        ok(repo.save(TestData.simpleNote("X", "b").copy(modified = 3)))
        ok(repo.save(TestData.simpleNote("X/Y", "c").copy(modified = 2)))
        val gone = TestData.simpleNote("X", "weg")
        ok(repo.save(gone))
        ok(repo.delete(gone.id))
        assertEquals(listOf("b", "c"), repo.listAll("X").valueOrNull()!!.map { it.title })
        assertEquals(3, repo.listAll("").valueOrNull()!!.size)
        assertEquals(0, repo.listAll("Gibt es nicht").valueOrNull()!!.size)
        assertEquals(PnoteError.InvalidFolder, repo.listAll("../x").errorOrNull())
    }

    @Test
    fun listAllFoldersIsRecursiveWithoutTrash() {
        ok(repo.createFolder("B"))
        ok(repo.createFolder("a/Sub"))
        val n = TestData.simpleNote("", "n")
        ok(repo.save(n))
        ok(repo.delete(n.id)) // legt .trash an, das nicht erscheinen darf
        assertEquals(listOf("a", "a/Sub", "B"), repo.listAllFolders().valueOrNull())
    }
}
