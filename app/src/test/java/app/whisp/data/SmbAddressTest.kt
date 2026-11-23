// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.*
import org.junit.Test

class SmbAddressTest {
    @Test
    fun unicodeSpacesAndLiteralPlusRoundTrip() {
        val address = SmbAddress.parse("smb://nas.example/media/示例目录/Audio/")
        assertEquals("media", address.share)
        assertEquals(
            "示例目录\\Audio\\RJ000001\\音频\\03_日文 + 字幕.mp3",
            address.filePath("RJ000001/音频/03_日文 + 字幕.mp3"),
        )
        assertEquals(address, SmbAddress.parse(address.normalized))
        val path = "RJ000001/台本/01_声 + 耳%?.txt"
        val uri = SmbAddress.assetUri("abc123", path)
        assertEquals(path, SmbAddress.assetPath(uri, "abc123"))
        assertFalse(uri.contains("台本"))
    }

    @Test
    fun customPortAndDomainAccount() {
        assertEquals(1445, SmbAddress.parse("smb://localhost:1445/ASMR").port)
        assertEquals(SmbAccount("listener", "WORKGROUP"), SmbAccount.parse("WORKGROUP\\listener"))
        assertEquals(SmbAccount("listener", ""), SmbAccount.parse("listener"))
        val unencrypted = SmbAddress.parse("smb://nas.example/media/Audio/", encrypt = false)
        assertFalse(unencrypted.encrypt)
        assertTrue(unencrypted.normalized.endsWith("?encrypt=false"))
        assertEquals(unencrypted, SmbAddress.parse(unencrypted.normalized))
        assertTrue(SmbAddress.parse("smb://nas.example/media/Audio/").encrypt)
    }

    @Test
    fun traversalEncodedSeparatorsAndCredentialUrlsAreRejected() {
        val invalid =
            listOf(
                "smb://nas",
                "smb://user:secret@nas/data",
                "smb://nas/data/../secret",
                "smb://nas/data/%2e%2e/secret",
                "smb://nas/data/x%2fy",
                "smb://nas/data/x%5cy",
                "smb://nas/data/%00",
                "smb://nas/data?password=x",
                "smb://nas:0/data",
            )
        invalid.forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { SmbAddress.parse(value) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmbAddress.assetPath("whisp-smb://other/file", "expected")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmbAddress.assetPath("whisp-smb://expected/%2e%2e/file", "expected")
        }
    }
}
