package app.archivepocket.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class RarPreviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun solidDiscardHandlesWholeBufferWithoutBytewiseProgress() {
        val updates = mutableListOf<Long>()
        val control = OperationControl { bytes, _ -> updates.add(bytes) }
        val data = ByteArray(128 * 1024)
        ArchiveEngine.previewDiscard(control, ExpansionBudget(data.size.toLong(), data.size.toLong()), "prefix").write(data)
        assertEquals(listOf(data.size.toLong()), updates)
    }

    @Test fun solidDiscardHonoursCancellation() {
        val control = OperationControl().also { it.cancel() }
        try {
            ArchiveEngine.previewDiscard(control, ExpansionBudget(100, 100), "prefix").write(ByteArray(10))
            fail("Cancelled solid-prefix work must stop")
        } catch (_: PocketError) { }
    }

    @Test fun solidDiscardHonoursExpansionBudget() {
        val out = ArchiveEngine.previewDiscard(OperationControl(), ExpansionBudget(100, 10), "prefix")
        out.write(ByteArray(10))
        try { out.write(1); fail("Prefix must count toward preview budget") } catch (_: PocketError) { }
    }

    @Test fun lastSolidRar4AndRar5MemberOpensWithoutWritingPredecessors() {
        for (fixture in listOf(RAR4_SOLID, RAR5_SOLID)) {
            val archive = temporary.newFile().also { it.writeBytes(Base64.getDecoder().decode(fixture)) }
            val out = temporary.newFolder()
            val member = File(out, "file9.txt")
            val labels = mutableListOf<String>()
            ArchiveEngine.openItem(archive, "file9.txt", member, null, OperationControl { _, label -> labels.add(label) })
            assertEquals("file9\n", member.readText())
            assertEquals(listOf("file9.txt"), out.list()!!.toList())
            assertTrue("Solid predecessors should be processed through the guarded bulk path", labels.any { it.startsWith("Preparing solid RAR:") })
        }
    }

    @Test fun encryptedRarPreviewStillOpensOnlyRequestedFile() {
        for (format in listOf("rar4", "rar5")) for (kind in listOf("password", "encrypted")) {
            val archive = temporary.newFile()
            requireNotNull(javaClass.getResourceAsStream("/rar/$format-$kind-junrar.rar")).use { stream ->
                archive.outputStream().use { stream.copyTo(it) }
            }
            val out = temporary.newFolder()
            val member = File(out, "file1.txt")
            val password = "junrar".toCharArray() // public upstream fixture password
            try {
                ArchiveEngine.openItem(archive, "file1.txt", member, password, OperationControl())
                assertEquals("file1\n", member.readText().replace("\r\n", "\n"))
                assertEquals(listOf("file1.txt"), out.list()!!.toList())
            } finally { password.fill('\u0000') }
        }
    }

    companion object {
        // Tiny public Junrar v8.1.1 test fixtures, kept under its bundled UnRAR license.
        // Source: https://github.com/junrar/junrar/tree/v8.1.1/src/test/resources/com/github/junrar/solid
        // rar4-solid.rar Git blob: babd59622740316f7e53b33cea3d572af7e5277f (464 bytes).
        private const val RAR4_SOLID = "UmFyIRoHADvQcwgADQAAAAAAAACylHSAkCsAGgAAAAYAAAADBPcp4veq81AdMwkApIEAAGZpbGUxLnR4dADADQwM/hAMt2G79EFqVSh/2gEYP7Diz78doSBa4XSQkCsAAwAAAAYAAAADx6QEyfeq81AdMwkApIEAAGZpbGUyLnR4dADAepIAyjN0kJArAAMAAAAGAAAAA4aVH9D3qvNQHTMJAKSBAABmaWxlMy50eHQAwHsSAPkEdJCQKwADAAAABgAAAANBA16f96rzUB0zCQCkgQAAZmlsZTQudHh0AMB7kgBp1nSQkCsAAwAAAAYAAAADADJFhveq81AdMwkApIEAAGZpbGU1LnR4dADAfBIAmKd0kJArAAMAAAAGAAAAA8NhaK33qvNQHTMJAKSBAABmaWxlNi50eHQAwHySAAh1dJCQKwADAAAABgAAAAOCUHO096rzUB0zCQCkgQAAZmlsZTcudHh0AMB9EgD+yXSQkCsAAwAAAAYAAAADTUzrM/eq81AdMwkApIEAAGZpbGU4LnR4dADAfZIAbht0kJArAAMAAAAGAAAAAwx98Cr3qvNQHTMJAKSBAABmaWxlOS50eHQAwH4SgMQ9ewBABwA="
        // rar5-solid.rar Git blob: c46109d27f65afb2b0a97ddf7816c41e46c4d631 (423 bytes).
        private const val RAR5_SOLID = "UmFyIRoHAQAJ78hvCwEFBwQGAQGAgIAA5PLueR8CApsABoYApIMCY0kUXwT3KeKAGwEJZmlsZTEudHh0wYMYNDAz+EAy3Ybv0QWJRKH8DMPdhxsia/YACAfgmR8CAoUABoYApIMCY0kUX8ekBMnAGwEJZmlsZTIudHh0QhoCe4DgrmJzHwIChQAGhgCkgwJjSRRfhpUf0MAbAQlmaWxlMy50eHRCGgJ8ALv5fIsfAgKFAAaGAKSDAmNJFF9BA16fwBsBCWZpbGU0LnR4dEIaAnyAU1D+YR8CAoUABoYApIMCY0kUXwAyRYbAGwEJZmlsZTUudHh0QhoCfQAqrAiFHwIChQAGhgCkgwJjSRRfw2ForcAbAQlmaWxlNi50eHRCGgJ9gMIFim8fAgKFAAaGAKSDAmNJFF+CUHO0wBsBCWZpbGU3LnR4dEIaAn4A3QRFrh8CAoUABoYApIMCY0kUX01M6zPAGwEJZmlsZTgudHh0QhoCfoA1rcdEHwIChQAGhgCkgwJjSRRfDH3wKsAbAQlmaWxlOS50eHRCGgJ/AB13VlEDBQQA"
    }
}
