package com.example.orchestrator

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(controllers = [Controller::class])
class ControllerTests {

    @Autowired
    lateinit var webClient: WebTestClient

    @com.ninjasquad.springmockk.MockkBean(relaxUnitFun = true)
    lateinit var manager: AppManager

    @BeforeEach
    fun resetMocks() {
        clearMocks(manager)
    }

    @Test
    fun deployShouldReturnOkAndCallManager() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        webClient.post()
            .uri { it.path("/deploy").queryParam("name", "my-app").build() }
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(bytes)
            .exchange()
            .expectStatus().isOk

        verify(exactly = 1) { manager.deploy("my-app", match { it.contentEquals(bytes) }) }
    }

    @Test
    fun deployShouldReturnBadRequestOnInvalidName() {
        val bytes = byteArrayOf(1, 2, 3)
        webClient.post()
            .uri { it.path("/deploy").queryParam("name", "bad name!").build() }
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(bytes)
            .exchange()
            .expectStatus().isBadRequest

        verify(exactly = 0) { manager.deploy(any(), any()) }
    }

    @Test
    fun startShouldReturnOkWhenExists() {
        every { manager.exists("app1") } returns true

        webClient.post()
            .uri { it.path("/start").queryParam("name", "app1").build() }
            .exchange()
            .expectStatus().isOk

        verify(exactly = 1) { manager.start("app1") }
    }

    @Test
    fun startShouldReturnNotFoundWhenNotExists() {
        every { manager.exists("nope") } returns false

        webClient.post()
            .uri { it.path("/start").queryParam("name", "nope").build() }
            .exchange()
            .expectStatus().isNotFound

        verify(exactly = 0) { manager.start(any()) }
    }

    @Test
    fun startShouldReturnBadRequestOnInvalidName() {
        webClient.post()
            .uri { it.path("/start").queryParam("name", "bad name").build() }
            .exchange()
            .expectStatus().isBadRequest

        verify(exactly = 0) { manager.start(any()) }
    }

    @Test
    fun stopShouldReturnOkWhenExists() {
        every { manager.exists("app2") } returns true

        webClient.post()
            .uri { it.path("/stop").queryParam("name", "app2").build() }
            .exchange()
            .expectStatus().isOk

        verify(exactly = 1) { manager.stop("app2") }
    }

    @Test
    fun stopShouldReturnNotFoundWhenNotExists() {
        every { manager.exists("ghost") } returns false

        webClient.post()
            .uri { it.path("/stop").queryParam("name", "ghost").build() }
            .exchange()
            .expectStatus().isNotFound

        verify(exactly = 0) { manager.stop(any()) }
    }

    @Test
    fun stopShouldReturnBadRequestOnInvalidName() {
        webClient.post()
            .uri { it.path("/stop").queryParam("name", "bad/name").build() }
            .exchange()
            .expectStatus().isBadRequest

        verify(exactly = 0) { manager.stop(any()) }
    }

    @Test
    fun statusShouldReturnOkWithBodyWhenExists() {
        every { manager.exists("app3") } returns true
        every { manager.status("app3") } returns AppStatus.RUNNING

        webClient.get()
            .uri { it.path("/status").queryParam("name", "app3").build() }
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("RUNNING")
    }

    @Test
    fun statusShouldReturnNotFoundWhenNotExists() {
        every { manager.exists("missing") } returns false

        webClient.get()
            .uri { it.path("/status").queryParam("name", "missing").build() }
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun statusShouldReturnBadRequestOnInvalidName() {
        webClient.get()
            .uri { it.path("/status").queryParam("name", "").build() }
            .exchange()
            .expectStatus().isBadRequest
    }
}
