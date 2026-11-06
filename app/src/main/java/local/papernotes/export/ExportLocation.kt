package local.papernotes.export

import android.os.Environment
import java.io.File

/** Zielordner für gespeicherte PDFs: `Documents/PaperNotesPDF`, bewusst außerhalb des Notizordners `Documents/PaperNotes`. */
internal object ExportLocation {
    const val FOLDER_NAME = "PaperNotesPDF"

    /** Der Ordner ohne Zugriffsprüfung und ohne Anlegen. */
    fun directory(): File {
        @Suppress("DEPRECATION") // Unter API 29 mit Legacy-Zugriff der vorgesehene Weg (wie NotesLocation).
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(documents, FOLDER_NAME)
    }
}
