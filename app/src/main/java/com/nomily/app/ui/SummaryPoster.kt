package com.nomily.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.nomily.app.R
import com.nomily.app.core.text.PosterBlock
import com.nomily.app.core.text.SummaryPosterContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/** Poster styles, mirroring iOS `PosterTemplate`. */
internal enum class PosterTemplate(val labelRes: Int, val icon: ImageVector) {
    MINIMAL(R.string.poster_template_minimal, Icons.Filled.CropSquare),
    MIDNIGHT(R.string.poster_template_midnight, Icons.Filled.DarkMode),
    BUSINESS(R.string.poster_template_business, Icons.Filled.BusinessCenter);

    /** [header] is the header band colour (business); equal to [background] elsewhere. */
    class Palette(val background: Int, val header: Int, val headerInk: Int, val ink: Int, val secondary: Int, val accent: Int, val divider: Int)

    val palette: Palette
        get() = when (this) {
            MINIMAL -> Palette(
                background = rgb(1.00, 1.00, 1.00), header = rgb(1.00, 1.00, 1.00), headerInk = rgb(0.10, 0.10, 0.11),
                ink = rgb(0.10, 0.10, 0.11), secondary = rgb(0.45, 0.45, 0.47), accent = rgb(0.85, 0.26, 0.18), divider = rgb(0.88, 0.88, 0.89),
            )
            MIDNIGHT -> Palette(
                background = rgb(0.055, 0.075, 0.10), header = rgb(0.055, 0.075, 0.10), headerInk = rgb(0.95, 0.95, 0.96),
                ink = rgb(0.92, 0.92, 0.94), secondary = rgb(0.60, 0.62, 0.66), accent = rgb(1.00, 0.45, 0.34), divider = rgb(0.22, 0.25, 0.30),
            )
            BUSINESS -> Palette(
                background = rgb(0.965, 0.960, 0.945), header = rgb(0.12, 0.23, 0.37), headerInk = android.graphics.Color.WHITE,
                ink = rgb(0.13, 0.14, 0.16), secondary = rgb(0.45, 0.46, 0.48), accent = rgb(0.12, 0.23, 0.37), divider = rgb(0.82, 0.82, 0.80),
            )
        }

    private companion object {
        fun rgb(r: Double, g: Double, b: Double) =
            android.graphics.Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }
}

/**
 * Draws a Markdown summary into a long shareable image with [android.graphics.Canvas], mirroring iOS
 * `SummaryPosterExporter` (sizes are iOS points at a 1080-px-wide canvas, scale 1).
 *
 * Layout runs once and the draw pass replays it: every block's [StaticLayout] is built a single time and
 * both the height sum and the drawing use it, so the business header band — exactly `margin + header`
 * tall — always ends where the body starts.
 */
internal object SummaryPosterRenderer {

    private const val WIDTH = 1080
    private const val MARGIN = 76f
    private const val LINE_SPACING = 1.28f
    /** Safety valve only: the poster carries the whole summary, anything taller than this is not a summary. */
    private const val BODY_BUDGET = 40_000f
    /** Bitmap budget in pixels; a taller poster is drawn smaller rather than failing to allocate. */
    private const val MAX_PIXELS = 30_000_000f
    /** Indentation per outline level. */
    private const val INDENT_STEP = 26f
    private const val FOOTER_HEIGHT = 118f

    private class Placed(val block: PosterBlock, val layout: StaticLayout, val height: Float)

    fun render(template: PosterTemplate, title: String, meta: String, markdown: String, brand: String): Bitmap? {
        val blocks = SummaryPosterContent.blocks(markdown)
        if (blocks.isEmpty()) return null
        val p = template.palette
        val contentWidth = WIDTH - MARGIN * 2

        // --- Layout -----------------------------------------------------
        val titleLayout = title.takeIf { it.isNotEmpty() }?.let { text(it, paint(52f, 700, p.headerInk), contentWidth, 2) }
        val metaLayout = meta.takeIf { it.isNotEmpty() }?.let { text(it, paint(26f, 400, p.secondary), contentWidth, 1) }
        val headingPaint = paint(34f, 600, p.ink)
        // Deeper headings step down so the outline reads as a hierarchy without boxes or colour.
        val subheadingPaint = paint(30f, 600, p.ink)
        val bodyPaint = paint(29f, 400, p.ink)
        val placed = mutableListOf<Placed>()
        var bodyHeight = 0f
        for (block in blocks) {
            val item = when (block) {
                is PosterBlock.Heading -> text(
                    block.text, if (block.level <= 0) headingPaint else subheadingPaint, contentWidth - 26 - block.level * INDENT_STEP, 3,
                ).let { Placed(block, it, it.height + if (block.level <= 0) 20f else 14f) }
                is PosterBlock.Bullet -> text(block.text, bodyPaint, contentWidth - 40 - block.level * INDENT_STEP, 6).let { Placed(block, it, it.height + 10f) }
                is PosterBlock.Paragraph -> text(block.text, bodyPaint, contentWidth, 8).let { Placed(block, it, it.height + 12f) }
            }
            placed += item
            bodyHeight += item.height
            if (bodyHeight > BODY_BUDGET) break
        }
        val dividerFill = if (template == PosterTemplate.BUSINESS) 1.5f else 7f
        var headerHeight = 0f
        titleLayout?.let { headerHeight += it.height + 18f }
        metaLayout?.let { headerHeight += it.height + 10f }
        if (headerHeight > 0) headerHeight += 14f + dividerFill + 14f
        val bodyTop = MARGIN + headerHeight
        val height = ceil(bodyTop + bodyHeight + 44f + FOOTER_HEIGHT).toInt()

        val scale = minOf(1f, sqrt(MAX_PIXELS / (WIDTH.toFloat() * height)))
        val bitmap = runCatching {
            Bitmap.createBitmap((WIDTH * scale).toInt(), ceil(height * scale).toInt(), Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // --- Background and header --------------------------------------
        canvas.drawColor(p.background)
        if (template == PosterTemplate.BUSINESS) {
            fill.color = p.header
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), bodyTop, fill)
        }
        if (template == PosterTemplate.MINIMAL) {
            // A thin accent line on top gives the plain card an anchor.
            fill.color = p.accent
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), 10f, fill)
        }
        var y = MARGIN
        titleLayout?.let { drawAt(canvas, it, MARGIN, y); y += it.height + 18f }
        metaLayout?.let { drawAt(canvas, it, MARGIN, y); y += it.height + 10f }
        if (headerHeight > 0) {
            y += 14f
            if (template == PosterTemplate.BUSINESS) {
                fill.color = p.headerInk
                fill.alpha = 64
                canvas.drawRect(MARGIN, y, WIDTH - MARGIN, y + dividerFill, fill)
                fill.alpha = 255
            } else {
                // Accent underline: short, thick, left-aligned.
                fill.color = p.accent
                canvas.drawRect(MARGIN, y, MARGIN + 92f, y + dividerFill, fill)
            }
        }

        // --- Body: starts at the band's bottom edge by construction -------
        y = bodyTop
        fill.color = p.accent
        for (item in placed) {
            when (item.block) {
                is PosterBlock.Heading -> {
                    val top = item.block.level <= 0
                    val indent = item.block.level * INDENT_STEP
                    val bar = minOf(34f, maxOf(20f, item.height - if (top) 20f else 14f))
                    canvas.drawRect(MARGIN + indent, y + 4f, MARGIN + indent + if (top) 7f else 5f, y + 4f + bar, fill)
                    drawAt(canvas, item.layout, MARGIN + indent + 26f, y)
                }
                is PosterBlock.Bullet -> {
                    val indent = item.block.level * INDENT_STEP
                    // Dot centred on the first text line.
                    val fm = bodyPaint.fontMetrics
                    val dotY = y + item.layout.getLineBaseline(0) + (fm.ascent + fm.descent) / 2f
                    canvas.drawCircle(MARGIN + indent + 2f + 5.5f, dotY, 5.5f, fill)
                    drawAt(canvas, item.layout, MARGIN + indent + 34f, y)
                }
                is PosterBlock.Paragraph -> drawAt(canvas, item.layout, MARGIN, y)
            }
            y += item.height
        }

        // --- Footer -------------------------------------------------------
        val footerY = height - FOOTER_HEIGHT - 8f
        fill.color = p.divider
        canvas.drawRect(MARGIN, footerY, WIDTH - MARGIN, footerY + 1.5f, fill)
        drawAt(canvas, text(brand, paint(23f, 500, p.secondary), contentWidth, 1), MARGIN, footerY + 30f)
        return bitmap
    }

    private fun paint(size: Float, weight: Int, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, weight, false)
        } else {
            if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /** Wrapped text, truncated with an ellipsis past [maxLines]. */
    private fun text(text: String, paint: TextPaint, width: Float, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun drawAt(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }
}

/**
 * Poster sheet, mirroring iOS `SummaryPosterView`: template chips, a live preview that re-renders on
 * change, and Copy / Share. Share goes through an `ACTION_SEND` chooser with a FileProvider URI; Copy puts
 * the same URI on the clipboard.
 */
@Composable
internal fun SummaryPosterDialog(
    markdown: String,
    title: String?,
    date: Date?,
    durationMs: Int?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var template by remember { mutableStateOf(PosterTemplate.MINIMAL) }
    var poster by remember { mutableStateOf<Bitmap?>(null) }
    var rendered by remember { mutableStateOf(false) }
    val displayTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.clip_detail_summary)
    val meta = remember(date, durationMs) { posterMeta(date, durationMs) }
    val brand = stringResource(R.string.poster_brand)
    LaunchedEffect(template, markdown) {
        poster = withContext(Dispatchers.Default) { SummaryPosterRenderer.render(template, displayTitle, meta, markdown, brand) }
        rendered = true
    }
    // A 1080 × ~9000 bitmap can exceed the GPU texture limit; the on-screen preview is a half-size copy.
    val preview = remember(poster) {
        poster?.let { Bitmap.createScaledBitmap(it, it.width / 2, maxOf(1, it.height / 2), true) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        stringResource(R.string.clip_detail_share_as_image),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosterTemplate.entries.forEach { candidate ->
                        val on = candidate == template
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { template = candidate }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            Icon(candidate.icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                            Text(
                                stringResource(candidate.labelRes),
                                color = tint,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (preview != null) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
                            Image(
                                preview.asImageBitmap(),
                                contentDescription = stringResource(R.string.clip_detail_share_as_image),
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)),
                            )
                        }
                    } else if (rendered) {
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                            Text(
                                stringResource(R.string.poster_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
                val image = poster
                if (image != null) {
                    val copied = stringResource(R.string.poster_copied)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val file = writePoster(context, image)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, displayTitle, uri))
                                    // Android 13+ shows its own clipboard confirmation.
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.poster_copy), modifier = Modifier.padding(start = 6.dp))
                        }
                        Button(onClick = { scope.launch { shareFile(context, writePoster(context, image), "image/png") } }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.poster_share), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** `date  ·  m:ss` (either part may be missing), as the iOS poster header. */
private fun posterMeta(date: Date?, durationMs: Int?): String {
    val parts = mutableListOf<String>()
    date?.let {
        parts += java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.getDefault()).format(it) + " " +
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault()).format(it)
    }
    if (durationMs != null && durationMs > 0) {
        val total = durationMs / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        parts += if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
    return parts.joinToString("  ·  ")
}

/** PNG under `cacheDir/exports` (the FileProvider-exposed directory), overwritten on each share. */
private suspend fun writePoster(context: Context, image: Bitmap): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    File(dir, "summary-poster.png").also { f -> f.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
}
