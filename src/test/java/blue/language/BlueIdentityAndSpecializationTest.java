package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.Properties.BLUE_DIRECTIVE_IMPORTS;
import static blue.language.utils.Properties.LIST_CONTROL_REPLACE;
import static blue.language.utils.Properties.OBJECT_BLUE;
import static blue.language.utils.Properties.OBJECT_BLUE_ID;
import static blue.language.utils.Properties.OBJECT_TYPE;
import static blue.language.utils.Properties.OBJECT_VALUE;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the final one-BlueId terminology and the distinction between graph
 * expansion and type specialization.
 */
final class BlueIdentityAndSpecializationTest {

    @Test
    void shouldCalculateSourceDocumentBlueIdFromCanonicalIdentityInput() {
        // given
        Blue blue = new Blue();
        Node source = new Node()
                .type(new Node().blueId(TEXT_TYPE_BLUE_ID))
                .value("hello");

        // when
        Node canonicalIdentityInput = blue.canonicalize(source);
        String sourceDocumentBlueId =
                blue.calculateSourceDocumentBlueId(source);

        // then
        assertEquals(
                blue.calculateBlueId(canonicalIdentityInput),
                sourceDocumentBlueId);
    }

    @Test
    void shouldCalculateSourceDocumentBlueIdWithoutCallingMinimize() {
        // given
        Node canonicalIdentityInput = new Node().value("canonical");
        Blue blue = new Blue() {
            @Override
            public Node canonicalize(Node node) {
                return canonicalIdentityInput.clone();
            }

            @Override
            public Node minimize(Node node) {
                throw new AssertionError(
                        "Source Document identity must not call minimize");
            }
        };

        // when
        String actual = blue.calculateSourceDocumentBlueId(
                new Node().value("source"));

        // then
        assertEquals(BlueIdCalculator.calculateBlueId(
                canonicalIdentityInput), actual);
    }

    @Test
    void shouldRejectSourceOnlyConstructsWhenCalculatingObjectBlueIdDirectly() {
        // given
        Map<String, Object> importedType = new LinkedHashMap<>();
        importedType.put(OBJECT_BLUE_ID, TEXT_TYPE_BLUE_ID);
        Map<String, Object> imports = new LinkedHashMap<>();
        imports.put("TextAlias", importedType);
        Map<String, Object> directive = new LinkedHashMap<>();
        directive.put(BLUE_DIRECTIVE_IMPORTS, imports);
        Map<String, Object> sourceObject = new LinkedHashMap<>();
        sourceObject.put(OBJECT_BLUE, directive);
        sourceObject.put(OBJECT_TYPE, "TextAlias");
        sourceObject.put(OBJECT_VALUE, "hello");
        Blue blue = new Blue();

        // when
        Throwable directHashFailure = captureFailure(
                () -> blue.calculateBlueId(sourceObject));

        // then
        assertInstanceOf(IllegalArgumentException.class, directHashFailure);
        assertTrue(directHashFailure.getMessage()
                .contains("preprocessing directive"));
    }

    @Test
    void shouldPreserveMinimizedOverlayIdentityOnlyThroughSourcePipeline() {
        // given
        Blue blue = new Blue();
        Node parent = blue.yamlToNode(
                "type: List\n" +
                "mergePolicy: positional\n" +
                "items:\n" +
                "  - A\n" +
                "  - B");
        Node source = new Node()
                .type(parent)
                .items(Collections.singletonList(
                        new Node()
                                .position(1)
                                .properties(LIST_CONTROL_REPLACE,
                                        new Node().value("C"))));

        // when
        Node minimizedOverlay = blue.minimize(source);
        String sourceDocumentBlueId =
                blue.calculateSourceDocumentBlueId(source);
        String minimizedOverlayBlueId =
                blue.calculateSourceDocumentBlueId(minimizedOverlay);
        Throwable directHashFailure = captureFailure(
                () -> blue.calculateBlueId(minimizedOverlay));

        // then
        assertEquals(sourceDocumentBlueId, minimizedOverlayBlueId);
        assertEquals(Integer.valueOf(1),
                minimizedOverlay.getItems().get(0).getPosition());
        assertInstanceOf(IllegalArgumentException.class, directHashFailure);
    }

    @Test
    void shouldPreserveBlueIdWhenExpandingExactReference() {
        // given
        Node exact = new Node().value("exact content");
        String exactBlueId = BlueIdCalculator.calculateBlueId(exact);
        BasicNodeProvider provider = new BasicNodeProvider(exact);
        Blue blue = new Blue(provider);

        // when
        Node expanded = blue.expand(new Node().blueId(exactBlueId));

        // then
        assertEquals(exactBlueId,
                BlueIdCalculator.calculateBlueId(expanded));
        assertEquals("exact content", expanded.getValue());
    }

    @Test
    void shouldCreateNewNodeWhenSpecializingTypeWithCompatibleOverlay() {
        // given
        Blue blue = new Blue();
        Node type = new Node().blueId(TEXT_TYPE_BLUE_ID);
        Node overlay = new Node().value("hello");

        // when
        Node specialization = blue.specialize(type, overlay);
        String specializedBlueId =
                blue.calculateSourceDocumentBlueId(specialization);

        // then
        assertEquals(TEXT_TYPE_BLUE_ID,
                specialization.getType().getBlueId());
        assertEquals("hello", specialization.getValue());
        assertNotEquals(TEXT_TYPE_BLUE_ID, specializedBlueId);
        assertNull(overlay.getType());
    }
}
