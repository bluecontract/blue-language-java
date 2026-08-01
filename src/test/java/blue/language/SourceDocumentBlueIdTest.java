package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceDocumentBlueIdTest {

    @Test
    void shouldCalculateEquivalentSourceDocumentBlueIdForSourceTypedIntegerValueOne() {
        // given
        Blue blue = new Blue();
        Node source = YAML_MAPPER.readValue("type: Integer\nvalue: 1", Node.class);
        Node canonical = YAML_MAPPER.readValue(
                "type:\n" +
                        "  blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                        "value: 1", Node.class);

        // when
        String sourceBlueId = blue.calculateSourceDocumentBlueId(source);
        String canonicalBlueId = blue.calculateSourceDocumentBlueId(canonical);

        // then
        assertEquals(canonicalBlueId, sourceBlueId);
    }

    @Test
    void shouldCanonicalizeSourceTypedIntegerToIntegerBlueId() {
        // given
        Blue blue = new Blue();
        Node source = YAML_MAPPER.readValue("type: Integer\nvalue: 1", Node.class);

        // when
        Node canonical = blue.canonicalize(source);

        // then
        assertEquals(INTEGER_TYPE_BLUE_ID, canonical.getType().getBlueId());
        assertEquals(BigInteger.ONE, canonical.getValue());
    }

    @Test
    void shouldRejectSourceTypedIntegerDuringDirectBlueIdCalculation() {
        // given
        Node source = YAML_MAPPER.readValue("type: Integer\nvalue: 1", Node.class);

        // when
        Throwable failure = captureFailure(() -> BlueIdCalculator.calculateBlueId(source));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldAcceptCanonicalIntegerDuringDirectBlueIdCalculation() {
        // given
        Blue blue = new Blue();
        Node canonical = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                "value: 1", Node.class);

        // when
        String directBlueId = BlueIdCalculator.calculateBlueId(canonical);
        String facadeBlueId = blue.calculateBlueId(canonical);

        // then
        assertEquals(directBlueId, facadeBlueId);
    }

    @Test
    void shouldRemoveRedundantInheritedOverridesBeforeSourceDocumentIdentity() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product Type\n" +
                "x: 1\n" +
                "label: inherited");
        String productTypeBlueId = nodeProvider.getBlueIdByName("Product Type");

        Blue blue = new Blue(nodeProvider);
        Node noisy = YAML_MAPPER.readValue(
                "name: Product Instance\n" +
                "type:\n" +
                "  blueId: " + productTypeBlueId + "\n" +
                "x: 1\n" +
                "label: inherited\n" +
                "y: 2", Node.class);
        Node minimal = YAML_MAPPER.readValue(
                "name: Product Instance\n" +
                "type:\n" +
                "  blueId: " + productTypeBlueId + "\n" +
                "y: 2", Node.class);

        // when
        Node canonical = blue.canonicalize(noisy);
        String minimalBlueId = blue.calculateSourceDocumentBlueId(minimal);
        String noisyBlueId = blue.calculateSourceDocumentBlueId(noisy);
        String canonicalBlueId = BlueIdCalculator.calculateBlueId(canonical);

        // then
        assertEquals(productTypeBlueId, canonical.getType().getBlueId());
        assertFalse(canonical.getProperties().containsKey("x"));
        assertFalse(canonical.getProperties().containsKey("label"));
        assertEquals(minimalBlueId, noisyBlueId);
        assertEquals(canonicalBlueId, noisyBlueId);
    }

    @Test
    void shouldResolveTypesWhenCalculatingSourceDocumentBlueId() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product Type\n" +
                "inherited: value");
        String productTypeBlueId = nodeProvider.getBlueIdByName("Product Type");

        Blue blue = new Blue(nodeProvider);
        Node source = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + productTypeBlueId + "\n" +
                "inherited: value\n" +
                "own: value", Node.class);

        // when
        Node canonical = blue.canonicalize(source);
        String canonicalBlueId = BlueIdCalculator.calculateBlueId(canonical);
        String sourceDocumentBlueId = blue.calculateSourceDocumentBlueId(source);

        // then
        assertFalse(canonical.getProperties().containsKey("inherited"));
        assertEquals(canonicalBlueId, sourceDocumentBlueId);
    }

    @Test
    void shouldPreprocessRootBlueWhenCalculatingSourceDocumentBlueId() {
        // given
        Blue blue = new Blue();
        Node aliased = YAML_MAPPER.readValue(
                "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "type: Person\n" +
                "value: hello", Node.class);
        Node direct = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "value: hello", Node.class);

        // when
        Node canonical = blue.canonicalize(aliased);
        String directBlueId = blue.calculateSourceDocumentBlueId(direct);
        String aliasedBlueId = blue.calculateSourceDocumentBlueId(aliased);

        // then
        assertNull(canonical.getBlue());
        assertEquals(directBlueId, aliasedBlueId);
    }

    @Test
    void shouldRejectInvalidProviderContentWhenCalculatingSourceDocumentBlueId() {
        // given
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("expected"));
        Blue blue = new Blue(blueId -> Collections.singletonList(new Node().value("actual")));
        Node source = new Node().type(new Node().blueId(requestedBlueId)).value("x");

        // when
        Throwable failure = captureFailure(() -> blue.calculateSourceDocumentBlueId(source));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldRejectUnresolvableProviderReferencesWhenCalculatingSourceDocumentBlueId() {
        // given
        String missingBlueId = BlueIdCalculator.calculateBlueId(new Node().value("missing"));
        Blue blue = new Blue(blueId -> null);
        Node source = new Node().type(new Node().blueId(missingBlueId)).value("x");

        // when
        Throwable failure = captureFailure(() -> blue.calculateSourceDocumentBlueId(source));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldExcludePreviousAndPositionControlsFromSemanticCanonicalOverlay() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Append Type\n" +
                "type: List\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - value: A");
        String typeBlueId = nodeProvider.getBlueIdByName("Append Type");
        String previousBlueId = BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(
                "items:\n" +
                "  - value: A", Node.class).getItems());
        Blue blue = new Blue(nodeProvider);
        Node source = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + typeBlueId + "\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + previousBlueId + "\n" +
                "  - value: B", Node.class);

        // when
        Node canonical = blue.canonicalize(source);
        String canonicalBlueId = BlueIdCalculator.calculateBlueId(canonical);
        String sourceDocumentBlueId = blue.calculateSourceDocumentBlueId(source);

        // then
        assertNoPreviousOrPos(canonical);
        assertEquals(canonicalBlueId, sourceDocumentBlueId);
    }

    @Test
    void shouldCanonicalizeContractsAsReservedField() {
        // given
        Blue blue = new Blue();
        Node source = YAML_MAPPER.readValue(
                "value: x\n" +
                "contracts:\n" +
                "  audit:\n" +
                "    enabled: true", Node.class);

        // when
        Node canonical = blue.canonicalize(source);
        String canonicalBlueId = BlueIdCalculator.calculateBlueId(canonical);
        String sourceDocumentBlueId = blue.calculateSourceDocumentBlueId(source);

        // then
        assertEquals("x", canonical.getValue());
        assertEquals(Boolean.TRUE, canonical.get("/contracts/audit/enabled/value"));
        assertFalse(canonical.getProperties() != null && canonical.getProperties().containsKey("contracts"));
        assertEquals(canonicalBlueId, sourceDocumentBlueId);
    }

    private void assertNoPreviousOrPos(Node node) {
        if (node == null) {
            return;
        }
        assertNull(node.getPreviousBlueId());
        assertNull(node.getPosition());
        assertNull(node.getBlue());
        assertNoPreviousOrPos(node.getType());
        assertNoPreviousOrPos(node.getItemType());
        assertNoPreviousOrPos(node.getKeyType());
        assertNoPreviousOrPos(node.getValueType());
        assertNoPreviousOrPos(node.getContracts());
        if (node.getItems() != null) {
            node.getItems().forEach(this::assertNoPreviousOrPos);
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(this::assertNoPreviousOrPos);
        }
    }
}
