package local.papernotes.editor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.home.PreviewStamper
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.PnoteCodec
import local.papernotes.storage.PnoteResult
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Editor: Kopfzeile, Werkzeugleiste und Unterleiste (Stift/Radierer) um die [PageCanvasView]. Die Logik liegt in
 * [NoteSession] (Noten) und [EditorToolState] (Werkzeuge); diese Klasse verbindet nur die Views.
 * Es werden bewusst weder Titel, Pfade noch Strichdaten geloggt.
 */
class EditorActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var io: ExecutorService? = null
    private var session: NoteSession? = null
    private lateinit var tools: EditorToolState

    private lateinit var canvas: PageCanvasView
    private lateinit var textTitle: TextView
    private lateinit var textPage: TextView
    private lateinit var buttonPrev: View
    private lateinit var buttonNext: View
    private lateinit var buttonUndo: View
    private lateinit var buttonRedo: View

    private lateinit var toolBar: View
    private lateinit var popupView: View
    private lateinit var popup: PopupWindow
    private lateinit var buttonPin: View
    private lateinit var penBar: View
    private lateinit var eraserBar: View
    private lateinit var seekPen: SeekBar
    private lateinit var seekEraser: SeekBar
    private lateinit var textPenWidth: TextView
    private lateinit var textEraserSize: TextView

    private val toolButtons = HashMap<PageCanvasView.Tool, View>()
    private val penButtons = HashMap<String, View>()
    private val colorButtons = HashMap<PenColor, View>()
    private val eraserButtons = HashMap<PageCanvasView.EraserStyle, View>()

    @Suppress("DEPRECATION") // Keine Übergangsanimation (E-Ink); die Theme-Attribute decken den Rest ab.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val noteId = intent?.getStringExtra(EXTRA_NOTE_ID)
        if (noteId == null || !PnoteCodec.isValidId(noteId)) {
            finish()
            return
        }
        tools = EditorToolState(PrefsStore(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)))
        setContentView(R.layout.activity_editor)
        bindViews()
        val executor = Executors.newSingleThreadExecutor()
        io = executor
        if (!canvas.isInkAvailable) toast(R.string.error_no_ink)
        applyPen()
        applyEraser()
        canvas.setTool(tools.tool)
        refreshToolUi()
        loadNote(executor, noteId)
    }

    private fun bindViews() {
        canvas = findViewById(R.id.pageCanvas)
        toolBar = findViewById(R.id.toolBar)
        // Stift-/Radierer-Leiste als eigenes Fenster über dem Blatt (nicht fokussierbar, Stift-Eingaben daneben gehen durch).
        popupView = layoutInflater.inflate(R.layout.popup_bars, null)
        popup = PopupWindow(popupView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isFocusable = false
            isOutsideTouchable = false
            isClippingEnabled = false
            animationStyle = 0
        }
        // Fläche des Fensters vom nativen Schreiben ausnehmen (sonst werden Stiftantippen dort zu Strichen).
        popupView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWriteExclusion() }
        buttonPin = popupView.findViewById(R.id.buttonPinBar)
        buttonPin.setOnClickListener { tools.togglePinned(); refreshToolUi() }
        textTitle = findViewById(R.id.textTitle)
        textPage = findViewById(R.id.textPage)
        buttonPrev = findViewById(R.id.buttonPrevPage)
        buttonNext = findViewById(R.id.buttonNextPage)
        buttonUndo = findViewById(R.id.buttonUndo)
        buttonRedo = findViewById(R.id.buttonRedo)
        penBar = popupView.findViewById(R.id.penBar)
        eraserBar = popupView.findViewById(R.id.eraserBar)
        seekPen = popupView.findViewById(R.id.seekPenWidth)
        seekEraser = popupView.findViewById(R.id.seekEraserSize)
        textPenWidth = popupView.findViewById(R.id.textPenWidth)
        textEraserSize = popupView.findViewById(R.id.textEraserSize)

        findViewById<View>(R.id.buttonBack).setOnClickListener { finish() }
        buttonPrev.setOnClickListener { switchPage { it.goToPage(it.pageIndex - 1) } }
        buttonNext.setOnClickListener { switchPage { it.goToPage(it.pageIndex + 1) } }
        buttonUndo.setOnClickListener { canvas.undo(); updateChrome() }
        buttonRedo.setOnClickListener { canvas.redo(); updateChrome() }
        for (id in intArrayOf(R.id.buttonAddPage, R.id.buttonToolPageAdd)) {
            findViewById<View>(id).setOnClickListener { switchPage { it.addPage(); true } }
        }
        findViewById<View>(R.id.buttonDeletePage).setOnClickListener { confirmDeletePage() }
        findViewById<View>(R.id.buttonMore).setOnClickListener { showMoreMenu(it) }
        findViewById<View>(R.id.buttonTemplate).setOnClickListener { showTemplateDialog() }

        toolButtons[PageCanvasView.Tool.PEN] = findViewById(R.id.buttonToolPen)
        toolButtons[PageCanvasView.Tool.ERASER] = findViewById(R.id.buttonToolEraser)
        toolButtons[PageCanvasView.Tool.LASSO] = findViewById(R.id.buttonToolLasso)
        for ((tool, button) in toolButtons) {
            button.setOnClickListener {
                tools.selectTool(tool)
                canvas.setTool(tools.tool)
                refreshToolUi()
            }
        }

        // Reihenfolge der Ids entspricht PenCatalog.pens (Füllfeder, Kugelschreiber, Bleistift, Marker).
        val penIds = intArrayOf(R.id.buttonPenFountain, R.id.buttonPenBallpoint, R.id.buttonPenPencil, R.id.buttonPenMarker)
        for ((i, spec) in PenCatalog.pens.withIndex()) {
            val button = popupView.findViewById<View>(penIds[i])
            penButtons[spec.id] = button
            button.setOnClickListener {
                tools.selectPen(spec)
                applyPen()
                refreshToolUi()
            }
        }
        colorButtons[PenColor.BLACK] = popupView.findViewById(R.id.buttonColorBlack)
        colorButtons[PenColor.GRAY] = popupView.findViewById(R.id.buttonColorGray)
        colorButtons[PenColor.WHITE] = popupView.findViewById(R.id.buttonColorWhite)
        for ((color, button) in colorButtons) {
            button.setOnClickListener {
                tools.selectColor(color)
                applyPen()
                refreshToolUi()
            }
        }
        popupView.findViewById<View>(R.id.buttonPenSmaller).setOnClickListener { tools.stepPenWidth(-1); applyPen(); refreshToolUi() }
        popupView.findViewById<View>(R.id.buttonPenLarger).setOnClickListener { tools.stepPenWidth(1); applyPen(); refreshToolUi() }
        seekPen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tools.setPenWidth(tools.pen.minWidth + progress)
                textPenWidth.text = num(tools.penWidth)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                applyPen()
            }
        })

        eraserButtons[PageCanvasView.EraserStyle.STANDARD] = popupView.findViewById(R.id.buttonEraserStandard)
        eraserButtons[PageCanvasView.EraserStyle.AREA] = popupView.findViewById(R.id.buttonEraserArea)
        eraserButtons[PageCanvasView.EraserStyle.STROKES] = popupView.findViewById(R.id.buttonEraserStrokes)
        for ((style, button) in eraserButtons) {
            button.setOnClickListener {
                tools.selectEraserStyle(style)
                applyEraser()
                refreshToolUi()
            }
        }
        popupView.findViewById<View>(R.id.buttonEraserSmaller).setOnClickListener { tools.stepEraserRadius(-1); applyEraser(); refreshToolUi() }
        popupView.findViewById<View>(R.id.buttonEraserLarger).setOnClickListener { tools.stepEraserRadius(1); applyEraser(); refreshToolUi() }
        seekEraser.max = EditorToolState.MAX_ERASER_RADIUS - EditorToolState.MIN_ERASER_RADIUS
        seekEraser.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tools.setEraserRadius(EditorToolState.MIN_ERASER_RADIUS + progress)
                textEraserSize.text = num(tools.eraserRadius)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                applyEraser()
            }
        })
        setEnabledAll(false)
    }

    private fun num(value: Int): String = NumberFormat.getInstance().format(value)

    private fun applyPen() {
        canvas.setPen(tools.pen.frameworkType, tools.penColor.argb, tools.penWidth.toFloat())
    }

    private fun applyEraser() {
        canvas.setEraser(tools.eraserStyle, tools.eraserRadius)
    }

    /** Aktualisiert Auswahlzustand und Sichtbarkeit der Unterleiste. Ändert nie die Größe der Schreibfläche. */
    private fun refreshToolUi() {
        for ((tool, button) in toolButtons) button.isSelected = tool == tools.tool
        val showPen = tools.barVisible && tools.tool == PageCanvasView.Tool.PEN
        val showEraser = tools.barVisible && tools.tool == PageCanvasView.Tool.ERASER
        penBar.visibility = if (showPen) View.VISIBLE else View.GONE
        eraserBar.visibility = if (showEraser) View.VISIBLE else View.GONE
        buttonPin.isSelected = tools.barPinned
        updatePopup(showPen || showEraser)

        for ((id, button) in penButtons) button.isSelected = id == tools.pen.id
        for ((color, button) in colorButtons) button.isSelected = color == tools.penColor
        seekPen.max = tools.pen.maxWidth - tools.pen.minWidth
        seekPen.progress = tools.penWidth - tools.pen.minWidth
        textPenWidth.text = num(tools.penWidth)

        for ((style, button) in eraserButtons) button.isSelected = style == tools.eraserStyle
        seekEraser.progress = tools.eraserRadius - EditorToolState.MIN_ERASER_RADIUS
        textEraserSize.text = num(tools.eraserRadius)
    }

    /** Zeigt oder versteckt die Unterleiste als Fenster direkt unter der Werkzeugzeile. */
    private fun updatePopup(show: Boolean) {
        if (!show) {
            if (popup.isShowing) popup.dismiss()
            canvas.setWriteExclusion(null)
            return
        }
        if (isFinishing || isDestroyed) return
        toolBar.post {
            if (isFinishing || isDestroyed || !tools.barVisible || popup.isShowing) return@post
            popup.showAsDropDown(toolBar)
            popupView.post { updateWriteExclusion() }
        }
    }

    private fun updateWriteExclusion() {
        if (!::popup.isInitialized || !popup.isShowing) return
        val loc = IntArray(2)
        popupView.getLocationOnScreen(loc)
        canvas.setWriteExclusion(Rect(loc[0], loc[1], loc[0] + popupView.width, loc[1] + popupView.height))
    }

    /** Dialoge und Menüs liegen über dem Blatt: solange sie offen sind, schreibt der Stift nicht. */
    private var overlayCount = 0

    private fun overlayOpened() {
        if (overlayCount++ == 0) canvas.suspendWriting(true)
    }

    private fun overlayClosed() {
        if (overlayCount > 0 && --overlayCount == 0) canvas.suspendWriting(false)
    }

    private fun showMoreMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(R.string.menu_more_soon).isEnabled = false
        menu.setOnDismissListener { overlayClosed() }
        overlayOpened()
        menu.show()
    }

    private fun loadNote(executor: ExecutorService, noteId: String) {
        if (!NotesLocation.hasAccess(this)) {
            toast(R.string.error_no_storage)
            finish()
            return
        }
        val root = NotesLocation.root(this)
        executor.execute {
            val result = try {
                NoteRepository(root).load(noteId)
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                val note = (result as? PnoteResult.Ok)?.value
                if (note == null || note.pageIds.isEmpty()) {
                    toast(R.string.error_open)
                    finish()
                } else {
                    startSession(NoteRepository(root), note)
                }
            }
        }
    }

    private fun startSession(repo: NoteRepository, note: local.papernotes.model.Note) {
        val executor = io ?: return
        val stamper = PreviewStamper(this)
        val s = NoteSession(note, { repo.save(stamper.stamp(it)) }, executor, HandlerScheduler(ui))
        s.onSaveResult = { ok -> if (!ok) ui.post { if (!isDestroyed) toast(R.string.error_save) } }
        session = s
        canvas.onChanged = { strokes ->
            s.onStrokesChanged(strokes)
            updateChrome()
            if (tools.barVisible && !tools.barPinned) {
                tools.onContentChanged()
                refreshToolUi()
            }
        }
        textTitle.text = note.title
        setEnabledAll(true)
        showCurrentPage()
    }

    private fun showCurrentPage() {
        val s = session ?: return
        canvas.showPage(s.currentPage, s.currentHistory)
        updateChrome()
    }

    /** Erfasst offene Änderungen, führt die Seitenaktion aus und zeigt danach die (neue) aktuelle Seite. */
    private fun switchPage(action: (NoteSession) -> Boolean) {
        val s = session ?: return
        canvas.flush()
        if (action(s)) showCurrentPage()
    }

    /** Vorlagendialog; wie alle Overlays mit [overlayOpened]/[overlayClosed] gekoppelt, damit der Stift die Knöpfe bedienen kann. */
    private fun showTemplateDialog() {
        val s = session ?: return
        val page = s.currentPage
        val dialog = TemplateDialog(this, page.template, page.width, page.height) { template, allPages ->
            canvas.flush()
            if (s.setTemplate(template, allPages)) canvas.setTemplate(s.currentPage.template)
        }
        dialog.onDismiss = { overlayClosed() }
        overlayOpened()
        dialog.show()
    }

    private fun confirmDeletePage() {
        val s = session ?: return
        if (s.pageCount <= 1) {
            toast(R.string.error_last_page)
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete_page)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> switchPage { it.deletePage() } }
            .setOnDismissListener { overlayClosed() }
            .also { overlayOpened() }
            .show()
    }

    private fun updateChrome() {
        val s = session ?: return
        textPage.text = s.currentPageNumberText()
        buttonPrev.isEnabled = s.pageIndex > 0
        buttonNext.isEnabled = s.pageIndex < s.pageCount - 1
        buttonUndo.isEnabled = canvas.canUndo
        buttonRedo.isEnabled = canvas.canRedo
    }

    private fun setEnabledAll(enabled: Boolean) {
        for (id in CONTROL_IDS) findViewById<View>(id).isEnabled = enabled
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onPause() {
        super.onPause()
        if (::popup.isInitialized && popup.isShowing) popup.dismiss()
        persist()
    }

    override fun onStop() {
        super.onStop()
        persist()
    }

    private fun persist() {
        val s = session ?: return
        canvas.flush()
        s.save()
    }

    override fun onResume() {
        super.onResume()
        if (::tools.isInitialized && ::popup.isInitialized) refreshToolUi()
    }

    override fun onDestroy() {
        if (::popup.isInitialized && popup.isShowing) popup.dismiss()
        session?.close()
        session?.onSaveResult = null
        session = null
        if (::canvas.isInitialized) canvas.onChanged = null
        io?.shutdown() // bereits eingereihte Schreibvorgänge laufen noch zu Ende
        super.onDestroy()
    }

    private class HandlerScheduler(private val handler: Handler) : SaveScheduler {
        override fun postDelayed(delayMs: Long, task: Runnable): Any {
            handler.postDelayed(task, delayMs)
            return task
        }

        override fun cancel(token: Any) = handler.removeCallbacks(token as Runnable)
    }

    private class PrefsStore(private val prefs: SharedPreferences) : ToolSettingsStore {
        override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
        override fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    }

    companion object {
        private const val EXTRA_NOTE_ID = "local.papernotes.NOTE_ID"
        private const val PREFS_NAME = "editor_tools"

        // Erst nach dem Laden der Notiz bedienbar. Werkzeuge und Menü sind immer aktiv; Platzhalter bleiben deaktiviert.
        private val CONTROL_IDS = intArrayOf(
            R.id.buttonPrevPage, R.id.buttonNextPage, R.id.buttonUndo, R.id.buttonRedo, R.id.buttonAddPage,
            R.id.buttonToolPageAdd, R.id.buttonDeletePage, R.id.buttonTemplate,
        )

        fun intent(context: Context, noteId: String): Intent =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_NOTE_ID, noteId)
    }
}
