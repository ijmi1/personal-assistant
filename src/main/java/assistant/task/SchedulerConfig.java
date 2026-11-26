package assistant.task;

import assistant.delivery.DeliveryWorker;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

@Configuration
public class SchedulerConfig {
    @Bean
    @Primary
    @DependsOnDatabaseInitialization
    SchedulerClient schedulerClient(DataSource dataSource) {
        return SchedulerClient.Builder.create(new TransactionAwareDataSourceProxy(dataSource), List.of()).build();
    }

    @Bean(destroyMethod = "stop")
    @DependsOnDatabaseInitialization
    Scheduler scheduler(DataSource dataSource, JobRunner runner, DeliveryWorker delivery, TaskTracing tracing) {
        var jobTask = Tasks.oneTime(Jobs.TASK).execute((task, context) ->
                tracing.run(task.getData(), "assistant.job", () -> runner.run(task.getId())));
        var sendTask = Tasks.oneTime(DeliveryWorker.TASK).execute((task, context) ->
                tracing.run(task.getData(), "assistant.delivery", () -> delivery.run(task.getId())));
        return Scheduler.create(dataSource, List.of(jobTask, sendTask))
                .threads(4).pollingInterval(Duration.ofSeconds(1))
                .heartbeatInterval(Duration.ofSeconds(5)).missedHeartbeatsLimit(4)
                .shutdownMaxWait(Duration.ofSeconds(15))
                .build();
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> startScheduler(Scheduler scheduler) {
        return event -> scheduler.start();
    }
}
