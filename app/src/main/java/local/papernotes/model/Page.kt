package local.papernotes.model

/**
 * Eine Seite. [id] ist eine UUID (wird als Dateiname im .pnote verwendet).
 * [template] z. B. "blank", "lines_wide", "lines_narrow", "dotted", "millimeter", "grid", "todo".
 * Textboxen und Bilder folgen in Phase 4; das JSON-Format ignoriert unbekannte Felder beim Lesen.
 */
data class Page(
    val id: String,
    val template: String,
    val width: Int,
    val height: Int,
    val strokes: List<Stroke>,
    val bookmarked: Boolean,
)
