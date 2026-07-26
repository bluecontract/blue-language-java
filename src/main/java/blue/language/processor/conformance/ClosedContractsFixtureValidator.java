package blue.language.processor.conformance;

import blue.language.BlueContractsFixtureCategory;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Executable fail-closed validator for {@code blue-contracts-fixture/1.0}.
 *
 * <p>The published JSON Schema is shipped and package-verified as the
 * authoritative schema. This validator mirrors its closed object surfaces and
 * additionally enforces the operation-specific rules published in
 * CONTROL-LANGUAGE.md and HARNESS.md.</p>
 */
public final class ClosedContractsFixtureValidator {

    private static final Pattern ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*$");
    private static final Pattern VECTOR =
            Pattern.compile("^C-[A-Z0-9]+-[0-9]{2}$");

    private static final Set<String> TOP = set(
            "schema", "id", "vectors", "category", "description",
            "operation", "input", "expected");
    private static final Set<String> INPUT = set(
            "root", "event", "feeder", "provider", "runtime", "builders", "variants",
            "namespace", "counter", "quantity", "weightManifest", "oldLength", "limit",
            "charges", "textCodePointsExamined", "proofKey", "uses",
            "directCanonicalBytes", "operation", "leftLimbs", "rightLimbs",
            "replaceIndex", "priorExactIdentity", "append");
    private static final Set<String> BUILDER = set(
            "kind", "target", "memberCount", "itemCount", "codePointCount",
            "keyPrefix", "value", "item", "text");
    private static final Set<String> PROVIDER = set(
            "mode", "semanticDemandsOnly", "nodes", "transientUnavailableAt");
    private static final Set<String> RUNTIME = set(
            "typeRegistryManifest", "handlers", "cascadeMutation", "childEmissions",
            "gasLimit", "gasLimitDuringTermination", "generalizationCandidates",
            "initializationPatches", "nestedEnqueues", "rootForwardAll",
            "terminationRequests", "validCandidate");
    private static final Set<String> SCRIPTED_HANDLER = set("result", "fail");
    private static final Set<String> SCRIPTED_RESULT = set(
            "patches", "events", "termination", "fail", "runtimeCounters");
    private static final Set<String> CASCADE = set(
            "afterPatchIndex", "replaceScope", "thenReaddSamePath",
            "replaceScopeDuringLifecycle", "sourceCutOffDuringUpdate");
    private static final Set<String> TERMINATION_REQUEST = set("cause", "reason");
    private static final Set<String> FEEDER = set(
            "managedRootRevision", "indexedRootRevision", "evaluatedRevision",
            "eventOrderKey", "deliverySnapshot", "acceptanceStateVariants",
            "canonicalPreselection", "casConflict", "channelLawCases",
            "currentEventAddsChannel", "eventQueue", "intervalHistory",
            "rawIndexCandidates", "sameFailureCount", "targetsByEvent");
    private static final Set<String> DELIVERY_HINT = set(
            "scopePath", "channelKey", "order", "activationStartExclusive");
    private static final Set<String> CHANNEL_LAW = set(
            "accepts", "preselects", "keyIntersection");
    private static final Set<String> VARIANT = set(
            "name", "accept", "batching", "cache", "checkpointSubject",
            "listOperation", "newEmbeddedSurface", "rootForm", "rootRevision", "sameEvent");
    private static final Set<String> LIST_OPERATION = set("op", "size", "delta", "index");
    private static final Set<String> EXPECTED = set(
            "assertions", "trace", "totalGas", "listFoldStepRecomputed", "admitted",
            "failedChargeAbsent", "textBlockExamined", "validationProofReused",
            "directIdentityHashBlock", "integerLimbOperation");
    private static final Set<String> ASSERTION = set(
            "actual", "op", "expected", "expectedProjection", "variant", "ordered");
    private static final Set<String> CHARGE = set("counter", "quantity");
    private static final Set<String> OPERATIONS =
            set("process", "process-attempt", "platform", "gas-micro");
    private static final Set<String> ASSERTION_OPERATORS = set(
            "equals", "notEquals", "equalsProjection", "absent", "present",
            "sequenceEquals", "contains", "notContains", "lessThan", "greaterThan",
            "sameAcrossVariants", "failsWith", "all", "none");

    public void validate(JsonNode fixture) {
        requireObject(fixture, "$");
        closed(fixture, "$", TOP);
        requireFields(fixture, "$", "schema", "id", "vectors", "category",
                "operation", "input", "expected");
        requireExactText(fixture, "$", "schema", "blue-contracts-fixture/1.0");
        requirePatternText(fixture, "$", "id", ID);
        validateVectors(fixture.get("vectors"));
        BlueContractsFixtureCategory.fromLabel(requireText(fixture, "$", "category"));
        String operation = requireText(fixture, "$", "operation");
        requireMember(operation, "$.operation", OPERATIONS);
        optionalText(fixture, "$", "description");

        JsonNode input = requireObjectField(fixture, "$", "input");
        validateInput(input, operation);
        JsonNode expected = requireObjectField(fixture, "$", "expected");
        validateExpected(expected);
    }

    private void validateInput(JsonNode input, String operation) {
        closed(input, "$.input", INPUT);
        if (!"gas-micro".equals(operation)) {
            requireFields(input, "$.input", "root", "event", "feeder", "provider", "runtime");
        }
        if (input.has("builders")) {
            requireArray(input.get("builders"), "$.input.builders");
            int index = 0;
            for (JsonNode builder : input.get("builders")) {
                validateBuilder(builder, "$.input.builders[" + index++ + "]");
            }
        }
        if (input.has("provider")) {
            validateProvider(input.get("provider"));
        }
        if (input.has("runtime")) {
            validateRuntime(input.get("runtime"));
        }
        if (input.has("feeder")) {
            validateFeeder(input.get("feeder"));
        }
        if (input.has("variants")) {
            requireArray(input.get("variants"), "$.input.variants");
            Set<String> names = new LinkedHashSet<>();
            int index = 0;
            for (JsonNode variant : input.get("variants")) {
                String path = "$.input.variants[" + index++ + "]";
                requireObject(variant, path);
                closed(variant, path, VARIANT);
                requireFields(variant, path, "name");
                String name = requireText(variant, path, "name");
                if (!names.add(name)) {
                    fail(path + ".name", "duplicate variant name " + name);
                }
                if (variant.size() == 1) {
                    fail(path, "a variant name alone has no semantics");
                }
                optionalEnum(variant, path, "rootForm", set("inline", "reference", "eager", "lazy"));
                optionalEnum(variant, path, "cache", set("warm", "cold"));
                optionalEnum(variant, path, "batching", set("batched", "unbatched"));
                optionalBoolean(variant, path, "accept");
                optionalBoolean(variant, path, "sameEvent");
                optionalNonNegativeInteger(variant, path, "rootRevision");
                if (variant.has("listOperation")) {
                    validateListOperation(variant.get("listOperation"), path + ".listOperation");
                }
            }
        }
        optionalEnum(input, "$.input", "namespace", set("processor", "semantic", "runtime"));
        optionalText(input, "$.input", "counter");
        optionalText(input, "$.input", "weightManifest");
        for (String field : Arrays.asList(
                "quantity", "oldLength", "limit", "textCodePointsExamined", "uses",
                "directCanonicalBytes", "leftLimbs", "rightLimbs", "replaceIndex", "append")) {
            optionalNonNegativeInteger(input, "$.input", field);
        }
        optionalText(input, "$.input", "proofKey");
        optionalText(input, "$.input", "operation");
        optionalBoolean(input, "$.input", "priorExactIdentity");
        if (input.has("charges")) {
            requireArray(input.get("charges"), "$.input.charges");
            int index = 0;
            for (JsonNode charge : input.get("charges")) {
                String path = "$.input.charges[" + index++ + "]";
                if (charge.isIntegralNumber()) {
                    requireNonNegative(charge, path);
                } else {
                    requireObject(charge, path);
                    closed(charge, path, CHARGE);
                    requireFields(charge, path, "counter", "quantity");
                    requireText(charge, path, "counter");
                    requireNonNegative(charge.get("quantity"), path + ".quantity");
                }
            }
        }
    }

    private void validateBuilder(JsonNode builder, String path) {
        requireObject(builder, path);
        closed(builder, path, BUILDER);
        requireFields(builder, path, "kind", "target");
        String kind = requireText(builder, path, "kind");
        requireMember(kind, path + ".kind",
                set("generated-object", "repeated-text", "generated-list"));
        requireText(builder, path, "target");
        if ("generated-object".equals(kind)) {
            requireFields(builder, path, "memberCount", "keyPrefix", "value");
            requireNonNegative(builder.get("memberCount"), path + ".memberCount");
            requireText(builder, path, "keyPrefix");
        } else if ("generated-list".equals(kind)) {
            requireFields(builder, path, "itemCount", "item");
            requireNonNegative(builder.get("itemCount"), path + ".itemCount");
        } else {
            requireFields(builder, path, "codePointCount", "text");
            requireNonNegative(builder.get("codePointCount"), path + ".codePointCount");
            String text = requireText(builder, path, "text");
            if (text.codePointCount(0, text.length()) != 1) {
                fail(path + ".text", "repeated-text requires exactly one Unicode code point");
            }
        }
    }

    private void validateProvider(JsonNode provider) {
        requireObject(provider, "$.input.provider");
        closed(provider, "$.input.provider", PROVIDER);
        requireFields(provider, "$.input.provider", "mode", "semanticDemandsOnly");
        requireExactText(provider, "$.input.provider", "mode", "exact-node");
        JsonNode semanticOnly = provider.get("semanticDemandsOnly");
        if (semanticOnly == null || !semanticOnly.isBoolean() || !semanticOnly.asBoolean()) {
            fail("$.input.provider.semanticDemandsOnly", "must be true");
        }
        if (provider.has("nodes")) {
            requireObject(provider.get("nodes"), "$.input.provider.nodes");
        }
        optionalText(provider, "$.input.provider", "transientUnavailableAt");
    }

    private void validateRuntime(JsonNode runtime) {
        requireObject(runtime, "$.input.runtime");
        closed(runtime, "$.input.runtime", RUNTIME);
        requireFields(runtime, "$.input.runtime", "typeRegistryManifest");
        requireExactText(runtime, "$.input.runtime",
                "typeRegistryManifest", "../../registry/manifest.yaml");
        if (runtime.has("handlers")) {
            requireObject(runtime.get("handlers"), "$.input.runtime.handlers");
            for (Iterator<Map.Entry<String, JsonNode>> it = runtime.get("handlers").fields();
                 it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String path = "$.input.runtime.handlers." + entry.getKey();
                if (!entry.getKey().startsWith("/")) {
                    fail(path, "handler key must be an absolute Root pointer");
                }
                requireObject(entry.getValue(), path);
                closed(entry.getValue(), path, SCRIPTED_HANDLER);
                if (entry.getValue().has("result")) {
                    validateScriptedResult(entry.getValue().get("result"), path + ".result");
                }
                optionalText(entry.getValue(), path, "fail");
            }
        }
        if (runtime.has("cascadeMutation")) {
            JsonNode cascade = runtime.get("cascadeMutation");
            requireObject(cascade, "$.input.runtime.cascadeMutation");
            closed(cascade, "$.input.runtime.cascadeMutation", CASCADE);
            optionalNonNegativeInteger(cascade, "$.input.runtime.cascadeMutation", "afterPatchIndex");
            optionalText(cascade, "$.input.runtime.cascadeMutation", "replaceScope");
            optionalBoolean(cascade, "$.input.runtime.cascadeMutation", "thenReaddSamePath");
            optionalBoolean(cascade, "$.input.runtime.cascadeMutation", "replaceScopeDuringLifecycle");
            optionalBoolean(cascade, "$.input.runtime.cascadeMutation", "sourceCutOffDuringUpdate");
        }
        if (runtime.has("childEmissions")) {
            requireArray(runtime.get("childEmissions"), "$.input.runtime.childEmissions");
        }
        if (runtime.has("generalizationCandidates")) {
            requireTextArray(runtime.get("generalizationCandidates"),
                    "$.input.runtime.generalizationCandidates");
        }
        if (runtime.has("initializationPatches")) {
            requireArray(runtime.get("initializationPatches"),
                    "$.input.runtime.initializationPatches");
        }
        if (runtime.has("terminationRequests")) {
            requireArray(runtime.get("terminationRequests"),
                    "$.input.runtime.terminationRequests");
            int index = 0;
            for (JsonNode request : runtime.get("terminationRequests")) {
                String path = "$.input.runtime.terminationRequests[" + index++ + "]";
                requireObject(request, path);
                closed(request, path, TERMINATION_REQUEST);
                requireFields(request, path, "cause");
                requireText(request, path, "cause");
                optionalText(request, path, "reason");
            }
        }
        optionalNonNegativeInteger(runtime, "$.input.runtime", "gasLimit");
        optionalNonNegativeInteger(runtime, "$.input.runtime", "nestedEnqueues");
        optionalBoolean(runtime, "$.input.runtime", "gasLimitDuringTermination");
        optionalBoolean(runtime, "$.input.runtime", "rootForwardAll");
        optionalText(runtime, "$.input.runtime", "validCandidate");
    }

    private void validateScriptedResult(JsonNode result, String path) {
        requireObject(result, path);
        closed(result, path, SCRIPTED_RESULT);
        if (result.has("patches")) {
            requireArray(result.get("patches"), path + ".patches");
        }
        if (result.has("events")) {
            requireArray(result.get("events"), path + ".events");
        }
        optionalText(result, path, "fail");
        if (result.has("runtimeCounters")) {
            requireObject(result.get("runtimeCounters"), path + ".runtimeCounters");
            for (Iterator<Map.Entry<String, JsonNode>> it =
                 result.get("runtimeCounters").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                requireNonNegative(entry.getValue(), path + ".runtimeCounters." + entry.getKey());
            }
        }
    }

    private void validateFeeder(JsonNode feeder) {
        requireObject(feeder, "$.input.feeder");
        closed(feeder, "$.input.feeder", FEEDER);
        requireFields(feeder, "$.input.feeder",
                "managedRootRevision", "indexedRootRevision", "eventOrderKey", "deliverySnapshot");
        optionalNonNegativeInteger(feeder, "$.input.feeder", "managedRootRevision");
        optionalNonNegativeInteger(feeder, "$.input.feeder", "indexedRootRevision");
        optionalNonNegativeInteger(feeder, "$.input.feeder", "evaluatedRevision");
        optionalNonNegativeInteger(feeder, "$.input.feeder", "sameFailureCount");
        validateOrderKey(feeder.get("eventOrderKey"), "$.input.feeder.eventOrderKey");
        requireArray(feeder.get("deliverySnapshot"), "$.input.feeder.deliverySnapshot");
        int index = 0;
        for (JsonNode hint : feeder.get("deliverySnapshot")) {
            validateDeliveryHint(hint, "$.input.feeder.deliverySnapshot[" + index++ + "]");
        }
        if (feeder.has("canonicalPreselection")) {
            requireArray(feeder.get("canonicalPreselection"),
                    "$.input.feeder.canonicalPreselection");
            index = 0;
            for (JsonNode hint : feeder.get("canonicalPreselection")) {
                validateDeliveryHint(hint,
                        "$.input.feeder.canonicalPreselection[" + index++ + "]");
            }
        }
        if (feeder.has("channelLawCases")) {
            requireArray(feeder.get("channelLawCases"), "$.input.feeder.channelLawCases");
            index = 0;
            for (JsonNode law : feeder.get("channelLawCases")) {
                String path = "$.input.feeder.channelLawCases[" + index++ + "]";
                requireObject(law, path);
                closed(law, path, CHANNEL_LAW);
                requireFields(law, path, "accepts", "preselects", "keyIntersection");
                requireBoolean(law.get("accepts"), path + ".accepts");
                requireBoolean(law.get("preselects"), path + ".preselects");
                requireBoolean(law.get("keyIntersection"), path + ".keyIntersection");
            }
        }
        if (feeder.has("intervalHistory")) {
            requireTextArray(feeder.get("intervalHistory"), "$.input.feeder.intervalHistory");
        }
        if (feeder.has("rawIndexCandidates")) {
            requireTextArray(feeder.get("rawIndexCandidates"),
                    "$.input.feeder.rawIndexCandidates");
        }
        if (feeder.has("targetsByEvent")) {
            requireObject(feeder.get("targetsByEvent"), "$.input.feeder.targetsByEvent");
            for (Iterator<Map.Entry<String, JsonNode>> it =
                 feeder.get("targetsByEvent").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                requireTextArray(entry.getValue(),
                        "$.input.feeder.targetsByEvent." + entry.getKey());
            }
        }
        if (feeder.has("eventQueue")) {
            requireArray(feeder.get("eventQueue"), "$.input.feeder.eventQueue");
        }
        if (feeder.has("acceptanceStateVariants")) {
            requireArray(feeder.get("acceptanceStateVariants"),
                    "$.input.feeder.acceptanceStateVariants");
            for (JsonNode variant : feeder.get("acceptanceStateVariants")) {
                requireObject(variant, "$.input.feeder.acceptanceStateVariants[]");
            }
        }
        optionalBoolean(feeder, "$.input.feeder", "casConflict");
        optionalBoolean(feeder, "$.input.feeder", "currentEventAddsChannel");
    }

    private void validateDeliveryHint(JsonNode hint, String path) {
        requireObject(hint, path);
        closed(hint, path, DELIVERY_HINT);
        requireFields(hint, path, "scopePath", "channelKey");
        String scope = requireText(hint, path, "scopePath");
        if (!scope.startsWith("/")) {
            fail(path + ".scopePath", "must be an absolute runtime pointer");
        }
        requireText(hint, path, "channelKey");
        optionalNonNegativeInteger(hint, path, "order");
        if (hint.has("activationStartExclusive")) {
            validateOrderKey(hint.get("activationStartExclusive"),
                    path + ".activationStartExclusive");
        }
    }

    private void validateListOperation(JsonNode operation, String path) {
        requireObject(operation, path);
        closed(operation, path, LIST_OPERATION);
        requireFields(operation, path, "op", "size");
        requireMember(requireText(operation, path, "op"), path + ".op",
                set("append", "replace"));
        requireNonNegative(operation.get("size"), path + ".size");
        optionalNonNegativeInteger(operation, path, "delta");
        optionalNonNegativeInteger(operation, path, "index");
    }

    private void validateExpected(JsonNode expected) {
        closed(expected, "$.expected", EXPECTED);
        if (expected.size() == 0) {
            fail("$.expected", "at least one assertion or exact gas outcome is required");
        }
        if (expected.has("assertions")) {
            requireArray(expected.get("assertions"), "$.expected.assertions");
            if (expected.get("assertions").size() == 0) {
                fail("$.expected.assertions", "must not be empty");
            }
            int index = 0;
            for (JsonNode assertion : expected.get("assertions")) {
                validateAssertion(assertion, "$.expected.assertions[" + index++ + "]");
            }
        }
        if (expected.has("trace")) {
            requireArray(expected.get("trace"), "$.expected.trace");
        }
        for (String field : Arrays.asList(
                "totalGas", "listFoldStepRecomputed", "textBlockExamined",
                "validationProofReused", "directIdentityHashBlock", "integerLimbOperation")) {
            optionalNonNegativeInteger(expected, "$.expected", field);
        }
        optionalBoolean(expected, "$.expected", "failedChargeAbsent");
        if (expected.has("admitted")) {
            JsonNode admitted = expected.get("admitted");
            if (admitted.isBoolean()) {
                return;
            }
            requireArray(admitted, "$.expected.admitted");
            int index = 0;
            for (JsonNode item : admitted) {
                requireNonNegative(item, "$.expected.admitted[" + index++ + "]");
            }
        }
    }

    private void validateAssertion(JsonNode assertion, String path) {
        requireObject(assertion, path);
        closed(assertion, path, ASSERTION);
        requireFields(assertion, path, "actual", "op");
        requireText(assertion, path, "actual");
        String op = requireText(assertion, path, "op");
        requireMember(op, path + ".op", ASSERTION_OPERATORS);
        optionalText(assertion, path, "variant");
        optionalBoolean(assertion, path, "ordered");
        if ("equalsProjection".equals(op)) {
            requireFields(assertion, path, "expectedProjection");
            requireText(assertion, path, "expectedProjection");
            if (assertion.has("expected")) {
                fail(path + ".expected", "equalsProjection must not also declare expected");
            }
        } else if ("absent".equals(op)
                || "present".equals(op)
                || "sameAcrossVariants".equals(op)) {
            if (assertion.has("expected") || assertion.has("expectedProjection")) {
                fail(path, op + " does not accept an expected value");
            }
        } else {
            requireFields(assertion, path, "expected");
            if (assertion.has("expectedProjection")) {
                fail(path + ".expectedProjection",
                        "only equalsProjection accepts expectedProjection");
            }
        }
    }

    private static void validateVectors(JsonNode vectors) {
        requireArray(vectors, "$.vectors");
        if (vectors.size() == 0) {
            fail("$.vectors", "must not be empty");
        }
        Set<String> unique = new HashSet<>();
        int index = 0;
        for (JsonNode vector : vectors) {
            String path = "$.vectors[" + index++ + "]";
            if (!vector.isTextual() || !VECTOR.matcher(vector.asText()).matches()) {
                fail(path, "must match " + VECTOR.pattern());
            }
            if (!unique.add(vector.asText())) {
                fail(path, "duplicate vector " + vector.asText());
            }
        }
    }

    private static void validateOrderKey(JsonNode key, String path) {
        requireArray(key, path);
        if (key.size() < 3) {
            fail(path, "must contain at least three values");
        }
    }

    private static void closed(JsonNode object, String path, Set<String> allowed) {
        requireObject(object, path);
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!allowed.contains(field)) {
                fail(path + "." + field, "unknown field");
            }
        }
    }

    private static void requireFields(JsonNode object, String path, String... fields) {
        for (String field : fields) {
            if (!object.has(field) || object.get(field).isNull()) {
                fail(path + "." + field, "required field is missing");
            }
        }
    }

    private static JsonNode requireObjectField(JsonNode object, String path, String field) {
        requireFields(object, path, field);
        JsonNode value = object.get(field);
        requireObject(value, path + "." + field);
        return value;
    }

    private static void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            fail(path, "must be an object");
        }
    }

    private static void requireArray(JsonNode node, String path) {
        if (node == null || !node.isArray()) {
            fail(path, "must be an array");
        }
    }

    private static void requireTextArray(JsonNode node, String path) {
        requireArray(node, path);
        int index = 0;
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                fail(path + "[" + index + "]", "must be text");
            }
            index++;
        }
    }

    private static String requireText(JsonNode object, String path, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            fail(path + "." + field, "must be non-empty text");
        }
        return value.asText();
    }

    private static void optionalText(JsonNode object, String path, String field) {
        if (object.has(field)) {
            requireText(object, path, field);
        }
    }

    private static void requireExactText(JsonNode object,
                                         String path,
                                         String field,
                                         String expected) {
        String value = requireText(object, path, field);
        if (!expected.equals(value)) {
            fail(path + "." + field, "must equal " + expected);
        }
    }

    private static void requirePatternText(JsonNode object,
                                           String path,
                                           String field,
                                           Pattern pattern) {
        String value = requireText(object, path, field);
        if (!pattern.matcher(value).matches()) {
            fail(path + "." + field, "must match " + pattern.pattern());
        }
    }

    private static void optionalEnum(JsonNode object,
                                     String path,
                                     String field,
                                     Set<String> values) {
        if (object.has(field)) {
            requireMember(requireText(object, path, field), path + "." + field, values);
        }
    }

    private static void requireMember(String value, String path, Set<String> allowed) {
        if (!allowed.contains(value)) {
            fail(path, "unsupported value " + value);
        }
    }

    private static void optionalBoolean(JsonNode object, String path, String field) {
        if (object.has(field)) {
            requireBoolean(object.get(field), path + "." + field);
        }
    }

    private static void requireBoolean(JsonNode node, String path) {
        if (node == null || !node.isBoolean()) {
            fail(path, "must be a boolean");
        }
    }

    private static void optionalNonNegativeInteger(JsonNode object,
                                                   String path,
                                                   String field) {
        if (object.has(field)) {
            requireNonNegative(object.get(field), path + "." + field);
        }
    }

    private static void requireNonNegative(JsonNode node, String path) {
        if (node == null || !node.isIntegralNumber() || node.bigIntegerValue().signum() < 0) {
            fail(path, "must be a non-negative integer");
        }
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static void fail(String path, String message) {
        throw new IllegalArgumentException(path + ": " + message);
    }
}
