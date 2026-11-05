package local.papernotes.storage

import local.papernotes.model.Note
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.UUID

/** Unterordner mit Anzahl der direkt darin liegenden Notizen. */
data class FolderSummary(val name: String, val path: String, val noteCount: Int)

/**
 * Dateiablage unter [rootDir]: Ordner = Verzeichnisse, Notiz = `<id>.pnote` (Titel steht nur im Manifest,
 * Umbenennen verschiebt also keine Datei). Gelöschte Notizen landen in `.trash/` (Wiederherstellen/Leeren später in der UI).
 *
 * Ordnerpfad: Das Manifest ist die Quelle der Wahrheit, das Verzeichnis spiegelt ihn. Bei Abweichung gewinnt das Manifest
 * NICHT automatisch; [move] und [renameFolder] ändern deshalb beides gemeinsam, [save] legt die Datei immer
 * in dem Verzeichnis ab, das `note.folderPath` entspricht.
 *
 * Pfadsicherheit: Ordnernamen per Allowlist ([FolderPath]), aufgelöste Pfade per `canonicalFile` auf [rootDir] begrenzt,
 * Dateinamen entstehen nur aus UUIDs. Symbolische Links werden beim Suchen nicht verfolgt.
 * Noch nicht an die UI angeschlossen.
 */
class NoteRepository(rootDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val root: File = rootDir.canonicalFile
    private val trashDir: File = File(root, TRASH_DIR)
    private val lock = Any()

    // ---------------------------------------------------------------- Lesen

    fun list(folderPath: String): PnoteResult<List<NoteSummary>> = synchronized(lock) {
        val dir = resolveFolder(folderPath) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        if (!dir.isDirectory) return PnoteResult.Ok(emptyList())
        val result = noteFiles(dir).mapNotNull { PnoteCodec.readSummary(it).valueOrNull() }
        PnoteResult.Ok(NoteOrdering.sort(result))
    }

    fun listFolders(folderPath: String): PnoteResult<List<FolderSummary>> = synchronized(lock) {
        val dir = resolveFolder(folderPath) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        if (!dir.isDirectory) return PnoteResult.Ok(emptyList())
        val folders = (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && FolderPath.isValidName(it.name) }
            .map { FolderSummary(it.name, join(folderPath, it.name), noteFiles(it).size) }
            .sortedBy { it.name.lowercase() }
        PnoteResult.Ok(folders)
    }

    /**
     * Sucht [query] (ohne Beachtung der Groß-/Kleinschreibung) in den Titeln aller Notizen in allen Ordnern;
     * der Papierkorb `.trash` wird nicht durchsucht. Leere oder überlange Eingaben ([MAX_QUERY_LENGTH]) liefern nichts.
     * Sortierung wie in der Liste: fixierte zuerst, dann nach Änderungsdatum absteigend.
     */
    fun search(query: String): PnoteResult<List<NoteSummary>> = synchronized(lock) {
        val q = query.trim()
        if (q.isEmpty() || q.length > MAX_QUERY_LENGTH) return PnoteResult.Ok(emptyList())
        val hits = allNoteFiles(root)
            .mapNotNull { PnoteCodec.readSummary(it).valueOrNull() }
            .filter { it.title.contains(q, ignoreCase = true) }
        PnoteResult.Ok(NoteOrdering.sort(hits))
    }

    /** Alle Ordnerpfade (ohne Wurzel und ohne `.trash`), alphabetisch, für den Verschieben-Dialog. */
    fun listAllFolders(): PnoteResult<List<String>> = synchronized(lock) {
        val result = ArrayList<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (sub in visibleSubdirs(dir)) {
                result.add(relativeFolder(sub))
                walk(sub, depth + 1)
            }
        }
        walk(root, 0)
        PnoteResult.Ok(result.sortedBy { it.lowercase() })
    }

    /** Alle Notizen in [folderPath] und allen Unterordnern (ohne Papierkorb), sortiert wie die Liste. */
    fun listAll(folderPath: String): PnoteResult<List<NoteSummary>> = synchronized(lock) {
        val dir = resolveFolder(folderPath) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        if (!dir.isDirectory) return PnoteResult.Ok(emptyList())
        PnoteResult.Ok(NoteOrdering.sort(allNoteFiles(dir).mapNotNull { PnoteCodec.readSummary(it).valueOrNull() }))
    }

    /** Datei der Notiz (nur für Teilen/Kopieren nach außen); null bei ungültiger oder unbekannter ID. */
    fun noteFile(id: String): File? = synchronized(lock) {
        if (PnoteCodec.isValidId(id)) findFile(id) else null
    }

    /** Eingebettete Vorschau (`preview.jpg`) der Notiz oder null (keine Vorschau, unbekannte ID, defekte Datei). */
    fun loadPreview(id: String): ByteArray? = synchronized(lock) {
        if (!PnoteCodec.isValidId(id)) return null
        val file = findFile(id) ?: return null
        PnoteCodec.readPreview(file)
    }

    fun load(id: String): PnoteResult<Note> = synchronized(lock) {
        if (!PnoteCodec.isValidId(id)) return PnoteResult.Err(PnoteError.InvalidId)
        val file = findFile(id) ?: return PnoteResult.Err(PnoteError.NotFound)
        when (val r = PnoteCodec.read(file)) {
            is PnoteResult.Ok -> if (r.value.id == id) r else PnoteResult.Err(PnoteError.Corrupt)
            is PnoteResult.Err -> r
        }
    }

    // ---------------------------------------------------------------- Schreiben

    /** Speichert [note] unter `note.folderPath`; war sie vorher in einem anderen Ordner, wird die alte Datei entfernt. */
    fun save(note: Note): PnoteResult<Unit> = synchronized(lock) {
        if (!PnoteCodec.isValidId(note.id)) return PnoteResult.Err(PnoteError.InvalidId)
        val dir = resolveFolder(note.folderPath) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        val old = findFile(note.id)
        try {
            Files.createDirectories(dir.toPath())
        } catch (e: IOException) {
            return PnoteResult.Err(PnoteError.Io)
        }
        val target = File(dir, fileName(note.id))
        val r = PnoteCodec.write(note, target)
        if (r is PnoteResult.Ok && old != null && old.canonicalFile != target.canonicalFile) old.delete()
        r
    }

    /** Verschiebt die Notiz in den Papierkorb `.trash/` (kein endgültiges Löschen). */
    fun delete(id: String): PnoteResult<Unit> = synchronized(lock) {
        if (!PnoteCodec.isValidId(id)) return PnoteResult.Err(PnoteError.InvalidId)
        val file = findFile(id) ?: return PnoteResult.Err(PnoteError.NotFound)
        io {
            Files.createDirectories(trashDir.toPath())
            var target = File(trashDir, fileName(id))
            if (target.exists()) target = File(trashDir, "$id.${clock()}.pnote")
            var n = 0
            while (target.exists()) target = File(trashDir, "$id.${clock()}.${++n}.pnote")
            FileOps.moveNoReplace(file, target)
        }
    }

    fun rename(noteId: String, title: String): PnoteResult<Unit> = synchronized(lock) {
        if (!NoteTitles.isValid(title)) return PnoteResult.Err(PnoteError.InvalidNote)
        val note = load(noteId).let { it.valueOrNull() ?: return PnoteResult.Err(it.errorOrNull()!!) }
        save(note.copy(title = title, modified = clock()))
    }

    /** Setzt das Favoriten-Kennzeichen. `modified` bleibt unverändert, damit die Sortierung nicht springt. */
    fun setFavorite(noteId: String, favorite: Boolean): PnoteResult<Unit> = synchronized(lock) {
        val note = load(noteId).let { it.valueOrNull() ?: return PnoteResult.Err(it.errorOrNull()!!) }
        if (note.favorite == favorite) PnoteResult.Ok(Unit) else save(note.copy(favorite = favorite))
    }

    /** Setzt das Fixiert-Kennzeichen. `modified` bleibt unverändert. */
    fun setPinned(noteId: String, pinned: Boolean): PnoteResult<Unit> = synchronized(lock) {
        val note = load(noteId).let { it.valueOrNull() ?: return PnoteResult.Err(it.errorOrNull()!!) }
        if (note.pinned == pinned) PnoteResult.Ok(Unit) else save(note.copy(pinned = pinned))
    }

    /**
     * Kopiert die Notiz im selben Ordner: neue Notiz- und Seiten-UUIDs, Titel [newTitle], `created` und `modified` = jetzt.
     * Liefert die ID der Kopie.
     */
    fun copy(noteId: String, newTitle: String): PnoteResult<String> = synchronized(lock) {
        if (!NoteTitles.isValid(newTitle)) return PnoteResult.Err(PnoteError.InvalidNote)
        val note = load(noteId).let { it.valueOrNull() ?: return PnoteResult.Err(it.errorOrNull()!!) }
        val now = clock()
        val idMap = note.pageIds.associateWith { UUID.randomUUID().toString() }
        val copy = note.copy(
            id = UUID.randomUUID().toString(),
            title = newTitle,
            created = now,
            modified = now,
            pageIds = note.pageIds.map { idMap.getValue(it) },
            pages = note.pageIds.associate { old -> idMap.getValue(old) to note.pages.getValue(old).copy(id = idMap.getValue(old)) },
        )
        when (val r = save(copy)) {
            is PnoteResult.Ok -> PnoteResult.Ok(copy.id)
            is PnoteResult.Err -> r
        }
    }

    /** Ändert Manifest-Ordnerpfad und Verzeichnis gemeinsam (neue Datei schreiben, dann alte entfernen). */
    fun move(noteId: String, folderPath: String): PnoteResult<Unit> = synchronized(lock) {
        if (!FolderPath.isValid(folderPath)) return PnoteResult.Err(PnoteError.InvalidFolder)
        val note = load(noteId).let { it.valueOrNull() ?: return PnoteResult.Err(it.errorOrNull()!!) }
        save(note.copy(folderPath = folderPath))
    }

    // ---------------------------------------------------------------- Ordner

    fun createFolder(folderPath: String): PnoteResult<Unit> = synchronized(lock) {
        if (folderPath.isEmpty()) return PnoteResult.Err(PnoteError.InvalidFolder)
        val dir = resolveFolder(folderPath) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        if (dir.exists()) return PnoteResult.Err(PnoteError.AlreadyExists)
        io { Files.createDirectories(dir.toPath()) }
    }

    /** Löscht den Ordner nur, wenn er vollständig leer ist (keine Notizen, keine Unterordner, keine fremden Dateien). */
    fun deleteFolder(folderPath: String): PnoteResult<Unit> = synchronized(lock) {
        if (folderPath.isEmpty()) return PnoteResult.Err(PnoteError.InvalidFolder)
        val dir = resolveFolder(folderPath) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        if (!dir.isDirectory) return PnoteResult.Err(PnoteError.NotFound)
        val content = dir.list() ?: return PnoteResult.Err(PnoteError.Io)
        if (content.isNotEmpty()) return PnoteResult.Err(PnoteError.NotEmpty)
        io { Files.delete(dir.toPath()) }
    }

    /**
     * Benennt den letzten Pfadteil von [folderPath] in [newName] um. Danach werden die Manifeste aller darin
     * liegenden Notizen auf den neuen Pfad gesetzt (Verzeichnis und Manifest bleiben konsistent).
     */
    fun renameFolder(folderPath: String, newName: String): PnoteResult<Unit> = synchronized(lock) {
        if (folderPath.isEmpty() || !FolderPath.isValidName(newName)) return PnoteResult.Err(PnoteError.InvalidFolder)
        val src = resolveFolder(folderPath) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        if (!src.isDirectory) return PnoteResult.Err(PnoteError.NotFound)
        val newPath = join(folderPath.substringBeforeLast('/', ""), newName)
        val dst = resolveFolder(newPath) ?: return PnoteResult.Err(PnoteError.InvalidFolder)
        if (dst.exists()) return PnoteResult.Err(PnoteError.AlreadyExists)
        val moved = io { FileOps.moveNoReplace(src, dst) }
        if (moved is PnoteResult.Err) return moved
        var failed = false
        for (file in allNoteFiles(dst)) {
            val note = PnoteCodec.read(file).valueOrNull()
            if (note == null) {
                failed = true
                continue
            }
            val dirPath = relativeFolder(file.parentFile ?: dst)
            if (note.folderPath != dirPath &&
                PnoteCodec.write(note.copy(folderPath = dirPath), file) is PnoteResult.Err
            ) {
                failed = true
            }
        }
        if (failed) PnoteResult.Err(PnoteError.Io) else PnoteResult.Ok(Unit)
    }

    // ---------------------------------------------------------------- Hilfen (PathGuard)

    /** Liefert das Verzeichnis zu [folderPath] oder null, wenn der Pfad ungültig ist oder [root] verlässt. */
    private fun resolveFolder(folderPath: String): File? {
        if (!FolderPath.isValid(folderPath)) return null
        val dir = if (folderPath.isEmpty()) root else File(root, folderPath).canonicalFile
        return if (isInsideRoot(dir)) dir else null
    }

    private fun isInsideRoot(f: File): Boolean =
        f == root || f.path.startsWith(root.path + File.separator)

    private fun relativeFolder(dir: File): String =
        dir.canonicalFile.path.removePrefix(root.path).trim(File.separatorChar).replace(File.separatorChar, '/')

    private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    private fun fileName(id: String) = "$id.pnote"

    /** `<uuid>.pnote`-Dateien direkt in [dir]; Dateien mit anderen Namen werden ignoriert und nie angefasst. */
    private fun noteFiles(dir: File): List<File> = (dir.listFiles() ?: emptyArray()).filter {
        it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.endsWith(".pnote") &&
            PnoteCodec.isValidId(it.name.removeSuffix(".pnote"))
    }

    private fun visibleSubdirs(dir: File): List<File> = (dir.listFiles() ?: emptyArray()).filter {
        it.isDirectory && !Files.isSymbolicLink(it.toPath()) && FolderPath.isValidName(it.name)
    }

    private fun allNoteFiles(dir: File, depth: Int = 0): List<File> {
        if (depth > MAX_DEPTH) return emptyList()
        return noteFiles(dir) + visibleSubdirs(dir).flatMap { allNoteFiles(it, depth + 1) }
    }

    private fun findFile(id: String): File? {
        val name = fileName(id)
        fun search(dir: File, depth: Int): File? {
            if (depth > MAX_DEPTH) return null
            val candidate = File(dir, name)
            if (candidate.isFile && !Files.isSymbolicLink(candidate.toPath())) return candidate
            for (sub in visibleSubdirs(dir)) search(sub, depth + 1)?.let { return it }
            return null
        }
        return search(root, 0)
    }

    private inline fun io(block: () -> Unit): PnoteResult<Unit> = try {
        block()
        PnoteResult.Ok(Unit)
    } catch (e: FileAlreadyExistsException) {
        PnoteResult.Err(PnoteError.AlreadyExists)
    } catch (e: NoSuchFileException) {
        PnoteResult.Err(PnoteError.NotFound)
    } catch (e: IOException) {
        PnoteResult.Err(PnoteError.Io)
    }

    companion object {
        const val TRASH_DIR = ".trash"
        private const val MAX_DEPTH = 16
        const val MAX_QUERY_LENGTH = 100
    }
}
