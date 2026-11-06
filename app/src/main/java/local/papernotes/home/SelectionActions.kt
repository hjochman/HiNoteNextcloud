package local.papernotes.home

import local.papernotes.storage.NoteRepository
import local.papernotes.storage.NoteSummary
import local.papernotes.storage.PnoteCodec
import local.papernotes.storage.PnoteError
import local.papernotes.storage.PnoteResult

/** Sammelaktionen auf der Auswahl. Dateizugriffe: nicht im UI-Thread aufrufen. Fehler werden nicht geloggt. */
object SelectionActions {
    /**
     * Notizen in den Papierkorb, Ordner samt Inhalt (ebenfalls in den Papierkorb). Macht bei Fehlern mit den übrigen
     * Einträgen weiter und liefert den ersten Fehler oder null. Bereits verschwundene Notizen zählen nicht als Fehler.
     */
    fun delete(repo: NoteRepository, sel: SelectionSnapshot): PnoteError? {
        var first: PnoteError? = null
        fun check(r: PnoteResult<*>) {
            val e = (r as? PnoteResult.Err)?.error
            if (e != null && e != PnoteError.NotFound && first == null) first = e
        }
        sel.noteIds.forEach { check(repo.delete(it)) }
        sel.topLevelFolders.forEach { check(repo.deleteFolderWithContents(it)) }
        return first
    }

    /**
     * Verschiebt die Auswahl nach [target] ("" = Wurzel). Ein Ordner kommt nie in sich selbst oder einen Unterordner
     * ([PnoteError.InvalidFolder], nichts wird verändert). Liefert den ersten Fehler oder null.
     */
    fun move(repo: NoteRepository, sel: SelectionSnapshot, target: String): PnoteError? {
        if (!sel.isValidMoveTarget(target)) return PnoteError.InvalidFolder
        var first: PnoteError? = null
        fun check(r: PnoteResult<*>) {
            val e = (r as? PnoteResult.Err)?.error
            if (e != null && first == null) first = e
        }
        sel.topLevelFolders.forEach { check(repo.moveFolder(it, target)) }
        sel.noteIds.forEach { check(repo.move(it, target)) }
        return first
    }

    /** Alle Notizen zum Teilen: die ausgewählten sowie alle Notizen der ausgewählten Ordner (samt Unterordnern), ohne Doppelte. */
    fun notesToShare(repo: NoteRepository, sel: SelectionSnapshot): List<NoteSummary> {
        val out = LinkedHashMap<String, NoteSummary>()
        for (id in sel.noteIds) {
            val file = repo.noteFile(id) ?: continue
            val summary = PnoteCodec.readSummary(file).valueOrNull() ?: continue
            out.putIfAbsent(summary.id, summary)
        }
        for (path in sel.topLevelFolders) {
            repo.listAll(path).valueOrNull().orEmpty().forEach { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }
}

/** Dateinamen für geteilte Notizen: unbedenkliche Zeichen, eindeutig innerhalb eines Teilvorgangs. */
object ShareNames {
    fun base(title: String): String =
        title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").trim().take(50).ifEmpty { "note" }

    /** Dateinamen (mit `.pnote`) zu [titles], gleiche Titel erhalten " (2)", " (3)" usw. */
    fun unique(titles: List<String>): List<String> {
        val used = HashSet<String>()
        return titles.map { title ->
            val base = base(title)
            var name = base
            var n = 1
            while (!used.add(name.lowercase())) name = "$base (${++n})"
            "$name.pnote"
        }
    }
}
