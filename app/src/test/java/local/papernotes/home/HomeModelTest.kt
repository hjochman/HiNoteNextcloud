package local.papernotes.home

import local.papernotes.model.NoteFactory
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun summary(id: String, modified: Long) = NoteSummary(id, id, "", modified, modified, false, false, 1)

    private fun metrics(list: Boolean = false) = GridMetrics.compute(1404, 2f, list, landscape = false)

    @Test
    fun lastSegmentAndParent() {
        assertEquals("B", HomeModel.lastSegment("A/B"))
        assertEquals("A", HomeModel.lastSegment("A"))
        assertEquals("A", HomeModel.parent("A/B"))
        assertEquals("", HomeModel.parent("A"))
    }

    @Test
    fun entriesOrderNewTileFoldersThenNotes() {
        val folder = FolderItem("F", "F", 0, 0, emptyList())
        val content = HomeContent(listOf(folder), listOf(summary("n1", 1)), 1)
        val withTile = HomeModel.entries(content, true)
        assertTrue(withTile[0] is Entry.NewNote)
        assertTrue(withTile[1] is Entry.Folder)
        assertTrue(withTile[2] is Entry.Note)
        assertEquals(2, HomeModel.entries(content, false).size)
    }

    @Test
    fun foldersSpanTwoColumnsAndFillRows() {
        val f = { Entry.Folder(FolderItem("F", "F", 0, 0, emptyList())) }
        val entries = listOf(f(), f(), f(), Entry.Note(summary("n", 1)))
        val m = metrics()
        val pages = HomeModel.pageLayout(entries, m, 100_000)
        assertEquals(1, pages.size)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), pages[0])
    }

    @Test
    fun noteGridHasFourColumnsAndPagesBreakByHeight() {
        val m = metrics()
        assertEquals(4, m.columns)
        val entries = (1..20).map { Entry.Note(summary("n$it", it.toLong())) }
        val pages = HomeModel.pageLayout(entries, m, m.topPadding + m.noteRowHeight * 3 + 1)
        assertEquals(2, pages.size)
        assertEquals(3, pages[0].size)
        assertEquals(2, pages[1].size)
        assertEquals(20, pages.sumOf { p -> p.sumOf { it.size } })
    }

    @Test
    fun listModeHasOneEntryPerRow() {
        val m = metrics(list = true)
        assertEquals(1, m.columns)
        val entries = (1..5).map { Entry.Note(summary("n$it", it.toLong())) }
        val pages = HomeModel.pageLayout(entries, m, m.topPadding + m.listRowHeight * 2)
        assertEquals(3, pages.size)
        assertTrue(pages.all { p -> p.all { it.size == 1 } })
    }

    @Test
    fun landscapeUsesSixColumns() {
        assertEquals(6, GridMetrics.compute(1872, 2f, false, landscape = true).columns)
    }

    @Test
    fun emptyEntriesGiveNoPages() {
        assertTrue(HomeModel.pageLayout(emptyList(), metrics(), 1000).isEmpty())
    }

    @Test
    fun loadCountsNotesInSubfoldersAndTakesFourNewestPreviews() {
        var now = 1000L
        val repo = NoteRepository(tmp.newFolder("root"), clock = { now++ })
        repo.createFolder("F")
        repo.createFolder("F/G")
        (1..5).forEach { repo.save(NoteFactory.newNote("n$it", "F", now = 1000L + it)) }
        repo.save(NoteFactory.newNote("deep", "F/G", now = 2000L))
        repo.save(NoteFactory.newNote("top", "", now = 3000L))
        val content = HomeModel.load(repo, "").valueOrNull()!!
        assertEquals(7, content.totalCount)
        assertEquals(1, content.notes.size)
        val f = content.folders.single()
        assertEquals(6, f.noteCount)
        assertEquals(HomeModel.MAX_FOLDER_PREVIEWS, f.previews.size)
        assertEquals("deep", f.previews.first().title)
        assertEquals(2000L, f.modified)
    }
}
