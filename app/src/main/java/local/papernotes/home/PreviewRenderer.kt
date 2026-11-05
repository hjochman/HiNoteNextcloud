package local.papernotes.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import local.papernotes.editor.TemplateRenderers
import local.papernotes.editor.templates.TemplateEnv
import local.papernotes.model.Note
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Zeichnet Seite 1 einer Notiz als Vorschau: weißer Grund, Vorlage (vorhandener Renderer), darüber alle Striche als Pfade.
 * Läuft im Hintergrund-Thread. `HWEditView.capture()` wird nicht benutzt.
 */
internal class PreviewRenderer(context: Context) {
    private val env = TemplateEnv(context.applicationContext)

    /** Rendert Seite 1 von [note] mit Breite [PREVIEW_WIDTH]; Höhe folgt dem Seitenverhältnis. Null bei Notiz ohne Seite. */
    fun render(note: Note): Bitmap? {
        val page = note.pageIds.firstOrNull()?.let { note.pages[it] } ?: return null
        val w = PREVIEW_WIDTH
        val h = max(1, Math.round(w.toFloat() * page.height / page.width))
        val bmp = createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFFFFFFF.toInt())
        val scale = w.toFloat() / page.width
        canvas.save()
        canvas.scale(scale, h.toFloat() / page.height)
        // Mindestens 1 Vorschau-Pixel breite Linien, damit feine Raster sichtbar bleiben.
        TemplateRenderers.forTemplate(env, page.template, minStroke = 1f / scale).draw(canvas, page.width, page.height)
        canvas.restore()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (s in page.strokes) {
            val pts = s.points
            if (pts.isEmpty()) continue
            paint.color = s.color
            paint.strokeWidth = max(1f, s.width * scale)
            path.rewind()
            path.moveTo(pts[0].x * scale, pts[0].y * scale)
            if (pts.size == 1) path.lineTo(pts[0].x * scale + 0.1f, pts[0].y * scale)
            for (i in 1 until pts.size) path.lineTo(pts[i].x * scale, pts[i].y * scale)
            canvas.drawPath(path, paint)
        }
        return bmp
    }

    /** Rendert die Vorschau wie [render] und kodiert sie als JPEG für `preview.jpg` im Paket. Null bei Fehler. */
    fun renderJpeg(note: Note): ByteArray? {
        val bmp = render(note) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            if (bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) out.toByteArray() else null
        } finally {
            bmp.recycle()
        }
    }

    companion object {
        /** Breite der Vorschau in Pixeln; genügt für die Kachel (ca. 300 px) und die Mini-Vorschau. */
        const val PREVIEW_WIDTH = 360
        private const val JPEG_QUALITY = 80

        fun writePng(bmp: Bitmap, target: File): Boolean {
            val tmp = File(target.parentFile, target.name + ".tmp")
            return try {
                BufferedOutputStream(FileOutputStream(tmp)).use { out ->
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return false
                }
                tmp.renameTo(target)
            } catch (e: java.io.IOException) {
                false
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}
