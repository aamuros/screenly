package com.screenly.app

/**
 * Conservative OCR labeling of ACTUAL accessibility targets. A detected word
 * with no unique click target is informational only. Preserve original indices,
 * bounds and switch states; never create synthetic clickable controls.
 */
internal object ScreenEvidenceMerger {
    fun augment(observation: ScreenObservation, lines: List<OcrTextLine>): ScreenObservation {
        if (lines.isEmpty()) return observation
        val nodes = observation.elements
        val alreadyNamed = ScreenControlCatalog.controls(observation).map { it.index }.toSet()
        val width = nodes.maxOfOrNull { it.right }?.coerceAtLeast(1) ?: 1
        val height = nodes.maxOfOrNull { it.bottom }?.coerceAtLeast(1) ?: 1
        val maxArea = width.toLong() * height / 3L
        val eligible = nodes.indices.filter { i ->
            val n = nodes[i]
            val area = (n.right - n.left).toLong() * (n.bottom - n.top)
            i !in alreadyNamed && n.clickable && n.enabled &&
                n.right > n.left && n.bottom > n.top && area in 1..maxArea
        }
        val byTarget = mutableMapOf<Int, MutableList<String>>()
        for (line in lines.take(40)) {
            if (line.right <= line.left || line.bottom <= line.top) continue
            val cx = line.left + (line.right - line.left) / 2
            val cy = line.top + (line.bottom - line.top) / 2
            val matches = eligible.filter { i ->
                val n = nodes[i]
                cx in n.left until n.right && cy in n.top until n.bottom
            }
            // Nested/overlapping targets are deliberately ambiguous.
            if (matches.size == 1) {
                byTarget.getOrPut(matches.single()) { mutableListOf() }.add(line.text)
            }
        }
        if (byTarget.isEmpty()) return observation
        val revised = nodes.mapIndexed { i, node ->
            val newLabel = byTarget[i]?.take(2)?.joinToString(" ")
                ?.let(::sanitizeObservationText)
            if (newLabel != null && node.text == null && node.contentDescription == null)
                node.copy(text = newLabel)
            else node
        }
        return observation.copy(elements = revised)
    }
}
