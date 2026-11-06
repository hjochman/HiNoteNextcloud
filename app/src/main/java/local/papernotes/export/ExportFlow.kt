package local.papernotes.export

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.media.MediaScannerConnection
import android.widget.Toast
import local.papernotes.EinkDialog
import local.papernotes.NoteSharing
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.model.Note
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ablauf "Exportieren" für Startseite und Editor: Format wählen ("Exportieren als" -> PDF), Ziel wählen (Teilen oder
 * In Dokumente speichern), PDFs im Hintergrund-Thread erzeugen (Fortschritt als Text "Seite 3/12", keine Animation).
 * Je Notiz eine Datei. Zuerst wird nach `cacheDir/export` geschrieben (vor jedem Export geleert); Teilen reicht diese Dateien
 * per FileProvider weiter, Speichern kopiert sie nach `Documents/PaperNotesPDF` (bestehende Dateien werden nie überschrieben).
 * Muss im UI-Thread benutzt werden. [onOverlayOpened]/[onOverlayClosed] melden jeden Dialog (Editor: Stift sperren).
 */
internal class ExportFlow(
    private val activity: Activity,
    private val onOverlayOpened: () -> Unit = {},
    private val onOverlayClosed: () -> Unit = {},
) {
    /** Eine zu exportierende Notiz; [load] läuft im Hintergrund-Thread und liefert null, wenn die Notiz nicht lesbar ist. */
    class Source(val title: String, val load: () -> Note?)

    private enum class Mode { SHARE, SAVE }

    private class Running(val cancelled: AtomicBoolean, val progress: EinkDialog.Progress)

    private var running: Running? = null

    /** Dialog "Exportieren als" (derzeit nur PDF), danach [chooseTarget]. */
    fun chooseFormat(sources: List<Source>) {
        if (sources.isEmpty()) {
            toast(R.string.export_nothing)
            return
        }
        track(
            EinkDialog.singleChoice(activity, activity.getString(R.string.export_as_title), listOf(activity.getString(R.string.export_format_pdf)), -1) {
                chooseTarget(sources)
            },
        )
    }

    /** Dialog mit "Teilen" und "In Dokumente speichern". */
    fun chooseTarget(sources: List<Source>) {
        if (sources.isEmpty()) {
            toast(R.string.export_nothing)
            return
        }
        track(
            EinkDialog.singleChoice(
                activity,
                activity.getString(R.string.export_target_title),
                listOf(activity.getString(R.string.action_share), activity.getString(R.string.export_save)),
                -1,
            ) { index -> run(sources, if (index == 0) Mode.SHARE else Mode.SAVE) },
        )
    }

    /** Bricht einen laufenden Export ab und schließt den Fortschrittsdialog (z. B. in `onDestroy`). */
    fun cancel() {
        running?.let {
            it.cancelled.set(true)
            it.progress.dismiss()
        }
        running = null
    }

    private fun track(dialog: Dialog) {
        onOverlayOpened()
        dialog.setOnDismissListener { onOverlayClosed() }
    }

    private fun run(sources: List<Source>, mode: Mode) {
        if (running != null) return
        if (mode == Mode.SAVE && !NotesLocation.hasAccess(activity)) {
            toast(R.string.error_no_storage)
            return
        }
        val cancelled = AtomicBoolean(false)
        val progress = EinkDialog.progress(
            activity,
            activity.getString(R.string.export_progress_title),
            activity.getString(R.string.export_progress_start),
        ) {
            cancelled.set(true)
            running = null
        }
        track(progress.dialog)
        running = Running(cancelled, progress)
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val outcome = try {
                produce(sources, mode, cancelled) { text -> activity.runOnUiThread { if (!cancelled.get()) progress.setText(text) } }
            } catch (e: Exception) {
                Outcome.Failed
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || cancelled.get()) return@runOnUiThread
                running = null
                progress.dismiss()
                finish(outcome, mode)
            }
            executor.shutdown()
        }
    }

    private sealed class Outcome {
        object Failed : Outcome()
        class Done(val files: List<File>, val failed: Int) : Outcome()
    }

    /** Hintergrund-Thread: erzeugt die PDFs; bei [Mode.SAVE] liegen die Ergebnisdateien danach in Documents. */
    private fun produce(sources: List<Source>, mode: Mode, cancelled: AtomicBoolean, progress: (String) -> Unit): Outcome {
        val dir = File(activity.cacheDir, "export")
        if (!dir.isDirectory && !dir.mkdirs()) return Outcome.Failed
        dir.listFiles()?.forEach { it.delete() }
        val names = ExportFileNames.unique(sources.map { it.title })
        val exporter = PdfExporter(activity)
        val done = ArrayList<File>()
        var failed = 0
        for ((i, source) in sources.withIndex()) {
            if (cancelled.get()) return Outcome.Failed
            val note = source.load()
            if (note == null) {
                failed++
                continue
            }
            val target = File(dir, names[i])
            val result = exporter.write(
                note,
                target,
                onPage = { page, total ->
                    val pageText = activity.getString(R.string.export_progress_page, page, total)
                    progress(if (sources.size > 1) activity.getString(R.string.export_progress_note, i + 1, sources.size) + "\n" + pageText else pageText)
                },
                isCancelled = { cancelled.get() },
            )
            when (result) {
                PdfExporter.Result.OK -> done.add(target)
                PdfExporter.Result.CANCELLED -> return Outcome.Failed
                PdfExporter.Result.FAILED -> failed++
            }
        }
        if (done.isEmpty()) return Outcome.Failed
        if (mode == Mode.SHARE) return Outcome.Done(done, failed)
        return save(done, dir, failed)
    }

    /** Kopiert [files] nach `Documents/PaperNotesPDF` (Namen eindeutig gegenüber vorhandenen Dateien), räumt den Cache auf. */
    private fun save(files: List<File>, cacheDir: File, failed: Int): Outcome {
        val out = ExportLocation.directory()
        if (!out.isDirectory && !out.mkdirs()) return Outcome.Failed
        val used = (out.list() ?: emptyArray()).mapTo(HashSet()) { it.lowercase() }
        val saved = ArrayList<File>()
        var copyFailed = failed
        for (file in files) {
            val name = ExportFileNames.next(file.name.removeSuffix(".pdf"), used)
            val target = File(out, name)
            val tmp = File(out, ".$name.tmp")
            try {
                Files.copy(file.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (tmp.renameTo(target)) saved.add(target) else copyFailed++
            } catch (e: IOException) {
                copyFailed++
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
        cacheDir.listFiles()?.forEach { it.delete() }
        if (saved.isEmpty()) return Outcome.Failed
        MediaScannerConnection.scanFile(activity, saved.map { it.path }.toTypedArray(), Array(saved.size) { "application/pdf" }, null)
        return Outcome.Done(saved, copyFailed)
    }

    private fun finish(outcome: Outcome, mode: Mode) {
        if (outcome !is Outcome.Done) {
            toast(R.string.error_export)
            return
        }
        if (outcome.failed > 0) toast(R.string.error_export_partial)
        if (mode == Mode.SAVE) {
            val n = outcome.files.size
            Toast.makeText(activity, activity.resources.getQuantityString(R.plurals.export_saved, n, n, "Documents/${ExportLocation.FOLDER_NAME}"), Toast.LENGTH_LONG).show()
            return
        }
        try {
            val title = activity.getString(if (outcome.files.size == 1) R.string.export_chooser else R.string.export_chooser_multi)
            activity.startActivity(NoteSharing.chooserIntent(activity, outcome.files, title, "application/pdf"))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_share)
        } catch (e: IllegalArgumentException) {
            toast(R.string.error_share)
        }
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()
}
