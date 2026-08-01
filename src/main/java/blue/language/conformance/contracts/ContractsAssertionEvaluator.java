package blue.language.conformance.contracts;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates the exact assertion vocabulary from the Contracts 1.0 harness.
 *
 * <p>The evaluator reads expected values only after execution and compares
 * them with a presence-aware actual projection. It does not mutate the
 * projection or execute fixture controls.</p>
 */
final class ContractsAssertionEvaluator {

    private static final String TEXT_BLUE_ID =
            BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
    private static final String INTEGER_BLUE_ID =
            BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
    private static final String DOUBLE_BLUE_ID =
            BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
    private static final String BOOLEAN_BLUE_ID =
            BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID;

    /**
     * Creates a stateless assertion evaluator.
     */
    public ContractsAssertionEvaluator() {
    }

    /**
     * Evaluates all general assertions and compact gas-micro expectations.
     *
     * <p>An absent assertion array is accepted as an empty assertion set.
     * Assertion failures use deterministic {@link AssertionError} messages;
     * structurally invalid fixtures are expected to have been rejected by
     * {@link ClosedContractsFixtureValidator} first.</p>
     *
     * @param fixture validated fixture containing expected assertions
     * @param projection actual execution projection to inspect
     * @throws AssertionError when any expected observable does not match
     * @throws NullPointerException when {@code fixture} or
     *         {@code projection} is {@code null}
     */
    public void evaluate(JsonNode fixture, ContractsConformanceProjection projection) {
        evaluateGasEnvelope(fixture, projection);
        JsonNode assertions = fixture
                .path(ContractsFixtureConstants.Field.EXPECTED)
                .path(ContractsFixtureConstants.Field.ASSERTIONS);
        if (!assertions.isArray()) {
            return;
        }
        int index = 0;
        for (JsonNode assertion : assertions) {
            evaluateAssertion(assertion, projection, index++);
        }
    }

    /**
     * Gas-micro envelopes use compact top-level expectations instead of the
     * general assertion array. They are still evaluated here, after execution,
     * so the gas implementation never reads expected output while producing
     * its actual projection.
     */
    private void evaluateGasEnvelope(JsonNode fixture,
                                     ContractsConformanceProjection projection) {
        if (!ContractsFixtureConstants.Operation.GAS_MICRO.equals(
                fixture.path(
                        ContractsFixtureConstants.Field.OPERATION).asText())
                || fixture.path(ContractsFixtureConstants.Field.INPUT)
                .has(ContractsFixtureConstants.Field.ROOT)) {
            return;
        }
        JsonNode expected = fixture.path(
                ContractsFixtureConstants.Field.EXPECTED);
        compareGasField(
                expected,
                ContractsFixtureConstants.Field.TRACE,
                projection,
                ContractsFixtureConstants.Projection.GAS_TRACE);
        compareGasField(
                expected,
                ContractsFixtureConstants.Field.TOTAL_GAS,
                projection,
                ContractsFixtureConstants.Projection.GAS_TOTAL);
        compareGasField(
                expected,
                ContractsFixtureConstants.Field.ADMITTED,
                projection,
                ContractsFixtureConstants.Projection.GAS_ADMITTED);
        compareGasField(
                expected,
                ContractsFixtureConstants.Field.FAILED_CHARGE_ABSENT,
                projection,
                ContractsFixtureConstants.Projection
                        .GAS_FAILED_CHARGE_ABSENT);
        compareGasField(
                expected,
                ContractsFixtureConstants.Field
                        .LIST_FOLD_STEP_RECOMPUTED,
                projection,
                ContractsFixtureConstants.Projection
                        .GAS_LIST_FOLD_STEP_RECOMPUTED);
        compareGasField(
                expected,
                ContractsFixtureConstants.Field.TEXT_BLOCK_EXAMINED,
                projection,
                ContractsFixtureConstants.Projection
                        .GAS_TEXT_BLOCK_EXAMINED);
        compareGasField(
                expected,
                ContractsFixtureConstants.Field.VALIDATION_PROOF_REUSED,
                projection,
                ContractsFixtureConstants.Projection
                        .GAS_VALIDATION_PROOF_REUSED);
        compareGasField(
                expected,
                ContractsFixtureConstants.Field.DIRECT_IDENTITY_HASH_BLOCK,
                projection,
                ContractsFixtureConstants.Projection
                        .GAS_DIRECT_IDENTITY_HASH_BLOCK);
        compareGasField(
                expected,
                ContractsFixtureConstants.Field.INTEGER_LIMB_OPERATION,
                projection,
                ContractsFixtureConstants.Projection
                        .GAS_INTEGER_LIMB_OPERATION);
    }

    private static void compareGasField(JsonNode expected,
                                        String expectedField,
                                        ContractsConformanceProjection projection,
                                        String actualPath) {
        if (!expected.has(expectedField)) {
            return;
        }
        ContractsConformanceProjection.Presence actual =
                projection.project(actualPath);
        check(actual.isPresent(),
                "Gas expectation " + expectedField
                        + " selected an absent actual projection");
        Object expectedValue =
                ContractsConformanceProjection.normalize(
                        expected.get(expectedField));
        if (ContractsFixtureConstants.Field.TRACE.equals(
                expectedField)) {
            check(actual.getValue() instanceof List
                            && expectedValue instanceof List
                            && sequenceEquals(
                            ContractsFixtureConstants.Projection
                                    .TRACE_NAMED_ENTRIES,
                            actual.getValue(),
                            expectedValue),
                    "Gas expectation trace mismatch: actual="
                            + debug(actual.getValue())
                            + ", expected=" + debug(expectedValue));
            return;
        }
        check(deepEquals(actual.getValue(), expectedValue),
                "Gas expectation " + expectedField + " mismatch: actual="
                        + debug(actual.getValue())
                        + ", expected=" + debug(expectedValue));
    }

    private void evaluateAssertion(JsonNode assertion,
                                   ContractsConformanceProjection projection,
                                   int index) {
        String path = assertion.path(
                ContractsFixtureConstants.Field.ACTUAL).asText();
        String op = assertion.path(
                ContractsFixtureConstants.Field.OP).asText();
        String message = "Fixture " + path + " " + op + " assertion " + index;
        if (ContractsFixtureConstants.AssertionOperator
                .SAME_ACROSS_VARIANTS.equals(op)) {
            assertSameAcrossVariants(
                    projection.projectAcrossVariants(
                            path,
                            assertion.path(
                                    ContractsFixtureConstants.Field.VARIANT)
                                    .asText(null)),
                    message);
            return;
        }

        String variant = assertion.path(
                ContractsFixtureConstants.Field.VARIANT).asText(null);
        if (variant != null && !variant.isEmpty()) {
            if (ContractsFixtureConstants.VariantSelector.ALL.equals(
                    variant)) {
                check(!projection.variants().isEmpty(),
                        message + " requested all variants but none were executed");
                for (Map.Entry<String, ContractsConformanceProjection> entry
                        : projection.variants().entrySet()) {
                    evaluateValueAssertion(assertion,
                            entry.getValue(),
                            message + " [variant=" + entry.getKey() + "]");
                }
                return;
            }
            ContractsConformanceProjection selected = projection.variants().get(variant);
            check(selected != null, message + " selected unknown variant " + variant);
            evaluateValueAssertion(
                    assertion, selected, message + " [variant=" + variant + "]");
            return;
        }
        evaluateValueAssertion(assertion, projection, message);
    }

    private void evaluateValueAssertion(JsonNode assertion,
                                        ContractsConformanceProjection projection,
                                        String message) {
        String path = assertion.path(
                ContractsFixtureConstants.Field.ACTUAL).asText();
        String op = assertion.path(
                ContractsFixtureConstants.Field.OP).asText();
        ContractsConformanceProjection.Presence actual = projection.project(path);

        if (ContractsFixtureConstants.AssertionOperator.ABSENT.equals(
                op)) {
            check(!actual.isPresent(), message + " expected absence");
            return;
        }
        if (ContractsFixtureConstants.AssertionOperator.PRESENT.equals(
                op)) {
            check(actual.isPresent(), message + " expected presence");
            return;
        }

        check(actual.isPresent(), message + " selected an absent projection");
        Object actualValue = actual.getValue();
        Object expected = assertion.has(
                ContractsFixtureConstants.Field.EXPECTED)
                ? ContractsConformanceProjection.normalize(assertion.get(
                ContractsFixtureConstants.Field.EXPECTED))
                : null;

        if (ContractsFixtureConstants.AssertionOperator.EQUALS_PROJECTION
                .equals(op)) {
            String expectedPath = assertion.path(
                    ContractsFixtureConstants.Field.EXPECTED_PROJECTION)
                    .asText();
            ContractsConformanceProjection.Presence other = projection.project(expectedPath);
            check(other.isPresent(), message + " expected projection is absent: " + expectedPath);
            check(exactProjectionEquals(actualValue, other.getValue()),
                    message + " mismatch: actual=" + debug(actualValue)
                            + ", expectedProjection=" + expectedPath
                            + " value=" + debug(other.getValue()));
        } else if (ContractsFixtureConstants.AssertionOperator.EQUALS
                .equals(op)
                || ContractsFixtureConstants.AssertionOperator.FAILS_WITH
                .equals(op)) {
            check(deepEquals(actualValue, expected),
                    message + " mismatch: actual=" + debug(actualValue)
                            + ", expected=" + debug(expected));
        } else if (ContractsFixtureConstants.AssertionOperator.NOT_EQUALS
                .equals(op)) {
            check(!deepEquals(actualValue, expected),
                    message + " unexpectedly matched " + debug(expected));
        } else if (ContractsFixtureConstants.AssertionOperator
                .SEQUENCE_EQUALS.equals(op)) {
            check(actualValue instanceof List && expected instanceof List,
                    message + " requires two sequences");
            check(sequenceEquals(path, actualValue, expected),
                    message + " sequence mismatch: actual=" + debug(actualValue)
                            + ", expected=" + debug(expected));
        } else if (ContractsFixtureConstants.AssertionOperator.CONTAINS
                .equals(op)) {
            boolean ordered = assertion.path(
                    ContractsFixtureConstants.Field.ORDERED)
                    .asBoolean(false);
            check(contains(actualValue, expected, ordered),
                    message + " did not contain " + debug(expected)
                            + " in " + debug(actualValue));
        } else if (ContractsFixtureConstants.AssertionOperator.NOT_CONTAINS
                .equals(op)) {
            boolean ordered = assertion.path(
                    ContractsFixtureConstants.Field.ORDERED)
                    .asBoolean(false);
            check(!contains(actualValue, expected, ordered),
                    message + " unexpectedly contained " + debug(expected));
        } else if (ContractsFixtureConstants.AssertionOperator.LESS_THAN
                .equals(op)) {
            check(compareNumbers(actualValue, expected, message) < 0,
                    message + " expected " + actualValue + " < " + expected);
        } else if (ContractsFixtureConstants.AssertionOperator.GREATER_THAN
                .equals(op)) {
            check(compareNumbers(actualValue, expected, message) > 0,
                    message + " expected " + actualValue + " > " + expected);
        } else if (ContractsFixtureConstants.AssertionOperator.ALL.equals(
                op)) {
            check(all(actualValue, expected),
                    message + " universal predicate failed for " + debug(actualValue));
        } else if (ContractsFixtureConstants.AssertionOperator.NONE.equals(
                op)) {
            check(none(actualValue, expected),
                    message + " empty predicate failed for " + debug(actualValue));
        } else {
            throw new IllegalArgumentException("Unsupported Contracts assertion operator: " + op);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean sequenceEquals(String path,
                                          Object actual,
                                          Object expected) {
        if (!ContractsFixtureConstants.Projection.TRACE_NAMED_ENTRIES
                .equals(path)) {
            return deepEquals(actual, expected);
        }
        List<Object> actualEntries = (List<Object>) actual;
        List<Object> expectedEntries = (List<Object>) expected;
        if (actualEntries.size() != expectedEntries.size()) {
            return false;
        }
        for (int index = 0; index < actualEntries.size(); index++) {
            Object actualEntry = actualEntries.get(index);
            Object expectedEntry = expectedEntries.get(index);
            if (actualEntry instanceof Map && expectedEntry instanceof Map) {
                Map<String, Object> actualMap =
                        new java.util.LinkedHashMap<>((Map<String, Object>) actualEntry);
                Map<String, Object> expectedMap = (Map<String, Object>) expectedEntry;
                if (!expectedMap.containsKey(ContractsFixtureConstants.Field.SEQUENCE)) {
                    actualMap.remove(ContractsFixtureConstants.Field.SEQUENCE);
                }
                if (!deepEquals(actualMap, expectedMap)) {
                    return false;
                }
            } else if (!deepEquals(actualEntry, expectedEntry)) {
                return false;
            }
        }
        return true;
    }

    private static void assertSameAcrossVariants(
            Map<String, ContractsConformanceProjection.Presence> variants,
            String message) {
        ContractsConformanceProjection.Presence reference = null;
        String referenceName = null;
        for (Map.Entry<String, ContractsConformanceProjection.Presence> entry : variants.entrySet()) {
            if (reference == null) {
                reference = entry.getValue();
                referenceName = entry.getKey();
                continue;
            }
            check(reference.isPresent() == entry.getValue().isPresent(),
                    message + " differs in presence between " + referenceName
                            + " and " + entry.getKey());
            if (reference.isPresent()) {
                check(deepEquals(reference.getValue(), entry.getValue().getValue()),
                        message + " differs between " + referenceName
                                + "=" + debug(reference.getValue())
                                + " and " + entry.getKey()
                                + "=" + debug(entry.getValue().getValue()));
            }
        }
        check(reference != null, message + " has no variants");
    }

    @SuppressWarnings("unchecked")
    private static boolean contains(Object actual, Object expected, boolean ordered) {
        if (actual instanceof String && expected instanceof String) {
            return ((String) actual).contains((String) expected);
        }
        if (actual instanceof Map && expected instanceof Map) {
            return mapContains((Map<String, Object>) actual, (Map<String, Object>) expected);
        }
        if (!(actual instanceof List)) {
            return deepEquals(actual, expected);
        }
        List<Object> actualList = (List<Object>) actual;
        if (expected instanceof List) {
            List<Object> expectedList = (List<Object>) expected;
            if (ordered) {
                int cursor = 0;
                for (Object candidate : actualList) {
                    if (cursor < expectedList.size()
                            && containsElement(candidate, expectedList.get(cursor))) {
                        cursor++;
                    }
                }
                return cursor == expectedList.size();
            }
            for (Object item : expectedList) {
                if (!listContains(actualList, item)) {
                    return false;
                }
            }
            return true;
        }
        return listContains(actualList, expected);
    }

    private static boolean listContains(List<Object> actual, Object expected) {
        for (Object item : actual) {
            if (containsElement(item, expected)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean containsElement(Object actual, Object expected) {
        if (actual instanceof Map && expected instanceof Map) {
            return mapContains((Map<String, Object>) actual, (Map<String, Object>) expected);
        }
        return deepEquals(actual, expected);
    }

    private static boolean mapContains(Map<String, Object> actual, Map<String, Object> expected) {
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            if (!actual.containsKey(entry.getKey())
                    || !containsElement(actual.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean all(Object actual, Object predicate) {
        if (!(actual instanceof Iterable)) {
            return false;
        }
        for (Object item : (Iterable<Object>) actual) {
            if (!containsElement(item, predicate)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean none(Object actual, Object predicate) {
        if (!(actual instanceof Iterable)) {
            return false;
        }
        for (Object item : (Iterable<Object>) actual) {
            if (containsElement(item, predicate)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static boolean deepEquals(Object left, Object right) {
        TypedScalar leftScalar = typedScalar(left);
        TypedScalar rightScalar = typedScalar(right);
        if (leftScalar != null && rightScalar != null) {
            return leftScalar.typeBlueId.equals(rightScalar.typeBlueId)
                    && deepEquals(leftScalar.value, rightScalar.value);
        }
        if (leftScalar != null) {
            return leftScalar.matchesSource(right)
                    && deepEquals(leftScalar.value, right);
        }
        if (rightScalar != null) {
            return rightScalar.matchesSource(left)
                    && deepEquals(left, rightScalar.value);
        }
        if (left instanceof Number && right instanceof Number) {
            return decimal((Number) left).compareTo(decimal((Number) right)) == 0;
        }
        if (left instanceof Map && right instanceof Map) {
            Map<String, Object> leftMap = (Map<String, Object>) left;
            Map<String, Object> rightMap = (Map<String, Object>) right;
            if (!leftMap.keySet().equals(rightMap.keySet())) {
                return false;
            }
            for (String key : leftMap.keySet()) {
                if (!deepEquals(leftMap.get(key), rightMap.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<Object> leftList = (List<Object>) left;
            List<Object> rightList = (List<Object>) right;
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int i = 0; i < leftList.size(); i++) {
                if (!deepEquals(leftList.get(i), rightList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    /**
     * Projection equality normally compares normalized values recursively.
     * When either projection is a pure exact-node reference, Language 1.0
     * additionally requires its verified materialization to compare equal:
     * expansion and collapse are representation changes, not semantic ones.
     */
    private static boolean exactProjectionEquals(Object left, Object right) {
        if (deepEquals(left, right)) {
            return true;
        }
        String leftReference = pureReferenceBlueId(left);
        if (leftReference != null) {
            return leftReference.equals(exactNodeBlueId(right));
        }
        String rightReference = pureReferenceBlueId(right);
        return rightReference != null
                && rightReference.equals(exactNodeBlueId(left));
    }

    @SuppressWarnings("unchecked")
    private static String pureReferenceBlueId(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<String, Object> reference = (Map<String, Object>) value;
        if (reference.size() != 1
                || !(reference.get(BlueLanguageConstants.OBJECT_BLUE_ID) instanceof String)) {
            return null;
        }
        String blueId = (String) reference.get(BlueLanguageConstants.OBJECT_BLUE_ID);
        try {
            return BlueIds.requirePlainBlueId(
                    blueId,
                    ContractsFixtureConstants.AssertionOperator
                            .EQUALS_PROJECTION);
        } catch (IllegalArgumentException invalidReference) {
            return null;
        }
    }

    private static String exactNodeBlueId(Object value) {
        try {
            Node node = UncheckedObjectMapper.JSON_MAPPER.convertValue(
                    value, Node.class);
            return BlueIdCalculator.calculateBlueId(node);
        } catch (RuntimeException notAnExactNode) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static TypedScalar typedScalar(Object candidate) {
        if (!(candidate instanceof Map)) {
            return null;
        }
        Map<String, Object> wrapper = (Map<String, Object>) candidate;
        if (wrapper.size() != 2
                || !wrapper.containsKey(BlueLanguageConstants.OBJECT_TYPE)
                || !wrapper.containsKey(BlueLanguageConstants.OBJECT_VALUE)
                || !(wrapper.get(BlueLanguageConstants.OBJECT_TYPE) instanceof Map)) {
            return null;
        }
        Map<String, Object> type =
                (Map<String, Object>) wrapper.get(BlueLanguageConstants.OBJECT_TYPE);
        if (type.size() != 1
                || !(type.get(BlueLanguageConstants.OBJECT_BLUE_ID) instanceof String)) {
            return null;
        }
        String typeBlueId = (String) type.get(BlueLanguageConstants.OBJECT_BLUE_ID);
        Object value = wrapper.get(BlueLanguageConstants.OBJECT_VALUE);
        if ((TEXT_BLUE_ID.equals(typeBlueId) && value instanceof String)
                || (INTEGER_BLUE_ID.equals(typeBlueId)
                && isIntegralNumber(value))
                || (DOUBLE_BLUE_ID.equals(typeBlueId)
                && isFloatingNumber(value))
                || (BOOLEAN_BLUE_ID.equals(typeBlueId)
                && value instanceof Boolean)) {
            return new TypedScalar(typeBlueId, value);
        }
        return null;
    }

    private static boolean isIntegralNumber(Object value) {
        return value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger;
    }

    private static boolean isFloatingNumber(Object value) {
        return value instanceof Float
                || value instanceof Double
                || value instanceof BigDecimal;
    }

    private static final class TypedScalar {
        private final String typeBlueId;
        private final Object value;

        private TypedScalar(String typeBlueId, Object value) {
            this.typeBlueId = typeBlueId;
            this.value = value;
        }

        private boolean matchesSource(Object source) {
            if (TEXT_BLUE_ID.equals(typeBlueId)) {
                return source instanceof String;
            }
            if (INTEGER_BLUE_ID.equals(typeBlueId)) {
                return isIntegralNumber(source);
            }
            if (DOUBLE_BLUE_ID.equals(typeBlueId)) {
                return isFloatingNumber(source);
            }
            return BOOLEAN_BLUE_ID.equals(typeBlueId)
                    && source instanceof Boolean;
        }
    }

    private static int compareNumbers(Object actual, Object expected, String message) {
        check(actual instanceof Number && expected instanceof Number,
                message + " requires numeric values");
        return decimal((Number) actual).compareTo(decimal((Number) expected));
    }

    private static BigDecimal decimal(Number value) {
        return new BigDecimal(value.toString());
    }

    private static String debug(Object value) {
        return String.valueOf(value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
