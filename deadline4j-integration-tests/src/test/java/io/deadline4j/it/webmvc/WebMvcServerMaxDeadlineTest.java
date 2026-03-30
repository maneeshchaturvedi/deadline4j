package io.deadline4j.it.webmvc;

import io.deadline4j.*;
import io.deadline4j.spring.webmvc.DeadlineExceptionHandler;
import io.deadline4j.spring.webmvc.DeadlineFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that server-imposed maximum deadline caps incoming deadlines.
 */
@SpringBootTest(
    classes = WebMvcTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.web-application-type=servlet",
        "deadline4j.enforcement=enforce"
    }
)
@Import(WebMvcServerMaxDeadlineTest.Config.class)
class WebMvcServerMaxDeadlineTest {

    @Autowired
    TestRestTemplate testRestTemplate;

    @TestConfiguration
    static class Config {

        @Bean
        DeadlineCodec deadlineCodec() {
            return DeadlineCodec.remainingMillis();
        }

        @Bean
        ServerDeadlineConfig serverDeadlineConfig() {
            // Server imposes a maximum deadline of 5 seconds
            return new ServerDeadlineConfig(Duration.ofSeconds(5));
        }

        @Bean
        DeadlineFilter deadlineFilter(DeadlineCodec codec, ServerDeadlineConfig serverConfig) {
            return new DeadlineFilter(codec, null, EnforcementMode.ENFORCE, serverConfig);
        }

        @Bean
        DeadlineExceptionHandler deadlineExceptionHandler() {
            return new DeadlineExceptionHandler();
        }

        @Bean
        MaxDeadlineController maxDeadlineController() {
            return new MaxDeadlineController();
        }
    }

    @RestController
    static class MaxDeadlineController {
        @GetMapping("/max-deadline-info")
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
    @DisplayName("Server max deadline caps incoming deadline of 30s to 5s")
    void serverMaxDeadlineCapsIncoming() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "30000");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = testRestTemplate.exchange(
            "/max-deadline-info", HttpMethod.GET, entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("has_deadline")).isEqualTo(true);
        assertThat(body.get("expired")).isEqualTo(false);

        // Should be capped to ~5000ms (server max) not 30000ms
        Number remainingMs = (Number) body.get("remaining_ms");
        assertThat(remainingMs.longValue()).isBetween(4500L, 5100L);
    }

    @Test
    @DisplayName("Deadline below server max is not modified")
    void deadlineBelowMaxIsUnchanged() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Deadline-Remaining-Ms", "2000");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = testRestTemplate.exchange(
            "/max-deadline-info", HttpMethod.GET, entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("has_deadline")).isEqualTo(true);

        // Should be around 1500-2000ms (not capped since < 5s)
        Number remainingMs = (Number) body.get("remaining_ms");
        assertThat(remainingMs.longValue()).isBetween(1500L, 2000L);
    }
}
