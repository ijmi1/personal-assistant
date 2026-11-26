package assistant.draft;

import assistant.common.Problem;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public record Outbound(List<String> to, List<String> cc, List<String> bcc, String subject, String body,
                       List<Attachment> attachments) {
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public static Outbound edit(Edit content, List<Attachment> previous) {
        if (content == null || content.subject() == null || content.body() == null) {
            throw Problem.invalid("请提供完整的草稿内容");
        }
        if (content.subject().length() > 998 || hasLineBreak(content.subject()) || content.body().length() > 100_000
                || content.body().contains("\u0000")) {
            throw Problem.invalid("主题或正文超出长度限制或含有无效字符");
        }
        return new Outbound(addresses(content.to()), addresses(content.cc()), addresses(content.bcc()), content.subject(),
                content.body(), attachments(content.attachments(), previous));
    }

    public View view() {
        return new View(to, cc, bcc, subject, body, attachments.stream()
                .map(item -> new AttachmentView(item.name(), item.size())).toList());
    }

    public void requireComplete() {
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            throw Problem.invalid("确认前请填写收件人");
        }
    }

    private static List<String> addresses(List<String> items) {
        if (items == null) {
            return List.of();
        }
        if (items.size() > 50) {
            throw Problem.invalid("单个地址栏最多允许 50 个地址");
        }
        return items.stream().map(Outbound::address).toList();
    }

    public static String address(String value) {
        if (value == null || value.isBlank() || hasLineBreak(value) || value.length() > 320) {
            throw Problem.invalid("邮件地址格式不正确");
        }
        try {
            var parsed = new InternetAddress(value.strip(), true);
            parsed.validate();
            if (parsed.isGroup() || parsed.getAddress().lastIndexOf('@') < 1) {
                throw new AddressException();
            }
            return parsed.getAddress();
        } catch (AddressException e) {
            throw Problem.invalid("邮件地址格式不正确");
        }
    }

    private static List<Attachment> attachments(List<AttachmentEdit> items, List<Attachment> previous) {
        if (items == null) {
            return List.of();
        }
        if (items.size() > 10) {
            throw Problem.invalid("最多可添加 10 个附件");
        }
        List<Attachment> results = new ArrayList<>();
        long total = 0;
        for (var item : items) {
            Attachment attachment = attachment(item, previous);
            total += attachment.size();
            if (total > MAX_BYTES) {
                throw Problem.invalid("附件合计不能超过 8 MiB");
            }
            results.add(attachment);
        }
        return List.copyOf(results);
    }

    private static Attachment attachment(AttachmentEdit item, List<Attachment> previous) {
        if (item == null || item.name() == null || item.name().isBlank() || item.name().length() > 240
                || hasLineBreak(item.name()) || item.name().contains("/") || item.name().contains("\\")) {
            throw Problem.invalid("附件名称无效");
        }
        if (item.index() != null) {
            if (item.base64() != null || item.index() < 0 || item.index() >= previous.size()
                    || !previous.get(item.index()).name().equals(item.name())) {
                throw Problem.invalid("无法保留该附件，请重新选择");
            }
            return previous.get(item.index());
        }
        if (item.base64() == null || item.base64().length() > ((MAX_BYTES + 2) / 3) * 4) {
            throw Problem.invalid("附件缺少完整内容或超过 8 MiB");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(item.base64());
            return new Attachment(item.name(), Base64.getEncoder().encodeToString(bytes), bytes.length);
        } catch (IllegalArgumentException e) {
            throw Problem.invalid("附件无法读取，请重新选择");
        }
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
    }

    public record Edit(List<String> to, List<String> cc, List<String> bcc, String subject, String body,
                       List<AttachmentEdit> attachments) {}
    public record AttachmentEdit(String name, String base64, Integer index) {}
    public record Attachment(String name, String base64, int size) {
        public byte[] bytes() { return Base64.getDecoder().decode(base64); }
    }
    public record View(List<String> to, List<String> cc, List<String> bcc, String subject, String body,
                       List<AttachmentView> attachments) {}
    public record AttachmentView(String name, int size) {}
}
