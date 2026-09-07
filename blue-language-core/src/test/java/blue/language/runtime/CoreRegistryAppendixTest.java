package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

final class CoreRegistryAppendixTest {
    @TestFactory
    Stream<DynamicTest> canonicalAppendixNodesMatchAuthenticatedRegistry() throws Exception {
        String specification;
        try (InputStream input = getClass().getResourceAsStream(
                "/specifications/blue-language-specification-1.0.md")) {
            assertNotNull(input);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, length);
            }
            specification = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        String appendix = specification.substring(specification.indexOf("### A.1 Canonical core type nodes"),
                specification.indexOf("### A.2 Editorial and registry rules"));
        return BlueCoreTypeRegistry.INSTANCE.blueIdsByName().keySet().stream().map(name ->
                DynamicTest.dynamicTest(name, () -> {
                    Matcher example = Pattern.compile("#### " + name + "\\s+```yaml\\n(.*?)```", Pattern.DOTALL)
                            .matcher(appendix);
                    assertTrue(example.find(), "Missing canonical example: " + name);
                    try (BlueLanguage language = BlueLanguage.builder().build()) {
                        Node parsed = language.codec().parseSource(example.group(1), BlueFormat.YAML);
                        Node registry = BlueCoreTypeRegistry.INSTANCE.node(name);
                        assertEquals(registry.getDescription(), parsed.getDescription(),
                                "Identity-bearing descriptions must match after public Source parsing");
                        assertEquals(BlueCoreTypeRegistry.INSTANCE.blueId(name),
                                language.identity().directBlueId(parsed));
                    }
                }));
    }
}
