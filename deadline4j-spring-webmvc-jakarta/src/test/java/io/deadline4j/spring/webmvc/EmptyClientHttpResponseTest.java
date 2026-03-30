package io.deadline4j.spring.webmvc;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class EmptyClientHttpResponseTest {

    @Test
    void returns200Status() {
        EmptyClientHttpResponse response = EmptyClientHttpResponse.forSkippedCall("test-service");

        assertEquals(HttpStatusCode.valueOf(200), response.getStatusCode());
        assertEquals("OK", response.getStatusText());
    }

    @Test
    void emptyBody() throws IOException {
        EmptyClientHttpResponse response = EmptyClientHttpResponse.forSkippedCall("test-service");

        InputStream body = response.getBody();
        assertNotNull(body);
        assertEquals(-1, body.read(), "Body should be empty");
    }

    @Test
    void emptyHeaders() {
        EmptyClientHttpResponse response = EmptyClientHttpResponse.forSkippedCall("test-service");

        HttpHeaders headers = response.getHeaders();
        assertNotNull(headers);
        assertTrue(headers.isEmpty(), "Headers should be empty");
    }

    @Test
    void skippedServiceName() {
        EmptyClientHttpResponse response = EmptyClientHttpResponse.forSkippedCall("my-service");

        assertEquals("my-service", response.skippedService());
    }
}
