package blue.language.conformance;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.merge.ResolvedSnapshot;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

import static blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BasicScalarPayloadKindTest {

    @Test
    void shouldCheckScalarPayloadKindWithoutSchema() {
        // Given scalar values whose supplied kind conflicts with the declaration.
        BasicTypesVerifier verifier = new BasicTypesVerifier();
        BasicNodeProvider provider = new BasicNodeProvider();
        Node badInteger = new Node().type(reference(INTEGER_TYPE_BLUE_ID))
                .value("not-an-integer");
        Node badText = new Node().type(reference(TEXT_TYPE_BLUE_ID)).value(42);
        Node badDecimal = new Node().type(reference(INTEGER_TYPE_BLUE_ID))
                .value(new BigDecimal("1.5"));
        Node badDouble = new Node().type(reference(DOUBLE_TYPE_BLUE_ID))
                .value("not-a-double");
        Node badBoolean = new Node().type(reference(BOOLEAN_TYPE_BLUE_ID))
                .value(1);
        // When verifying the completed scalar, then reject every wrong kind.
        for (Node node : Arrays.asList(
                badInteger, badText, badDecimal, badDouble, badBoolean)) {
            assertThrows(IllegalArgumentException.class,
                    () -> verifier.postProcess(node, node, provider, null,
                            CanonicalTypeIdentityLookup.incomplete()));
        }
        for (Node node : Arrays.asList(
                new Node().type(reference(INTEGER_TYPE_BLUE_ID))
                        .value(new BigInteger("123456789012345678901234567890")),
                new Node().type(reference(INTEGER_TYPE_BLUE_ID))
                        .value("123456789012345678901234567890"),
                new Node().type(reference(TEXT_TYPE_BLUE_ID)).value("42"),
                new Node().type(reference(DOUBLE_TYPE_BLUE_ID)).value(1.5),
                new Node().type(reference(BOOLEAN_TYPE_BLUE_ID)).value(true),
                new Node().type(reference(INTEGER_TYPE_BLUE_ID)))) {
            assertDoesNotThrow(() -> verifier.postProcess(
                    node, node, provider, null,
                    CanonicalTypeIdentityLookup.incomplete()));
        }
    }

    @Test
    void shouldRejectWrongKindWithCustomInlineAndReferenceTypes() {
        // Given a custom Integer type with a misleading human-readable name.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider).build()) {
            Node definition = new Node().name("Text")
                    .type(reference(INTEGER_TYPE_BLUE_ID));
            Node stored = language.identity().canonicalIdentityInput(definition);
            String typeId = language.identity().directBlueId(stored);
            provider.addSingleNodes(stored);
            // When resolving either representation, then ancestry controls kind.
            for (Node type : Arrays.asList(stored, reference(typeId))) {
                assertThrows(IllegalArgumentException.class,
                        () -> language.resolution().resolve(new Node()
                                .type(type.clone()).value("not-an-integer")));
                assertDoesNotThrow(() -> language.resolution().resolve(new Node()
                        .type(type.clone()).value(42)));
            }
        }
    }

    @Test
    void shouldApplyNumericBoundsToCanonicalCustomIntegerText() {
        // Given an arbitrary-precision constrained Integer type with no sample.
        BasicNodeProvider provider = new BasicNodeProvider();
        BigInteger maximum = new BigInteger("999999999999999999999999999999");
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider).build()) {
            Node definition = new Node().name("BoundedInteger")
                    .type(reference(INTEGER_TYPE_BLUE_ID))
                    .schema(new Schema()
                            .minimum(new Node().value(BigInteger.ZERO))
                            .maximum(new Node().value(maximum))
                            .multipleOf(new Node().value(BigInteger.valueOf(3))));
            Node canonical = language.identity().canonicalIdentityInput(definition);
            String typeId = language.identity().directBlueId(canonical);
            provider.addSingleNodes(canonical);
            // When a canonical decimal spelling is supplied through either type form.
            for (Node type : Arrays.asList(canonical, reference(typeId))) {
                // Then numeric constraints use the proven Integer payload exactly.
                assertDoesNotThrow(() -> language.resolution().resolve(
                        new Node().type(type.clone()).value(maximum.toString())));
                assertThrows(IllegalArgumentException.class,
                        () -> language.resolution().resolve(new Node().type(type.clone())
                                .value(maximum.add(BigInteger.valueOf(3)).toString())));
                assertThrows(IllegalArgumentException.class,
                        () -> language.resolution().resolve(new Node().type(type.clone())
                                .value(maximum.subtract(BigInteger.ONE).toString())));
            }
        }
    }

    @Test
    void shouldMatchCanonicalCustomIntegerTextThroughNumericPatterns() {
        // Given a custom Integer and a numeric pattern evaluated independently.
        BasicNodeProvider provider = new BasicNodeProvider();
        BigInteger maximum = new BigInteger("999999999999999999999999999999");
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider).build()) {
            Node definition = new Node().name("CustomInteger")
                    .type(reference(INTEGER_TYPE_BLUE_ID));
            Node canonical = language.identity().canonicalIdentityInput(definition);
            String typeId = language.identity().directBlueId(canonical);
            provider.addSingleNodes(canonical);
            Node pattern = new Node().schema(new Schema()
                    .minimum(new Node().value(BigInteger.ZERO))
                    .maximum(new Node().value(maximum))
                    .multipleOf(new Node().value(BigInteger.valueOf(3))));
            FrozenNode frozenPattern = FrozenNode.fromNode(pattern);
            // When mutable and snapshot-backed immutable matching inspect it.
            for (Node type : Arrays.asList(canonical, reference(typeId))) {
                Node value = new Node().type(type.clone()).value(maximum.toString());
                ResolvedSnapshot snapshot = language.snapshots().resolve(value);
                // Then both use the same proven numeric projection as validation.
                assertTrue(language.matching().matches(value, pattern));
                assertTrue(language.matching().matches(snapshot, "", frozenPattern));
                Node invalid = new Node().type(type.clone())
                        .value(maximum.subtract(BigInteger.ONE).toString());
                assertFalse(language.matching().matches(invalid, pattern));
                assertFalse(language.matching().matches(
                        language.snapshots().resolve(invalid), "", frozenPattern));
            }
            assertFalse(language.matching().matches(new Node().value("3"), pattern));
        }
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
