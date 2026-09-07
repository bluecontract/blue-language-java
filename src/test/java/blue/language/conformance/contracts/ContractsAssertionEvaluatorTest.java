package blue.language.conformance.contracts;

import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractsAssertionEvaluatorTest {

    @Test
    void shouldVerifyCanonicalPrimitiveWrappersEqualSourceShorthandRecursively() {
        // given
        Map<String, Object> actualEvent = object(
                "id", typed("Text", "A"),
                "count", typed("Integer", BigInteger.ONE));
        // when
        Map<String, Object> expectedEvent = object(
                "id", "A",
                "count", 1);

        // then
        assertTrue(ContractsAssertionEvaluator.deepEquals(
                Arrays.asList(actualEvent, actualEvent),
                Arrays.asList(expectedEvent, expectedEvent)));
    }

    @Test
    void shouldVerifyOrdinaryMapsAndDifferentPrimitiveTypesRemainDistinct() {
        // given
        Map<String, Object> typedWithExtraField = object(
                "type", object(
                        "blueId",
                        BlueCoreTypeRegistry.INSTANCE.blueId("Text")),
                "value", "A",
                "schema", object("required", true));

        // when
        boolean typedScalarEqual =
                ContractsAssertionEvaluator.deepEquals(
                        typedWithExtraField, "A");
        boolean mapsEqual =
                ContractsAssertionEvaluator.deepEquals(
                        object(
                                "id",
                                typed("Text", "A"),
                                "extra",
                                true),
                        object("id", "A"));
        boolean primitiveTypesEqual =
                ContractsAssertionEvaluator.deepEquals(
                        typed("Text", "1"),
                        typed("Boolean", true));

        // then
        assertFalse(typedScalarEqual);
        assertFalse(mapsEqual);
        assertFalse(primitiveTypesEqual);
    }

    @Test
    void shouldVerifyEqualsProjectionTreatsPureReferenceAsExactMaterialization() {
        // given
        Node materialized = new Node().name("preinitialized");
        String blueId = DirectBlueIdCalculator.calculateBlueId(materialized);
        // when
        ContractsConformanceProjection projection =
                new ContractsConformanceProjection()
                        .put("actual", new Node().blueId(blueId))
                        .put("input.root", materialized);
        Throwable failure = captureFailure(
                () -> new ContractsAssertionEvaluator()
                        .evaluate(equalsProjectionFixture(), projection));

        // then
        assertTrue(failure == null);
    }

    @Test
    void shouldVerifyEqualsProjectionRejectsReferenceToAnotherExactNode() {
        // given
        Node materialized = new Node().name("preinitialized");
        String otherBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("different"));
        // when
        ContractsConformanceProjection projection =
                new ContractsConformanceProjection()
                        .put("actual", new Node().blueId(otherBlueId))
                        .put("input.root", materialized);
        Throwable failure = captureFailure(
                () -> new ContractsAssertionEvaluator()
                        .evaluate(
                                equalsProjectionFixture(),
                                projection));

        // then
        assertTrue(failure instanceof AssertionError);
    }

    @Test
    void shouldVerifyNotEqualsProjectionUsesExactNodeIdentity() {
        // given
        Node first = new Node().name("first");
        Node second = new Node().name("second");
        ContractsConformanceProjection projection =
                new ContractsConformanceProjection()
                        .put("actual", first)
                        .put("expected", second)
                        .put("same", first);

        // when
        Throwable distinctFailure = captureFailure(
                () -> new ContractsAssertionEvaluator().evaluate(
                        projectionFixture(
                                "notEqualsProjection", "expected"),
                        projection));
        Throwable equalFailure = captureFailure(
                () -> new ContractsAssertionEvaluator().evaluate(
                        projectionFixture(
                                "notEqualsProjection", "same"),
                        projection));

        // then
        assertTrue(distinctFailure == null);
        assertTrue(equalFailure instanceof AssertionError);
    }

    @Test
    void shouldValidateCatalogPathsInsideNamedVariant() {
        // given
        ContractsProjectionCatalog catalog =
                new ContractsProjectionCatalog();

        // when
        Runnable declared = () -> catalog.requireDeclared(
                "variants.reference-warm.result.documentBlueId",
                "test");
        Runnable undeclared = () -> catalog.requireDeclared(
                "variants.reference-warm.result.notDeclared",
                "test");

        // then
        assertDoesNotThrow(declared::run);
        assertThrows(IllegalArgumentException.class, undeclared::run);
    }

    private static ObjectNode equalsProjectionFixture() {
        return projectionFixture("equalsProjection", "input.root");
    }

    private static ObjectNode projectionFixture(
            String operation,
            String expectedProjection) {
        ObjectNode fixture =
                UncheckedObjectMapper.JSON_MAPPER.createObjectNode();
        ObjectNode assertion = fixture.putObject("expected")
                .putArray("assertions")
                .addObject();
        assertion.put("actual", "actual");
        assertion.put("op", operation);
        assertion.put("expectedProjection", expectedProjection);
        return fixture;
    }

    private static Map<String, Object> typed(String type, Object value) {
        return object(
                "type", object(
                        "blueId",
                        BlueCoreTypeRegistry.INSTANCE.blueId(type)),
                "value", value);
    }

    private static Map<String, Object> object(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            value.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return value;
    }
}
