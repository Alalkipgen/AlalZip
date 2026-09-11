package app.archivepocket.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Upstream disposable fixtures; passwords here are public test data, never user secrets. */
class RarEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String): File = temporary.newFile("${System.nanoTime()}-$name").also { file ->
        requireNotNull(javaClass.getResourceAsStream("/rar/$name")).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
    }
    private fun extract(name: String, password: String?): File {
        val out = temporary.newFolder()
        ArchiveEngine.extract(fixture(name), out, password?.toCharArray(), OperationControl())
        return out
    }
    /**
     * The upstream junrar fixtures are not uniform: rar4.rar / rar5.rar were packed on
     * Windows and contain FILE1.TXT / FILE2.TXT with CRLF bodies, while the password
     * fixtures contain file1.txt with LF bodies. The engine must preserve names as
     * stored, so the test (not the engine) tolerates case and line-ending differences.
     */
    private fun extracted(out: File, name: String): String {
        val file = out.walkTopDown().firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
            ?: throw AssertionError("Missing $name; extracted: " + out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).path }.toList())
        return file.readText().replace("\r\n", "\n")
    }
    @Test fun plainRar4AndRar5() {
        for (name in listOf("rar4.rar", "rar5.rar")) {
            val out = extract(name, null)
            assertEquals(name, "file1\n", extracted(out, "file1.txt"))
            assertEquals(name, "file2\n", extracted(out, "file2.txt"))
            assertEquals("$name must contain exactly two files", 2, out.walkTopDown().count { it.isFile })
        }
    }
    @Test fun encryptedRar4AndRar5DataAndHeaders() {
        for (format in listOf("rar4", "rar5")) for (kind in listOf("password", "encrypted")) {
            val out = extract("$format-$kind-junrar.rar", "junrar")
            assertEquals("$format/$kind", "file1\n", extracted(out, "file1.txt"))
        }
    }
    @Test fun wrongRarPasswordsFail() {
        for (format in listOf("rar4", "rar5")) for (kind in listOf("password", "encrypted")) {
            var failed = false
            try { extract("$format-$kind-junrar.rar", "incorrect") } catch (_: Exception) { failed = true }
            assertTrue("Wrong password must fail: $format/$kind", failed)
        }
    }
    @Test fun corruptRarFails() {
        val rar = fixture("rar5.rar")
        rar.writeBytes(rar.readBytes().copyOf(12))
        var failed = false
        try { ArchiveEngine.extract(rar, temporary.newFolder(), null, OperationControl()) } catch (_: Exception) { failed = true }
        assertTrue("Truncated RAR must fail", failed)
    }
}