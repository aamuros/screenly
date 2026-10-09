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
            "These are counts among returned nodes, not measured visual extraction coverage."
}

internal object UiExtractionDiagnostics {
    fun inspect(observation: ScreenObservation): UiExtractionReport {
        val elements = observation.elements
        val entries = elements.withIndex()
            .filter { (_, element) ->
                element.text != null || element.contentDescription != null ||
                    element.clickable || element.scrollable
            }
            .take(20)
            .map { (index, element) ->
                val name = element.text ?: element.contentDescription
                    ?: element.viewId ?: element.className ?: "Unlabeled element"
                val detail = buildString {
                    append("class: ${element.className ?: "none"}\n")
                    append("resource ID: ${element.viewId ?: "none"}\n")
                    append("text: ${element.text ?: "none"}\n")
                    append("description: ${element.contentDescription ?: "none"}\n")
                    append("clickable: ${element.clickable}; enabled: ${element.enabled}\n")
                    append("checked: ${element.checked}; scrollable: ${element.scrollable}\n")
                    append("bounds (px): (${element.left}, ${element.top}) - " +
                        "(${element.right}, ${element.bottom})")
                }
                AccessibleScreenAssistant.VisibleItem(index, name, detail)
            }
        return UiExtractionReport(
            nodeCount = elements.size,
            labeledCount = elements.count { it.text != null || it.contentDescription != null },
            enabledClickableCount = elements.count { it.enabled && it.clickable },
            labeledClickableCount = elements.count {
                it.enabled && it.clickable && (it.text != null || it.contentDescription != null)
            },
            resourceIdCount = elements.count { it.viewId != null },
            entries = entries
        )
    }
}
