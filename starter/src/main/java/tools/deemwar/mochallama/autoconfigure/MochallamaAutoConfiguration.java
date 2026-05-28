package tools.deemwar.mochallama.autoconfigure;

import tools.deemwar.mochallama.MochallamaClient;
import tools.deemwar.mochallama.service.LlamaCppService;
import tools.deemwar.mochallama.web.ChatCompletionsController;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Auto-configuration for the mochallama Spring Boot starter.
 *
 * <p>Declares the {@link LlamaCppService} (exposed as {@link MochallamaClient})
 * and — only when Spring Web is on the classpath and the OpenAI endpoint is
 * enabled — the OpenAI-compatible REST controller. This module carries NO
 * Spring AI dependency, so it stays usable regardless of the consumer's Spring
 * AI version (or absence thereof).
 */
@AutoConfiguration
@EnableConfigurationProperties(MochallamaProperties.class)
public class MochallamaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MochallamaClient.class)
    public LlamaCppService llamaCppService(MeterRegistry meterRegistry, MochallamaProperties properties) {
        return new LlamaCppService(meterRegistry, properties);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnProperty(prefix = "mochallama.openai-endpoint", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(ChatCompletionsController.class)
    public ChatCompletionsController chatCompletionsController(
            LlamaCppService llamaCppService, ExecutorService mochallamaStreamExecutor) {
        return new ChatCompletionsController(llamaCppService, mochallamaStreamExecutor);
    }

    /**
     * Daemon pool for off-servlet-thread SSE streaming. The bridge call is
     * blocking and {@code synchronized} in the service, so streaming must run on
     * a worker thread to free the servlet container thread. Bounded by the
     * service's own per-call serialisation.
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnMissingBean(name = "mochallamaStreamExecutor")
    public ExecutorService mochallamaStreamExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mochallama-stream");
            t.setDaemon(true);
            return t;
        });
    }
}
