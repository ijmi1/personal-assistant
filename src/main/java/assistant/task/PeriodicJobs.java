package assistant.task;

import assistant.common.Store;
import assistant.knowledge.SyncDocuments;
import assistant.mail.SyncMailbox;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class PeriodicJobs {
    private final SyncDocuments documents;
    private final SyncMailbox mailbox;

    PeriodicJobs(SyncDocuments documents, SyncMailbox mailbox) {
        this.documents = documents;
        this.mailbox = mailbox;
    }

    @Scheduled(fixedDelayString = "${assistant.schedule.index-ms}", initialDelayString = "${assistant.schedule.index-ms}")
    void index() {
        documents.sync(Store.id());
    }

    @Scheduled(fixedDelayString = "${assistant.schedule.mail-ms}", initialDelayString = "${assistant.schedule.mail-ms}")
    void inbox() {
        if (mailbox.configured()) {
            mailbox.sync(Store.id());
        }
    }
}
