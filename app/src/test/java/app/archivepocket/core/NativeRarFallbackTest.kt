package app.archivepocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The native 7-Zip library is only shipped inside the Android APK. On the host JVM these tests
 * verify that the native RAR entry points decline cleanly (writing nothing) so the pure-Java
 * junrar path, covered by RarEngineTest/RarPreviewTest, takes over.
 */
class NativeRarFallbackTest {
    @get:Rule val folder = TemporaryFolder()

    private fun resource(name: String): File = folder.newFile("${System.nanoTime()}-$name").also { file ->
        requireNotNull(javaClass.getResourceAsStream("/rar/$name")).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
    }

    @Test
    fun dictionarySizeIsParsedFromMethodStrings() {
        assertEquals(128L * 1024 * 1024, SevenZipSupport.dictionaryBytes("v5.0:m3:128m"))
        assertEquals(4L * 1024 * 1024, SevenZipSupport.dictionaryBytes("m3:4096k"))
        assertEquals(1L * 1024 * 1024 * 1024, SevenZipSupport.dictionaryBytes("v5.0:m5:1g"))
        assertNull(SevenZipSupport.dictionaryBytes("LZMA:24"))
        assertNull(SevenZipSupport.dictionaryBytes("Copy"))
        assertNull(SevenZipSupport.dictionaryBytes(null))
    }

    @Test
    fun nativeProbeIsStableAcrossCalls() {
        assertEquals(SevenZipSupport.nativeReady, SevenZipSupport.nativeReady)
    }

    @Test
    fun openRarItemDeclinesWithoutNativeLibraryAndWritesNothing() {
        assumeFalse("native 7-Zip present; junrar fallback not exercised", SevenZipSupport.nativeReady)
        val output = File(folder.newFolder("cache"), "item.bin")
        val handled = SevenZipSupport.openRarItem(resource("rar4.rar"), "missing.txt", output, null, OperationControl(), rar5 = false)
        assertFalse(handled)
        assertFalse(output.exists())
    }

    @Test
    fun extractRarDeclinesWithoutNativeLibraryAndWritesNothing() {
        assumeFalse("native 7-Zip present; junrar fallback not exercised", SevenZipSupport.nativeReady)
        val root = folder.newFolder("out")
        val handled = SevenZipSupport.extractRar(resource("rar5.rar"), root, null, OperationControl(), rar5 = true)
        assertFalse(handled)
        assertEquals(0, root.listFiles()!!.size)
    }
}
