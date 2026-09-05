package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Independent public Java execution; consumed by the packed TypeScript checks. */
final class CrossLanguageIdentityFixtureTest {
    @Test
    void executePublicSourceIdentityFixtures() throws Exception {
        List<Map<String, String>> fixtures;
        try (InputStream input = getClass().getResourceAsStream("/stabilization/identity-inputs.json")) {
            fixtures = UncheckedObjectMapper.JSON_MAPPER.readValue(input,
                    new TypeReference<List<Map<String, String>>>() {});
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            for (Map<String, String> fixture : fixtures) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", fixture.get("name"));
                result.put("source", fixture.get("source"));
                try {
                    Node parsed = language.codec().parseSource(fixture.get("source"), BlueFormat.YAML);
                    Node canonical = language.identity().canonicalIdentityInput(parsed);
                    String id = language.identity().sourceDocumentBlueId(parsed);
                    assertEquals(id, language.identity().directBlueId(canonical));
                    result.put("accepted", true);
                    result.put("blueId", id);
                    result.put("canonical", UncheckedObjectMapper.JSON_MAPPER.readValue(
                            language.codec().write(canonical, BlueFormat.JSON), Object.class));
                } catch (IllegalArgumentException exception) {
                    result.put("accepted", false);
                    result.put("error", exception.getMessage());
                }
                assertEquals(!fixture.get("name").startsWith("invalid-"), result.get("accepted"), fixture.get("name"));
                results.add(result);
            }
        }
        assertEquals(34, results.size());
        Path receipt = Paths.get("build/stabilization/java-identity-results.json");
        Files.createDirectories(receipt.getParent());
        UncheckedObjectMapper.JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(receipt.toFile(), results);
    }
}
