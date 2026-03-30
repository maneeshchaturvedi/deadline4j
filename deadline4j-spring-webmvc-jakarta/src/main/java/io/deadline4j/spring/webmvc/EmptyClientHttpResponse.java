package io.deadline4j.spring.webmvc;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Synthetic 200 OK response with empty body. Returned when optional
 * calls are skipped due to insufficient budget.
 */
final class EmptyClientHttpResponse implements ClientHttpResponse {

    private final String skippedService;

    private EmptyClientHttpResponse(String skippedService) {
        this.skippedService = skippedService;
    }

    static EmptyClientHttpResponse forSkippedCall(String serviceName) {
        return new EmptyClientHttpResponse(serviceName);
    }

    @Override
    public HttpStatusCode getStatusCode() {
        return HttpStatusCode.valueOf(200);
    }

    @Override
    public String getStatusText() {
        return "OK";
    }

    @Override
    public HttpHeaders getHeaders() {
        return new HttpHeaders();
    }

    @Override
    public InputStream getBody() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void close() {
        // no-op
    }

    String skippedService() {
        return skippedService;
    }
}
