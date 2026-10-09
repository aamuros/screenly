package com.screenly.app

/** UI-extraction diagnostics for a non-sensitive, explicitly requested Settings test. */
internal data class UiExtractionReport(
    val nodeCount: Int,
    val labeledCount: Int,
    val enabledClickableCount: Int,
    val labeledClickableCount: Int,
    val resourceIdCount: Int,
    val entries: List<AccessibleScreenAssistant.VisibleItem>
) {
    val summary: String get() =
        "Android Settings accessibility metadata (no screenshot or AI).\n" +
            "Nodes: $nodeCount  |  Labeled: $labeledCount\n" +
            "Enabled clickable: $enabledClickableCount  |  Self-labeled: $labeledClickableCount\n" +
            "Resource IDs: $resourceIdCount\n" +
            "Listed rows combine a clickable parent with its child labels. " +
            "Raw node counts are not measured visual extraction coverage."
}

/**
 * The accessibility tree describes individual views, not complete rows.
 * A TextView containing "Network & internet" is often not clickable, but its
 * ancestor LinearLayout is. Keep the original nodes and indices; group only
 * the human-facing diagnostic view, and never invent a tap target.
 */
internal object UiExtractionDiagnostics {
    fun inspect(observation: ScreenObservation): UiExtractionReport {
        val elements = observation.elements
        val entries = elements.indices.mapNotNull { index ->
            val element = elements[index]
            when {
                element.clickable && hasBounds(element) ->
                    actionableRow(elements, index)
                element.clickable -> null
                labelOf(element) != null && hasBounds(element) &&
                    nearestClickableAncestor(elements, index) == null ->
                    AccessibleScreenAssistant.VisibleItem(
                        index,
                        labelOf(element)!!,
                        "label only (not clickable)\n" +
                            "class: ${element.className ?: "none"}\n" +
                            "resource ID: ${element.viewId ?: "none"}\n" +
                            "bounds (px): ${bounds(element)}"
                    )
                else -> null
            }
        }.take(20)

        return UiExtractionReport(
            nodeCount = elements.size,
            labeledCount = elements.count { labelOf(it) != null },
            enabledClickableCount = elements.count { it.enabled && it.clickable },
            labeledClickableCount = elements.count {
                it.enabled && it.clickable && labelOf(it) != null
            },
            resourceIdCount = elements.count { it.viewId != null },
            entries = entries
        )
    }

    private fun actionableRow(
        elements: List<AccessibleUiElement>,
        rowIndex: Int
    ): AccessibleScreenAssistant.VisibleItem? {
        val row = elements[rowIndex]
        // Only inherit labels whose closest clickable ancestor is this row.
        // A nested switch belongs to itself rather than to a containing row.
        val labeledChildren = elements.indices.filter { index ->
            labelOf(elements[index]) != null &&
                nearestClickableAncestor(elements, index) == rowIndex
        }
        val titleIndex = labeledChildren.firstOrNull { isId(elements[it], "title") }
            ?: labeledChildren.firstOrNull { !isId(elements[it], "summary") }
        val title = labelOf(row) ?: titleIndex?.let { labelOf(elements[it]) }
            ?: return null
        val summaryIndex = labeledChildren.firstOrNull {
            isId(elements[it], "summary") && labelOf(elements[it]) != title
        } ?: labeledChildren.firstOrNull {
            it != titleIndex && labelOf(elements[it]) != title
        }
        val summary = summaryIndex?.let { labelOf(elements[it]) }
        val labelOrigin = if (labelOf(row) != null) rowIndex else titleIndex
        val originElement = labelOrigin?.let(elements::get)
        val detail = buildString {
            append("actionable row: ${row.enabled && row.clickable}\n")
            append("clickable: ${row.clickable}; enabled: ${row.enabled}\n")
            append("class: ${row.className ?: "none"}\n")
            append("resource ID: ${row.viewId ?: "none"}\n")
            if (summary != null) append("summary: $summary\n")
            if (originElement != null && labelOrigin != rowIndex) {
                append("label node: #$labelOrigin (${originElement.className ?: "unknown"}), " +
                    "clickable: ${originElement.clickable}\n")
                append("label bounds (px): ${bounds(originElement)}\n")
            }
            append("tap target bounds (px): ${bounds(row)}")
        }
        return AccessibleScreenAssistant.VisibleItem(rowIndex, title, detail)
    }

    private fun isId(element: AccessibleUiElement, suffix: String): Boolean =
        element.viewId?.endsWith("/$suffix") == true

    private fun labelOf(element: AccessibleUiElement): String? =
        (element.text ?: element.contentDescription)?.takeIf { it.isNotBlank() }

    private fun hasBounds(element: AccessibleUiElement): Boolean =
        element.right > element.left && element.bottom > element.top

    private fun bounds(element: AccessibleUiElement): String =
        "(${element.left}, ${element.top}) - (${element.right}, ${element.bottom})"

    private fun nearestClickableAncestor(
        elements: List<AccessibleUiElement>, index: Int
    ): Int? {
        var parent = elements[index].parentIndex
        var depth = 0
        // Defensive bound and cycle guard for malformed synthetic observations.
        while (parent != null && depth++ < 40) {
            if (parent !in elements.indices || parent >= index) return null
            if (elements[parent].clickable) return parent
            parent = elements[parent].parentIndex
        }
        return null
    }
}
