// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface WhispRoute : NavKey {
    @Serializable data object Library : WhispRoute

    @Serializable data object Sources : WhispRoute

    @Serializable data object Settings : WhispRoute

    @Serializable data object Storage : WhispRoute

    @Serializable data object Player : WhispRoute

    @Serializable data class Detail(val workId: String) : WhispRoute

    @Serializable data class Dlsite(val sourceId: String) : WhispRoute

    @Serializable data class DlsiteWork(val sourceId: String, val workno: String) : WhispRoute

    @Serializable
    data class AsmrOne(
        val sourceId: String,
        val facetKind: String? = null,
        val facetName: String? = null,
    ) : WhispRoute

    @Serializable data class AsmrPreview(val sourceId: String, val workId: Long) : WhispRoute
}

internal val WhispRoute.isTopLevelTab: Boolean
    get() = this is WhispRoute.Library || this is WhispRoute.Sources || this is WhispRoute.Settings
