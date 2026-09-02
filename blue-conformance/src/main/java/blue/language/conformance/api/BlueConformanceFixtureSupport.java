package blue.language.conformance.api;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.NodeDeserializer;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.TransformationProcessor;
import blue.language.preprocess.TransformationProcessorProvider;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.DirectNodeManifest;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderEvidenceVerifier;
import blue.language.provider.ProviderMode;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePath;
import blue.language.registry.NodeProviderWrapper;
import blue.language.model.NodeWireForm;
import blue.language.model.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


/** Evaluates deterministic Language fixture assertions. */
abstract class BlueConformanceFixtureSupport extends BlueConformanceFixturePrimitives {

    static void assertResolutionExpectations(JsonNode spec,
                                                     Node actual,
                                                     LanguageFixtureRuntime blue,
                                                     Node source) {
        assertExpectedResolvedIfPresent(spec, FixtureField.EXPECTED_RESOLVED, actual, blue);
        if (spec.has(FixtureField.EXPECTED_RESOLVED_ITEMS)) {
            assertItemValues(spec.get(FixtureField.EXPECTED_RESOLVED_ITEMS),
                    actual.getItems());
        }
        if (spec.has(FixtureField.EXPECTED_MERGE_POLICY)) {
            String effective = actual.getMergePolicy() == null
                    ? BlueLanguageConstants.LIST_MERGE_POLICY_POSITIONAL
                    : actual.getMergePolicy();
            assertEquals(requireText(spec, FixtureField.EXPECTED_MERGE_POLICY), effective);
        }
        assertEffectiveTypes(singletonPathMap(
                spec, FixtureField.EXPECTED_EFFECTIVE_TYPE), actual);
        assertExpectedValues(spec.get(FixtureField.EXPECTED_VALUE), actual);
        if (spec.path(
                FixtureField.EXPECTED_SOURCE_REFERENCE_PRESERVED_BY_CANONICALIZATION)
                .asBoolean(false)) {
            Node canonical = blue.canonicalize(source);
            assertEquals(source.getContracts().getBlueId(),
                    canonical.getContracts().getBlueId());
            assertTrue(canonical.getContracts().isReferenceOnly(),
                    "Canonical contracts reference was not preserved.");
        }
    }

    static JsonNode singletonPathMap(JsonNode spec, String field) {
        return spec.get(field);
    }

    static Node sourceWithParent(JsonNode spec) {
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        attachBaselineType(source, spec);
        return source;
    }

    static void attachBaselineType(Node source, JsonNode fixture) {
        Node baseline = null;
        if (fixture.has(FixtureField.PARENT)) {
            baseline = readNode(fixture.get(FixtureField.PARENT));
        } else if (fixture.has(FixtureField.BASE)) {
            baseline = readNode(fixture.get(FixtureField.BASE));
        } else if (fixture.has(FixtureField.FIELD_DECLARATION)) {
            baseline = readNode(fixture.get(FixtureField.FIELD_DECLARATION));
        }
        if (baseline == null) return;
        if (source.getType() == null) {
            source.type(baseline);
        } else if (source.getType().getType() == null) {
            source.getType().type(baseline);
        } else {
            Node cursor = source.getType();
            while (cursor.getType() != null) cursor = cursor.getType();
            cursor.type(baseline);
        }
    }

    static void assertDemandedValue(JsonNode spec,
                                            BlueOperationResult<Node> result,
                                            BlueOperationLimits limits) {
        if (!spec.has(FixtureField.EXPECTED_VALUE)) return;
        Node selected = selectFirstDemand(result.requireEstablished(), limits);
        assertSemanticScalar(spec.get(FixtureField.EXPECTED_VALUE), selected);
    }

    static Node selectFirstDemand(Node root,
                                          BlueOperationLimits limits) {
        String path = limits.demandedPaths().iterator().next();
        return BlueViewPath.select(root, path);
    }

    static void assertExpectedValues(JsonNode expected, Node actual) {
        if (expected == null || expected.isNull()) return;
        if (expected.isObject()) {
            expected.fields().forEachRemaining(entry -> {
                Node selected = BlueViewPath.select(actual, entry.getKey());
                assertSemanticScalar(entry.getValue(), selected);
            });
        } else {
            assertSemanticScalar(expected, actual);
        }
    }

    static void assertEffectiveTypes(JsonNode expected, Node actual) {
        if (expected == null || expected.isNull()) return;
        if (!expected.isObject()) {
            throw new AssertionError(
                    "Expected effective types must be a path map.");
        }
        expected.fields().forEachRemaining(entry -> {
            Node selected = BlueViewPath.select(actual, entry.getKey());
            assertEquals(entry.getValue().asText(),
                    coreTypeName(selected.getType()));
        });
    }

    static String coreTypeName(Node type) {
        if (type == null) return null;
        String blueId = type.getBlueId();
        for (Map.Entry<String, String> entry :
                BlueLanguageConstants.CORE_TYPE_NAME_TO_BLUE_ID_MAP.entrySet()) {
            if (entry.getValue().equals(blueId)) return entry.getKey();
        }
        return blueId;
    }

    static void assertSemanticScalar(JsonNode expected, Node actual) {
        if (actual == null) {
            throw new AssertionError("Expected semantic value but path was absent.");
        }
        Object value = actual.getValue();
        if (expected.isTextual()) {
            assertEquals(expected.asText(),
                    value == null ? null : value.toString());
        } else if (expected.isBoolean()) {
            assertEquals(expected.asBoolean(), value);
        } else if (expected.isIntegralNumber()) {
            assertEquals(expected.bigIntegerValue(),
                    value instanceof BigInteger
                            ? value
                            : new BigInteger(value.toString()));
        } else if (expected.isFloatingPointNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(
                    value instanceof BigDecimal
                            ? (BigDecimal) value
                            : new BigDecimal(value.toString())));
        } else {
            assertNodeEquals(readNode(expected), actual);
        }
    }

    static void assertItemValues(JsonNode expected,
                                         List<Node> actual) {
        if (actual == null) {
            throw new AssertionError("Expected list items but actual was not a list.");
        }
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSemanticScalar(expected.get(i), actual.get(i));
        }
    }

    static void assertOnlyAllowedMinimizationControls(
            Node minimized, Collection<String> allowed) {
        Set<String> controls = new LinkedHashSet<>();
        collectControls(minimized, controls);
        assertTrue(allowed.containsAll(controls),
                "Minimized overlay used undeclared controls: " + controls);
    }

    static void collectControls(Node node, Set<String> controls) {
        if (node == null) return;
        if (node.getPreviousBlueId() != null) {
            controls.add(BlueLanguageConstants.LIST_CONTROL_PREVIOUS);
        }
        if (node.getPosition() != null) {
            controls.add(BlueLanguageConstants.LIST_CONTROL_POS);
        }
        if (node.getProperties() != null) {
            if (node.getProperties().containsKey(
                    BlueLanguageConstants.LIST_CONTROL_REPLACE)) {
                controls.add(BlueLanguageConstants.LIST_CONTROL_REPLACE);
            }
            for (Node child : node.getProperties().values()) {
                collectControls(child, controls);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) collectControls(child, controls);
        }
        collectControls(node.getType(), controls);
        collectControls(node.getContracts(), controls);
    }

    static boolean containsListControls(Node node) {
        Set<String> controls = new HashSet<>();
        collectControls(node, controls);
        return !controls.isEmpty();
    }

    static void assertOutcome(JsonNode spec,
                                      String field,
                                      BlueOperationOutcome actual) {
        String expected = requireText(spec, field);
        assertEquals(BlueOperationOutcome.valueOf(
                expected.toUpperCase(java.util.Locale.ROOT)), actual);
    }

    static NodeProviderOutcome providerOutcome(String value) {
        return NodeProviderOutcome.valueOf(
                value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                        .replace("-", "_")
                        .toUpperCase(java.util.Locale.ROOT));
    }

    static BlueOperationLimits operationLimits(JsonNode spec) {
        JsonNode limits = requirePresent(spec, FixtureField.LIMITS);
        List<String> demanded = new ArrayList<>();
        JsonNode paths = limits.get("demandedPaths");
        if (paths == null || !paths.isArray() || paths.size() == 0) {
            demanded.add("");
        } else {
            for (JsonNode path : paths) demanded.add(path.asText());
        }
        int max = limits.has(FixtureField.MAX_REFERENCE_EXPANSIONS)
                ? limits.get(FixtureField.MAX_REFERENCE_EXPANSIONS).asInt()
                : Integer.MAX_VALUE;
        return new BlueOperationLimits(demanded, max);
    }

    static void assertEquivalentInputs(String actual,
                                               JsonNode inputs) {
        if (inputs == null || inputs.isNull()) return;
        if (inputs.isArray()) {
            for (JsonNode input : inputs) {
                assertEquals(actual,
                        DirectBlueIdCalculator.calculateBlueId(readNode(input)));
            }
        } else {
            assertEquals(actual,
                    DirectBlueIdCalculator.calculateBlueId(readNode(inputs)));
        }
    }

    static void assertDifferentInputs(String actual,
                                              JsonNode inputs) {
        if (inputs == null || inputs.isNull()) return;
        if (inputs.isArray()) {
            for (JsonNode input : inputs) {
                assertTrue(!actual.equals(
                                DirectBlueIdCalculator.calculateBlueId(readNode(input))),
                        "Expected a different BlueId.");
            }
        } else {
            assertTrue(!actual.equals(
                            DirectBlueIdCalculator.calculateBlueId(readNode(inputs))),
                    "Expected a different BlueId.");
        }
    }

    static void assertRequestedIds(JsonNode expected,
                                           List<String> actual,
                                           boolean requested) {
        if (expected == null || expected.isNull()) return;
        for (JsonNode blueId : expected) {
            assertEquals(requested, actual.contains(blueId.asText()));
        }
        if (requested) {
            assertTextList(expected, actual);
        }
    }

    static void assertAllNodeEqual(List<Node> nodes) {
        for (int i = 1; i < nodes.size(); i++) {
            assertNodeEquals(nodes.get(0), nodes.get(i));
        }
    }

    static <T> void assertAllEqual(List<T> values) {
        for (int i = 1; i < values.size(); i++) {
            assertEquals(values.get(0), values.get(i));
        }
    }

    static void assertExpectedErrorCategory(
            JsonNode spec, String field, Throwable failure) {
        BlueLanguageErrorCategory expected =
                BlueLanguageErrorCategory.valueOf(requireText(spec, field));
        BlueLanguageErrorCategory actual =
                BlueLanguageErrorClassifier.classify(failure);
        assertEquals(expected, actual);
    }

    static void assertExpectedNodeIfPresent(
            JsonNode spec, String field, Node actual) {
        if (spec.has(field)) {
            assertNodeEquals(readNode(spec.get(field)), actual);
        }
    }

    static void assertExpectedResolvedIfPresent(
            JsonNode spec, String field, Node actual,
            LanguageFixtureRuntime blue) {
        if (spec.has(field)) {
            Node expected = blue.preprocess(readNode(spec.get(field)));
            assertNodeEquals(expected, actual);
        }
    }

    static void assertNodeEquals(Node expected, Node actual) {
        JsonNode expectedTree = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeWireForm.get(expected));
        JsonNode actualTree = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeWireForm.get(actual));
        assertJsonNodeEquals(expectedTree, actualTree, "/");
    }

    static boolean nodesEqual(Node left, Node right) {
        JsonNode leftTree = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeWireForm.get(left));
        JsonNode rightTree = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeWireForm.get(right));
        return leftTree.equals(rightTree);
    }

    static void assertCanonicalTypeReferences(Node canonical) {
        JsonNode tree = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeWireForm.get(canonical));
        assertCanonicalTypeReferences(tree, "/");
    }

    private static void assertCanonicalTypeReferences(
            JsonNode node, String path) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                assertCanonicalTypeReferences(
                        node.get(index), JsonPointer.append(path,
                                Integer.toString(index)));
            }
            return;
        }
        if (!node.isObject()) return;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String childPath = JsonPointer.append(path, field.getKey());
            if (isTypeMetadataField(field.getKey())) {
                JsonNode reference = field.getValue();
                assertTrue(reference != null && reference.isObject()
                                && reference.size() == 1
                                && reference.hasNonNull(
                                        BlueLanguageConstants.OBJECT_BLUE_ID)
                                && reference.get(
                                        BlueLanguageConstants.OBJECT_BLUE_ID)
                                        .isTextual(),
                        "Canonical type metadata must be one pure non-null BlueId reference at "
                                + childPath + ".");
                String blueId = reference.get(
                        BlueLanguageConstants.OBJECT_BLUE_ID).asText();
                BlueIds.requireNoThisPlaceholderOutsideCyclicApi(
                        blueId, childPath);
                BlueIds.requireBlueIdOrCyclicMember(blueId, childPath);
            } else {
                assertCanonicalTypeReferences(field.getValue(), childPath);
            }
        }
    }

    private static boolean isTypeMetadataField(String field) {
        return BlueLanguageConstants.OBJECT_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_KEY_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(field);
    }

    static void assertJsonNodeEquals(JsonNode expected,
                                             JsonNode actual,
                                             String path) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, "Node mismatch at " + path);
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            Set<String> expectedFields = new LinkedHashSet<>();
            expected.fieldNames().forEachRemaining(expectedFields::add);
            Set<String> actualFields = new LinkedHashSet<>();
            actual.fieldNames().forEachRemaining(actualFields::add);
            assertEquals(expectedFields, actualFields,
                    "Object field mismatch at " + path);
            for (String field : expectedFields) {
                assertJsonNodeEquals(expected.get(field), actual.get(field),
                        JsonPointer.append(path, field));
            }
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            assertEquals(expected.size(), actual.size(),
                    "Array length mismatch at " + path);
            for (int index = 0; index < expected.size(); index++) {
                assertJsonNodeEquals(expected.get(index), actual.get(index),
                        path + "/" + index);
            }
            return;
        }
        if (expected.isIntegralNumber() && actual.isIntegralNumber()) {
            assertEquals(expected.bigIntegerValue(), actual.bigIntegerValue(),
                    "Integer mismatch at " + path);
            return;
        }
        if (expected.isFloatingPointNumber() && actual.isFloatingPointNumber()) {
            assertEquals(0,
                    expected.decimalValue().compareTo(actual.decimalValue()),
                    "Double mismatch at " + path);
            return;
        }
        assertEquals(expected, actual, "Node mismatch at " + path);
    }

}
