package local.papernotes.editor

import android.content.Context
import android.eink.view.edit.HWEditView
import android.eink.view.edit.IHWEditParam
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import local.papernotes.model.Page
import local.papernotes.model.Stroke

/**
 * Schreibfläche: unten [PageBackgroundView] (Vorlage), darüber transparent und vollflächig die [HWEditView]
 * (belegt in Spike 2). Dünne Schicht über der E-Ink-Klasse; alles Rechnerische liegt in [PageHistory] und [TrackMapper].
 *
 * Annahmen (auf dem Tablet zu prüfen):
 * - `OnEditListener.onEdit` kommt im UI-Thread und nach jeder Änderung der Track-Liste (Strichende, Radieren, Lasso
 *   löschen/ausschneiden/einfügen, erster Lasso-Verschiebeschritt). Weitere Verschiebungen derselben Auswahl melden
 *   laut Dekompilat KEIN Ereignis, sie werden beim Beenden der Auswahl (`onSelectCancel`) und in [flush] erfasst.
 * - `setTrackList`/`clear` lösen selbst kein `onEdit` aus (`cancelSelection` darin ruft aber `onSelectCancel`). Alle
 *   eigenen Aufrufe laufen deshalb unter dem Reentrancy-Flag [applying]; Ereignisse darin werden ignoriert.
 * - Der Verlauf des Frameworks (`undo()`/`redo()`) wird nicht benutzt. Löst jemand ihn dennoch aus (Typ 1/2), wird das
 *   Ereignis ignoriert.
 * - Der Stift-Pfad bleibt unberührt: Es gibt keine Listener am Touch-Pfad, nur am Strichende.
 * - Die Tracks der `HWEditView` liegen in Ansichts-Pixeln, [Stroke], [PageHistory] und [onChanged] in Seitenkoordinaten.
 *   Dazwischen vermittelt [PageTransform] (Seite füllt die Ansichtsbreite, Versatz 0; bei Seitenbreite = Ansichtsbreite
 *   also 1:1). Zoom und Verschieben ändern später nur [transform] (siehe [updateTransform]).
 * - Die Stiftbreite ([setPen]) gilt in Ansichts-Pixeln, die gespeicherte Breite ist durch den Maßstab geteilt.
 */
class PageCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class Tool { PEN, ERASER, LASSO }

    /**
     * Radierstil in der Sprache von HiNote ("Standard, Fläche, Striche löschen"). Zuordnung zu den Framework-Werten
     * ist eine Annahme aus dem Dekompilat/Spike (0 = Track, 1 = Quick, 2 = Range) und auf dem Gerät zu bestätigen.
     * Literale statt Stub-Konstanten, damit der Enum keine E-Ink-Klasse lädt.
     */
    enum class EraserStyle(internal val frameworkValue: Int) {
        STANDARD(0),
        AREA(2),
        STROKES(1),
    }

    private val background = PageBackgroundView(context)
    private var ink: HWEditView? = null
    private var mapper: TrackMapper? = null
    private var history = PageHistory(emptyList())
    private var applying = false
    private var pageWidth = 0
    private var transform = PageTransform.IDENTITY

    private var tool = Tool.PEN
    private var penType = DEFAULT_PEN_TYPE
    private var penColor = Color.BLACK
    private var penWidth = DEFAULT_PEN_WIDTH
    private var eraserStyle = EraserStyle.STANDARD
    private var eraserRadius = DEFAULT_ERASER_RADIUS

    /** Wird nach jeder erfassten Änderung mit der neuen Strichliste aufgerufen (UI-Thread). */
    var onChanged: ((List<Stroke>) -> Unit)? = null

    /** False, wenn `HWEditView` nicht erzeugt werden konnte (z. B. kein Huawei-Gerät). Dann gibt es nur den Hintergrund. */
    val isInkAvailable: Boolean get() = ink != null

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    init {
        addView(background, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        try {
            val v = HWEditView(context)
            mapper = TrackMapper(v.getHWTrackCreator())
            configure(v)
            addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            ink = v
        } catch (t: Throwable) {
            ink = null
            mapper = null
        }
    }

    private fun configure(v: HWEditView) {
        val fb = v.getFBSurface()
        fb.setAutoHandWrite(true)
        fb.setAhwToolTypes(intArrayOf(TOOL_TYPE_STYLUS))
        fb.setAhwPenType(penType)
        fb.setAhwPenWidth(penWidth)
        fb.setAhwPenColor(penColor)
        v.setSelectToolTypes(intArrayOf(TOOL_TYPE_STYLUS))
        v.setEraseToolTypes(intArrayOf(TOOL_TYPE_STYLUS))

        // Schneller Radierer wie in HiNote: Hintergrund als Weiß binden, sonst läuft der träge Standardpfad (Spike 2).
        // Bei Stil "Fläche" (Range) bindet der Spike nichts. Der Radierkreis fehlt im Schnellpfad (Folgepunkt).
        v.setOnEraserPrepareListener { _, picture ->
            if (eraserStyle != EraserStyle.AREA) picture.bindColor(Color.WHITE)
        }
        v.setEraserShowBorder(false)
        v.setEraserStyle(eraserStyle.frameworkValue)
        v.setEraserRadius(eraserRadius)

        v.setOnEditListener { _, param ->
            if (param.type == IHWEditParam.TYPE_EDIT) syncHistory()
        }
        // Pflicht: HWEditView ruft den Listener beim Beenden einer Auswahl ohne Null-Prüfung auf.
        v.setOnSelectCompleteListener(object : HWEditView.OnSelectCompleteListener {
            override fun onSelectCancel(view: HWEditView) {
                syncHistory()
            }

            override fun onSelectComlete(view: HWEditView) = Unit
        })
        v.setState(stateFor(tool))
    }

    /** Zeigt [page] mit ihrem Verlauf. Der Aufrufer muss vorher [flush] für die bisherige Seite aufgerufen haben. */
    fun showPage(page: Page, pageHistory: PageHistory) {
        history = pageHistory
        pageWidth = page.width
        transform = computeTransform()
        mapper?.transform = transform
        background.setPageGeometry(pageWidth, transform)
        background.setTemplate(page.template)
        applyToInk(pageHistory.strokes)
    }

    /** Abbildung Seite -> Ansicht für die aktuelle Seite; vor dem Layout (Breite 0) die Identität. */
    private fun computeTransform(): PageTransform =
        if (width > 0 && pageWidth > 0) PageTransform.fitWidth(width.toFloat(), pageWidth.toFloat()) else PageTransform.IDENTITY

    /**
     * Übernimmt eine geänderte [transform] (Größenwechsel der Ansicht, später Zoom/Verschieben): offene Änderungen werden
     * noch mit der alten Abbildung erfasst, danach werden die Tracks aus dem Verlauf (Seitenkoordinaten) neu gesetzt.
     */
    private fun updateTransform() {
        if (pageWidth <= 0) return
        val next = computeTransform()
        if (next == transform) return
        flush()
        transform = next
        mapper?.transform = next
        background.setPageGeometry(pageWidth, next)
        applyToInk(history.strokes)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    /** Wechselt nur den Hintergrund der gezeigten Seite; die Striche bleiben unberührt. */
    fun setTemplate(id: String) = background.setTemplate(id)

    /** Erfasst ausstehende Änderungen (z. B. verschobene Lasso-Auswahl) im Verlauf. */
    fun flush() {
        val v = ink ?: return
        v.cancelSelection()
        syncHistory()
    }

    /** Liest die Strichliste der Ansicht zurück (`getTrackList()`) und gibt den Stand des Verlaufs zurück. */
    fun currentStrokes(): List<Stroke> {
        syncHistory()
        return history.strokes
    }

    fun setTool(newTool: Tool) {
        if (newTool == tool) return
        tool = newTool
        ink?.setState(stateFor(newTool))
    }

    fun setPen(type: Int, color: Int, width: Float) {
        penType = type
        penColor = color
        penWidth = width
        val fb = ink?.getFBSurface() ?: return
        fb.setAhwPenType(type)
        fb.setAhwPenColor(color)
        fb.setAhwPenWidth(width)
    }

    fun setEraser(style: EraserStyle, radius: Int) {
        eraserStyle = style
        eraserRadius = radius
        val v = ink ?: return
        v.setEraserStyle(style.frameworkValue)
        v.setEraserRadius(radius)
    }

    /**
     * Nimmt einen Bereich (Bildschirmkoordinaten, z. B. die Stift-Leiste als Popup-Fenster) vom nativen Schreiben aus.
     * Sonst wertet die E-Ink-Schicht Stiftberührungen dort als Striche und die Knöpfe bleiben unbedienbar. Wie HiNote:
     * erlaubt ist die Fläche der Ansicht abzüglich des Fensters. `null` hebt die Ausnahme auf.
     */
    fun setWriteExclusion(screenRect: Rect?) {
        val v = ink ?: return
        val clip = if (screenRect == null || width == 0 || height == 0) {
            null
        } else {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val hole = Path().apply {
                addRect(
                    (screenRect.left - loc[0]).toFloat(), (screenRect.top - loc[1]).toFloat(),
                    (screenRect.right - loc[0]).toFloat(), (screenRect.bottom - loc[1]).toFloat(), Path.Direction.CW,
                )
            }
            Path().apply {
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                op(hole, Path.Op.DIFFERENCE)
            }
        }
        v.getFBSurface().setClip(clip)
    }

    /**
     * Sperrt das native Schreiben ganz, solange ein Dialog oder Menü offen ist (deren Fenster liegen über dem Blatt;
     * der Stift würde sonst dort schreiben, statt die Knöpfe zu bedienen).
     */
    fun suspendWriting(suspended: Boolean) {
        ink?.getFBSurface()?.setAutoHandWrite(!suspended)
    }

    fun undo(): Boolean {
        flush()
        val strokes = history.undo() ?: return false
        applyToInk(strokes)
        onChanged?.invoke(strokes)
        return true
    }

    fun redo(): Boolean {
        flush()
        val strokes = history.redo() ?: return false
        applyToInk(strokes)
        onChanged?.invoke(strokes)
        return true
    }

    private fun syncHistory() {
        val v = ink ?: return
        val m = mapper ?: return
        if (applying) return
        if (history.record(m.fromTracks(v.getTrackList()))) onChanged?.invoke(history.strokes)
    }

    private fun applyToInk(strokes: List<Stroke>) {
        val v = ink ?: return
        val m = mapper ?: return
        applying = true
        try {
            val tracks = m.toTracks(strokes)
            v.setTrackList(tracks)
            // Wie im Spike 2 bei leerer Liste: clear() stellt sicher, dass alte Striche vom Bildschirm verschwinden.
            if (tracks.isEmpty()) v.clear()
        } finally {
            applying = false
        }
    }

    private fun stateFor(t: Tool): Int = when (t) {
        Tool.PEN -> STATE_HANDWRITE
        Tool.ERASER -> STATE_ERASE
        Tool.LASSO -> STATE_SELECT
    }

    private companion object {
        // Werte aus HWEraserView/HWEditView (Stub: android.eink.view). Literale, damit der Companion keine E-Ink-Klasse lädt.
        const val STATE_HANDWRITE = 1
        const val STATE_ERASE = 2
        const val STATE_SELECT = 3
        const val TOOL_TYPE_STYLUS = 2
        const val DEFAULT_PEN_TYPE = 2
        const val DEFAULT_PEN_WIDTH = 4f
        const val DEFAULT_ERASER_RADIUS = 20
    }
}
