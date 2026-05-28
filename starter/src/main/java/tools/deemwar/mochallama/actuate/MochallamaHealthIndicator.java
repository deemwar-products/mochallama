package tools.deemwar.mochallama.actuate;

import tools.deemwar.mochallama.service.LlamaCppService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Reports model load progress as Spring Boot health. The service is UP only
 * once the model is READY; while DOWNLOADING/LOADING — and permanently on
 * FAILED — it is DOWN so liveness/readiness probes and dashboards can gate on
 * actual inference availability.
 */
public class MochallamaHealthIndicator implements HealthIndicator {

    private final LlamaCppService service;

    public MochallamaHealthIndicator(LlamaCppService service) {
        this.service = service;
    }

    @Override
    public Health health() {
        LlamaCppService.LoadState state = service.getState();
        Health.Builder builder = state == LlamaCppService.LoadState.READY
                ? Health.up()
                : Health.down();

        builder.withDetail("model", service.getModelId())
                .withDetail("state", state.name())
                .withDetail("loadDurationMs", service.getLoadDurationMs());

        if (state == LlamaCppService.LoadState.FAILED) {
            builder.withDetail("error", service.getLastError());
        }
        return builder.build();
    }
}
