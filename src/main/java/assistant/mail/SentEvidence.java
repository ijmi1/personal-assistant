package assistant.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.search.HeaderTerm;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SentEvidence {
    private final MailboxConnection connection;

    SentEvidence(MailboxConnection connection) { this.connection = connection; }

    /** Returns exact Message-IDs found in the provider's configured Sent folder. */
    public Set<String> acknowledged(List<String> ids) {
        if (ids.isEmpty() || !connection.configured() || connection.sentFolder().isBlank()) {
            return Set.of();
        }
        try {
            return connection.read(connection.sentFolder(), folder -> {
                Set<String> found = new HashSet<>();
                for (String id : ids) {
                    for (var message : folder.search(new HeaderTerm("Message-ID", id))) {
                        if (id.equals(MailText.headerId(message))) {
                            found.add(id);
                        }
                    }
                }
                return found;
            });
        } catch (MessagingException e) {
            return Set.of();
        }
    }
}
