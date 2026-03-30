package io.deadline4j.spring.webmvc;

import io.deadline4j.DeadlineExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link DeadlineExceededException} to 504 Gateway Timeout.
 * Mirrors gRPC's automatic Status.DEADLINE_EXCEEDED response.
 *
 * <p>Auto-registered by Spring Boot auto-configuration.
 * Opt-out by defining your own handler bean.
 */
@ControllerAdvice
public class DeadlineExceptionHandler {

    @ExceptionHandler(DeadlineExceededException.class)
    public ResponseEntity<Map<String, Object>> handleDeadlineExceeded(
            DeadlineExceededException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 504);
        body.put("error", "Gateway Timeout");
        body.put("message", ex.getMessage());
        if (ex.serviceName() != null) {
            body.put("service", ex.serviceName());
        }
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }
}
