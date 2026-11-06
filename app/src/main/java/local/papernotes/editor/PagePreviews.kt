package local.papernotes.editor

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import local.papernotes.home.PreviewRenderer
import local.papernotes.model.Page
import local.papernotes.model.Stroke
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Vorschaubilder der Seitenleiste: jede Seite (Vorlage + Striche) wird mit dem [PreviewRenderer] auf einem
 * Hintergrund-Thread gezeichnet und in einem kleinen Arbeitsspeicher-Cache gehalten. Ein Bild gilt, solange Vorlage,
 * Größe und die Strichliste (Referenz) der Seite gleich sind; ein Lesezeichenwechsel macht es also nicht ungültig.
 * [onReady] kommt im UI-Thread mit der Seiten-ID. Nach [shutdown] kommen keine Rückrufe mehr. Es wird nichts geloggt.
 */
internal class PagePreviews(context: Context) {
    private class Entry(val bitmap: Bitmap, val template: String, val strokes: List<Stroke>, val pageW: Int, val pageH: Int) {
        fun matches(page: Page) =
            template == page.template && strokes === page.strokes && pageW == page.width && pageH == page.height
    }

    private val appContext = context.applicationContext
    private val memory = object : LruCache<String, Entry>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Entry): Int = value.bitmap.byteCount
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "page-previews").apply { isDaemon = true }
    }
    private var renderer: PreviewRenderer? = null

    @Volatile
    private var closed = false

    /** Wird im UI-Thread aufgerufen, sobald für eine Seite ein Bild bereitsteht. */
    var onReady: ((pageId: String) -> Unit)? = null

    private fun key(page: Page, width: Int) = "${page.id}@$width"

    /** Fertiges Bild oder null (dann [request] aufrufen). Nur im UI-Thread. */
    fun peek(page: Page, width: Int): Bitmap? = memory.get(key(page, width))?.takeIf { it.matches(page) }?.bitmap

    /** Stößt das Zeichnen an, falls das Bild fehlt oder veraltet ist. Mehrfaches Anfordern zeichnet nichts doppelt. */
    fun request(page: Page, width: Int) {
        if (closed || width <= 0 || peek(page, width) != null) return
        val k = key(page, width)
        if (!pending.add(k)) return
        try {
            worker.execute {
                try {
                    val r = renderer ?: PreviewRenderer(appContext).also { renderer = it }
                    val bmp = r.renderPage(page, width)
                    if (!closed) {
                        memory.put(k, Entry(bmp, page.template, page.strokes, page.width, page.height))
                        main.post { if (!closed) onReady?.invoke(page.id) }
                    }
                } catch (e: RuntimeException) {
                    // Vorschau ist nur Beiwerk; ohne Bild bleibt die Fläche leer.
                } catch (e: OutOfMemoryError) {
                    memory.evictAll()
                } finally {
                    pending.remove(k)
                }
            }
        } catch (e: RejectedExecutionException) {
            pending.remove(k)
        }
    }

    fun shutdown() {
        closed = true
        onReady = null
        worker.shutdownNow()
        memory.evictAll()
    }

    private companion object {
        const val MEMORY_BYTES = 8 * 1024 * 1024
    }
}
