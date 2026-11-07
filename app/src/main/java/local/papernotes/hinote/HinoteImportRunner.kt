package local.papernotes.hinote

import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteResult
import java.io.IOException
import java.io.InputStream

/** Eine zu importierende Quelle. [open] liefert einen frischen Strom (oder null); der Aufrufer-Thread schließt ihn. */
class HinoteImportSource(val name: String, val open: () -> InputStream?)

/** Zählung des Laufs: importiert, übersprungen (bereits importiert), davon mit Warnungen, fehlgeschlagen. */
data class HinoteImportReport(val imported: Int, val skipped: Int, val withWarnings: Int, val failed: Int)

/**
 * Importiert mehrere `.hinote`-Dateien in das [NoteRepository] (Zielordner [HinoteImporter.TARGET_FOLDER]). Läuft auf dem
 * aufrufenden (Hintergrund-)Thread. Doppelimport: Eine Notiz mit gleicher `importSource` (`hinote:<noteId>`) im Bestand
 * oder in diesem Lauf wird übersprungen. Fehler einer Datei brechen den Lauf nicht ab und tragen keine Namen oder Pfade.
 */
object HinoteImportRunner {
    fun run(
        sources: List<HinoteImportSource>,
        repo: NoteRepository,
        fallbackTitle: String,
        now: Long = System.currentTimeMillis(),
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): HinoteImportReport {
        val known = HashSet<String>()
        repo.listAll("").valueOrNull()?.forEach { s -> s.importSource?.let { known.add(it) } }
        var imported = 0
        var skipped = 0
        var withWarnings = 0
        var failed = 0
        for ((i, source) in sources.withIndex()) {
            if (isCancelled()) break
            onProgress(i + 1, sources.size)
            val container = try {
                source.open()?.use { HinoteContainerReader.read(it) }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }?.valueOrNull()
            if (container == null) {
                failed++
                continue
            }
            val sourceId = HinoteImporter.SOURCE_PREFIX + container.noteId
            if (sourceId in known) {
                skipped++
                continue
            }
            val result = HinoteImporter.importNote(container, HinoteImporter.TARGET_FOLDER, fallbackTitle, now)
            if (repo.save(result.note) is PnoteResult.Ok) {
                known.add(sourceId)
                imported++
                if (result.warnings > 0) withWarnings++
            } else {
                failed++
            }
        }
        return HinoteImportReport(imported, skipped, withWarnings, failed)
    }
}
