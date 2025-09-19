package com.example.orchestrator

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import org.springframework.core.io.buffer.DataBuffer

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SubdomainRoutingFilter(
    private val manager: AppManager,
    private val config: Config,
    webClientBuilder: WebClient.Builder
) : WebFilter {

    private val nameRegex = Regex("^[A-Za-z0-9-]+$")
    private val client: WebClient = webClientBuilder.build()

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val hostHeader = exchange.request.headers.getFirst("Host") ?: ""
        val host = hostHeader.substringBefore(":")

        if (!host.endsWith(".${config.domain}")) {
            return chain.filter(exchange)
        }

        val name = host.removeSuffix(".${config.domain}").removePrefix("www.")
        if (name.isBlank() || !nameRegex.matches(name)) {
            exchange.response.statusCode = HttpStatus.BAD_REQUEST
            return exchange.response.setComplete()
        }

        val baseUrl = manager.appURL(name) ?: run {
            exchange.response.statusCode = HttpStatus.NOT_FOUND
            return exchange.response.setComplete()
        }

        val incomingUri = exchange.request.uri
        val targetUri = UriComponentsBuilder.fromHttpUrl(baseUrl)
            .path(incomingUri.rawPath)
            .query(incomingUri.rawQuery)
            .build(true)
            .toUri()

        val requestSpec = client.method(exchange.request.method)
            .uri(targetUri)
            .headers { headers ->
                exchange.request.headers.forEach { (k, v) ->
                    if (!k.equals("host", ignoreCase = true)) {
                        headers[k] = v
                    }
                }
            }

        return requestSpec
            .body(BodyInserters.fromPublisher(exchange.request.body, DataBuffer::class.java))
            .exchangeToMono { clientResponse ->
                exchange.response.statusCode = clientResponse.statusCode()
                clientResponse.headers().asHttpHeaders().forEach { (k, v) ->
                    if (!k.equals("transfer-encoding", ignoreCase = true)) {
                        exchange.response.headers[k] = v
                    }
                }
                exchange.response.writeWith(clientResponse.bodyToFlux(DataBuffer::class.java))
            }
    }
}
