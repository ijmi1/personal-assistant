package assistant.knowledge;

import assistant.common.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
class IndexProfile {
    final String id;
    private final Store db;

    IndexProfile(Store db, @Value("${assistant.embedding-profile}") String explicit,
                 @Value("${spring.ai.openai.base-url}") String endpoint,
                 @Value("${spring.ai.openai.embedding.model}") String model) {
        this.db = db;
        id = Store.hash(db.write(new String[]{endpoint, model, explicit}).getBytes(StandardCharsets.UTF_8));
    }

    String active() {
        return db.one(String.class, "select profile from index_settings where singleton").orElse(null);
    }
}
