package com.screenly.app

import com.screenly.app.ai.navigation.NavigationProtocol
import java.util.Collections

internal data class SnapshotKey(val sessionId: Long, val revision: Long)

/** Android owns observation bounds; enriched planning labels keep the same original indices. */
internal data class GuidanceSnapshot(
    val key: SnapshotKey,
    val observation: ScreenObservation,
    val planningElements: List<AccessibleUiElement>,
    val candidateIndices: List<Int>
)

internal fun guidanceSnapshot(
    key: SnapshotKey,
    observation: ScreenObservation,
    width: Int,
    height: Int
): GuidanceSnapshot {
    val originals = Collections.unmodifiableList(observation.elements.toList())
    val planning = originals.mapIndexed { index, element ->
        if (!element.clickable || NavigationProtocol.labelsOf(element).isNotEmpty()) element else {
            // Follow copied tree relationships, not geometric containment or live nodes.
            val descendants = originals.indices.filter { child ->
                var parent = originals[child].parentIndex
                var remaining = 40
                while (parent != null && parent != index && remaining-- > 0) {
                    if (parent !in originals.indices || originals[parent].clickable) break
                    parent = originals[parent].parentIndex
                }
                parent == index && child != index && !originals[child].clickable
            }
            val title = descendants.firstOrNull { originals[it].viewId == "android:id/title" }
                ?: descendants.firstOrNull { NavigationProtocol.labelsOf(originals[it]).isNotEmpty() }
            val summary = descendants.firstOrNull { originals[it].viewId == "android:id/summary" }
            element.copy(
                text = title?.let { originals[it].text ?: originals[it].contentDescription },
                contentDescription = summary?.let { originals[it].text ?: originals[it].contentDescription }
            )
        }
    }
    return GuidanceSnapshot(
        key, observation.copy(elements = originals), Collections.unmodifiableList(planning),
        Collections.unmodifiableList(originals.indices.filter { index ->
            originals[index].enabled && originals[index].clickable &&
                originals[index].intersectsScreen(width, height) &&
                NavigationProtocol.labelsOf(planning[index]).isNotEmpty()
        })
    )
}
