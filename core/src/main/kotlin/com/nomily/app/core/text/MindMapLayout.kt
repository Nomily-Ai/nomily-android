package com.nomily.app.core.text

/**
 * Pure geometry for a left-to-right mind map, mirroring iOS `MindMapLayout`. Units are dp.
 *
 * The engine only measures text (through [Measure]) and produces rectangles, which keeps it cheap to
 * re-run whenever a branch is folded and makes the layout testable on its own.
 */
class MindMapLayout private constructor(
    val items: List<Item>,
    val edges: List<Edge>,
    val width: Float,
    val height: Float,
) {
    class Item(
        val id: Int,
        val parentId: Int?,
        val depth: Int,
        val title: String,
        val kind: OutlineNode.Kind,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        /** Which top-level branch this node belongs to — the colour index. */
        val branchIndex: Int,
        /** Direct children in the full tree, drawn or not. Non-zero makes the node tappable and is the folded badge count. */
        val childCount: Int,
        /** Whether those children are currently drawn. */
        val isExpanded: Boolean,
    )

    class Edge(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val branchIndex: Int,
        val depth: Int,
    )

    /**
     * Measures [title] laid out at depth [depth] within [maxWidth], capped at [maxLines] lines, and
     * returns its (width, height). Must use the same font the node is drawn with.
     */
    fun interface Measure {
        fun measure(title: String, depth: Int, maxWidth: Float, maxLines: Int): Pair<Float, Float>
    }

    companion object {
        private const val MARGIN = 24f
        private const val HORIZONTAL_GAP = 34f
        private const val VERTICAL_GAP = 9f
        private const val DEEP_VERTICAL_GAP = 6f

        /**
         * Width held back beside a label that has children, for the fold chevron and its count. The box
         * reserves the wider of the badge's two states so it never resizes while branches open and close.
         */
        const val FOLDED_BADGE_RESERVE = 26f

        fun maxLines(depth: Int): Int = when (depth) {
            0 -> 3
            1 -> 4
            else -> 6
        }

        fun horizontalPadding(depth: Int): Float = if (depth == 0) 14f else 11f
        fun verticalPadding(depth: Int): Float = if (depth == 0) 9f else 7f

        private fun maxNodeWidth(depth: Int): Float = when (depth) {
            0 -> 210f
            1 -> 200f
            else -> 190f
        }

        private fun nodeSize(title: String, depth: Int, hasChildren: Boolean, measure: Measure): Pair<Float, Float> {
            val padH = horizontalPadding(depth)
            val padV = verticalPadding(depth)
            val badge = if (hasChildren) FOLDED_BADGE_RESERVE else 0f
            val textCap = maxNodeWidth(depth) - padH * 2
            val (w, h) = measure.measure(title, depth, textCap, maxLines(depth))
            // Two dp of slack keeps a box measured at the tight width from wrapping a glyph onto a new line.
            val width = minOf(textCap, w + 2f) + badge + padH * 2
            val height = maxOf(30f, h + 2f + padV * 2)
            // A two-character label would otherwise collapse into a dot.
            return maxOf(56f, width) to height
        }

        /**
         * Builds the map, drawing every branch not in [collapsed]. A collapsed node is still drawn; its
         * children are not. An opening level is just an initial [collapsed] set (see [foldPoints]).
         */
        fun build(tree: OutlineNode, collapsed: Set<Int>, measure: Measure): MindMapLayout {
            class Visible(val node: OutlineNode, val parentId: Int?, val branch: Int, val isExpanded: Boolean)

            val visible = mutableListOf<Visible>()
            fun walk(node: OutlineNode, parentId: Int?, branch: Int) {
                val expanded = node.id !in collapsed
                visible += Visible(node, parentId, branch, expanded)
                if (!expanded) return
                node.children.forEachIndexed { i, child -> walk(child, node.id, if (node.depth == 0) i else branch) }
            }
            walk(tree, null, 0)

            val sizes = HashMap<Int, Pair<Float, Float>>()
            val childrenOf = HashMap<Int, MutableList<Int>>()
            val byId = HashMap<Int, Visible>()
            for (v in visible) {
                sizes[v.node.id] = nodeSize(v.node.title, v.node.depth, v.node.children.isNotEmpty(), measure)
                byId[v.node.id] = v
                v.parentId?.let { childrenOf.getOrPut(it) { mutableListOf() } += v.node.id }
            }

            // Every node at a given depth shares an x; the column is as wide as its widest member.
            val deepest = visible.maxOf { it.node.depth }
            val columnWidth = FloatArray(deepest + 1)
            for (v in visible) columnWidth[v.node.depth] = maxOf(columnWidth[v.node.depth], sizes.getValue(v.node.id).first)
            val columnX = FloatArray(deepest + 1) { MARGIN }
            for (d in 1..deepest) columnX[d] = columnX[d - 1] + columnWidth[d - 1] + HORIZONTAL_GAP

            fun gapBelow(id: Int) = if (byId.getValue(id).node.depth >= 1) DEEP_VERTICAL_GAP else VERTICAL_GAP

            // A subtree is as tall as the sum of its children; the parent is centred against that span.
            val subtreeHeight = HashMap<Int, Float>()
            fun measureHeight(id: Int): Float {
                val kids = childrenOf[id].orEmpty()
                val own = sizes.getValue(id).second
                val height = if (kids.isEmpty()) {
                    own
                } else {
                    maxOf(own, kids.sumOf { measureHeight(it).toDouble() }.toFloat() + gapBelow(id) * (kids.size - 1))
                }
                subtreeHeight[id] = height
                return height
            }
            val rootHeight = measureHeight(tree.id)

            val frames = HashMap<Int, FloatArray>()
            fun place(id: Int, top: Float) {
                val (w, h) = sizes.getValue(id)
                val total = subtreeHeight.getValue(id)
                frames[id] = floatArrayOf(columnX[byId.getValue(id).node.depth], top + (total - h) / 2, w, h)
                val kids = childrenOf[id].orEmpty()
                if (kids.isEmpty()) return
                val gap = gapBelow(id)
                val childrenTotal = kids.sumOf { subtreeHeight.getValue(it).toDouble() }.toFloat() + gap * (kids.size - 1)
                var cursor = top + (total - childrenTotal) / 2
                for (child in kids) {
                    place(child, cursor)
                    cursor += subtreeHeight.getValue(child) + gap
                }
            }
            place(tree.id, MARGIN)

            val items = mutableListOf<Item>()
            val edges = mutableListOf<Edge>()
            for (v in visible) {
                val f = frames.getValue(v.node.id)
                items += Item(
                    v.node.id, v.parentId, v.node.depth, v.node.title, v.node.kind,
                    f[0], f[1], f[2], f[3], v.branch, v.node.children.size, v.isExpanded,
                )
                val p = v.parentId?.let { frames[it] } ?: continue
                edges += Edge(p[0] + p[2], p[1] + p[3] / 2, f[0], f[1] + f[3] / 2, v.branch, v.node.depth)
            }
            val width = columnX[deepest] + columnWidth[deepest] + MARGIN
            return MindMapLayout(items, edges, kotlin.math.ceil(width), kotlin.math.ceil(rootHeight + MARGIN * 2))
        }

        /**
         * The ids to fold so that only the first [levels] tiers are open. Nodes shallower than the cut are
         * left open; deeper ones are folded too, so a single tap opens exactly one more tier.
         */
        fun foldPoints(tree: OutlineNode, levels: Int): Set<Int> {
            val folded = HashSet<Int>()
            fun walk(node: OutlineNode) {
                if (node.depth >= levels && node.children.isNotEmpty()) folded += node.id
                node.children.forEach(::walk)
            }
            walk(tree)
            return folded
        }
    }
}
