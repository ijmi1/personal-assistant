package assistant.task;

import assistant.common.Store;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class QueueMetrics {
    @Bean
    MeterBinder queuedTasks(Store db) {
        return registry -> Gauge.builder("assistant.tasks.queued", db,
                        store -> store.one(Long.class, "select count(*) from tasks where status='QUEUED'").orElseThrow())
                .description("Number of assistant jobs awaiting a worker")
                .register(registry);
    }
}
