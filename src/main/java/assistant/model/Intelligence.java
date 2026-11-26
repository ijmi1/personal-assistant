package assistant.model;

import java.util.List;

public interface Intelligence {
    List<float[]> embed(List<String> texts);
    String complete(String instruction, String input);

    static String json(String response) {
        return response.strip().replaceFirst("^\\x60{3}(?:json)?\\s*", "").replaceFirst("\\s*\\x60{3}$", "");
    }
}
