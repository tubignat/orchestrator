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
        assertEquals(ProcessStatus.NOT_STARTED, runner.status(null))
    }

    @Test
    fun statusRunningWhenAlive() {
        val runner = AppRunnerImpl(Config())
        val p = mockk<Process>(relaxed = true)
        every { p.isAlive } returns true
        assertEquals(ProcessStatus.RUNNING, runner.status(AppProcess(1, "name", 1000, p, false)))
    }

    @Test
    fun statusStoppedWhenExitedZero() {
        val runner = AppRunnerImpl(Config())
        val p = mockk<Process>(relaxed = true)
        every { p.isAlive } returns false
        every { p.exitValue() } returns 0
        assertEquals(ProcessStatus.STOPPED, runner.status(AppProcess(2, "name", 1001, p, false)))
    }

    @Test
    fun statusErrorWhenExitedNonZero() {
        val runner = AppRunnerImpl(Config())
        val p = mockk<Process>(relaxed = true)
        every { p.isAlive } returns false
        every { p.exitValue() } returns 1
        assertEquals(ProcessStatus.ERROR, runner.status(AppProcess(3, "name", 1002, p, false)))
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
            assertEquals(ProcessStatus.RUNNING, runner.status(appProcess))

            pingServer(appProcess.port)
        } finally {
            runner.stop(appProcess)
            assertFalse((appProcess.innerProcess as Process).isAlive)
        }
    }

    @Test
    fun usageReturnsValidOSProcessUsageMetrics() {
        val runner = AppRunnerImpl(Config(stopProcessTimeoutSeconds = 1))
        val appDir = File("test-apps/app")
        if (appDir.exists()) {
            appDir.deleteRecursively()
        }
        appDir.mkdirs()
        File(appDir, "index.js").writeText(
            """
                const array = [];
                setInterval(() => { 
                    array.push("test");
                    console.log("test"); 
                }, 10)
            """.trimIndent()
        )
        val appProcess = runner.start(appDir) {}
        try {
            Thread.sleep(1000)
            val usage = runner.usage(appProcess)
            assertNotNull(usage)
            if (usage != null) {
                println(usage)
                assertTrue(usage.cpu > 0 && usage.cpu < 20)
                assertTrue(usage.mem > 0 && usage.mem < 50 * 1024)
            }
        } finally {
            runner.stop(appProcess)
        }
    }

    @Test
    fun appRunnerEnforcesMemLimit() {
        val runner = AppRunnerImpl(Config(memLimitPerAppInMB = 30))
        val appDir = File("test-apps/app")
        if (appDir.exists()) {
            appDir.deleteRecursively()
        }
        appDir.mkdirs()
        File(appDir, "index.js").writeText(
            """
                const array = ["0"];
                setInterval(() => {
                    array.push(Array.from({ length: array.length }, (_, i) => i).join(''));
                }, 2);
            """.trimIndent()
        )
        var appProcess = runner.start(appDir) {}
        try {
            repeat(100) {
                val usage = runner.usage(appProcess)
                if (usage != null) {
                    assertTrue(usage.mem < 50 * 1024)
                } else {
                    appProcess = runner.start(appDir) {}
                }

                Thread.sleep(100)
            }
        } finally {
            runner.stop(appProcess)
        }
    }

    @Test
    fun appRunnerEnforcesCPULimit() {
        val cpuLimit = 10.0
        val runner = AppRunnerImpl(Config(cpuLimitPerApp = cpuLimit))
        val appDir = File("test-apps/app")
        if (appDir.exists()) {
            appDir.deleteRecursively()
        }
        appDir.mkdirs()
        File(appDir, "index.js").writeText(
            """
                setInterval(() => { console.log("test") }, 1);
            """.trimIndent()
        )
        var appProcess = runner.start(appDir) {}
        var total = 0.0
        val iterations = 100

        try {
            repeat(iterations) {
                val usage = runner.usage(appProcess)
                if (usage != null) {
                    total += usage.cpu
                } else {
                    appProcess = runner.start(appDir) {}
                }

                Thread.sleep(100)
            }

            val avgCpuUsage = total / iterations
            assertTrue(avgCpuUsage < cpuLimit * 1.5)
        } finally {
            runner.stop(appProcess)
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
