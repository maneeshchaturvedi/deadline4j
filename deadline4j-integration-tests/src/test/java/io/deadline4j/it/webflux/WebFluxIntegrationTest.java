package io.deadline4j.it.webflux;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.deadline4j.*;
import io.deadline4j.spring.webflux.DeadlineWebClientFilter;
import io.deadline4j.spring.webflux.DeadlineWebFilter;
import io.deadline4j.spring.webflux.ReactorDeadlineBridge;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests exercising the full DeadlineWebFilter + WebClient filter chain
 * with a real reactive Spring Boot context and WireMock for downstream calls.
 */
@SpringBootTest(
    classes = WebFluxTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.web-application-type=reactive",
        "deadline4j.enforcement=enforce"
    }
)
@Import(WebFluxIntegrationTest.WebFluxTestConfig.class)
class WebFluxIntegrationTest {

    private static WireMockServer wireMock;

    @Autowired
    WebTestClient webTestClient;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    // ---------------------------------------------------------------
    // Test Configuration
    // ---------------------------------------------------------------

    @TestConfiguration
    static class WebFluxTestConfig {

        @Bean
        DeadlineCodec deadlineCodec() {
            return DeadlineCodec.remainingMillis();
        }

        @Bean
        AdaptiveTimeoutRegistry adaptiveTimeoutRegistry() {
            return new AdaptiveTimeoutRegistry(
                serviceName -> AdaptiveTimeoutConfig.builder().build()
            );
        }

        @Bean
        ServiceConfigRegistry serviceConfigRegistry() {
            return new ServiceConfigRegistry(
                serviceName -> ServiceConfig.builder()
                    .enforcement(EnforcementMode.ENFORCE)
                    .build()
            );
        }

        @Bean
        ServerDeadlineConfig serverDeadlineConfig() {
            return ServerDeadlineConfig.none();
        }

        @Bean
        DeadlineWebFilter deadlineWebFilter(DeadlineCodec codec,
                                             ServerDeadlineConfig serverConfig) {
            return new DeadlineWebFilter(codec, null, serverConfig);
        }

        @Bean
        DeadlineWebClientFilter deadlineWebClientFilter(
                DeadlineCodec codec,
                AdaptiveTimeoutRegistry registry,
                ServiceConfigRegistry serviceConfigRegistry) {
            return new DeadlineWebClientFilter(
                codec, registry, ServiceNameResolver.byHost(), serviceConfigRegistry
            );
        }

        @Bean
        WebClient deadlineWebClient(DeadlineWebClientFilter filter) {
            return WebClient.builder().filter(filter).build();
        }

        @Bean
        ReactiveTestController reactiveTestController(WebClient deadlineWebClient) {
            return new ReactiveTestController(deadlineWebClient);
        }
    }

    // ---------------------------------------------------------------
    // Test Controller
    // ---------------------------------------------------------------

    @RestController
    static class ReactiveTestController {

        private final WebClient webClient;

        ReactiveTestController(WebClient webClient) {
            this.webClient = webClient;
        }

        @GetMapping("/reactive/deadline-info")
        public Mono<Map<String, Object>> deadlineInfo() {
            return Mono.deferContextual(ctx -> {
                Deadline deadline = ReactorDeadlineBridge.fromContext(ctx);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("has_deadline", deadline != null);
                if (deadline != null) {
                    body.put("remaining_ms", deadline.remainingMillis());
                    body.put("expired", deadline.isExpired());
                }
                return Mono.just(body);
            });
        }

        @GetMapping("/reactive/call-downstream")
        public Mono<Map<String, Object>> callDownstream(@RequestParam("url") String url) {
            return webClient.get()
                .uri(URI.create(url))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> Mono.deferContextual(ctx -> {
                    Deadline deadline = ReactorDeadlineBridge.fromContext(ctx);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("downstream_response", response);
                    body.put("remaining_ms",
                        deadline != null ? deadline.remainingMillis() : -1L);
                    return Mono.just(body);
                }));
        }

        @GetMapping("/reactive/slow-downstream")
        public Mono<Map<String, Object>> slowDownstream(@RequestParam("url") String url) {
            return webClient.get()
                .uri(URI.create(url))
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("downstream_response", response);
                    return body;
                })
                .onErrorResume(ex -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("error", true);
                    body.put("error_type", ex.getClass().getSimpleName());
                    return Mono.just(body);
                });
        }
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Reactive filter extracts deadline from X-Deadline-Remaining-Ms header")
    void reactiveFilterExtractsDeadline() {
        webTestClient.get()
            .uri("/reactive/deadline-info")
            .header("X-Deadline-Remaining-Ms", "5000")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.has_deadline").isEqualTo(true)
            .jsonPath("$.expired").isEqualTo(false)
            .jsonPath("$.remaining_ms").value(remaining -> {
                long ms = ((Number) remaining).longValue();
                assertThat(ms).isBetween(4000L, 5100L);
            });
    }

    @Test
    @DisplayName("No deadline header means no deadline in Reactor context")
    void reactiveNoDeadlinePassthrough() {
        webTestClient.get()
            .uri("/reactive/deadline-info")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.has_deadline").isEqualTo(false);
    }

    @Test
    @DisplayName("WebClient filter propagates deadline header to downstream service")
    void reactiveWebClientPropagatesDeadline() {
        wireMock.stubFor(get(urlEqualTo("/api"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("downstream-ok")));

        String downstreamUrl = "http://localhost:" + wireMock.port() + "/api";

        webTestClient.get()
            .uri("/reactive/call-downstream?url=" + downstreamUrl)
            .header("X-Deadline-Remaining-Ms", "5000")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.downstream_response").isEqualTo("downstream-ok")
            .jsonPath("$.remaining_ms").value(remaining -> {
                long ms = ((Number) remaining).longValue();
                assertThat(ms).isLessThan(5000L);
                assertThat(ms).isGreaterThan(0L);
            });

        // Verify WireMock received the deadline header
        wireMock.verify(getRequestedFor(urlEqualTo("/api"))
            .withHeader("X-Deadline-Remaining-Ms", WireMock.matching("\\d+")));
    }

    @Test
    @DisplayName("Propagated deadline header value is reduced (time consumed)")
    void propagatedDeadlineIsReduced() {
        wireMock.stubFor(get(urlEqualTo("/api"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("ok")));

        String downstreamUrl = "http://localhost:" + wireMock.port() + "/api";

        webTestClient.get()
            .uri("/reactive/call-downstream?url=" + downstreamUrl)
            .header("X-Deadline-Remaining-Ms", "5000")
            .exchange()
            .expectStatus().isOk();

        // Get the value that was propagated to WireMock
        var requests = wireMock.findAll(getRequestedFor(urlEqualTo("/api")));
        assertThat(requests).hasSize(1);
        String propagatedValue = requests.get(0).getHeader("X-Deadline-Remaining-Ms");
        long propagatedMs = Long.parseLong(propagatedValue);

        // Should be less than 5000 because time was consumed
        assertThat(propagatedMs).isLessThan(5000L);
        assertThat(propagatedMs).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Reactive timeout cancellation: short deadline aborts slow downstream")
    void reactiveTimeoutCancellation() {
        // Downstream takes 3 seconds to respond
        wireMock.stubFor(get(urlEqualTo("/slow-api"))
            .willReturn(aResponse()
                .withFixedDelay(3000)
                .withStatus(200)
                .withBody("too-slow")));

        String downstreamUrl = "http://localhost:" + wireMock.port() + "/slow-api";

        long startMs = System.currentTimeMillis();

        webTestClient
            .mutate()
            .responseTimeout(Duration.ofSeconds(10))
            .build()
            .get()
            .uri("/reactive/slow-downstream?url=" + downstreamUrl)
            .header("X-Deadline-Remaining-Ms", "500")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.error").isEqualTo(true);

        long elapsedMs = System.currentTimeMillis() - startMs;

        // Should complete well before 3s (the WireMock delay).
        // The adaptive timeout or deadline-derived timeout should kick in.
        assertThat(elapsedMs).isLessThan(2500L);
    }
}
