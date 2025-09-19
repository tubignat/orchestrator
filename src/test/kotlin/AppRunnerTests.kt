package com.example.orchestrator

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

class AppRunnerTests {

    @Test
    fun statusNotStartedWhenNull() {
        val runner = AppRunnerImpl(Config())
        assertEquals(AppStatus.NOT_STARTED, runner.status(null))
    }

    @Test
    fun statusRunningWhenAlive() {
        val runner = AppRunnerImpl(Config())
        val p = mockk<Process>(relaxed = true)
        every { p.isAlive } returns true
        assertEquals(AppStatus.RUNNING, runner.status(AppProcess(1, "name", 1000, p, false)))
    }

    @Test
    fun statusStoppedWhenExitedZero() {
        val runner = AppRunnerImpl(Config())
        val p = mockk<Process>(relaxed = true)
        every { p.isAlive } returns false
        every { p.exitValue() } returns 0
        assertEquals(AppStatus.STOPPED, runner.status(AppProcess(2, "name", 1001, p, false)))
    }

    @Test
    fun statusErrorWhenExitedNonZero() {
        val runner = AppRunnerImpl(Config())
        val p = mockk<Process>(relaxed = true)
        every { p.isAlive } returns false
        every { p.exitValue() } returns 1
        assertEquals(AppStatus.ERROR, runner.status(AppProcess(3, "name", 1002, p, false)))
    }

    @Test
    fun stopDoesNothingWhenNotAlive() {
        val runner = AppRunnerImpl(Config(stopProcessTimeoutSeconds = 1))
        val p = mockk<Process>(relaxed = true)
        every { p.isAlive } returns false
        runner.stop(AppProcess(4, "name", 1003, p, false))
        verify(exactly = 0) { p.destroy() }
        verify(exactly = 0) { p.destroyForcibly() }
        verify(exactly = 0) { p.waitFor(any<Long>(), any<TimeUnit>()) }
    }

    @Test
    fun stopTerminatesGracefullyWhenWaitSucceeds() {
        val runner = AppRunnerImpl(Config(stopProcessTimeoutSeconds = 1))
        val p = mockk<Process>(relaxed = true)
        every { p.isAlive } returns true
        every { p.waitFor(any<Long>(), any<TimeUnit>()) } returns true
        runner.stop(AppProcess(5, "name", 1004, p, false))
        verify(exactly = 1) { p.destroy() }
        verify(exactly = 1) { p.waitFor(any<Long>(), any<TimeUnit>()) }
        verify(exactly = 0) { p.destroyForcibly() }
    }

    @Test
    fun stopForcesWhenWaitTimesOut() {
        val runner = AppRunnerImpl(Config(stopProcessTimeoutSeconds = 1))
        val p = mockk<Process>(relaxed = true)
        every { p.isAlive } returns true
        every { p.waitFor(any<Long>(), any<TimeUnit>()) } returns false
        runner.stop(AppProcess(6, "name", 1005, p, false))
        verify(exactly = 1) { p.destroy() }
        verify(exactly = 1) { p.waitFor(any<Long>(), any<TimeUnit>()) }
        verify(exactly = 1) { p.destroyForcibly() }
    }

    @Test
    fun startLaunchesTheOSProcess() {
        val runner = AppRunnerImpl(Config(stopProcessTimeoutSeconds = 1))
        val appDir = File("test-apps/app")
        if (appDir.exists()) {
            appDir.deleteRecursively()
        }
        appDir.mkdirs()
        File(appDir, "index.js").writeText(
            """
            const http = require('http'); const port = process.env.SERVER_PORT || 0;
            const server = http.createServer((req, res) => { res.end('ok') });
            server.listen(port);
            """.trimIndent()
        )
        val appProcess = runner.start(appDir) {}
        try {
            assertTrue(appProcess.id > 0)
            assertTrue(appProcess.port > 0)
            assertTrue(appProcess.innerProcess is Process)

            val inner = appProcess.innerProcess as Process
            Thread.sleep(200)

            assertTrue(inner.isAlive)
            assertEquals(inner.pid(), appProcess.id)
            assertEquals(AppStatus.RUNNING, runner.status(appProcess))

            pingServer(appProcess.port)
        } finally {
            runner.stop(appProcess)
            assertFalse((appProcess.innerProcess as Process).isAlive)
        }
    }

    private fun pingServer(port: Int) {
        val url = "http://127.0.0.1:${port}/"
        var body: String? = null
        var error: Exception? = null

        repeat(10) {
            try {
                val client = HttpClient.newHttpClient()

                val request = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:${port}")).GET().build()

                body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
                error = null
                return@repeat
            } catch (e: Exception) {
                error = e
                Thread.sleep(100)
            }
        }

        assertNull(error)
        assertNotNull(body, "Failed to GET " + url + ": " + (error?.message ?: "unknown error"))
        assertEquals("ok", body)
    }
}
