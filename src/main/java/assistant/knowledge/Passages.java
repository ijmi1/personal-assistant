package assistant.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class Passages {
    private static final Pattern WORD = Pattern.compile("[a-z0-9][a-z0-9_]*|\\p{IsHan}+");
    private static final int LENGTH = 640;

    private Passages() {}

    public static List<Passage> split(List<SourceFiles.Unit> units) {
        List<Passage> results = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            var unit = units.get(i);
            String text = unit.text();
            int start = 0;
            while (start < text.length()) {
                int end = safeIndex(text, Math.min(text.length(), start + LENGTH));
                if (end < text.length()) {
                    int space = text.lastIndexOf(' ', end);
                    if (space > start + LENGTH / 2) {
                        end = space;
                    }
                }
                String excerpt = text.substring(start, end).strip();
                if (!excerpt.isBlank()) {
                    results.add(new Passage(unit.anchor(), excerpt, context(units, i, start, end)));
                }
                if (end == text.length()) {
                    break;
                }
                start = safeIndex(text, end - 80);
            }
        }
        return results;
    }

    public static Passage location(List<SourceFiles.Unit> units, int index) {
        var unit = units.get(index);
        int end = safeIndex(unit.text(), Math.min(unit.text().length(), 200));
        return new Passage(unit.anchor(), unit.text().substring(0, end), context(units, index, 0, end));
    }

    public static Match locate(List<SourceFiles.Unit> units, String excerpt, String neighborhood) {
        List<SourceFiles.Anchor> matching = new ArrayList<>();
        List<SourceFiles.Anchor> contextual = new ArrayList<>();
        if (!excerpt.isBlank()) {
            for (int i = 0; i < units.size(); i++) {
                var unit = units.get(i);
                int from = unit.text().indexOf(excerpt);
                while (from >= 0) {
                    matching.add(unit.anchor());
                    if (context(units, i, from, from + excerpt.length()).equals(neighborhood)) {
                        contextual.add(unit.anchor());
                    }
                    from = unit.text().indexOf(excerpt, from + 1);
                }
            }
        }
        if (matching.size() == 1) {
            return new Match(matching.getFirst(), null);
        }
        if (contextual.size() == 1) {
            return new Match(contextual.getFirst(), null);
        }
        return new Match(null, matching.isEmpty() ? "原段在当前版本中未找到" : "当前版本有多处匹配，无法定位");
    }

    public static String terms(String text) {
        Set<String> terms = new TreeSet<>();
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            if (Character.UnicodeScript.of(term.codePointAt(0)) != Character.UnicodeScript.HAN) {
                terms.add(term);
                continue;
            }
            int[] letters = term.codePoints().toArray();
            if (letters.length == 1) {
                terms.add(term);
            }
            for (int i = 0; i + 1 < letters.length; i++) {
                terms.add(new String(letters, i, 2));
            }
        }
        return String.join(" ", terms);
    }

    private static String context(List<SourceFiles.Unit> units, int index, int start, int end) {
        String current = units.get(index).text();
        String before = current.substring(0, start);
        String after = current.substring(end);
        if (index > 0) {
            before = units.get(index - 1).text() + " " + before;
        }
        if (index + 1 < units.size()) {
            after += " " + units.get(index + 1).text();
        }
        int head = safeIndex(before, Math.max(0, before.length() - 60));
        int tail = safeIndex(after, Math.min(after.length(), 60));
        return before.substring(head).strip() + "\n" + after.substring(0, tail).strip();
    }

    private static int safeIndex(String text, int index) {
        return index > 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index)) ? index - 1 : index;
    }

    public record Passage(SourceFiles.Anchor anchor, String excerpt, String context) {}
    public record Match(SourceFiles.Anchor anchor, String error) {}
}
