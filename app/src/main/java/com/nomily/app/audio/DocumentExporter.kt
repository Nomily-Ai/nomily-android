package com.nomily.app.audio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.nomily.app.core.text.MarkdownOutline
import com.nomily.app.core.text.MindMapLayout
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Writes the document exports (txt / md / pdf / mind-map png) into a directory; staging and the "ready to
 * share" ledger stay with [ExportCenter]. File names match iOS: `<stem>.txt|md|pdf`, `<stem>-mindmap.png`.
 */
object DocumentExporter {

    class RenderFailed(message: String) : Exception(message)

    fun writeText(dir: File, stem: String, ext: String, text: String): File =
        File(dir, "$stem.$ext").apply { writeText(text, Charsets.UTF_8) }

    // ── PDF ───────────────────────────────────────────────────────────────

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN_X = 36f
    private const val MARGIN_Y = 40f

    private class Block(val layout: StaticLayout, val spaceBefore: Float)

    private fun block(text: String, size: Float, bold: Boolean, spaceBefore: Float): Block {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            // The system default face falls back to a CJK font per glyph.
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = (PAGE_W - 2 * MARGIN_X).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        return Block(layout, spaceBefore)
    }

    /** A4 pages: title, optional summary, transcript; blocks flow line by line across as many pages as needed. */
    fun writePdf(
        dir: File, stem: String, summary: String?, transcript: String,
        summaryHeading: String, transcriptHeading: String,
    ): File {
        val blocks = buildList {
            add(block(stem, 17f, true, 0f))
            if (!summary.isNullOrEmpty()) {
                add(block(summaryHeading, 13f, true, 14f))
                add(block(summary, 11f, false, 4f))
            }
            add(block(transcriptHeading, 13f, true, 14f))
            add(block(transcript, 10.5f, false, 4f))
        }
        val doc = PdfDocument()
        try {
            var pageNo = 0
            var page: PdfDocument.Page? = null
            var canvas: Canvas? = null
            var y = MARGIN_Y
            val bottom = PAGE_H - MARGIN_Y
            fun newPage() {
                page?.let(doc::finishPage)
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNo).create())
                canvas = page!!.canvas
                y = MARGIN_Y
            }
            newPage()
            for (b in blocks) {
                y += if (y == MARGIN_Y) 0f else b.spaceBefore
                val l = b.layout
                for (i in 0 until l.lineCount) {
                    val top = l.getLineTop(i).toFloat()
                    val h = l.getLineBottom(i) - top
                    if (y + h > bottom && y > MARGIN_Y) newPage()
                    val c = canvas!!
                    c.save()
                    c.translate(MARGIN_X, y - top)
                    c.clipRect(0f, top, l.width.toFloat(), top + h)
                    l.draw(c)
                    c.restore()
                    y += h
                }
            }
            page?.let(doc::finishPage)
            return File(dir, "$stem.pdf").also { f -> f.outputStream().use(doc::writeTo) }
        } finally {
            doc.close()
        }
    }

    // ── Mind map PNG ──────────────────────────────────────────────────────

    private val BRANCHES = intArrayOf(0xFF333334.toInt(), 0xFF4A7C59.toInt(), 0xFFC66B4E.toInt(), 0xFF3A6A8A.toInt(), 0xFF8D5B9E.toInt(), 0xFFB8863A.toInt())
    private const val CANVAS = 0xFFFAFAF7.toInt()
    private const val MAX_EDGE_PX = 8192f

    private fun nodePaint(depth: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = when (depth) { 0 -> 17f; 1 -> 15f; else -> 13.5f }
        typeface = if (depth <= 1) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    private fun labelLayout(text: String, depth: Int, width: Int, maxLines: Int, color: Int = 0): StaticLayout {
        val paint = nodePaint(depth).also { it.color = color }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
    }

    /**
     * Renders the whole (unfolded) map of [summary] in the light theme at up to 2x, headlessly: same layout
     * engine as the screen, drawn straight onto a Bitmap. Returns null when the summary has no outline.
     */
    fun writeMindMap(dir: File, stem: String, summary: String): File? {
        val tree = MarkdownOutline.parse(summary) ?: return null
        val layout = MindMapLayout.build(tree, emptySet()) { title, depth, maxWidth, maxLines ->
            val l = labelLayout(title, depth, ceil(maxWidth).toInt(), maxLines)
            val w = (0 until l.lineCount).maxOfOrNull { l.getLineWidth(it) } ?: 0f
            w to l.height.toFloat()
        }
        val scale = min(2f, MAX_EDGE_PX / max(layout.width, layout.height))
        val bmp = Bitmap.createBitmap(ceil(layout.width * scale).toInt(), ceil(layout.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.drawColor(CANVAS)
            c.scale(scale, scale)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
            for (e in layout.edges) {
                val dx = max(12f, (e.toX - e.fromX) * 0.45f)
                stroke.color = withAlpha(BRANCHES[e.branchIndex % BRANCHES.size], if (e.depth <= 1) 0.75f else 0.5f)
                stroke.strokeWidth = if (e.depth <= 1) 2f else 1.4f
                c.drawPath(Path().apply {
                    moveTo(e.fromX, e.fromY)
                    cubicTo(e.fromX + dx, e.fromY, e.toX - dx, e.toY, e.toX, e.toY)
                }, stroke)
            }
            for (item in layout.items) {
                val colour = BRANCHES[item.branchIndex % BRANCHES.size]
                val rect = RectF(item.x, item.y, item.x + item.width, item.y + item.height)
                val r = when (item.depth) { 0 -> 10f; 1 -> 9f; else -> 7f }
                fill.style = Paint.Style.FILL
                fill.color = when (item.depth) {
                    0 -> 0xFF333333.toInt()
                    1 -> blend(withAlpha(colour, 0.13f), CANVAS)
                    else -> 0xFFFFFFFF.toInt()
                }
                c.drawRoundRect(rect, r, r, fill)
                if (item.depth == 1) {
                    stroke.color = withAlpha(colour, 0.45f); stroke.strokeWidth = 1f
                    c.drawRoundRect(rect, r, r, stroke)
                }
                if (item.depth >= 2) {
                    fill.color = withAlpha(colour, 0.8f)
                    val h = max(6f, item.height - 10f)
                    c.drawRoundRect(RectF(item.x + 4f, item.y + (item.height - h) / 2, item.x + 7f, item.y + (item.height + h) / 2), 1.5f, 1.5f, fill)
                }
                val text = if (item.title.isEmpty() && item.depth == 0) stem else item.title
                val textColor = when (item.depth) { 0 -> 0xFFFFFFFF.toInt(); 1 -> colour; else -> 0xFF1C1B1F.toInt() }
                val padH = MindMapLayout.horizontalPadding(item.depth)
                val badge = if (item.childCount > 0) MindMapLayout.FOLDED_BADGE_RESERVE else 0f
                val l = labelLayout(text, item.depth, (item.width - padH * 2 - badge).toInt(), MindMapLayout.maxLines(item.depth), textColor)
                c.save()
                c.translate(item.x + padH, item.y + MindMapLayout.verticalPadding(item.depth))
                l.draw(c)
                c.restore()
            }
            return File(dir, "$stem-mindmap.png").also { f ->
                f.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) throw RenderFailed("png") }
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)

    /** [top] (translucent) flattened onto an opaque [bottom]. */
    private fun blend(top: Int, bottom: Int): Int {
        val a = (top ushr 24) / 255f
        fun ch(shift: Int) = (((top shr shift) and 0xFF) * a + ((bottom shr shift) and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
