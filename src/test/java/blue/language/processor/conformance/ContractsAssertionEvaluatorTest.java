package blue.language.processor.conformance;

import blue.language.registry.BlueCoreTypeRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractsAssertionEvaluatorTest {

    @Test
    void canonicalPrimitiveWrappersEqualSourceShorthandRecursively() {
        Map<String, Object> actualEvent = object(
                "id", typed("Text", "A"),
                "count", typed("Integer", BigInteger.ONE));
        Map<String, Object> expectedEvent = object(
                "id", "A",
                "count", 1);

        assertTrue(ContractsAssertionEvaluator.deepEquals(
                Arrays.asList(actualEvent, actualEvent),
                Arrays.asList(expectedEvent, expectedEvent)));
    }

    @Test
    void ordinaryMapsAndDifferentPrimitiveTypesRemainDistinct() {
        Map<String, Object> typedWithExtraField = object(
                "type", object(
                        "blueId",
                        BlueCoreTypeRegistry.INSTANCE.blueId("Text")),
                "value", "A",
                "schema", object("required", true));

        assertFalse(ContractsAssertionEvaluator.deepEquals(
                typedWithExtraField, "A"));
        assertFalse(ContractsAssertionEvaluator.deepEquals(
                object("id", typed("Text", "A"), "extra", true),
                object("id", "A")));
        assertFalse(ContractsAssertionEvaluator.deepEquals(
                typed("Text", "1"),
                typed("Boolean", true)));
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
