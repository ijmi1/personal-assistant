package assistant.knowledge;

import assistant.common.Problem;
import assistant.task.Jobs;
import org.springframework.stereotype.Service;

@Service
public class AnswerQuestion {
    private final Jobs jobs;

    public AnswerQuestion(Jobs jobs) { this.jobs = jobs; }

    /** Validates a question and queues a grounded answer. docs/api/knowledge.md#k02 */
    public Jobs.Accepted answer(String question, String key) {
        if (question == null || question.isBlank() || question.length() > 4000) {
            throw Problem.invalid("请输入 1 到 4,000 字的资料问题");
        }
        return jobs.submit(new Jobs.JobSpec("QUESTION", question.strip(), null), key);
    }
}
