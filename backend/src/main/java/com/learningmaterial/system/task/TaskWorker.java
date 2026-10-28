package com.learningmaterial.system.task;

import com.learningmaterial.system.dto.TaskLease;
import com.learningmaterial.system.service.TaskLeaseService;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@EnableScheduling
class TaskWorkerConfig {
    @Bean
    @ConditionalOnMissingBean(TaskProcessor.class)
    @ConditionalOnProperty(name = "lms.task.worker.enabled", havingValue = "true")
    TaskProcessor pendingProcessor() {
        return lease -> { throw new TaskExecutionException("PROCESSOR_NOT_CONFIGURED"); };
    }
}

@Component
@ConditionalOnProperty(name = "lms.task.worker.enabled", havingValue = "true")
public class TaskWorker {
    private final TaskLeaseService leases;
    private final TaskProcessor processor;
    private final Semaphore slots = new Semaphore(2);
    private final Set<TaskLease> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    public TaskWorker(TaskLeaseService leases, TaskProcessor processor) {
        this.leases = leases;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${lms.task.worker.poll-ms:1000}")
    public void poll() {
        while (slots.tryAcquire()) {
            TaskLease lease;
            try { lease = leases.claim(); }
            catch (RuntimeException ex) { slots.release(); throw ex; }
            if (lease == null) { slots.release(); break; }
            active.add(lease);
            try { pool.execute(() -> run(lease)); }
            catch (RuntimeException ex) { active.remove(lease); slots.release(); throw ex; }
        }
    }

    @Scheduled(fixedDelayString = "${lms.task.worker.heartbeat-ms:10000}")
    public void heartbeat() {
        for (TaskLease lease : active) {
            try { leases.heartbeat(lease); }
            catch (RuntimeException ignored) { /* The lease expires and another worker can recover it. */ }
        }
    }

    private void run(TaskLease lease) {
        try {
            if (!leases.isCurrent(lease)) return;
            processor.execute(lease);
            if (leases.isCurrent(lease)) leases.fail(lease, "PROCESSOR_INCOMPLETE");
        } catch (TaskExecutionException ex) {
            leases.fail(lease, ex.errorCode());
        } catch (RuntimeException ex) {
            leases.fail(lease, "TASK_EXECUTION_FAILED");
        } finally {
            active.remove(lease);
            slots.release();
        }
    }

    @PreDestroy
    void stop() { pool.shutdownNow(); }
}
