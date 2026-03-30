package io.deadline4j.spring.webmvc;

import io.deadline4j.DeadlineExceededException;
import io.deadline4j.OptionalCallSkippedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeadlineExceptionHandlerTest {

    private final DeadlineExceptionHandler handler = new DeadlineExceptionHandler();

    @Test
    void deadlineExceededException_returns504() {
        DeadlineExceededException ex =
                new DeadlineExceededException("Timeout", "inventory-service", 50);

        ResponseEntity<Map<String, Object>> response = handler.handleDeadlineExceeded(ex);

        assertEquals(504, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(504, body.get("status"));
        assertEquals("Gateway Timeout", body.get("error"));
        assertEquals("Timeout", body.get("message"));
        assertEquals("inventory-service", body.get("service"));
    }

    @Test
    void optionalCallSkippedException_returns504() {
        OptionalCallSkippedException ex =
                new OptionalCallSkippedException("recommendations", 100);

        ResponseEntity<Map<String, Object>> response = handler.handleDeadlineExceeded(ex);

        assertEquals(504, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(504, body.get("status"));
        assertEquals("Gateway Timeout", body.get("error"));
        assertEquals("recommendations", body.get("service"));
        assertNotNull(body.get("message"));
    }

    @Test
    void missingServiceName_omitsServiceField() {
        DeadlineExceededException ex = new DeadlineExceededException("Timeout");

        ResponseEntity<Map<String, Object>> response = handler.handleDeadlineExceeded(ex);

        assertEquals(504, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("service"));
    }

    @Test
    void bodyContainsCorrectMessage() {
        String expectedMessage = "Deadline exceeded for order-service";
        DeadlineExceededException ex =
                new DeadlineExceededException(expectedMessage, "order-service", 0);

        ResponseEntity<Map<String, Object>> response = handler.handleDeadlineExceeded(ex);

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(expectedMessage, body.get("message"));
    }
}
