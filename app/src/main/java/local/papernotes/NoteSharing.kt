package local.papernotes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import local.papernotes.home.ShareNames
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.NoteSummary
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Teilen von `.pnote`-Dateien: Kopien mit unbedenklichen Dateinamen in `cacheDir/share` (nur dieser Ordner ist per
 * FileProvider freigegeben, siehe `res/xml/file_paths.xml`), Weitergabe per `ACTION_SEND` bzw. `ACTION_SEND_MULTIPLE`
 * mit temporärer Leseberechtigung. Dateizugriffe: nicht im UI-Thread aufrufen.
 */
internal object NoteSharing {
    /** Kopiert die Dateien der [notes] nach `cacheDir/share` (frühere Kopien werden entfernt). Null bei Fehlern. */
    fun prepareFiles(cacheDir: File, repo: NoteRepository, notes: List<NoteSummary>): List<File>? {
        val dir = File(cacheDir, "share")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        dir.listFiles()?.forEach { it.delete() }
        val names = ShareNames.unique(notes.map { it.title })
        val out = ArrayList<File>(notes.size)
        notes.forEachIndexed { i, note ->
            val source = repo.noteFile(note.id) ?: return null
            val target = File(dir, names[i])
            try {
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                return null
            }
            out.add(target)
        }
        return out
    }

    /**
     * Auswahldialog-Intent für [files] (mindestens eine Datei). Wirft [IllegalArgumentException], wenn eine Datei
     * außerhalb des freigegebenen Ordners liegt.
     */
    fun chooserIntent(context: Context, files: List<File>, chooserTitle: String): Intent {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("", uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
