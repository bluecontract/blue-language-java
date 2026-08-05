package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlueTest {

    private static final int MAXIMUM_PUBLIC_MEMBERS = 24;

    @Test
    void shouldExposeOnlyTheAuditedConvenienceSurface() {
        // given
        long publicConstructors = java.util.Arrays.stream(
                        Blue.class.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(
                        constructor.getModifiers()))
                .count();
        long publicMethods = java.util.Arrays.stream(
                        Blue.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(
                        method.getModifiers()))
                .count();

        // when
        long publicMembers = publicConstructors + publicMethods;

        // then
        assertEquals(MAXIMUM_PUBLIC_MEMBERS, publicMembers);
        assertTrue(Modifier.isFinal(Blue.class.getModifiers()));
    }

    @Test
    void shouldDelegateTransportAndMappingToFocusedServices() {
        // given
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("message", "hello");

        // when
        try (Blue blue = new Blue()) {
            Node yaml = blue.yamlToNode("message: hello\n");
            Node mapped = blue.objectToNode(value);
            String json = blue.nodeToJson(yaml);
            String normalizedYaml = blue.nodeToYaml(mapped);
            Map<?, ?> restored = blue.nodeToObject(mapped, Map.class);

            // then
            assertEquals("hello", yaml.getProperties()
                    .get("message").getValue());
            assertTrue(json.contains("message"));
            assertTrue(normalizedYaml.contains("message"));
            assertEquals("hello", restored.get("message"));
        }
    }

    @Test
    void shouldDelegateLanguageOperationsThroughOneProvider() {
        // given
        Node providerContent = new Node().value("provided");
        String providerBlueId = DirectBlueIdCalculator.calculateBlueId(
                providerContent);

        // when
        try (Blue blue = new Blue(blueId -> providerBlueId.equals(blueId)
                ? Collections.singletonList(providerContent.clone())
                : null)) {
            Node expanded = blue.expand(new Node().blueId(providerBlueId));
            ResolvedSnapshot snapshot = blue.loadSnapshot(providerBlueId);
            Node source = new Node().properties(
                    "message", new Node().value("hello"));
            Node canonical = blue.canonicalize(source);
            Node resolved = blue.resolve(source);
            Node minimized = blue.minimize(source);
            String directBlueId = blue.calculateBlueId(canonical);
            String sourceBlueId = blue.calculateSourceDocumentBlueId(source);

            // then
            assertEquals("provided", expanded.getValue());
            assertEquals("provided", snapshot.canonicalRoot().getValue());
            assertNotNull(resolved.getProperties().get("message"));
            assertNotNull(minimized.getProperties().get("message"));
            assertEquals(directBlueId, sourceBlueId);
            assertTrue(blue.nodeMatchesType(source, null));
        }
    }

    @Test
    void shouldDelegateContractsAndCloseTheOwnedRuntime() {
        // given
        Blue blue = Blue.withCachePolicy(
                BlueCachePolicy.boundedDefaults());

        // when
        DocumentProcessingResult result = blue.processDocument(
                new Node().value("root"),
                new Node().value("event"));
        blue.close();
        blue.close();

        // then
        assertNotNull(result.status());
        assertNotNull(result.events());
        assertTrue(blue.isClosed());
        assertThrows(IllegalStateException.class,
                () -> blue.resolve(new Node()));
    }
}
