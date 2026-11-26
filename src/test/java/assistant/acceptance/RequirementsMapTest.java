package assistant.acceptance;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementsMapTest {
    @Test
    void entriesAndAcceptanceMethodsExist() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(options));
        var mapping = (Map<?, ?>) yaml.load(Files.readString(root.resolve("docs/requirements-map.yaml")));
        var entries = (Map<?, ?>) mapping.get("requirements");
        assertThat(Set.copyOf(entries.keySet())).isEqualTo(Set.of("K01", "K02", "K03", "K04", "E01", "E02", "E03", "M01"));
        Set<Path> paths = new HashSet<>();
        for (Object entry : entries.values()) {
            var target = (Map<?, ?>) entry;
            var method = target.get("entry").toString().split("#");
            Path file = root.resolve(target.get("file").toString()).normalize();
            assertThat(file).isEqualTo(root.resolve("src/main/java/" + method[0].replace('.', '/') + ".java")).exists();
            assertThat(paths.add(file)).as("duplicate path " + file).isTrue();
            assertThat(Files.readString(file)).containsPattern(Pattern.compile(
                    "/\\*\\*(?:(?!\\*/)[\\s\\S])*docs/api/[^\\s]+(?:(?!\\*/)[\\s\\S])*\\*/\\s+public [^\\n]+\\b"
                            + Pattern.quote(method[1]) + "\\("));
            resolveMethod(method, false);
            resolveMethod(target.get("test").toString().split("#"), true);
        }
        var scenarios = (Map<?, ?>) mapping.get("scenarios");
        assertThat(scenarios.keySet()).hasSize(1);
        resolveMethod(((Map<?, ?>) scenarios.get("C01")).get("test").toString().split("#"), true);
    }

    private static void resolveMethod(String[] reference, boolean test) throws ClassNotFoundException {
        assertThat(Arrays.asList(Class.forName(reference[0]).getDeclaredMethods()))
                .anyMatch(method -> method.getName().equals(reference[1]) && (!test || method.isAnnotationPresent(Test.class)));
    }
}
