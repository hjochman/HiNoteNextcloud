package local.papernotes

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import local.papernotes.about.AboutActivity
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteResult
import local.papernotes.trash.TrashActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Einstellungen: Speicherort (nur Anzeige), Papierkorb (Anzahl, Ansicht, leeren) und Version. Die Sprache folgt dem System.
 * Dateizugriffe laufen nie im UI-Thread.
 */
class SettingsActivity : Activity() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var textTrashCount: TextView
    private lateinit var rowTrash: View
    private lateinit var rowEmptyTrash: View

    private var trashCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        textTrashCount = findViewById(R.id.textTrashCount)
        rowTrash = findViewById(R.id.rowTrash)
        rowEmptyTrash = findViewById(R.id.rowEmptyTrash)

        findViewById<ImageButton>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.textLocation).text = NotesLocation.location().absolutePath
        findViewById<TextView>(R.id.textVersion).text = versionName()

        rowTrash.setOnClickListener { startActivity(Intent(this, TrashActivity::class.java)) }
        rowEmptyTrash.setOnClickListener { if (trashCount > 0) askEmptyTrash() }
        findViewById<View>(R.id.rowAbout).setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }

        updateTrashUi()
    }

    override fun onResume() {
        super.onResume()
        refreshTrashCount()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    private fun refreshTrashCount() {
        if (!NotesLocation.hasAccess(this)) {
            trashCount = 0
            updateTrashUi()
            return
        }
        val repo = NoteRepository(NotesLocation.root(this))
        io.execute {
            val count = repo.trashCount()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                trashCount = count
                updateTrashUi()
            }
        }
    }

    private fun updateTrashUi() {
        textTrashCount.text = resources.getQuantityString(R.plurals.notes_count, trashCount, trashCount)
        // Ausgegraut, wenn der Papierkorb leer ist (die Kindansichten übernehmen den Zustand per duplicateParentState).
        rowEmptyTrash.isEnabled = trashCount > 0
        rowEmptyTrash.isClickable = trashCount > 0
    }

    private fun askEmptyTrash() {
        EinkDialog.confirm(
            this,
            getString(R.string.trash_empty_title),
            getString(R.string.trash_empty_message),
            getString(R.string.trash_empty_confirm),
        ) {
            val repo = NoteRepository(NotesLocation.root(this))
            io.execute {
                val ok = repo.emptyTrash() is PnoteResult.Ok
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (!ok) Toast.makeText(this, R.string.error_action_failed, Toast.LENGTH_SHORT).show()
                    refreshTrashCount()
                }
            }
        }
    }
}
