// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.File
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real, authenticated Samba contract tests; fixtures and credentials are never packaged. */
class SmbServerTest {
    @Test
    fun authenticatedScanRandomAccessAndLargeOffsets(): Unit = runBlocking {
        val config = System.getenv("WHISP_SMB_TEST_CONFIG")
        assumeTrue("Set WHISP_SMB_TEST_CONFIG for the real Samba test", config != null)
        val props = Properties().apply { File(config!!).inputStream().use { load(it) } }
        val source =
            SourceEntity(
                "smb-test",
                "Test",
                "smb",
                props.getProperty("root"),
                props.getProperty("username"),
            )
        val password = props.getProperty("password")
        val client = SmbClient()
        client.check(source, password)
        val entries = client.walk(source, password)
        val mp3 = entries.single { it.path.endsWith("03_测试音轨.mp3") }
        assertTrue("Generated fixture supports a 40 MB offset", mp3.size > 40_001_024L)
        assertTrue(entries.any { it.path.endsWith(".vtt") })
        val original = File(props.getProperty("fixture"), mp3.path)
        client.open(source, password, mp3.uri).use { reader ->
            val offset = 40_000_003L
            val actual = ByteArray(1021)
            assertEquals(actual.size, reader.read(actual, offset, 0, actual.size))
            java.io.RandomAccessFile(original, "r").use { f ->
                val expected = ByteArray(actual.size)
                f.seek(offset)
                f.readFully(expected)
                assertArrayEquals(expected, actual)
            }
            assertEquals(-1, reader.read(actual, reader.length, 0, 5))
        }
        val sparse = entries.single { it.path == "large-offset.bin" }
        val marker = client.range(sparse.uri, source, password, 4_294_967_303L, 5)
        assertArrayEquals("WHISP".toByteArray(), marker)
        val subtitle = entries.first { it.path.endsWith(".vtt") }
        assertArrayEquals(
            File(props.getProperty("fixture"), subtitle.path).readBytes(),
            client.bytes(subtitle.uri, source, password, 1024 * 1024),
        )
        val failure =
            assertThrows(java.io.IOException::class.java) {
                client.check(source, "deliberately-wrong")
            }
        assertTrue(failure.message!!.contains("账号或密码"))
        assertThrows(IllegalArgumentException::class.java) {
            client.bytes(mp3.uri, source, password, 1024)
        }
    }
}
