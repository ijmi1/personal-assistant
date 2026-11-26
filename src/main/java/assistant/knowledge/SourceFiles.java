package assistant.knowledge;

import assistant.common.Problem;
import assistant.common.Store;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class SourceFiles {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private final String directory;
    private final Parser markdown = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    private final TextContentRenderer plain = TextContentRenderer.builder().build();

    public SourceFiles(@Value("${assistant.documents}") String directory) { this.directory = directory; }

    public List<Discovered> scan() {
        List<Discovered> results = new ArrayList<>();
        try {
            Path root = root();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    String kind = kind(path);
                    if (attrs.isRegularFile() && kind != null) {
                        String relative = root.relativize(path).toString();
                        try {
                            Snapshot file = read(relative);
                            results.add(new Discovered(relative, kind, file.version(), null));
                        } catch (IOException | Problem e) {
                            results.add(new Discovered(relative, kind, null, "原文无法读取"));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException e) {
            throw Problem.unavailable("资料目录不可达或扫描未完成");
        }
        return results;
    }

    /** Reads and verifies a repository-relative document without following paths outside the configured root. */
    public Snapshot read(String relative) throws IOException {
        Path root = root();
        Path path = root.resolve(relative).normalize().toRealPath();
        if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw Problem.missing("原文已删除或不在资料目录内");
        }
        try (var file = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = file.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("资料超过 64 MiB");
            }
            return new Snapshot(bytes, Store.hash(bytes), kind(path));
        }
    }

    public List<Unit> sections(Snapshot snapshot) throws IOException {
        return "PDF".equals(snapshot.kind()) ? pdf(snapshot.bytes()) : markdown(snapshot.bytes());
    }

    private Path root() throws IOException {
        if (directory.isBlank()) {
            throw new IOException("资料目录未配置");
        }
        Path path = Path.of(directory).toRealPath();
        if (!Files.isDirectory(path)) {
            throw new IOException("资料目录不可用");
        }
        return path;
    }

    private static String kind(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return "PDF";
        }
        return name.endsWith(".md") || name.endsWith(".markdown") ? "MARKDOWN" : null;
    }

    private List<Unit> markdown(byte[] bytes) throws IOException {
        String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (source.startsWith("\uFEFF")) {
            source = source.substring(1);
        }
        String[] lines = source.split("\\R", -1);
        String[] headings = new String[6];
        List<Unit> units = new ArrayList<>();
        for (Node node = markdown.parse(source).getFirstChild(); node != null; node = node.getNext()) {
            var spans = node.getSourceSpans();
            if (spans.isEmpty()) {
                continue;
            }
            int first = spans.getFirst().getLineIndex();
            int end = spans.getLast().getLineIndex() + 1;
            String text = normalize(plain.render(node));
            if (node instanceof Heading heading) {
                headings[heading.getLevel() - 1] = text;
                Arrays.fill(headings, heading.getLevel(), headings.length, null);
            }
            Anchor anchor = new Anchor(null, Arrays.stream(headings).filter(Objects::nonNull).toList(), units.size() + 1);
            units.add(new Unit(anchor, text, String.join("\n", Arrays.copyOfRange(lines, first, end))));
        }
        if (units.isEmpty()) {
            units.add(new Unit(new Anchor(null, List.of(), 1), "", source));
        }
        return units;
    }

    private List<Unit> pdf(byte[] bytes) throws IOException {
        try (var pdf = Loader.loadPDF(bytes)) {
            String[] texts = new String[pdf.getNumberOfPages()];
            StringWriter writer = new StringWriter();
            PDFTextStripper stripper = new PDFTextStripper() {
                private int start;

                @Override protected void startPage(PDPage page) {
                    start = writer.getBuffer().length();
                }

                @Override protected void endPage(PDPage page) {
                    texts[getCurrentPageNo() - 1] = normalize(writer.getBuffer().substring(start));
                    writer.getBuffer().setLength(0);
                }
            };
            stripper.setSortByPosition(true);
            stripper.writeText(pdf, writer);
            List<Unit> units = new ArrayList<>(texts.length);
            for (int i = 0; i < texts.length; i++) {
                units.add(new Unit(new Anchor(i + 1, null, null), Objects.requireNonNullElse(texts[i], ""), ""));
            }
            return units;
        }
    }

    public static String normalize(String text) {
        return text.replace("\u0000", "").replaceAll("(?U)\\s+", " ").strip();
    }

    public record Snapshot(byte[] bytes, String version, String kind) {}
    public record Discovered(String path, String kind, String version, String reason) {}
    public record Anchor(Integer page, List<String> heading, Integer paragraph) {}
    public record Unit(Anchor anchor, String text, String markdown) {}
}
