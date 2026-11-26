package assistant.mail;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component
class MailboxConnection {
    private final String host;
    private final int port;
    private final boolean tls;
    private final String username;
    private final String password;
    private final String mechanisms;
    private final String sentFolder;

    MailboxConnection(@Value("${assistant.mail.inbox-host}") String host,
                      @Value("${assistant.mail.inbox-port}") int port,
                      @Value("${assistant.mail.inbox-tls}") boolean tls,
                      @Value("${assistant.mail.username}") String username,
                      @Value("${assistant.mail.password}") String password,
                      @Value("${assistant.mail.auth-mechanisms}") String mechanisms,
                      @Value("${assistant.mail.sent-folder}") String sentFolder) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.username = username;
        this.password = password;
        this.mechanisms = mechanisms;
        this.sentFolder = sentFolder;
    }

    boolean configured() { return !host.isBlank() && !username.isBlank() && !password.isBlank(); }
    String account() { return host + ":" + port + "/" + username; }
    String sentFolder() { return sentFolder; }

    <T> T read(String name, FolderReader<T> reader) throws MessagingException {
        String protocol = tls ? "imaps" : "imap";
        String prefix = "mail." + protocol + ".";
        var options = new Properties();
        if (!mechanisms.isBlank()) {
            options.setProperty(prefix + "auth.mechanisms", mechanisms);
        }
        options.setProperty(prefix + "ssl.checkserveridentity", "true");
        options.setProperty(prefix + "connectiontimeout", "10000");
        options.setProperty(prefix + "timeout", "30000");
        options.setProperty(prefix + "writetimeout", "30000");
        try (var store = Session.getInstance(options).getStore(protocol)) {
            store.connect(host, port, username, password);
            try (var folder = (IMAPFolder) store.getFolder(name)) {
                folder.open(Folder.READ_ONLY);
                return reader.read(folder);
            }
        }
    }

    interface FolderReader<T> {
        T read(IMAPFolder folder) throws MessagingException;
    }
}
