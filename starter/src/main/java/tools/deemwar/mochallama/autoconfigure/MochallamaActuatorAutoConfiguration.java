package tools.deemwar.mochallama.autoconfigure;

import tools.deemwar.mochallama.actuate.MochallamaHealthIndicator;
import tools.deemwar.mochallama.service.LlamaCppService;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires the mochallama {@link HealthIndicator} only when Spring Boot Actuator
 * is on the classpath. The starter brings Actuator transitively, but gating on
 * {@code HealthIndicator} keeps this resilient if a consumer excludes it.
 */
@AutoConfiguration(after = MochallamaAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
public class MochallamaActuatorAutoConfiguration {

    @Bean
    @ConditionalOnBean(LlamaCppService.class)
    @ConditionalOnMissingBean(name = "mochallamaHealthIndicator")
    public MochallamaHealthIndicator mochallamaHealthIndicator(LlamaCppService llamaCppService) {
        return new MochallamaHealthIndicator(llamaCppService);
    }
}
