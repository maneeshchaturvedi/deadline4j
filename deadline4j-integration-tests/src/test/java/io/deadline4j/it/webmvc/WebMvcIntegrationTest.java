package io.deadline4j.it.webmvc;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.deadline4j.*;
import io.deadline4j.spring.webmvc.DeadlineExceptionHandler;
import io.deadline4j.spring.webmvc.DeadlineFilter;
import io.deadline4j.spring.webmvc.DeadlineRestTemplateInterceptor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests exercising the full DeadlineFilter + RestTemplate interceptor
 * chain with a real Spring Boot servlet context and WireMock for downstream calls.
 */
@SpringBootTest(
    classes = WebMvcTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.web-application-type=servlet",
        "deadline4j.enforcement=enforce"
    }
)
@Import(WebMvcIntegrationTest.WebMvcTestConfig.class)
class WebMvcIntegrationTest {

    private static WireMockServer wireMock;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate testRestTemplate;

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

    @DynamicPropertySource
    static void wireMockProperties(DynamicPropertyRegistry registry) {
        // Not used directly, but available if needed
    }

    // ---------------------------------------------------------------
    // Test Configuration
    // ---------------------------------------------------------------

    @TestConfiguration
    static class WebMvcTestConfig {

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
        DeadlineFilter deadlineFilter(DeadlineCodec codec, ServerDeadlineConfig serverConfig) {
            return new DeadlineFilter(codec, null, EnforcementMode.ENFORCE, serverConfig);
        }

        @Bean
        DeadlineRestTemplateInterceptor deadlineRestTemplateInterceptor(
                DeadlineCodec codec,
                AdaptiveTimeoutRegistry registry,
                ServiceConfigRegistry serviceConfigRegistry) {
            return new DeadlineRestTemplateInterceptor(
                codec, registry, ServiceNameResolver.byHost(), serviceConfigRegistry
            );
        }

        @Bean
        DeadlineExceptionHandler deadlineExceptionHandler() {
            return new DeadlineExceptionHandler();
        }

        @Bean
        RestTemplate restTemplate(DeadlineRestTemplateInterceptor interceptor) {
            RestTemplate rt = new RestTemplate();
            rt.setInterceptors(List.of(interceptor));
            return rt;
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    // ---------------------------------------------------------------
    // Test Controller
    // ---------------------------------------------------------------

    @RestController
    static class TestController {

        @Autowired
        RestTemplate restTemplate;

        @GetMapping("/deadline-info")
        public Map<String, Object> info() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("has_deadline", DeadlineContext.current().isPresent());
            DeadlineContext.current().ifPresent(d -> {
                result.put("remaining_ms", d.remainingMillis());
                result.put("expired", d.isExpired());
            });
            result.put("budget_is_noop", TimeoutBudget.current() == TimeoutBudget.NOOP);
            return result;
        }

        @GetMapping("/call-downstream")
        public Map<String, Object> callDownstream(@RequestParam("url") String url) {
            String response = restTemplate.getForObject(url, String.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("downstream_response", response);
            result.put("remaining_ms",
                DeadlineContext.current().map(Deadline::remainingMillis).orElse(-1L));
            result.put("budget_segments", TimeoutBudget.current().segments().size());
            return result;
        }

        @GetMapping("/throw-deadline-exceeded")
        public String throwDeadlineExceeded() {
            throw new DeadlineExceededException(
                "Deadline expired in test", "test-service", 0);
        }

        @GetMapping("/slow-then-downstream")
        public Map<String, Object> slowThenDownstream(@RequestParam("url") String url)
                throws InterruptedException {
            Thread.sleep(100);
            // By now a very short deadline should be expired
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("expired", DeadlineContext.isExpired());
            try {
                String response = restTemplate.getForObject(url, String.class);
                result.put("downstream_response", response);
            } catch (DeadlineExceededException e) {
                result.put("deadline_exceeded", true);
                result.put("message", e.getMessage());
            }
            return result;
        }
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Filter extracts deadline from X-Deadline-Remaining-Ms header")
    void filterExtractsDeadline() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "5000");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = testRestTemplate.exchange(
            "/deadline-info", HttpMethod.GET, entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("has_deadline")).isEqualTo(true);
        assertThat(body.get("expired")).isEqualTo(false);
        assertThat(body.get("budget_is_noop")).isEqualTo(false);

        // remaining_ms should be between 4000 and 5000 (some time consumed by HTTP)
        Number remainingMs = (Number) body.get("remaining_ms");
        assertThat(remainingMs.longValue()).isBetween(4000L, 5000L);
    }

    @Test
    @DisplayName("No deadline header and no default means no deadline context")
    void filterNoDeadlinePassthrough() {
        ResponseEntity<Map> response = testRestTemplate.getForEntity(
            "/deadline-info", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("has_deadline")).isEqualTo(false);
        assertThat(body.get("budget_is_noop")).isEqualTo(true);
    }

    @Test
    @DisplayName("DeadlineContext is cleaned up after request completes")
    void contextCleanupAfterRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "5000");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = testRestTemplate.exchange(
            "/deadline-info", HttpMethod.GET, entity, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // On this test thread, no deadline should be set
        assertThat(DeadlineContext.current()).isEmpty();
        assertThat(TimeoutBudget.current()).isSameAs(TimeoutBudget.NOOP);
    }

    @Test
    @DisplayName("RestTemplate interceptor propagates deadline header to downstream")
    void restTemplatePropagatesToDownstream() {
        wireMock.stubFor(get(urlEqualTo("/api"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("downstream-ok")));

        String downstreamUrl = "http://localhost:" + wireMock.port() + "/api";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "5000");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = testRestTemplate.exchange(
            "/call-downstream?url=" + downstreamUrl,
            HttpMethod.GET, entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("downstream_response")).isEqualTo("downstream-ok");

        // Budget should have recorded one segment
        Number segments = (Number) body.get("budget_segments");
        assertThat(segments.intValue()).isEqualTo(1);

        // Remaining time should be less than what was sent
        Number remainingMs = (Number) body.get("remaining_ms");
        assertThat(remainingMs.longValue()).isLessThan(5000L);
        assertThat(remainingMs.longValue()).isGreaterThan(0L);

        // Verify WireMock received the deadline header
        wireMock.verify(getRequestedFor(urlEqualTo("/api"))
            .withHeader("X-Deadline-Remaining-Ms", WireMock.matching("\\d+")));
    }

    @Test
    @DisplayName("Propagated deadline header value is less than original (time consumed)")
    void propagatedDeadlineIsReduced() {
        wireMock.stubFor(get(urlEqualTo("/api"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("ok")));

        String downstreamUrl = "http://localhost:" + wireMock.port() + "/api";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "5000");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        testRestTemplate.exchange(
            "/call-downstream?url=" + downstreamUrl,
            HttpMethod.GET, entity, Map.class);

        // Get the value that was propagated to WireMock
        var requests = wireMock.findAll(getRequestedFor(urlEqualTo("/api")));
        assertThat(requests).hasSize(1);
        String propagatedValue = requests.get(0).getHeader("X-Deadline-Remaining-Ms");
        long propagatedMs = Long.parseLong(propagatedValue);

        // Should be less than 5000 because time was consumed by the inbound filter + processing
        assertThat(propagatedMs).isLessThan(5000L);
        assertThat(propagatedMs).isGreaterThan(0L);
    }

    @Test
    @DisplayName("DeadlineExceededException is mapped to 504 Gateway Timeout")
    void deadlineExceededReturns504() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "5000");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = testRestTemplate.exchange(
            "/throw-deadline-exceeded", HttpMethod.GET, entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(504);
        assertThat(body.get("error")).isEqualTo("Gateway Timeout");
        assertThat(body.get("service")).isEqualTo("test-service");
    }

    @Test
    @DisplayName("Expired deadline causes DeadlineExceededException on downstream call")
    void expiredDeadlinePreventsDownstreamCall() {
        wireMock.stubFor(get(urlEqualTo("/api"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("should-not-reach")));

        String downstreamUrl = "http://localhost:" + wireMock.port() + "/api";

        // Send with a very short deadline (1ms) so it expires during sleep
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "1");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = testRestTemplate.exchange(
            "/slow-then-downstream?url=" + downstreamUrl,
            HttpMethod.GET, entity, Map.class);

        // The endpoint catches the exception internally and returns 200
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("expired")).isEqualTo(true);
        assertThat(body.get("deadline_exceeded")).isEqualTo(true);
    }
}
