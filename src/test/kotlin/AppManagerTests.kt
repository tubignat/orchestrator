package com.example.orchestrator

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppManagerTests {

    private fun createTarGz(files: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gzos ->
            TarArchiveOutputStream(gzos).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for ((path, content) in files) {
                    val bytes = content.toByteArray(StandardCharsets.UTF_8)
                    val entry = TarArchiveEntry(path)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return baos.toByteArray()
    }

    private fun appsDir(): File = File("test-apps")
    private fun testConfig(): Config = Config("test-apps")

    @BeforeEach
    fun setup() {
        appsDir().deleteRecursively()
        appsDir().mkdirs()
    }

    @Test
    fun managerShouldStartExistingAppsUponInitialization() {
        val d1 = File(appsDir(), "test").apply { mkdirs() }
        val d2 = File(appsDir(), "test2").apply { mkdirs() }

        val runner = mockk<AppRunner>(relaxed = true)

        ThreadSafeAppManager(runner, testConfig())

        verify(exactly = 1) { runner.start(d1, any()) }
        verify(exactly = 1) { runner.start(d2, any()) }
    }

    @Test
    fun existsShouldReturnCorrectResult() {
        File(appsDir(), "test").apply { mkdirs() }
        File(appsDir(), "test2").apply { mkdirs() }

        val runner = mockk<AppRunner>(relaxed = true)

        val manager = ThreadSafeAppManager(runner, testConfig())

        assertTrue(manager.exists("test"))
        assertFalse(manager.exists("non-existent"))
    }

    @Test
    fun startShouldStartNonRunningAnApp() {
        val runner = mockk<AppRunner>(relaxed = true)
        val manager = ThreadSafeAppManager(runner, testConfig())

        manager.start("test-app")

        verify(exactly = 1) { runner.start(any(), any()) }
    }

    @Test
    fun startShouldDoNothingIfAppIsRunningAlready() {
        val runner = mockk<AppRunner>(relaxed = true)
        every { runner.status(any()) } answers { ProcessStatus.RUNNING }
        val manager = ThreadSafeAppManager(runner, testConfig())

        manager.start("test-app")

        verify(exactly = 0) { runner.start(any(), any()) }
    }

    @Test
    fun stopShouldStopTheRunningApp() {
        val runner = mockk<AppRunner>(relaxed = true)
        val manager = ThreadSafeAppManager(runner, testConfig())

        every { runner.status(any()) } answers { ProcessStatus.NOT_STARTED }
        manager.start("test-app")

        every { runner.status(any()) } answers { ProcessStatus.RUNNING }
        manager.stop("test-app")

        verify(exactly = 1) { runner.stop(any()) }
    }

    @Test
    fun stopShouldDoNothingIfAppIsNotRunning() {
        val runner = mockk<AppRunner>(relaxed = true)
        every { runner.status(any()) } answers { ProcessStatus.STOPPED }
        val manager = ThreadSafeAppManager(runner, testConfig())

        manager.stop("test-app")

        verify(exactly = 0) { runner.stop(any()) }
    }

    @Test
    fun statusShouldReturnValueFromRunner() {
        val runner = mockk<AppRunner>(relaxed = true)
        val manager = ThreadSafeAppManager(runner, testConfig())

        every { runner.status(any()) } answers { ProcessStatus.STOPPED }
        assertEquals(ProcessStatus.STOPPED, manager.status("test-app").status)

        every { runner.status(any()) } answers { ProcessStatus.RUNNING }
        assertEquals(ProcessStatus.RUNNING, manager.status("test-app").status)

        every { runner.status(any()) } answers { ProcessStatus.NOT_STARTED }
        assertEquals(ProcessStatus.NOT_STARTED, manager.status("test-app").status)
    }

    @Test
    fun appURLShouldReturnCorrectURL() {
        val runner = mockk<AppRunner>(relaxed = true)
        val manager = ThreadSafeAppManager(runner, testConfig())

        every { runner.status(any()) } answers { ProcessStatus.NOT_STARTED }
        assertEquals(null, manager.appURL("test-app"))

        every { runner.start(any(), any()) } answers { AppProcess(1, "test-app",1111, Any(), false) }
        manager.start("test-app")

        every { runner.status(any()) } answers { ProcessStatus.RUNNING }
        assertEquals("http://127.0.0.1:1111", manager.appURL("test-app"))

        every { runner.status(any()) } answers { ProcessStatus.STOPPED }
        assertEquals(null, manager.appURL("test-app"))
    }

    @Test
    fun deployShouldCreateAndRunTheApp() {
        val runner = mockk<AppRunner>(relaxed = true)
        val manager = ThreadSafeAppManager(runner, testConfig())

        val tar = createTarGz(
            mapOf("index.js" to "console.log('hi')")
        )

        manager.deploy("test-app", tar)

        val dir = File(appsDir(), "test-app")
        assertTrue(dir.exists() && dir.isDirectory)
        assertTrue(File(dir, "index.js").exists())

        verify(exactly = 1) { runner.start(dir, any()) }
    }

    @Test
    fun deployShouldReplaceExistingApp() {
        val dir = File(appsDir(), "test-app").apply { mkdirs() }
        File(dir, "index.js").writeText("console.log('v1');\n")

        val runner = mockk<AppRunner>(relaxed = true)
        val manager = ThreadSafeAppManager(runner, testConfig())

        val tar = createTarGz(mapOf("index.js" to "console.log('v2');\n"))

        every { runner.status(any()) } answers { ProcessStatus.RUNNING }
        manager.deploy("test-app", tar)

        assertTrue(dir.exists() && dir.isDirectory)
        assertTrue(File(dir, "index.js").exists())
        assertEquals("console.log('v2');\n", File(dir, "index.js").readText())

        verify(exactly = 1) { runner.stop(any()) }
        verify(exactly = 2) { runner.start(dir, any()) }
    }
}
