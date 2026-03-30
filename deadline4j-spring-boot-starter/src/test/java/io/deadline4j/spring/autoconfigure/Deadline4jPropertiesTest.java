package io.deadline4j.spring.autoconfigure;

import io.deadline4j.AdaptiveTimeoutConfig;
import io.deadline4j.EnforcementMode;
import io.deadline4j.ServicePriority;
import io.deadline4j.spring.autoconfigure.Deadline4jProperties.AdaptiveDefaults;
import io.deadline4j.spring.autoconfigure.Deadline4jProperties.ServiceProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class Deadline4jPropertiesTest {

    @Test
    void defaultEnforcement_isObserve() {
        Deadline4jProperties props = new Deadline4jProperties();
        assertThat(props.getEnforcement()).isEqualTo(EnforcementMode.OBSERVE);
    }

    @Test
    void defaultDeadline_isNull() {
        Deadline4jProperties props = new Deadline4jProperties();
        assertThat(props.getDefaultDeadline()).isNull();
    }

    @Test
    void defaultServerMaxDeadline_isNull() {
        Deadline4jProperties props = new Deadline4jProperties();
        assertThat(props.getServerMaxDeadline()).isNull();
    }

    @Test
    void defaultAdaptive_hasCorrectDefaults() {
        AdaptiveDefaults adaptive = new Deadline4jProperties().getAdaptive();

        assertThat(adaptive.getPercentile()).isCloseTo(0.99, within(0.001));
        assertThat(adaptive.getMinTimeout()).isEqualTo(Duration.ofMillis(50));
        assertThat(adaptive.getMaxTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(adaptive.getColdStartTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(adaptive.getMinSamples()).isEqualTo(100);
        assertThat(adaptive.getWindowSize()).isEqualTo(Duration.ofSeconds(60));
        assertThat(adaptive.getHeadroomMultiplier()).isCloseTo(1.5, within(0.001));
    }

    @Test
    void defaultServices_isEmpty() {
        Deadline4jProperties props = new Deadline4jProperties();
        assertThat(props.getServices()).isEmpty();
    }

    @Test
    void setters_work() {
        Deadline4jProperties props = new Deadline4jProperties();

        props.setEnforcement(EnforcementMode.ENFORCE);
        props.setDefaultDeadline(Duration.ofSeconds(10));
        props.setServerMaxDeadline(Duration.ofSeconds(60));

        AdaptiveDefaults adaptive = new AdaptiveDefaults();
        adaptive.setPercentile(0.95);
        props.setAdaptive(adaptive);

        Map<String, ServiceProperties> services = new LinkedHashMap<>();
        services.put("test-service", new ServiceProperties());
        props.setServices(services);

        assertThat(props.getEnforcement()).isEqualTo(EnforcementMode.ENFORCE);
        assertThat(props.getDefaultDeadline()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.getServerMaxDeadline()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.getAdaptive().getPercentile()).isCloseTo(0.95, within(0.001));
        assertThat(props.getServices()).containsKey("test-service");
    }

    @Test
    void adaptiveDefaults_toConfig() {
        AdaptiveDefaults defaults = new AdaptiveDefaults();
        defaults.setPercentile(0.95);
        defaults.setMinTimeout(Duration.ofMillis(100));
        defaults.setMaxTimeout(Duration.ofSeconds(10));
        defaults.setColdStartTimeout(Duration.ofSeconds(3));
        defaults.setMinSamples(50);
        defaults.setWindowSize(Duration.ofSeconds(120));
        defaults.setHeadroomMultiplier(2.0);

        AdaptiveTimeoutConfig config = defaults.toConfig();

        assertThat(config.percentile()).isCloseTo(0.95, within(0.001));
        assertThat(config.minTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.maxTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.coldStartTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.minSamples()).isEqualTo(50);
        assertThat(config.windowSize()).isEqualTo(Duration.ofSeconds(120));
        assertThat(config.headroomMultiplier()).isCloseTo(2.0, within(0.001));
    }

    @Test
    void serviceProperties_defaults() {
        ServiceProperties sp = new ServiceProperties();

        assertThat(sp.getPriority()).isEqualTo(ServicePriority.REQUIRED);
        assertThat(sp.getEnforcement()).isNull();
        assertThat(sp.getMinBudgetRequired()).isNull();
        assertThat(sp.getMaxDeadline()).isNull();
        assertThat(sp.getAdaptive()).isNull();
    }

    @Test
    void serviceProperties_setters() {
        ServiceProperties sp = new ServiceProperties();

        sp.setEnforcement(EnforcementMode.ENFORCE);
        sp.setPriority(ServicePriority.OPTIONAL);
        sp.setMinBudgetRequired(Duration.ofMillis(200));
        sp.setMaxDeadline(Duration.ofSeconds(5));

        AdaptiveDefaults adaptive = new AdaptiveDefaults();
        adaptive.setPercentile(0.999);
        sp.setAdaptive(adaptive);

        assertThat(sp.getEnforcement()).isEqualTo(EnforcementMode.ENFORCE);
        assertThat(sp.getPriority()).isEqualTo(ServicePriority.OPTIONAL);
        assertThat(sp.getMinBudgetRequired()).isEqualTo(Duration.ofMillis(200));
        assertThat(sp.getMaxDeadline()).isEqualTo(Duration.ofSeconds(5));
        assertThat(sp.getAdaptive()).isNotNull();
        assertThat(sp.getAdaptive().getPercentile()).isCloseTo(0.999, within(0.0001));
    }
}
