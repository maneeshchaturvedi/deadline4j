package io.deadline4j.it.webmvc;

import io.deadline4j.*;
import io.deadline4j.spring.webmvc.DeadlineExceptionHandler;
import io.deadline4j.spring.webmvc.DeadlineFilter;
import io.deadline4j.spring.webmvc.DeadlineRestTemplateInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that a default deadline is applied when no X-Deadline-Remaining-Ms header
 * is present but deadline4j.default-deadline is configured.
 */
@SpringBootTest(
    classes = WebMvcTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.web-application-type=servlet",
        "deadline4j.enforcement=enforce",
        "deadline4j.default-deadline=10s"
    }
)
@Import(WebMvcDefaultDeadlineTest.Config.class)
class WebMvcDefaultDeadlineTest {

    @Autowired
    TestRestTemplate testRestTemplate;

    @TestConfiguration
    static class Config {

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
            // Apply default deadline of 10s
            return new DeadlineFilter(codec, Duration.ofSeconds(10),
                EnforcementMode.ENFORCE, serverConfig);
        }

        @Bean
        DeadlineExceptionHandler deadlineExceptionHandler() {
            return new DeadlineExceptionHandler();
        }

        @Bean
        DefaultDeadlineController defaultDeadlineController() {
            return new DefaultDeadlineController();
        }
    }

    @RestController
    static class DefaultDeadlineController {
        @GetMapping("/default-deadline-info")
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
    }

    @Test
    @DisplayName("Default deadline is applied when no header is present")
    void defaultDeadlineApplied() {
        ResponseEntity<Map> response = testRestTemplate.getForEntity(
            "/default-deadline-info", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("has_deadline")).isEqualTo(true);
        assertThat(body.get("expired")).isEqualTo(false);
        assertThat(body.get("budget_is_noop")).isEqualTo(false);

        // Should be around 9000-10000ms (10s default minus HTTP overhead)
        Number remainingMs = (Number) body.get("remaining_ms");
        assertThat(remainingMs.longValue()).isBetween(9000L, 10000L);
    }

    @Test
    @DisplayName("Explicit header overrides default deadline")
    void explicitHeaderOverridesDefault() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "3000");
        org.springframework.http.HttpEntity<Void> entity =
            new org.springframework.http.HttpEntity<>(headers);

        ResponseEntity<Map> response = testRestTemplate.exchange(
            "/default-deadline-info",
            org.springframework.http.HttpMethod.GET, entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("has_deadline")).isEqualTo(true);

        // Should be around 2500-3000ms (header value, not default 10s)
        Number remainingMs = (Number) body.get("remaining_ms");
        assertThat(remainingMs.longValue()).isBetween(2500L, 3000L);
    }
}
