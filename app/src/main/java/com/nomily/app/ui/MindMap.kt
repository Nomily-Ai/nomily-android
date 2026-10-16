package com.nomily.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.nomily.app.R
import com.nomily.app.core.text.MarkdownOutline
import com.nomily.app.core.text.MindMapLayout
import com.nomily.app.core.text.OutlineNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Icon for "open the mind map", shared by the clip detail and the full summary view. */
internal val MindMapIcon = Icons.Filled.AccountTree

/**
 * Branch colours, ported from Vobot Recorder's mind-map palette so a map reads the same in both apps.
 * Tuned for a near-white canvas; in dark mode each channel is lifted toward white to keep contrast.
 */
private val branchPalette = listOf(
    Color(0.20f, 0.20f, 0.20f), // #333334 — root
    Color(0.29f, 0.49f, 0.35f), // #4a7c59
    Color(0.78f, 0.42f, 0.31f), // #c66b4e
    Color(0.23f, 0.42f, 0.54f), // #3a6a8a
    Color(0.55f, 0.36f, 0.62f), // #8d5b9e
    Color(0.72f, 0.53f, 0.23f), // #b8863a
)

private fun branchColor(index: Int, dark: Boolean): Color {
    val c = branchPalette[index % branchPalette.size]
    if (!dark) return c
    val lift = 0.38f
    return Color(c.red + (1 - c.red) * lift, c.green + (1 - c.green) * lift, c.blue + (1 - c.blue) * lift)
}

private fun canvasFill(dark: Boolean) = if (dark) Color(0.07f, 0.07f, 0.07f) else Color(0.980f, 0.980f, 0.969f)

private fun nodeTextStyle(depth: Int) = when (depth) {
    0 -> TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    1 -> TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    else -> TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal)
}

private const val MIN_SCALE = 0.25f
private const val MAX_SCALE = 3f

/**
 * Full-screen mind map for one summary, with pan, pinch-zoom, per-branch folding, a branch-level preset
 * and PNG export. Export renders the map as it currently stands: folding is how the user decides what
 * the picture contains.
 *
 * @param fallbackTitle Root label when the summary has no single top-level heading of its own.
 */
@Composable
fun MindMapScreen(markdown: String, fallbackTitle: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    // The summary never changes while the screen is open, so parse once.
    val tree = remember(markdown) { MarkdownOutline.parse(markdown) }
    val measure = remember(measurer, density) {
        MindMapLayout.Measure { title, depth, maxWidth, maxLines ->
            val r = measurer.measure(
                title,
                nodeTextStyle(depth),
                overflow = TextOverflow.Ellipsis,
                maxLines = maxLines,
                constraints = Constraints(maxWidth = with(density) { maxWidth.dp.roundToPx() }),
            )
            with(density) { r.size.width.toDp().value to r.size.height.toDp().value }
        }
    }
    // The opening level is the "Branches" menu's selection; folding a branch by hand does not move it.
    var expandLevel by remember { mutableStateOf((tree?.maxDepth ?: 1).coerceIn(1, 3)) }
    var collapsed by remember { mutableStateOf(tree?.let { MindMapLayout.foldPoints(it, expandLevel) } ?: emptySet()) }
    // Cached: laying the map out measures every label, which must not run on each pinch frame.
    val layout = remember(tree, collapsed, measure) { tree?.let { MindMapLayout.build(it, collapsed, measure) } }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var levelMenu by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val exportLayer = rememberGraphicsLayer()
    val pad = with(density) { 4.dp.toPx() }

    fun clampOffset(o: Offset, s: Float): Offset {
        val l = layout ?: return o
        val cw = with(density) { l.width.dp.toPx() } * s + pad * 2
        val ch = with(density) { l.height.dp.toPx() } * s + pad * 2
        return Offset(
            o.x.coerceIn(minOf(0f, viewport.width - cw), 0f),
            o.y.coerceIn(minOf(0f, viewport.height - ch), 0f),
        )
    }

    fun setZoom(target: Float, pivot: Offset = Offset.Zero) {
        val next = target.coerceIn(MIN_SCALE, MAX_SCALE)
        offset = clampOffset(pivot - (pivot - offset) * (next / scale), next)
        scale = next
    }

    fun fit() {
        val l = layout ?: return
        if (viewport.width == 0) return
        val vw = with(density) { viewport.width.toDp().value }
        val vh = with(density) { viewport.height.toDp().value }
        // Never shrink past the point where the 13.5 sp labels stop being readable; below that, scrolling beats squinting.
        setZoom(minOf((vw - 16) / l.width, (vh - 60) / l.height, 1f).coerceIn(0.42f, 1f))
    }

    fun exportPng() {
        if (layout == null) return
        scope.launch {
            runCatching {
                val image = exportLayer.toImageBitmap().asAndroidBitmap()
                val stem = fallbackTitle.ifEmpty { "mindmap" }.replace(Regex("""[/\\:?%*|"<>]"""), "_")
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(dir, "$stem.png").also { f -> f.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                }
                shareFile(context, file, "image/png")
            }.onFailure { exportError = it.message ?: context.getString(R.string.clip_detail_mind_map_export_failed) }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                // Navigation bar: Close · title · Branches / expand-collapse / share.
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                        Text(stringResource(R.string.common_close))
                    }
                    Text(
                        stringResource(R.string.clip_detail_mind_map),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                        if (tree != null) {
                            Box {
                                IconButton(onClick = { levelMenu = true }, enabled = tree.maxDepth > 1) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.FormatIndentIncrease,
                                        contentDescription = stringResource(R.string.clip_detail_mind_map_level),
                                    )
                                }
                                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                                    (1..maxOf(1, tree.maxDepth)).forEach { level ->
                                        DropdownMenuItem(
                                            text = { Text("$level") },
                                            leadingIcon = {
                                                if (level == expandLevel) Icon(Icons.Filled.Check, contentDescription = null)
                                            },
                                            onClick = {
                                                levelMenu = false
                                                expandLevel = level
                                                collapsed = MindMapLayout.foldPoints(tree, level)
                                                // A user who zoomed out to read a deep map should not stay zoomed out once it collapses back.
                                                scale = 1f
                                                offset = Offset.Zero
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (tree != null && tree.maxDepth > 1) {
                            // Nothing folded means the whole tree is on screen, so the button folds back to one tier; otherwise it opens everything.
                            IconButton(onClick = {
                                collapsed = if (collapsed.isEmpty()) MindMapLayout.foldPoints(tree, 1) else emptySet()
                                offset = clampOffset(offset, scale)
                            }) {
                                Icon(
                                    if (collapsed.isEmpty()) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                                    contentDescription = stringResource(
                                        if (collapsed.isEmpty()) R.string.clip_detail_mind_map_collapse_all else R.string.clip_detail_mind_map_expand_all,
                                    ),
                                )
                            }
                        }
                        IconButton(onClick = ::exportPng, enabled = layout != null) {
                            Icon(Icons.Filled.IosShare, contentDescription = null)
                        }
                    }
                }

                if (layout == null) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 40.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(MindMapIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
                        Text(
                            stringResource(R.string.clip_detail_mind_map_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(canvasFill(dark))
                            .onSizeChanged { viewport = it }
                            .pointerInput(layout) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val next = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                    offset = clampOffset(centroid - (centroid - offset) * (next / scale) + pan, next)
                                    scale = next
                                }
                            },
                    ) {
                        val widthDp = layout.width.dp
                        val heightDp = layout.height.dp
                        MindMapCanvas(
                            layout = layout,
                            dark = dark,
                            rootLabel = fallbackTitle,
                            onToggle = { id -> collapsed = if (id in collapsed) collapsed - id else collapsed + id },
                            modifier = Modifier
                                .graphicsLayer {
                                    translationX = offset.x + pad
                                    translationY = offset.y + pad
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                                .wrapContentSize(Alignment.TopStart, unbounded = true)
                                .requiredSize(widthDp, heightDp)
                                .drawWithContent {
                                    // Recorded unscaled, with a 20 dp frame, so the export is the map as laid out
                                    // rather than the viewport. The pixel budget keeps a deep map from failing to allocate.
                                    // shortcut: re-records on every frame, doubling draw cost; record only on export if pans stutter.
                                    val frame = 20.dp.toPx()
                                    val w = size.width + frame * 2
                                    val h = size.height + frame * 2
                                    val shrink = minOf(1f, sqrt(16_000_000f / (w * h)))
                                    exportLayer.record(IntSize((w * shrink).roundToInt(), (h * shrink).roundToInt())) {
                                        scale(shrink, pivot = Offset.Zero) {
                                            drawRect(canvasFill(dark), size = androidx.compose.ui.geometry.Size(w, h))
                                            translate(frame, frame) { this@drawWithContent.drawContent() }
                                        }
                                    }
                                    drawContent()
                                },
                        )
                        ZoomBar(
                            scale = scale,
                            onZoomOut = { setZoom(scale - 0.25f) },
                            onZoomIn = { setZoom(scale + 0.25f) },
                            onFit = ::fit,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                        )
                    }
                }
            }
        }
    }

    exportError?.let { msg ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text(stringResource(R.string.clip_detail_mind_map_export_failed)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

/** Floating zoom / fit controls over the map's bottom edge, so the top bar stays free for the level picker and export. */
@Composable
private fun ZoomBar(scale: Float, onZoomOut: () -> Unit, onZoomIn: () -> Unit, onFit: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 6.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onZoomOut, enabled = scale > MIN_SCALE + 0.01f) { Icon(Icons.Filled.ZoomOut, contentDescription = null) }
            Text(
                "${(scale * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 42.dp),
            )
            IconButton(onClick = onZoomIn, enabled = scale < MAX_SCALE) { Icon(Icons.Filled.ZoomIn, contentDescription = null) }
            VerticalDivider(Modifier.height(18.dp))
            TextButton(onClick = onFit) {
                Icon(Icons.Filled.OpenInFull, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.clip_detail_mind_map_fit), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** Draws a laid-out map at 1:1 — edges first, nodes on top. */
@Composable
private fun MindMapCanvas(
    layout: MindMapLayout,
    dark: Boolean,
    rootLabel: String,
    onToggle: (Int) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(canvasFill(dark))) {
        Canvas(Modifier.fillMaxSize()) {
            layout.edges.forEach { e ->
                val from = Offset(e.fromX.dp.toPx(), e.fromY.dp.toPx())
                val to = Offset(e.toX.dp.toPx(), e.toY.dp.toPx())
                val dx = maxOf(12.dp.toPx(), (to.x - from.x) * 0.45f)
                val path = Path().apply {
                    moveTo(from.x, from.y)
                    cubicTo(from.x + dx, from.y, to.x - dx, to.y, to.x, to.y)
                }
                drawPath(
                    path,
                    branchColor(e.branchIndex, dark).copy(alpha = if (e.depth <= 1) 0.75f else 0.5f),
                    style = Stroke(width = (if (e.depth <= 1) 2f else 1.4f).dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        layout.items.forEach { item ->
            MindMapNode(item, branchColor(item.branchIndex, dark), dark, rootLabel) { onToggle(item.id) }
        }
    }
}

@Composable
private fun MindMapNode(item: MindMapLayout.Item, colour: Color, dark: Boolean, rootLabel: String, onTap: () -> Unit) {
    val isRoot = item.depth == 0
    val isSection = item.depth == 1
    // Only a node with something behind it is a control.
    val tappable = item.childCount > 0
    val text = if (item.title.isEmpty() && isRoot && rootLabel.isNotEmpty()) rootLabel else item.title
    val shape = RoundedCornerShape(if (isRoot) 10.dp else if (isSection) 9.dp else 7.dp)
    // Translucent tints are flattened onto the canvas colour: a translucent fill lets the node's own shadow show through.
    val fill = when {
        isRoot -> if (dark) Color(0.92f, 0.92f, 0.92f) else Color(0.20f, 0.20f, 0.20f)
        isSection -> colour.copy(alpha = if (dark) 0.20f else 0.13f).compositeOver(canvasFill(dark))
        else -> if (dark) Color.White.copy(alpha = 0.07f).compositeOver(canvasFill(dark)) else Color.White
    }
    val textColour = when {
        isRoot -> if (dark) Color(0.1f, 0.1f, 0.1f) else Color.White
        isSection -> colour
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .offset { IntOffset(item.x.dp.roundToPx(), item.y.dp.roundToPx()) }
            .size(item.width.dp, item.height.dp)
            .shadow(if (dark) 0.dp else if (isRoot) 3.dp else 1.dp, shape)
            .clip(shape)
            .background(fill)
            .then(if (isSection) Modifier.border(1.dp, colour.copy(alpha = 0.45f), shape) else Modifier)
            // The whole box is the target, not just the glyphs.
            .then(if (tappable) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        // Deep leaves get a slim colour tick instead of an outline, which keeps dense areas from turning into a grid of boxes.
        if (item.depth >= 2) {
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(maxOf(6f, item.height - 10f).dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(colour.copy(alpha = 0.8f)),
            )
        }
        Row(
            Modifier
                .fillMaxHeight()
                .padding(
                    horizontal = MindMapLayout.horizontalPadding(item.depth).dp,
                    vertical = MindMapLayout.verticalPadding(item.depth).dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text,
                style = nodeTextStyle(item.depth),
                color = textColour,
                maxLines = MindMapLayout.maxLines(item.depth),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (tappable) {
                // Sits on the first text line; the count shows only while folded.
                Row(Modifier.padding(top = if (isRoot) 5.dp else 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tint = if (isRoot || isSection) textColour.copy(alpha = 0.6f) else colour.copy(alpha = 0.85f)
                    Icon(
                        if (item.isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(11.dp),
                    )
                    if (!item.isExpanded) {
                        Text("${item.childCount}", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = tint)
                    }
                }
            }
        }
    }
}

/** Hands [file] (under `cacheDir/exports`) to the system share sheet. */
internal fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** Hands plain [text] to the system share sheet. */
internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}
