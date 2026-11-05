package local.papernotes.storage

import local.papernotes.model.Note
import local.papernotes.model.Page
import local.papernotes.model.Point
import local.papernotes.model.Stroke
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Kurzfassung einer Notiz für Listen (nur das Manifest wird gelesen). */
data class NoteSummary(
    val id: String,
    val title: String,
    val folderPath: String,
    val created: Long,
    val modified: Long,
    val favorite: Boolean,
    val pinned: Boolean,
    val pageCount: Int,
)

/** Interne Abkürzung für Fehlerabbruch innerhalb von Parsern. Ohne Stacktrace und ohne Meldung. */
internal class PnoteException(val error: PnoteError) : RuntimeException(null, null, false, false)

/**
 * Liest und schreibt `.pnote` (ZIP mit JSON). Format Version 1:
 *
 * ```
 * manifest.json        version, id, title, folderPath, created, modified, favorite, pinned, pageIds
 * preview.jpg          optional, JPEG der ersten Seite; steht direkt hinter dem Manifest, damit Leser (Liste, Nextcloud-Plugin)
 *                      sie lesen können, ohne die Seiten zu entpacken
 * pages/<uuid>.json    template, width, height, bookmarked, strokes [{t, w, c, p:[[x,y,time,pressure],...]}]
 * media/<uuid>.<ext>   optional, wird beim Lesen toleriert, Inhalt wird derzeit nicht übernommen
 * ```
 *
 * Unbekannte JSON-Felder werden beim Lesen ignoriert. Es wird nichts geloggt; Fehler tragen weder Pfade noch Titel.
 * Eine Vorschau, die zu groß ist oder kein JPEG ist, wird beim Lesen verworfen (die Notiz bleibt lesbar). Ein altes
 * `preview.png` wird toleriert und ignoriert. Hinweis: Da das Modell noch keine Medien kennt, gehen Medien (`media/`)
 * beim Neuschreiben verloren.
 */
object PnoteCodec {
    const val FORMAT_VERSION = 1
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    const val MAX_PREVIEW_BYTES = 2 * 1024 * 1024
    const val PREVIEW_ENTRY = "preview.jpg"
    const val MAX_ENTRIES = 2000
    const val MAX_PAGES = 5000
    const val MAX_STROKES_PER_PAGE = 200_000
    const val MAX_POINTS_PER_STROKE = 20_000
    const val MAX_PAGE_DIMENSION = 20_000
    const val MANIFEST = "manifest.json"

    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val UUID_REGEX = Regex(UUID_PATTERN)
    private val PAGE_ENTRY = Regex("pages/($UUID_PATTERN)\\.json")
    private val MEDIA_ENTRY = Regex("media/$UUID_PATTERN\\.[a-z0-9]{1,8}")
    private val TEMPLATE_REGEX = Regex("[a-z0-9_]{1,32}")
    private const val MAX_NAME_LENGTH = 200

    /** IDs sind Pfadbestandteil und müssen daher kleingeschriebene UUIDs sein. */
    fun isValidId(id: String): Boolean = UUID_REGEX.matches(id)

    /** Prüft nur die JPEG-Kennung (FF D8 FF); die Vorschau wird nie als Pfad oder Befehl benutzt. */
    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    // ---------------------------------------------------------------- Schreiben

    /** Schreibt [note] atomar nach [target] (Temp-Datei im selben Verzeichnis, fsync, Move). */
    fun write(note: Note, target: File): PnoteResult<Unit> {
        try {
            validate(note)
        } catch (e: PnoteException) {
            return PnoteResult.Err(e.error)
        }
        return writeAtomic(target) { out -> encodeUnchecked(note, out) }
    }

    /** Kodiert [note] als ZIP in [out] (ohne zu schließen). Prüft die Notiz vorher. */
    fun encode(note: Note, out: OutputStream): PnoteResult<Unit> = try {
        validate(note)
        encodeUnchecked(note, out)
        PnoteResult.Ok(Unit)
    } catch (e: PnoteException) {
        PnoteResult.Err(e.error)
    } catch (e: IOException) {
        PnoteResult.Err(PnoteError.Io)
    }

    internal fun writeAtomic(target: File, body: (OutputStream) -> Unit): PnoteResult<Unit> {
        val dir = target.absoluteFile.parentFile ?: return PnoteResult.Err(PnoteError.Io)
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                val out = BufferedOutputStream(fos)
                body(out)
                out.flush()
                fos.fd.sync()
            }
            FileOps.moveReplacing(tmp, target)
            return PnoteResult.Ok(Unit)
        } catch (e: PnoteException) {
            return PnoteResult.Err(e.error)
        } catch (e: IOException) {
            return PnoteResult.Err(PnoteError.Io)
        } catch (e: RuntimeException) {
            return PnoteResult.Err(PnoteError.Io)
        } finally {
            tmp.delete()
        }
    }

    private fun validate(note: Note) {
        if (!isValidId(note.id)) throw PnoteException(PnoteError.InvalidId)
        note.preview?.let {
            if (it.size > MAX_PREVIEW_BYTES) throw PnoteException(PnoteError.LimitExceeded)
            if (!isJpeg(it)) throw PnoteException(PnoteError.InvalidNote)
        }
        if (!FolderPath.isValid(note.folderPath)) throw PnoteException(PnoteError.InvalidFolder)
        if (note.pageIds.size > MAX_PAGES) throw PnoteException(PnoteError.LimitExceeded)
        if (note.pageIds.toSet().size != note.pageIds.size || note.pageIds.toSet() != note.pages.keys) {
            throw PnoteException(PnoteError.InvalidNote)
        }
        for ((key, page) in note.pages) {
            if (!isValidId(key)) throw PnoteException(PnoteError.InvalidId)
            if (page.id != key) throw PnoteException(PnoteError.InvalidNote)
            if (!TEMPLATE_REGEX.matches(page.template)) throw PnoteException(PnoteError.InvalidNote)
            if (page.width !in 1..MAX_PAGE_DIMENSION || page.height !in 1..MAX_PAGE_DIMENSION) {
                throw PnoteException(PnoteError.InvalidNote)
            }
            if (page.strokes.size > MAX_STROKES_PER_PAGE) throw PnoteException(PnoteError.LimitExceeded)
            for (s in page.strokes) {
                if (s.points.size > MAX_POINTS_PER_STROKE) throw PnoteException(PnoteError.LimitExceeded)
                if (!s.width.isFinite()) throw PnoteException(PnoteError.InvalidNote)
                for (p in s.points) {
                    if (!p.x.isFinite() || !p.y.isFinite() || !p.pressure.isFinite()) {
                        throw PnoteException(PnoteError.InvalidNote)
                    }
                }
            }
        }
    }

    private fun encodeUnchecked(note: Note, out: OutputStream) {
        val zip = ZipOutputStream(out)
        putJson(zip, MANIFEST) { writeManifest(it, note) }
        note.preview?.let {
            zip.putNextEntry(ZipEntry(PREVIEW_ENTRY))
            zip.write(it)
            zip.closeEntry()
        }
        for (id in note.pageIds) {
            val page = note.pages.getValue(id)
            putJson(zip, "pages/$id.json") { writePage(it, page) }
        }
        zip.finish()
        zip.flush()
    }

    private fun putJson(zip: ZipOutputStream, name: String, body: (JsonWriter) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        val w = JsonWriter(OutputStreamWriter(zip, UTF_8))
        body(w)
        w.flush()
        zip.closeEntry()
    }

    private fun JsonWriter.num(n: Number) {
        value(n)
    }

    private fun writeManifest(w: JsonWriter, note: Note) {
        w.beginObject()
        w.name("version").value(FORMAT_VERSION.toLong())
        w.name("id").value(note.id)
        w.name("title").value(note.title)
        w.name("folderPath").value(note.folderPath)
        w.name("created").value(note.created)
        w.name("modified").value(note.modified)
        w.name("favorite").value(note.favorite)
        w.name("pinned").value(note.pinned)
        w.name("pageIds").beginArray()
        note.pageIds.forEach { w.value(it) }
        w.endArray()
        w.endObject()
    }

    private fun writePage(w: JsonWriter, page: Page) {
        w.beginObject()
        w.name("template").value(page.template)
        w.name("width").value(page.width.toLong())
        w.name("height").value(page.height.toLong())
        w.name("bookmarked").value(page.bookmarked)
        w.name("strokes").beginArray()
        for (s in page.strokes) {
            w.beginObject()
            w.name("t").value(s.penType.toLong())
            w.name("w")
            w.num(s.width)
            w.name("c").value(s.color.toLong())
            w.name("p").beginArray()
            for (p in s.points) {
                w.beginArray()
                w.num(p.x)
                w.num(p.y)
                w.value(p.time)
                w.num(p.pressure)
                w.endArray()
            }
            w.endArray()
            w.endObject()
        }
        w.endArray()
        w.endObject()
    }

    // ---------------------------------------------------------------- Lesen

    fun read(file: File): PnoteResult<Note> {
        val fis = try {
            FileInputStream(file)
        } catch (e: IOException) {
            return PnoteResult.Err(PnoteError.Io)
        }
        return fis.use { decode(BufferedInputStream(it)) }
    }

    /**
     * Liest nur die Vorschau (`preview.jpg`) einer Datei. Sie steht direkt hinter dem Manifest; sobald der erste
     * Seiteneintrag kommt, wird abgebrochen. Null, wenn keine (gültige) Vorschau vorhanden oder die Datei fehlerhaft ist.
     */
    fun readPreview(file: File): ByteArray? = try {
        FileInputStream(file).use { fis ->
            val zip = ZipInputStream(BufferedInputStream(fis))
            var entries = 0
            var result: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES || !isAllowedName(entry.name, entry.isDirectory)) break
                if (entry.name == PREVIEW_ENTRY) {
                    result = readLimited(zip, MAX_PREVIEW_BYTES.toLong(), true).bytes.takeIf { isJpeg(it) }
                    break
                }
                if (entry.name.startsWith("pages/")) break
            }
            result
        }
    } catch (e: PnoteException) {
        null
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** Liest nur das Manifest (Listenansicht). Es gelten dieselben Namens- und Größenprüfungen. */
    fun readSummary(file: File): PnoteResult<NoteSummary> {
        val fis = try {
            FileInputStream(file)
        } catch (e: IOException) {
            return PnoteResult.Err(PnoteError.Io)
        }
        return try {
            fis.use {
                val unpacked = unpackOrError(BufferedInputStream(it), manifestOnly = true)
                val manifest = parseManifest(unpacked.manifest ?: throw PnoteException(PnoteError.MissingManifest))
                PnoteResult.Ok(
                    NoteSummary(
                        manifest.id, manifest.title, manifest.folderPath, manifest.created, manifest.modified,
                        manifest.favorite, manifest.pinned, manifest.pageIds.size,
                    ),
                )
            }
        } catch (e: PnoteException) {
            PnoteResult.Err(e.error)
        } catch (e: IOException) {
            PnoteResult.Err(PnoteError.Corrupt)
        } catch (e: RuntimeException) {
            PnoteResult.Err(PnoteError.Corrupt)
        }
    }

    /** Dekodiert ein `.pnote` aus [input] (ohne zu schließen). */
    fun decode(input: InputStream): PnoteResult<Note> = try {
        val unpacked = unpackOrError(input, manifestOnly = false)
        val manifest = parseManifest(unpacked.manifest ?: throw PnoteException(PnoteError.MissingManifest))
        val pages = LinkedHashMap<String, Page>()
        for (id in manifest.pageIds) {
            val bytes = unpacked.pages[id] ?: throw PnoteException(PnoteError.Corrupt)
            pages[id] = parsePage(id, bytes)
        }
        PnoteResult.Ok(
            Note(
                id = manifest.id,
                title = manifest.title,
                folderPath = manifest.folderPath,
                created = manifest.created,
                modified = manifest.modified,
                favorite = manifest.favorite,
                pinned = manifest.pinned,
                pageIds = manifest.pageIds,
                pages = pages,
                preview = unpacked.preview,
            ),
        )
    } catch (e: PnoteException) {
        PnoteResult.Err(e.error)
    } catch (e: IOException) {
        PnoteResult.Err(PnoteError.Corrupt)
    } catch (e: RuntimeException) {
        PnoteResult.Err(PnoteError.Corrupt)
    }

    private class Unpacked(val manifest: ByteArray?, val pages: Map<String, ByteArray>, val preview: ByteArray? = null)

    /** Wie [unpack], mappt aber Lese-/Formatfehler des ZIP auf [PnoteError.Corrupt]. */
    private fun unpackOrError(input: InputStream, manifestOnly: Boolean): Unpacked = try {
        unpack(input, manifestOnly)
    } catch (e: IOException) {
        throw PnoteException(PnoteError.Corrupt)
    } catch (e: IllegalArgumentException) {
        throw PnoteException(PnoteError.Corrupt)
    }

    private fun unpack(input: InputStream, manifestOnly: Boolean): Unpacked {
        val zip = ZipInputStream(input)
        val seen = HashSet<String>()
        val pages = LinkedHashMap<String, ByteArray>()
        var manifest: ByteArray? = null
        var preview: ByteArray? = null
        var entries = 0
        var total = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            entries++
            if (entries > MAX_ENTRIES) throw PnoteException(PnoteError.LimitExceeded)
            val name = entry.name
            if (!isAllowedName(name, entry.isDirectory)) throw PnoteException(PnoteError.InvalidEntryName)
            if (!seen.add(name)) throw PnoteException(PnoteError.Corrupt)
            if (entry.isDirectory) continue
            val keep = name == MANIFEST || name == PREVIEW_ENTRY || PAGE_ENTRY.matches(name)
            val limit = minOf(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total)
            val data = readLimited(zip, limit, keep)
            total += data.size
            if (name == MANIFEST) {
                manifest = data.bytes
                if (manifestOnly) break
            } else if (name == PREVIEW_ENTRY) {
                preview = data.bytes.takeIf { it.size <= MAX_PREVIEW_BYTES && isJpeg(it) }
            } else if (keep) {
                pages[PAGE_ENTRY.matchEntire(name)!!.groupValues[1]] = data.bytes
            }
        }
        return Unpacked(manifest, pages, preview)
    }

    private fun isAllowedName(name: String, isDirectory: Boolean): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return false
        if (name.contains('\\') || name.startsWith("/") || name.contains("..")) return false
        if (isDirectory) return name == "pages/" || name == "media/"
        return name == MANIFEST || name == PREVIEW_ENTRY || name == "preview.png" ||
            PAGE_ENTRY.matches(name) || MEDIA_ENTRY.matches(name)
    }

    /** Liest den aktuellen Eintrag, bricht beim Überschreiten von [limit] ab; ohne [keep] werden Daten verworfen. */
    private fun readLimited(zip: ZipInputStream, limit: Long, keep: Boolean): LimitedData {
        val buffer = ByteArray(8192)
        val collected = if (keep) java.io.ByteArrayOutputStream() else null
        var count = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            count += n
            if (count > limit) throw PnoteException(PnoteError.LimitExceeded)
            collected?.write(buffer, 0, n)
        }
        return LimitedData(count.toInt(), collected?.toByteArray() ?: ByteArray(0))
    }

    private class LimitedData(val size: Int, val bytes: ByteArray)

    private class Manifest(
        val id: String,
        val title: String,
        val folderPath: String,
        val created: Long,
        val modified: Long,
        val favorite: Boolean,
        val pinned: Boolean,
        val pageIds: List<String>,
    )

    private fun reader(bytes: ByteArray) = JsonReader(InputStreamReader(ByteArrayInputStream(bytes), UTF_8))

    private fun JsonReader.finish() {
        endObject()
        if (peek() != JsonToken.END_DOCUMENT) throw PnoteException(PnoteError.Corrupt)
    }

    private fun JsonReader.floatValue(): Float {
        val f = nextString().toFloat()
        if (!f.isFinite()) throw PnoteException(PnoteError.Corrupt)
        return f
    }

    /** Erster Durchlauf: nur `version`, damit zukünftige Formate (anderes Schema) sauber abgelehnt werden. */
    private fun peekVersion(bytes: ByteArray): Int {
        var version: Int? = null
        reader(bytes).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "version") version = r.nextInt() else r.skipValue()
            }
            r.finish()
        }
        return version ?: throw PnoteException(PnoteError.Corrupt)
    }

    private fun parseManifest(bytes: ByteArray): Manifest {
        val version = try {
            peekVersion(bytes)
        } catch (e: IOException) {
            throw PnoteException(PnoteError.Corrupt)
        } catch (e: RuntimeException) {
            if (e is PnoteException) throw e
            throw PnoteException(PnoteError.Corrupt)
        }
        if (version > FORMAT_VERSION) throw PnoteException(PnoteError.UnsupportedVersion(version))
        if (version < 1) throw PnoteException(PnoteError.Corrupt)

        var id: String? = null
        var title: String? = null
        var folderPath = ""
        var created: Long? = null
        var modified: Long? = null
        var favorite = false
        var pinned = false
        var pageIds: List<String>? = null
        reader(bytes).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "id" -> id = r.nextString()
                    "title" -> title = r.nextString()
                    "folderPath" -> folderPath = r.nextString()
                    "created" -> created = r.nextLong()
                    "modified" -> modified = r.nextLong()
                    "favorite" -> favorite = r.nextBoolean()
                    "pinned" -> pinned = r.nextBoolean()
                    "pageIds" -> {
                        val list = ArrayList<String>()
                        r.beginArray()
                        while (r.hasNext()) {
                            if (list.size >= MAX_PAGES) throw PnoteException(PnoteError.LimitExceeded)
                            list.add(r.nextString())
                        }
                        r.endArray()
                        pageIds = list
                    }
                    else -> r.skipValue()
                }
            }
            r.finish()
        }
        val noteId = id ?: throw PnoteException(PnoteError.Corrupt)
        if (!isValidId(noteId)) throw PnoteException(PnoteError.InvalidId)
        val ids = pageIds ?: throw PnoteException(PnoteError.Corrupt)
        if (ids.any { !isValidId(it) }) throw PnoteException(PnoteError.InvalidId)
        if (ids.toSet().size != ids.size) throw PnoteException(PnoteError.Corrupt)
        if (!FolderPath.isValid(folderPath)) throw PnoteException(PnoteError.InvalidFolder)
        return Manifest(
            noteId,
            title ?: throw PnoteException(PnoteError.Corrupt),
            folderPath,
            created ?: throw PnoteException(PnoteError.Corrupt),
            modified ?: throw PnoteException(PnoteError.Corrupt),
            favorite,
            pinned,
            ids,
        )
    }

    private fun parsePage(id: String, bytes: ByteArray): Page {
        var template = "blank"
        var width: Int? = null
        var height: Int? = null
        var bookmarked = false
        var strokes: List<Stroke> = emptyList()
        reader(bytes).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "template" -> template = r.nextString()
                    "width" -> width = r.nextInt()
                    "height" -> height = r.nextInt()
                    "bookmarked" -> bookmarked = r.nextBoolean()
                    "strokes" -> strokes = readStrokes(r)
                    else -> r.skipValue()
                }
            }
            r.finish()
        }
        val w = width ?: throw PnoteException(PnoteError.Corrupt)
        val h = height ?: throw PnoteException(PnoteError.Corrupt)
        if (w !in 1..MAX_PAGE_DIMENSION || h !in 1..MAX_PAGE_DIMENSION) throw PnoteException(PnoteError.Corrupt)
        if (!TEMPLATE_REGEX.matches(template)) throw PnoteException(PnoteError.Corrupt)
        return Page(id, template, w, h, strokes, bookmarked)
    }

    private fun readStrokes(r: JsonReader): List<Stroke> {
        val strokes = ArrayList<Stroke>()
        r.beginArray()
        while (r.hasNext()) {
            if (strokes.size >= MAX_STROKES_PER_PAGE) throw PnoteException(PnoteError.LimitExceeded)
            var type = 0
            var width = 0f
            var color = 0
            var points: List<Point> = emptyList()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "t" -> type = r.nextInt()
                    "w" -> width = r.floatValue()
                    "c" -> color = r.nextInt()
                    "p" -> points = readPoints(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            strokes.add(Stroke(type, width, color, points))
        }
        r.endArray()
        return strokes
    }

    private fun readPoints(r: JsonReader): List<Point> {
        val points = ArrayList<Point>()
        r.beginArray()
        while (r.hasNext()) {
            if (points.size >= MAX_POINTS_PER_STROKE) throw PnoteException(PnoteError.LimitExceeded)
            r.beginArray()
            val x = r.floatValue()
            val y = r.floatValue()
            val time = r.nextLong()
            val pressure = r.floatValue()
            r.endArray()
            points.add(Point(x, y, time, pressure))
        }
        r.endArray()
        return points
    }
}

/** Dateioperationen, die Codec und Repository teilen. */
internal object FileOps {
    /** Verschiebt atomar (ersetzt ein vorhandenes Ziel); Fallback ohne ATOMIC_MOVE, falls nicht unterstützt. */
    fun moveReplacing(src: File, dst: File) {
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Verschiebt atomar, ersetzt ein vorhandenes Ziel aber NICHT (wirft [java.nio.file.FileAlreadyExistsException]). */
    fun moveNoReplace(src: File, dst: File) {
        if (dst.exists()) throw java.nio.file.FileAlreadyExistsException(null)
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(src.toPath(), dst.toPath())
        }
    }
}
