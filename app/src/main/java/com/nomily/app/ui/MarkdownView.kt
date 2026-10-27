package com.nomily.app.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Markdown rendering for the summary, with the shared summary stylesheet and enhancement pass
 * (`summary.css` / `summary.js`, the same files the iOS app ships).
 *
 * `marked.min.js` and both summary files are bundled into `assets/` and inlined into the HTML, **with no network
 * requests sent** (works offline, and also complies with the "data never leaves the device" principle; pulling a
 * copy from the CDN would amount to sending a request just for a purely local feature).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val assets = remember(context) {
        fun read(name: String) =
            runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
        SummaryAssets(read("marked.min.js"), read("summary.js"), read("summary.css"))
    }
    AndroidView(
        modifier = modifier,
        factory = {
            WebView(it).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
            }
        },
        update = { web ->
            // Reloading identical HTML throws away the reader's scroll position, and recomposition calls this for unrelated state changes.
            if (web.tag != markdown) {
                web.tag = markdown
                web.loadDataWithBaseURL(null, wrapHtml(markdown, assets), "text/html", "utf-8", null)
            }
        },
    )
}

private class SummaryAssets(val marked: String, val summaryJs: String, val summaryCss: String)

/**
 * `title` — add the space that CommonMark requires.
 *
 * Not just pedantry: when LLMs output Chinese they often write `title` (with no space),
 * and marked treats it as plain text, so level-1 headings never render (bug y2mnfqy).
 */
internal fun normalizeHeadings(md: String): String =
    md.split("\n").joinToString("\n") { line ->
        val m = Regex("^\\s{0,3}#{1,6}").find(line) ?: return@joinToString line
        val after = line.substring(m.range.last + 1)
        if (after.isEmpty() || after.first() == ' ') line else line.substring(0, m.range.last + 1) + " " + after
    }

/**
 * Escapes the source so it can sit inside a JavaScript template literal: `\\`, backtick and `$` would end the literal,
 * and `</` because the HTML tokenizer ends a `<script>` element on the text `</script` regardless of JS context.
 */
internal fun escapeForScript(md: String): String =
    md.replace("\\", "\\\\")
        .replace("</", "<\\/")
        .replace("`", "\\`")
        .replace("$", "\\$")

private fun wrapHtml(md: String, a: SummaryAssets): String {
    val escaped = escapeForScript(normalizeHeadings(md))
    // Not trimIndent(): the inlined assets have their own indentation, and the template literal must stay byte-exact.
    return """<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>${a.summaryCss}</style>
<script>${a.marked}</script>
<script>${a.summaryJs}</script>
</head>
<body>
<div id="content"></div>
<script>
  (function () {
    var source = `$escaped`;
    var html;
    try {
      html = (window.marked && marked.parse) ? marked.parse(source) : null;
    } catch (e) {
      html = null;
    }
    if (html === null || html === undefined) {
      var pre = document.createElement('pre');
      pre.textContent = source;
      html = pre.outerHTML;
    }
    document.getElementById('content').innerHTML = html;
    if (window.__nomilyEnhanceSummary) { window.__nomilyEnhanceSummary(); }
  })();
</script>
</body>
</html>
"""
}
