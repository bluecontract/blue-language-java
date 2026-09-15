package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceAwareMinimizedOverlayTest {

    @Test
    void rejectsTextToObjectReplacementBeforeMinimization() {
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String holderId = store(language, provider, new Node().name("Holder")
                    .properties("title", source(language, "type: Text\n")));
            String listId = store(language, provider,
                    source(language, "name: Holder List\ntype: List\n")
                            .items(Arrays.asList(new Node().value("prefix"), new Node().value("obsolete"))));
            Node invalid = new Node().type(reference(listId))
                    .items(Collections.singletonList(new Node().position(1)
                            .properties("$replace", new Node().type(reference(holderId))
                                    .properties("title", new Node().value("Coffee")))));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> language.identity().sourceDocumentBlueId(invalid));
            assertTrue(failure.getMessage().contains("payload kinds conflict"), failure::getMessage);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-field", "equal-field", "empty-item", "equal-item"})
    void preservesIdentityWhenAnExplicitCustomValueEqualsItsInheritedBaseline(String scope) {
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node termsType = new Node().name("Terms");
            if (scope.startsWith("equal")) {
                termsType.properties("title", new Node().value("Coffee"));
            }
            String termsTypeId = store(language, provider, termsType);
            Node child = new Node().type(reference(termsTypeId));
            if (scope.startsWith("equal")) {
                child.properties("title", new Node().value("Coffee"));
            }
            Node input;
            if (scope.endsWith("field")) {
                String holderId = store(language, provider, new Node().name("Holder")
                        .properties("terms", new Node().type(reference(termsTypeId))));
                input = new Node().type(reference(holderId)).properties("terms", child);
            } else {
                Node listType = source(language, "name: Terms List\ntype: List\n")
                        .items(Collections.singletonList(new Node().type(reference(termsTypeId))));
                String listId = store(language, provider, listType);
                input = new Node().type(reference(listId))
                        .items(Collections.singletonList(child.position(0)));
            }
            assertColdRoundTrip(language, provider, input, scope);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nested", "contracts", "list", "append", "position", "replace", "reference"})
    void preservesSourceIdentityAcrossNestedAndListOverlayScopes(String scope) {
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String termsTypeId = store(language, provider,
                    source(language, "name: Terms\ntitle:\n  type: Text\n"));
            String holderTypeId = store(language, provider,
                    new Node().name("Holder")
                            .properties("terms", new Node().type(reference(termsTypeId)))
                            .properties("unchanged", new Node().value("inherited payload")));
            Node terms = new Node().type(reference(termsTypeId))
                    .properties("title", new Node().value("Coffee"));
            Node holder = new Node().type(reference(holderTypeId))
                    .properties("terms", terms)
                    .properties("unchanged", new Node().value("inherited payload"));
            Node input;
            switch (scope) {
                case "nested":
                    input = new Node().properties("holder", holder);
                    break;
                case "contracts":
                    input = new Node().contracts(new Node().properties("holder", holder));
                    break;
                case "list":
                    input = new Node().items(Collections.singletonList(holder));
                    break;
                case "reference":
                    input = new Node().type(reference(holderTypeId))
                            .properties("terms", reference(store(language, provider, terms)));
                    break;
                default:
                    Node prefix = new Node().value("prefix");
                    Node inheritedList = source(language, "name: Holder List\ntype: List\n");
                    if ("append".equals(scope)) {
                        inheritedList.items(Collections.singletonList(prefix));
                    } else {
                        Node inheritedSecond = "position".equals(scope)
                                ? new Node().type(reference(holderTypeId))
                                : new Node().properties("obsolete", new Node().value("old field"));
                        inheritedList.items(Arrays.asList(prefix, inheritedSecond));
                    }
                    String listTypeId = store(language, provider, inheritedList);
                    input = new Node().type(reference(listTypeId));
                    if ("append".equals(scope)) {
                        Node exactPrefix = language.preprocessing().preprocess(prefix);
                        provider.addListAndItsItems(Collections.singletonList(exactPrefix));
                        // The prefix list's identity, not the appended holder's
                        // physical Source index, determines resolved index one.
                        String previousId = DirectBlueIdCalculator.calculateBlueId(
                                Collections.singletonList(exactPrefix));
                        input.items(Arrays.asList(new Node().previousBlueId(previousId), holder));
                    } else if ("position".equals(scope)) {
                        input.items(Collections.singletonList(holder.position(1)));
                    } else {
                        input.items(Collections.singletonList(new Node().position(1)
                                .properties("$replace", holder)));
                    }
                    break;
            }

            Node minimized = assertColdRoundTrip(language, provider, input, scope);
            if ("nested".equals(scope)) {
                assertFalse(minimized.getProperties().get("holder").getProperties().containsKey("unchanged"),
                        "Keep minimizing derivable payload; do not return the original Source as a fallback");
            } else if ("replace".equals(scope)) {
                assertTrue(minimized.getItems().stream().anyMatch(item ->
                                Integer.valueOf(1).equals(item.getPosition())
                                        && item.getProperties() != null
                                        && item.getProperties().containsKey("$replace")),
                        "Removing an inherited object field must retain whole-item replacement");
                assertFalse(language.resolution().resolve(input).getItems().get(1)
                        .getProperties().containsKey("obsolete"));
            }
        }
    }

    private static Node assertColdRoundTrip(
            BlueLanguage language, BasicNodeProvider provider, Node input, String scope) {
        Object before = NodeWireForm.get(input);
        String expectedId = language.identity().sourceDocumentBlueId(input);
        Object expectedResolved = NodeWireForm.get(language.resolution().resolve(input));
        Node minimized = language.resolution().minimize(input);
        String wire = language.codec().write(minimized, BlueFormat.JSON);
        assertEquals(before, NodeWireForm.get(input), "Minimization must not mutate Source");
        try (BlueLanguage reader = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node reloaded = reader.codec().parseSource(wire, BlueFormat.JSON);
            assertEquals(expectedId, reader.identity().sourceDocumentBlueId(reloaded), scope);
            assertEquals(expectedResolved, NodeWireForm.get(reader.resolution().resolve(reloaded)), scope);
        }
        return minimized;
    }

    private static String store(BlueLanguage language, BasicNodeProvider provider, Node source) {
        Node canonical = language.identity().canonicalIdentityInput(source);
        provider.addSingleNodes(canonical);
        return language.identity().directBlueId(canonical);
    }

    private static Node source(BlueLanguage language, String yaml) {
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
