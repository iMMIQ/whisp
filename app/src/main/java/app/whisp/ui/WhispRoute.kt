// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface WhispRoute : NavKey {
    @Serializable data object Library : WhispRoute

    @Serializable data object Favorites : WhispRoute

    @Serializable data object Sources : WhispRoute

    @Serializable data object Storage : WhispRoute

    @Serializable data object Player : WhispRoute

    @Serializable data class Detail(val workId: String) : WhispRoute

    @Serializable data class Dlsite(val sourceId: String) : WhispRoute

    @Serializable data class AsmrOne(val sourceId: String) : WhispRoute

    @Serializable data class AsmrPreview(val sourceId: String, val workId: Long) : WhispRoute
}

internal val WhispRoute.isTopLevelTab: Boolean
    get() = this is WhispRoute.Library || this is WhispRoute.Favorites || this is WhispRoute.Sources

internal val whispRouteSaver =
    Saver<SnapshotStateList<WhispRoute>, ArrayList<String>>(
        save = { stack ->
            ArrayList(stack.map { route -> Json.encodeToString(WhispRoute.serializer(), route) })
        },
        restore = { saved ->
            saved
                .mapNotNull { raw ->
                    runCatching { Json.decodeFromString(WhispRoute.serializer(), raw) }.getOrNull()
                }
                .ifEmpty { listOf(WhispRoute.Library) }
                .toMutableStateList()
        },
    )
