package assistant.draft;

import assistant.common.Problem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundTest {
    @Test void keepsAttachmentBytesAndReturnsOnlyMetadataInPreview() {
        var uploaded = Outbound.edit(edit("Hello", new Outbound.AttachmentEdit("note.txt", "AQID", null)), List.of());
        var retained = Outbound.edit(edit("Updated", new Outbound.AttachmentEdit("note.txt", null, 0)), uploaded.attachments());

        assertThat(retained.attachments().getFirst().bytes()).containsExactly(1, 2, 3);
        assertThat(retained.view().attachments()).containsExactly(new Outbound.AttachmentView("note.txt", 3));
        assertThat(retained.view().to()).containsExactly("student@example.test");
    }

    @Test void rejectsMalformedHeadersAndAttachmentReferences() {
        assertThatThrownBy(() -> Outbound.address("student@example.test\r\n"))
                .isInstanceOf(Problem.class);
        assertThatThrownBy(() -> Outbound.edit(edit("Subject", new Outbound.AttachmentEdit("other.txt", null, 0)),
                List.of(new Outbound.Attachment("note.txt", "AQID", 3)))).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> Outbound.edit(edit("Subject\n", new Outbound.AttachmentEdit("note.txt", "AQID", null)),
                List.of())).isInstanceOf(Problem.class);
    }

    private static Outbound.Edit edit(String subject, Outbound.AttachmentEdit attachment) {
        return new Outbound.Edit(List.of("Student <student@example.test>"), List.of(), List.of(), subject, "Body", List.of(attachment));
    }
}
