package assistant.knowledge;

import assistant.task.Jobs;
import org.springframework.stereotype.Service;

@Service
public class SyncDocuments {
    private final Jobs jobs;

    public SyncDocuments(Jobs jobs) { this.jobs = jobs; }

    /** Queues a scan and reuses active work for the configured directory. docs/api/knowledge.md#k01 */
    public Jobs.Accepted sync(String key) {
        return jobs.submit(new Jobs.JobSpec("INDEX", "documents", "directory"), key);
    }
}
