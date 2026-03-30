package io.deadline4j;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceNameResolverTest {

    @Test
    void byHost_extractsHostnameFromUri() {
        ServiceNameResolver<URI> resolver = ServiceNameResolver.byHost();

        String name = resolver.resolve(URI.create("http://order-service/api/orders"));

        assertThat(name).isEqualTo("order-service");
    }

    @Test
    void byHost_withPortReturnsHostnameWithoutPort() {
        ServiceNameResolver<URI> resolver = ServiceNameResolver.byHost();

        String name = resolver.resolve(URI.create("http://order-service:8080/api/orders"));

        assertThat(name).isEqualTo("order-service");
    }

    @Test
    void byHost_withPathReturnsJustHostname() {
        ServiceNameResolver<URI> resolver = ServiceNameResolver.byHost();

        String name = resolver.resolve(URI.create("https://payment-gateway.example.com/v1/charge"));

        assertThat(name).isEqualTo("payment-gateway.example.com");
    }
}
