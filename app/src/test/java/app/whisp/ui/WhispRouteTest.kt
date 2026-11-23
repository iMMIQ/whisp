// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class WhispRouteTest {
    @Test
    fun routeSaverPreservesSpecialCharactersAndTypedRoutes() {
        val original =
            mutableStateListOf<WhispRoute>(
                WhispRoute.Library,
                WhispRoute.Detail("work|with|pipes"),
                WhispRoute.AsmrOne("source|id"),
                WhispRoute.AsmrPreview("source|id", 123456L),
                WhispRoute.Dlsite("dlsite-src"),
                WhispRoute.Storage,
                WhispRoute.Player,
            )
        val saved = with(whispRouteSaver) { SaverScope { true }.save(original) }!!
        val restored = whispRouteSaver.restore(saved)!!
        assertEquals(original.toList(), restored.toList())
    }
}
