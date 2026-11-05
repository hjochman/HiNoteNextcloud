package local.papernotes.editor

import android.content.Context
import android.graphics.Canvas
import local.papernotes.editor.templates.ShapeTemplateRenderer
import local.papernotes.editor.templates.TemplateEnv
import android.view.View

/** Zeichnet die Vorlage einer Seite (Hintergrund). Austauschbar. */
interface TemplateRenderer {
    fun draw(canvas: Canvas, width: Int, height: Int)
}

object TemplateRenderers {
    /** Renderer für [name]; unbekannte IDs ergeben die leere Vorlage ("blank"). */
    fun forTemplate(env: TemplateEnv, name: String, minStroke: Float = 1f): TemplateRenderer =
        ShapeTemplateRenderer(TemplateCatalog.lookup(name).id, env, minStroke)
}

/** Hintergrund-View unter der Schreibschicht. Zeichnet nur die Vorlage, keine Striche. */
class PageBackgroundView(context: Context) : View(context) {
    private val env = TemplateEnv(context)
    private val renderers = HashMap<String, TemplateRenderer>()
    private var current = TemplateCatalog.DEFAULT_ID

    /** Wechselt die Vorlage (Renderer werden je ID einmal angelegt) und zeichnet sofort neu. */
    fun setTemplate(id: String) {
        val known = TemplateCatalog.lookup(id).id
        if (known != current) {
            current = known
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = renderers.getOrPut(current) { TemplateRenderers.forTemplate(env, current) }
        r.draw(canvas, width, height)
    }
}
