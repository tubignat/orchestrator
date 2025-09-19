package com.example.orchestrator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class E2ETest {

    @LocalServerPort
    private var port: Int = 0

    @TestConfiguration
    open class TestConfig {
        @Bean
        @Primary
        open fun testAppConfig(): Config {
            return Config(appsDirectory = "test-apps", stopProcessTimeoutSeconds = 5, domain = "localhost")
        }
    }

    @BeforeEach
    fun setup() {
        File("test-apps").deleteRecursively()
        File("test-apps").mkdirs()
    }

    @Test
    fun testDeployApp() {
        val appName = "e2e-app"
        val client = HttpClient.newHttpClient()

        deploy(client, appName, createSampleAppBundle())

        wait {
            assertTrue(status(client, appName).body().startsWith("$appName | RUNNING"))
            assertEquals("ok", httpGET("http://$appName.localhost:$port").body)
            assertEquals("ok", httpGET("http://www.$appName.localhost:$port").body)
        }

        stop(client, appName)

        wait {
            assertEquals("$appName | STOPPED\n", status(client, appName).body())
            assertThrows<WebClientResponseException> { httpGET("http://$appName.localhost:$port") }
            assertThrows<WebClientResponseException> { httpGET("http://www.$appName.localhost:$port") }
        }

        start(client, appName)

        wait {
            assertTrue(status(client, appName).body().startsWith("$appName | RUNNING"))
            assertEquals("ok", httpGET("http://$appName.localhost:$port").body)
            assertEquals("ok", httpGET("http://www.$appName.localhost:$port").body)
        }
    }

    @Test
    fun testDeployMultipleApps() {
        val client = HttpClient.newHttpClient()

        deploy(client, "first-app", createSampleAppBundle("payload #1"))
        deploy(client, "second-app", createSampleAppBundle("payload #2"))

        wait {
            assertTrue(status(client, "first-app").body().startsWith("first-app | RUNNING"))
            assertEquals("payload #1", httpGET("http://first-app.localhost:$port").body)

            assertTrue(status(client, "second-app").body().startsWith("second-app | RUNNING"))
            assertEquals("payload #2", httpGET("http://second-app.localhost:$port").body)
        }
    }

    @Test
    fun deployNewAppVersion() {
        val app = "test-app-version"
        val client = HttpClient.newHttpClient()

        deploy(client, app, createSampleAppBundle("Version: 1.0.0"))

        wait {
            assertTrue(status(client, app).body().startsWith("$app | RUNNING"))
            assertEquals("Version: 1.0.0", httpGET("http://$app.localhost:$port").body)
        }

        deploy(client, app, createSampleAppBundle("Version: 2.0.0"))

        wait {
            assertTrue(status(client, app).body().startsWith("$app | RUNNING"))
            assertEquals("Version: 2.0.0", httpGET("http://$app.localhost:$port").body)
        }
    }

    @Test
    fun crashedAppIsRevived() {
        val app = "test-app-revival"
        val client = HttpClient.newHttpClient()

        deploy(client, app, createSampleAppBundle())

        wait {
            assertEquals("ok", httpGET("http://$app.localhost:$port").body)
        }

        assertThrows<WebClientResponseException> { httpGET("http://$app.localhost:$port/badMethod").body }

        wait {
            assertEquals("ok", httpGET("http://$app.localhost:$port").body)
        }
    }

    @Test
    fun crashLoopingAppHasErrorStatus() {
        val app = "test-app-crash-loop"
        val client = HttpClient.newHttpClient()

        deploy(client, app, createSampleAppBundle(crashLoop = true))

        assertThrows<WebClientResponseException> { httpGET("http://$app.localhost:$port").body }

        wait {
            assertEquals("$app | ERROR\n", status(client, app).body())
        }
    }

    @Test
    fun subdomainIsRoutedToAppEvenIfItHasTheSameMethodsAsTheOrchestratorItself() {
        val app = "test-app"
        val client = HttpClient.newHttpClient()

        deploy(client, app, createSampleAppBundle())

        wait {
            assertTrue(status(client, app).body().startsWith("$app | RUNNING"))
            assertEquals("ok", httpGET("http://$app.localhost:$port/status").body)
        }
    }

    private fun deploy(client: HttpClient, appName: String, bundle: ByteArray): HttpResponse<Void> {
        val deployRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/deploy?name=$appName"))
            .header("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE)
            .POST(HttpRequest.BodyPublishers.ofByteArray(bundle))
            .build()
        return client.send(deployRequest, HttpResponse.BodyHandlers.discarding())
    }

    private fun status(client: HttpClient, appName: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder().uri(URI.create("http://localhost:$port/status?name=$appName")).GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun stop(client: HttpClient, appName: String): HttpResponse<Void> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/stop?name=$appName"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(req, HttpResponse.BodyHandlers.discarding())
    }

    private fun start(client: HttpClient, appName: String): HttpResponse<Void> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/start?name=$appName"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(req, HttpResponse.BodyHandlers.discarding())
    }

    private fun httpGET(url: String): ResponseEntity<String> {
        val uri = URI.create(url)
        return WebClient.builder()
            // This is a trick to allow testing urls like "http://app.localhost". While browsers are smart enough
            // to send this to localhost, Java clients assume this is a normal domain. So here we are sending the req
            // to localhost but set a Host header to the one we are actually trying to reach, simulating a GET request to http://app.localhost
            .baseUrl("http://localhost:$port/${uri.path}")
            .defaultHeader("Host", uri.host)
            .build()
            .get()
            .retrieve()
            .toEntity(String::class.java)
            .block()!!
    }

    private fun wait(timeout: Duration = 5.seconds, assertion: () -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            Thread.sleep(100)

            try {
                assertion()
                break
            } catch (e: Throwable) {
                if (start.elapsedNow() > timeout) {
                    throw AssertionError("Condition not met within $timeout", e)
                }
            }
        }
    }

    private fun createSampleAppBundle(appResponse: String = "ok", crashLoop: Boolean = false): ByteArray {
        val indexJs = if (crashLoop)
            "throw new Error('crashing');"
        else """
            const http = require('http'); 
            const port = process.env.SERVER_PORT || 3000;
            const server = http.createServer((req, res) => { 
               console.log(`${'$'}{req.method} ${'$'}{req.url}`);
               if (req.url === '/badMethod') {
                  process.exit(1);
               } 
               res.end('$appResponse'); 
            });
            server.listen(port);
            process.on('SIGTERM', () => process.exit(0));
            """

        val indexJsBytes = indexJs.trimIndent().toByteArray()
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gzos ->
            TarArchiveOutputStream(gzos).use { tos ->
                tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)

                val entry = TarArchiveEntry("index.js")
                entry.size = indexJsBytes.size.toLong()
                tos.putArchiveEntry(entry)
                tos.write(indexJsBytes)
                tos.closeArchiveEntry()

                tos.finish()
            }
        }

        return baos.toByteArray()
    }
}
