package io.deadline4j.it.webflux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration.class
})
class WebFluxTestApp {
    public static void main(String[] args) {
        SpringApplication.run(WebFluxTestApp.class, args);
    }
}
