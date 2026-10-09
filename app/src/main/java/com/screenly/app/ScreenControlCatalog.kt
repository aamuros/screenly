package com.screenly.app

/** Stable, actionable view of a raw accessibility tree. Original node IDs and bounds survive. */
internal data class ScreenControl(
    val index: Int,
    val label: String,
    val subtitle: String?,
    val type: Type,
    val checked: Boolean,
    val element: AccessibleUiElement
) {
    enum class Type { ROW, TOGGLE }
}

internal object ScreenControlCatalog {
    fun controls(observation: ScreenObservation): List<ScreenControl> {
        val nodes = observation.elements
        return nodes.indices.mapNotNull { index ->
            val node = nodes[index]
            if (!node.clickable || !node.enabled || node.right <= node.left ||
                node.bottom <= node.top) return@mapNotNull null
            val labels = nodes.indices.filter {
                it != index && ownLabel(nodes[it]) != null && clickableAncestor(nodes, it) == index
            }
            val titleIndex = labels.firstOrNull { nodes[it].viewId?.endsWith("/title") == true }
                ?: labels.firstOrNull { nodes[it].viewId?.endsWith("/summary") != true }
            val title = ownLabel(node) ?: titleIndex?.let { ownLabel(nodes[it]) }
                ?: return@mapNotNull null
            val subtitleIndex = labels.firstOrNull {
                nodes[it].viewId?.endsWith("/summary") == true
            } ?: labels.firstOrNull {
                it != titleIndex && ownLabel(nodes[it]) != title
            }
            ScreenControl(
                index = index,
                label = title,
                subtitle = subtitleIndex?.let { ownLabel(nodes[it]) }?.takeIf { it != title },
                type = if (isToggle(node)) ScreenControl.Type.TOGGLE else ScreenControl.Type.ROW,
                checked = node.checked,
                element = node
            )
        }
    }

    private fun ownLabel(node: AccessibleUiElement): String? =
        (node.text ?: node.contentDescription)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isToggle(node: AccessibleUiElement): Boolean =
        listOf("Switch", "CheckBox", "ToggleButton").any {
            node.className?.contains(it, ignoreCase = true) == true
        }

    private fun clickableAncestor(nodes: List<AccessibleUiElement>, index: Int): Int? {
        var parent = nodes[index].parentIndex
        var depth = 0
        while (parent != null && depth++ < 40) {
            if (parent !in 0 until index) return null
            if (nodes[parent].clickable) return parent
            parent = nodes[parent].parentIndex
        }
        return null
    }
}
