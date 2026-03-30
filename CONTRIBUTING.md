# Contributing to deadline4j

Thank you for your interest in contributing to deadline4j! This guide will help you get started.

## Development Setup

### Prerequisites

- JDK 17 or later
- Maven 3.8+

### Building

```bash
mvn clean verify
```

This compiles all modules, runs unit and integration tests, and generates coverage reports.

### Project Structure

| Module | Description |
|--------|-------------|
| `deadline4j-core` | Framework-agnostic core (Java 11+) |
| `deadline4j-spring-webmvc-jakarta` | Spring MVC servlet filter and RestTemplate interceptor |
| `deadline4j-spring-webflux` | WebFlux filter and WebClient filter |
| `deadline4j-spring-cloud-openfeign` | Feign request interceptor |
| `deadline4j-spring-boot-starter` | Auto-configuration for Spring Boot |
| `deadline4j-micrometer` | Micrometer metrics integration |
| `deadline4j-opentelemetry` | OpenTelemetry span attributes |
| `deadline4j-timer-netty` | Netty HashedWheelTimer adapter |
| `deadline4j-timer-agrona` | Agrona DeadlineWheelTimer adapter |
| `deadline4j-bom` | Bill of Materials for version alignment |
| `deadline4j-integration-tests` | End-to-end integration tests |

## Making Changes

1. Fork the repository and create a branch from `main`.
2. Make your changes, following the existing code style.
3. Add tests for any new functionality.
4. Ensure all tests pass: `mvn clean verify`.
5. Submit a pull request.

## Pull Requests

- Keep PRs focused — one concern per PR.
- Include a clear description of what the change does and why.
- Reference any related issues.
- All CI checks must pass before merging.

## Reporting Issues

- Use [GitHub Issues](https://github.com/maneeshchaturvedi/deadline4j/issues) to report bugs or request features.
- Include reproduction steps, expected vs actual behavior, and your environment (JDK version, Spring Boot version).

## Code Style

- Follow the existing code conventions in the project.
- Public APIs should include Javadoc.
- Prefer clarity over cleverness.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
