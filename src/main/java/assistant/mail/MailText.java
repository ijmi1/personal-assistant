package assistant.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

final class MailText {
    private static final Pattern MESSAGE_ID = Pattern.compile("<[^\\s<>]+>");
    private static final int MAX_BODY = 100_000;

    private MailText() {}

    static String body(Part part) throws MessagingException, IOException {
        return limit(clean(extract(part, 0)));
    }

    private static String extract(Part part, int depth) throws MessagingException, IOException {
        if (depth > 12 || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
            return "";
        }
        if (part.isMimeType("text/plain")) {
            return limit(part.getContent().toString());
        }
        if (part.isMimeType("text/html")) {
            var document = Jsoup.parse(limit(part.getContent().toString()));
            document.select("script, style").remove();
            return document.wholeText();
        }
        if (!(part.isMimeType("multipart/*") && part.getContent() instanceof Multipart parts)) {
            return "";
        }
        if (part.isMimeType("multipart/alternative")) {
            for (int i = 0; i < parts.getCount(); i++) {
                Part candidate = parts.getBodyPart(i);
                if (candidate.isMimeType("text/plain")) {
                    String text = extract(candidate, depth + 1);
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.getCount() && result.length() < MAX_BODY; i++) {
            String child = extract(parts.getBodyPart(i), depth + 1);
            if (child.isBlank()) {
                continue;
            }
            if (part.isMimeType("multipart/alternative")) {
                return child;
            }
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(child);
        }
        return result.toString();
    }

    static String headerId(Message message) throws MessagingException {
        return ids(message.getHeader("Message-ID")).stream().findFirst().orElse(null);
    }

    static List<String> parents(Message message) throws MessagingException {
        var reply = ids(message.getHeader("In-Reply-To"));
        var references = ids(message.getHeader("References"));
        var ordered = new LinkedHashSet<>(reply.reversed());
        ordered.addAll(references.reversed());
        return List.copyOf(ordered);
    }

    private static List<String> ids(String[] headers) {
        List<String> ids = new ArrayList<>();
        if (headers != null) {
            for (String header : headers) {
                MESSAGE_ID.matcher(header).results().map(match -> match.group()).forEach(ids::add);
            }
        }
        return ids;
    }

    static List<String> replyTo(Message message) throws MessagingException {
        var addresses = message.getReplyTo();
        List<String> result = new ArrayList<>();
        if (addresses != null) {
            for (var address : addresses) {
                if (address instanceof InternetAddress internet && !internet.isGroup()) {
                    result.add(clean(internet.getAddress()));
                }
            }
        }
        return result;
    }

    static String sender(Message message) throws MessagingException {
        var from = message.getFrom();
        return from == null || from.length == 0 ? "" : clean(from[0].toString());
    }

    static String clean(String value) { return value == null ? "" : value.replace("\u0000", "").strip(); }

    private static String limit(String text) {
        int end = Math.min(text.length(), MAX_BODY);
        if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }
}
