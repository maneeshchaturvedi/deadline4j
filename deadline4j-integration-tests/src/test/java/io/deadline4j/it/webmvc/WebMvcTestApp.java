package io.deadline4j.it.webmvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration.class
})
class WebMvcTestApp {
    public static void main(String[] args) {
        SpringApplication.run(WebMvcTestApp.class, args);
    }
}
