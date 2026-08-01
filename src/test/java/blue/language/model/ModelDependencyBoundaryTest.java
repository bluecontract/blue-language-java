package blue.language.model;

import blue.language.testing.RepositoryLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelDependencyBoundaryTest {

    private static final List<String> FORBIDDEN_DEPENDENCIES = Arrays.asList(
            "blue.language.identity",
            "blue.language.provider",
            "blue.language.mapping",
            "blue.language.processor",
            "blue.language.conformance",
            "blue.language.utils");

    @Test
    void shouldKeepModelSourcesIndependentOfHigherLayers() throws IOException {
        // given
        Path modelSources =
                RepositoryLayout.productionJavaRoot("blue-language-model")
                        .resolve("blue/language/model");
        List<String> violations = new ArrayList<>();

        // when
        try (Stream<Path> files = Files.walk(modelSources)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> findViolations(path, violations));
        }

        // then
        assertEquals(new ArrayList<String>(), violations,
                "The model boundary may depend only on JDK, Jackson, and model-owned packages");
    }

    private static void findViolations(
            Path path, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(
                    path, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                for (String forbidden : FORBIDDEN_DEPENDENCIES) {
                    if (line.startsWith("import " + forbidden)
                            || line.startsWith("import static " + forbidden)) {
                        violations.add(path + ":" + (index + 1)
                                + " -> " + line.trim());
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot inspect model source " + path, exception);
        }
    }
}
