// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WhispRouteTest {
    @Test
    fun typedRoutesRoundTripWithSpecialCharacters() {
        val original =
            listOf<WhispRoute>(
                WhispRoute.Library,
                WhispRoute.Detail("work|with|pipes"),
                WhispRoute.AsmrOne("source|id", "va", "声优"),
                WhispRoute.AsmrPreview("source|id", 123456L),
                WhispRoute.Dlsite("dlsite-src"),
                WhispRoute.Storage,
                WhispRoute.Player,
            )
        val encoded = original.map { Json.encodeToString(WhispRoute.serializer(), it) }
        val restored = encoded.map { Json.decodeFromString(WhispRoute.serializer(), it) }
        assertEquals(original, restored)
    }
}
