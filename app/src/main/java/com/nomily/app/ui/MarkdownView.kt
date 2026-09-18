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
 * Markdown rendering for the summary.
 *
 * `marked.min.js` is bundled into `assets/`, inlined into the HTML to run, **with no network requests sent**
 * (works offline, and also complies with the "data never leaves the device" principle; pulling a copy from
 * the CDN would amount to sending a request just for a purely local feature).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val js = remember(context) {
        runCatching { context.assets.open("marked.min.js").bufferedReader().use { it.readText() } }
            .getOrDefault("")
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
            web.loadDataWithBaseURL(null, wrapHtml(markdown, js), "text/html", "utf-8", null)
        },
    )
}

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

private fun wrapHtml(md: String, markedJs: String): String {
    val escaped = normalizeHeadings(md)
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("$", "\\$")
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <script>$markedJs</script>
        <style>
          :root { color-scheme: light dark; }
          body {
            font-family: -apple-system, BlinkMacSystemFont, sans-serif;
            font-size: 16px;
            line-height: 1.5;
            padding: 0 16px;
            margin: 0;
            color: var(--text);
            background: transparent;
          }
          @media (prefers-color-scheme: dark) {
            :root { --text: #e5e5e7; --code-bg: #2c2c2e; --border: #3a3a3c; }
          }
          @media (prefers-color-scheme: light) {
            :root { --text: #1c1c1e; --code-bg: #f2f2f7; --border: #d1d1d6; }
          }
          h1 { font-size: 1.7em; font-weight: 700; margin: 0.6em 0 0.35em; }
          h2 { font-size: 1.35em; font-weight: 700; margin: 0.5em 0 0.3em; }
          h3 { font-size: 1.12em; font-weight: 600; margin: 0.4em 0 0.2em; }
          p { margin: 0.4em 0; }
          ul, ol { padding-left: 1.4em; margin: 0.3em 0; }
          li { margin: 0.15em 0; }
          code {
            font-family: Menlo, monospace;
            font-size: 0.88em;
            background: var(--code-bg);
            padding: 1px 4px;
            border-radius: 3px;
          }
          pre {
            background: var(--code-bg);
            padding: 8px 10px;
            border-radius: 6px;
            overflow-x: auto;
          }
          pre code { background: none; padding: 0; }
          table {
            border-collapse: collapse;
            width: 100%;
            margin: 0.5em 0;
            font-size: 0.92em;
          }
          th, td {
            border: 1px solid var(--border);
            padding: 4px 8px;
            text-align: left;
          }
          th { font-weight: 600; }
          blockquote {
            border-left: 3px solid var(--border);
            margin: 0.4em 0;
            padding: 0.2em 0.8em;
            color: #888;
          }
          hr { border: none; border-top: 1px solid var(--border); margin: 0.8em 0; }
          strong { font-weight: 600; }
        </style>
        </head>
        <body>
        <div id="content"></div>
        <script>
          document.getElementById('content').innerHTML = marked.parse(`$escaped`);
        </script>
        </body>
        </html>
    """.trimIndent()
}
