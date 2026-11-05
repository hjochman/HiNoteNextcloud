package local.papernotes.editor

import local.papernotes.model.Note
import local.papernotes.model.NoteFactory
import local.papernotes.model.Page
import local.papernotes.model.Stroke
import local.papernotes.storage.PnoteResult
import java.util.UUID
import java.util.concurrent.Executor

/** Speicher-Schnittstelle (im Test ersetzbar). Wird nie im UI-Thread aufgerufen. */
fun interface NoteStore {
    fun save(note: Note): PnoteResult<Unit>
}

/** Verzögerte Ausführung im UI-Thread (im Test ersetzbar). */
interface SaveScheduler {
    /** Plant [task] nach [delayMs] und gibt ein Token zum Abbrechen zurück. */
    fun postDelayed(delayMs: Long, task: Runnable): Any

    fun cancel(token: Any)
}

/**
 * Zustand einer geöffneten Notiz im Editor: Seiten, aktuelle Seite, Verläufe, Speichern. Ohne Android-Bezug.
 *
 * Threading: alle Methoden nur im UI-Thread. Das Schreiben der Datei läuft auf [writer] (in der App ein
 * Ein-Thread-Executor, damit Schreibvorgänge in Reihenfolge ablaufen); es wird eine unveränderliche Momentaufnahme
 * der [Note] übergeben, daher gibt es keine geteilten veränderlichen Daten. Atomares Schreiben macht [NoteStore]
 * (PnoteCodec: temporäre Datei, dann verschieben).
 *
 * Speicherzeitpunkte: [save] (für onPause/onStop), jeder Seitenwechsel, Seite hinzufügen/löschen, und
 * [DEBOUNCE_MS] nach der letzten Änderung.
 */
class NoteSession(
    initial: Note,
    private val store: NoteStore,
    private val writer: Executor,
    private val scheduler: SaveScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyLimit: Int = PageHistory.DEFAULT_LIMIT,
) {
    init {
        require(initial.pageIds.isNotEmpty()) { "Notiz ohne Seiten" }
    }

    var note: Note = initial
        private set

    var pageIndex: Int = 0
        private set

    private val histories = HashMap<String, PageHistory>()
    private var dirty = false
    private var pendingToken: Any? = null

    /** Wird (auf dem Schreib-Thread!) mit dem Ergebnis jedes Speicherversuchs aufgerufen; true = Erfolg. */
    @Volatile
    var onSaveResult: ((Boolean) -> Unit)? = null

    val pageCount: Int get() = note.pageIds.size
    val currentPage: Page get() = note.pages.getValue(note.pageIds[pageIndex])
    val currentHistory: PageHistory get() = historyFor(currentPage)
    val hasUnsavedChanges: Boolean get() = dirty

    fun currentPageNumberText(): String = "${pageIndex + 1}/$pageCount"

    private fun historyFor(page: Page): PageHistory =
        histories.getOrPut(page.id) { PageHistory(page.strokes, historyLimit) }

    /** Übernimmt die Strichliste der aktuellen Seite ins Modell und plant das Speichern. */
    fun onStrokesChanged(strokes: List<Stroke>) {
        val page = currentPage
        if (page.strokes == strokes) return
        replacePage(page.copy(strokes = strokes))
        markDirty()
    }

    /** Wechselt zur Seite [index] (0-basiert) und speichert vorher. Gibt false bei ungültigem Index oder gleicher Seite. */
    fun goToPage(index: Int): Boolean {
        if (index !in 0 until pageCount || index == pageIndex) return false
        save()
        pageIndex = index
        return true
    }

    /**
     * Fügt hinter der aktuellen Seite eine leere Seite ein (gleiche Größe und Vorlage) und wechselt dorthin.
     * Annahme: Die neue Seite übernimmt die Vorlage der aktuellen Seite (so ist es in HiNote üblich).
     */
    fun addPage(): Page {
        val cur = currentPage
        val page = Page(
            id = UUID.randomUUID().toString(),
            template = cur.template.ifEmpty { NoteFactory.TEMPLATE_BLANK },
            width = cur.width,
            height = cur.height,
            strokes = emptyList(),
            bookmarked = false,
        )
        val ids = note.pageIds.toMutableList().also { it.add(pageIndex + 1, page.id) }
        note = note.copy(pageIds = ids, pages = note.pages + (page.id to page), modified = clock())
        pageIndex += 1
        dirty = true
        save()
        return page
    }

    /**
     * Setzt die Vorlage der aktuellen Seite, mit [allPages] aller Seiten. Striche bleiben unverändert. Nur Vorlagen aus
     * dem [TemplateCatalog] sind erlaubt. Gibt false zurück, wenn nichts zu ändern war. Speichert über den Debounce.
     */
    fun setTemplate(template: String, allPages: Boolean): Boolean {
        if (!TemplateCatalog.isKnown(template)) return false
        val targets = if (allPages) note.pageIds else listOf(note.pageIds[pageIndex])
        var changed = false
        var pages = note.pages
        for (id in targets) {
            val page = pages.getValue(id)
            if (page.template != template) {
                pages = pages + (id to page.copy(template = template))
                changed = true
            }
        }
        if (!changed) return false
        note = note.copy(pages = pages)
        markDirty()
        return true
    }

    /** Löscht die aktuelle Seite. Die letzte verbleibende Seite kann nicht gelöscht werden (false). */
    fun deletePage(): Boolean {
        if (pageCount <= 1) return false
        val id = note.pageIds[pageIndex]
        note = note.copy(
            pageIds = note.pageIds.filterIndexed { i, _ -> i != pageIndex },
            pages = note.pages - id,
            modified = clock(),
        )
        histories.remove(id)
        if (pageIndex >= pageCount) pageIndex = pageCount - 1
        dirty = true
        save()
        return true
    }

    /** Speichert sofort (nicht blockierend) und bricht einen geplanten Debounce-Lauf ab. Ohne Änderungen: nichts. */
    fun save() {
        cancelPending()
        if (!dirty) return
        dirty = false
        val snapshot = note
        writer.execute {
            val ok = try {
                store.save(snapshot) is PnoteResult.Ok
            } catch (e: Exception) {
                false
            }
            onSaveResult?.invoke(ok)
        }
    }

    /** Bricht geplante Läufe ab und speichert ausstehende Änderungen (für onDestroy). */
    fun close() = save()

    private fun markDirty() {
        note = note.copy(modified = clock())
        dirty = true
        cancelPending()
        pendingToken = scheduler.postDelayed(DEBOUNCE_MS) {
            pendingToken = null
            save()
        }
    }

    private fun cancelPending() {
        pendingToken?.let { scheduler.cancel(it) }
        pendingToken = null
    }

    private fun replacePage(page: Page) {
        note = note.copy(pages = note.pages + (page.id to page))
    }

    companion object {
        const val DEBOUNCE_MS = 5_000L
    }
}
