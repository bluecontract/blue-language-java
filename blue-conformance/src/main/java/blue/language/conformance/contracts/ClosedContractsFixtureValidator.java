package blue.language.conformance.contracts;

import blue.language.conformance.api.BlueContractsFixtureCategory;
import blue.language.processor.GasScheduleConstants;
import blue.language.model.wire.BlueLanguageConstants;
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
final class ClosedContractsFixtureValidator {

    private static final Pattern ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*$");
    private static final Pattern VECTOR =
            Pattern.compile("^C-[A-Z0-9]+-[0-9]{2}$");

    private static final Set<String> TOP = set(
            BlueLanguageConstants.OBJECT_SCHEMA,
            ContractsFixtureConstants.Field.ID,
            ContractsFixtureConstants.Field.VECTORS,
            ContractsFixtureConstants.Field.CATEGORY,
            ContractsFixtureConstants.Field.DESCRIPTION,
            ContractsFixtureConstants.Field.OPERATION,
            ContractsFixtureConstants.Field.INPUT,
            ContractsFixtureConstants.Field.EXPECTED);
    private static final Set<String> INPUT = set(
            ContractsFixtureConstants.Field.ROOT,
            ContractsFixtureConstants.Field.EVENT,
            ContractsFixtureConstants.Field.FEEDER,
            ContractsFixtureConstants.Field.PROVIDER,
            ContractsFixtureConstants.Field.RUNTIME,
            ContractsFixtureConstants.Field.BUILDERS,
            ContractsFixtureConstants.Field.VARIANTS,
            ContractsFixtureConstants.Field.NAMESPACE,
            ContractsFixtureConstants.Field.COUNTER,
            ContractsFixtureConstants.Field.QUANTITY,
            ContractsFixtureConstants.Field.WEIGHT_MANIFEST,
            ContractsFixtureConstants.Field.OLD_LENGTH,
            ContractsFixtureConstants.Field.LIMIT,
            ContractsFixtureConstants.Field.CHARGES,
            ContractsFixtureConstants.Field.TEXT_CODE_POINTS_EXAMINED,
            ContractsFixtureConstants.Field.PROOF_KEY,
            ContractsFixtureConstants.Field.USES,
            ContractsFixtureConstants.Field.DIRECT_CANONICAL_BYTES,
            ContractsFixtureConstants.Field.OPERATION,
            ContractsFixtureConstants.Field.LEFT_LIMBS,
            ContractsFixtureConstants.Field.RIGHT_LIMBS,
            ContractsFixtureConstants.Field.REPLACE_INDEX,
            ContractsFixtureConstants.Field.PRIOR_EXACT_IDENTITY,
            ContractsFixtureConstants.Field.APPEND);
    private static final Set<String> BUILDER = set(
            "kind", "target", "memberCount", "itemCount", "codePointCount",
            "keyPrefix", BlueLanguageConstants.OBJECT_VALUE, "item", "text");
    private static final Set<String> PROVIDER = set(
            "mode", "semanticDemandsOnly", "nodes", "transientUnavailableAt");
    private static final Set<String> RUNTIME = set(
            ContractsFixtureConstants.Field.TYPE_REGISTRY_MANIFEST,
            ContractsFixtureConstants.Field.HANDLERS,
            "cascadeMutation", "childEmissions",
            "gasLimit", "gasLimitDuringTermination", "generalizationCandidates",
            "generalizationSubtypeContracts",
            "initializationPatches", "nestedEnqueues", "rootForwardAll",
            "terminationRequests", "validCandidate");
    private static final Set<String> SCRIPTED_HANDLER = set(
            ContractsFixtureConstants.Field.RESULT,
            ContractsFixtureConstants.Field.FAIL);
    private static final Set<String> SCRIPTED_RESULT = set(
            ContractsFixtureConstants.Field.PATCHES,
            ContractsFixtureConstants.Field.EVENTS,
            ContractsFixtureConstants.Field.TERMINATION,
            ContractsFixtureConstants.Field.FAIL,
            ContractsFixtureConstants.Field.RUNTIME_COUNTERS);
    private static final Set<String> CASCADE = set(
            "afterPatchIndex", "replaceScope", "thenReaddSamePath",
            "replaceScopeDuringLifecycle", "sourceCutOffDuringUpdate");
    private static final Set<String> TERMINATION_REQUEST = set("cause", ContractsFixtureConstants.Field.REASON);
    private static final Set<String> FEEDER = set(
            "managedRootRevision", "indexedRootRevision", "evaluatedRevision",
            ContractsFixtureConstants.Field.EVENT_ORDER_KEY,
            ContractsFixtureConstants.Field.DELIVERY_SNAPSHOT,
            "acceptanceStateVariants",
            "canonicalPreselection", "casConflict", "channelLawCases",
            "currentEventAddsChannel", "eventQueue", "intervalHistory",
            "rawIndexCandidates", "sameFailureCount", "targetsByEvent");
    private static final Set<String> DELIVERY_HINT = set(
            ContractsFixtureConstants.Field.SCOPE_PATH,
            ContractsFixtureConstants.Field.CHANNEL_KEY,
            ContractsFixtureConstants.Field.ORDER,
            ContractsFixtureConstants.Field.ACTIVATION_START_EXCLUSIVE);
    private static final Set<String> CHANNEL_LAW = set(
            "accepts", "preselects", "keyIntersection");
    private static final Set<String> VARIANT = set(
            ContractsFixtureConstants.Field.NAME,
            ContractsFixtureConstants.Field.ACCEPT,
            ContractsFixtureConstants.Field.BATCHING,
            ContractsFixtureConstants.Field.CACHE,
            "checkpointSubject",
            ContractsFixtureConstants.Field.LIST_OPERATION,
            "newEmbeddedSurface",
            ContractsFixtureConstants.Field.ROOT_FORM,
            ContractsFixtureConstants.Field.ROOT_REVISION,
            ContractsFixtureConstants.Field.SAME_EVENT);
    private static final Set<String> LIST_OPERATION = set(
            ContractsFixtureConstants.Field.OP,
            ContractsFixtureConstants.Field.SIZE,
            ContractsFixtureConstants.Field.DELTA,
            ContractsFixtureConstants.Field.INDEX);
    private static final Set<String> EXPECTED = set(
            ContractsFixtureConstants.Field.ASSERTIONS,
            ContractsFixtureConstants.Field.TRACE,
            ContractsFixtureConstants.Field.TOTAL_GAS,
            ContractsFixtureConstants.Field.LIST_FOLD_STEP_RECOMPUTED,
            ContractsFixtureConstants.Field.ADMITTED,
            ContractsFixtureConstants.Field.FAILED_CHARGE_ABSENT,
            ContractsFixtureConstants.Field.TEXT_BLOCK_EXAMINED,
            ContractsFixtureConstants.Field.VALIDATION_PROOF_REUSED,
            ContractsFixtureConstants.Field.DIRECT_IDENTITY_HASH_BLOCK,
            ContractsFixtureConstants.Field.INTEGER_LIMB_OPERATION);
    private static final Set<String> ASSERTION = set(
            ContractsFixtureConstants.Field.ACTUAL,
            ContractsFixtureConstants.Field.OP,
            ContractsFixtureConstants.Field.EXPECTED,
            ContractsFixtureConstants.Field.EXPECTED_PROJECTION,
            ContractsFixtureConstants.Field.VARIANT,
            ContractsFixtureConstants.Field.ORDERED);
    private static final Set<String> CHARGE = set(
            ContractsFixtureConstants.Field.COUNTER,
            ContractsFixtureConstants.Field.QUANTITY);
    private static final Set<String> OPERATIONS =
            set(ContractsFixtureConstants.Operation.PROCESS,
                    ContractsFixtureConstants.Operation.PROCESS_ATTEMPT,
                    ContractsFixtureConstants.Operation.PLATFORM,
                    ContractsFixtureConstants.Operation.GAS_MICRO);
    private static final Set<String> ASSERTION_OPERATORS = set(
            ContractsFixtureConstants.AssertionOperator.EQUALS,
            ContractsFixtureConstants.AssertionOperator.NOT_EQUALS,
            ContractsFixtureConstants.AssertionOperator.EQUALS_PROJECTION,
            ContractsFixtureConstants.AssertionOperator.ABSENT,
            ContractsFixtureConstants.AssertionOperator.PRESENT,
            ContractsFixtureConstants.AssertionOperator.SEQUENCE_EQUALS,
            ContractsFixtureConstants.AssertionOperator.CONTAINS,
            ContractsFixtureConstants.AssertionOperator.NOT_CONTAINS,
            ContractsFixtureConstants.AssertionOperator.LESS_THAN,
            ContractsFixtureConstants.AssertionOperator.GREATER_THAN,
            ContractsFixtureConstants.AssertionOperator.SAME_ACROSS_VARIANTS,
            ContractsFixtureConstants.AssertionOperator.FAILS_WITH,
            ContractsFixtureConstants.AssertionOperator.ALL,
            ContractsFixtureConstants.AssertionOperator.NONE);

    /**
     * Creates a stateless validator for the closed Contracts 1.0 fixture format.
     */
    public ClosedContractsFixtureValidator() {
    }

    /**
     * Validates the complete fixture envelope and every operation-specific
     * control without executing the fixture.
     *
     * @param fixture candidate fixture JSON
     * @throws IllegalArgumentException when a required field, type, closed
     *         object surface, identifier, or operation-specific invariant is
     *         invalid
     */
    public void validate(JsonNode fixture) {
        requireObject(fixture, "$");
        closed(fixture, "$", TOP);
        requireFields(
                fixture,
                "$",
                BlueLanguageConstants.OBJECT_SCHEMA,
                ContractsFixtureConstants.Field.ID,
                ContractsFixtureConstants.Field.VECTORS,
                ContractsFixtureConstants.Field.CATEGORY,
                ContractsFixtureConstants.Field.OPERATION,
                ContractsFixtureConstants.Field.INPUT,
                ContractsFixtureConstants.Field.EXPECTED);
        requireExactText(
                fixture,
                "$",
                BlueLanguageConstants.OBJECT_SCHEMA,
                "blue-contracts-fixture/1.0");
        requirePatternText(
                fixture, "$", ContractsFixtureConstants.Field.ID, ID);
        validateVectors(
                fixture.get(ContractsFixtureConstants.Field.VECTORS));
        BlueContractsFixtureCategory.fromLabel(requireText(
                fixture, "$", ContractsFixtureConstants.Field.CATEGORY));
        String operation = requireText(
                fixture, "$", ContractsFixtureConstants.Field.OPERATION);
        requireMember(operation, "$.operation", OPERATIONS);
        optionalText(
                fixture, "$", ContractsFixtureConstants.Field.DESCRIPTION);

        JsonNode input = requireObjectField(
                fixture, "$", ContractsFixtureConstants.Field.INPUT);
        validateInput(input, operation);
        JsonNode expected = requireObjectField(
                fixture, "$", ContractsFixtureConstants.Field.EXPECTED);
        validateExpected(expected);
    }

    private void validateInput(JsonNode input, String operation) {
        closed(input, "$.input", INPUT);
        if (!ContractsFixtureConstants.Operation.GAS_MICRO.equals(
                operation)) {
            requireFields(
                    input,
                    "$.input",
                    ContractsFixtureConstants.Field.ROOT,
                    ContractsFixtureConstants.Field.EVENT,
                    ContractsFixtureConstants.Field.FEEDER,
                    ContractsFixtureConstants.Field.PROVIDER,
                    ContractsFixtureConstants.Field.RUNTIME);
        }
        if (input.has(ContractsFixtureConstants.Field.BUILDERS)) {
            requireArray(
                    input.get(ContractsFixtureConstants.Field.BUILDERS),
                    "$.input.builders");
            int index = 0;
            for (JsonNode builder
                    : input.get(ContractsFixtureConstants.Field.BUILDERS)) {
                validateBuilder(builder, "$.input.builders[" + index++ + "]");
            }
        }
        if (input.has(ContractsFixtureConstants.Field.PROVIDER)) {
            validateProvider(
                    input.get(ContractsFixtureConstants.Field.PROVIDER));
        }
        if (input.has(ContractsFixtureConstants.Field.RUNTIME)) {
            validateRuntime(
                    input.get(ContractsFixtureConstants.Field.RUNTIME));
        }
        if (input.has(ContractsFixtureConstants.Field.FEEDER)) {
            validateFeeder(
                    input.get(ContractsFixtureConstants.Field.FEEDER));
        }
        if (input.has(ContractsFixtureConstants.Field.VARIANTS)) {
            requireArray(
                    input.get(ContractsFixtureConstants.Field.VARIANTS),
                    "$.input.variants");
            Set<String> names = new LinkedHashSet<>();
            int index = 0;
            for (JsonNode variant
                    : input.get(ContractsFixtureConstants.Field.VARIANTS)) {
                String path = "$.input.variants[" + index++ + "]";
                requireObject(variant, path);
                closed(variant, path, VARIANT);
                requireFields(
                        variant, path, ContractsFixtureConstants.Field.NAME);
                String name = requireText(
                        variant, path, ContractsFixtureConstants.Field.NAME);
                if (!names.add(name)) {
                    fail(path + ".name", "duplicate variant name " + name);
                }
                if (variant.size() == 1) {
                    fail(path, "a variant name alone has no semantics");
                }
                optionalEnum(
                        variant,
                        path,
                        ContractsFixtureConstants.Field.ROOT_FORM,
                        set("inline", "reference", "eager", "lazy"));
                optionalEnum(
                        variant,
                        path,
                        ContractsFixtureConstants.Field.CACHE,
                        set("warm", "cold"));
                optionalEnum(
                        variant,
                        path,
                        ContractsFixtureConstants.Field.BATCHING,
                        set("batched", "unbatched"));
                optionalBoolean(
                        variant, path, ContractsFixtureConstants.Field.ACCEPT);
                optionalBoolean(
                        variant,
                        path,
                        ContractsFixtureConstants.Field.SAME_EVENT);
                optionalNonNegativeInteger(
                        variant,
                        path,
                        ContractsFixtureConstants.Field.ROOT_REVISION);
                if (variant.has(
                        ContractsFixtureConstants.Field.LIST_OPERATION)) {
                    validateListOperation(
                            variant.get(
                                    ContractsFixtureConstants.Field
                                            .LIST_OPERATION),
                            path + ".listOperation");
                }
            }
        }
        optionalEnum(
                input,
                "$.input",
                ContractsFixtureConstants.Field.NAMESPACE,
                set(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.Namespace.SEMANTIC,
                        ContractsFixtureConstants.RuntimeNamespace.RUNTIME));
        optionalText(
                input, "$.input", ContractsFixtureConstants.Field.COUNTER);
        optionalText(
                input,
                "$.input",
                ContractsFixtureConstants.Field.WEIGHT_MANIFEST);
        for (String field : Arrays.asList(
                ContractsFixtureConstants.Field.QUANTITY,
                ContractsFixtureConstants.Field.OLD_LENGTH,
                ContractsFixtureConstants.Field.LIMIT,
                ContractsFixtureConstants.Field.TEXT_CODE_POINTS_EXAMINED,
                ContractsFixtureConstants.Field.USES,
                ContractsFixtureConstants.Field.DIRECT_CANONICAL_BYTES,
                ContractsFixtureConstants.Field.LEFT_LIMBS,
                ContractsFixtureConstants.Field.RIGHT_LIMBS,
                ContractsFixtureConstants.Field.REPLACE_INDEX,
                ContractsFixtureConstants.Field.APPEND)) {
            optionalNonNegativeInteger(input, "$.input", field);
        }
        optionalText(
                input, "$.input", ContractsFixtureConstants.Field.PROOF_KEY);
        optionalText(
                input, "$.input", ContractsFixtureConstants.Field.OPERATION);
        optionalBoolean(
                input,
                "$.input",
                ContractsFixtureConstants.Field.PRIOR_EXACT_IDENTITY);
        if (input.has(ContractsFixtureConstants.Field.CHARGES)) {
            requireArray(
                    input.get(ContractsFixtureConstants.Field.CHARGES),
                    "$.input.charges");
            int index = 0;
            for (JsonNode charge
                    : input.get(ContractsFixtureConstants.Field.CHARGES)) {
                String path = "$.input.charges[" + index++ + "]";
                if (charge.isIntegralNumber()) {
                    requireNonNegative(charge, path);
                } else {
                    requireObject(charge, path);
                    closed(charge, path, CHARGE);
                    requireFields(
                            charge,
                            path,
                            ContractsFixtureConstants.Field.COUNTER,
                            ContractsFixtureConstants.Field.QUANTITY);
                    requireText(
                            charge,
                            path,
                            ContractsFixtureConstants.Field.COUNTER);
                    requireNonNegative(
                            charge.get(
                                    ContractsFixtureConstants.Field.QUANTITY),
                            path + ".quantity");
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
            requireFields(builder, path, "memberCount", "keyPrefix", BlueLanguageConstants.OBJECT_VALUE);
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
        requireFields(
                runtime,
                "$.input.runtime",
                ContractsFixtureConstants.Field.TYPE_REGISTRY_MANIFEST);
        requireExactText(
                runtime,
                "$.input.runtime",
                ContractsFixtureConstants.Field.TYPE_REGISTRY_MANIFEST,
                "../../registry/manifest.yaml");
        if (runtime.has(ContractsFixtureConstants.Field.HANDLERS)) {
            requireObject(
                    runtime.get(ContractsFixtureConstants.Field.HANDLERS),
                    "$.input.runtime.handlers");
            for (Iterator<Map.Entry<String, JsonNode>> it = runtime.get(
                    ContractsFixtureConstants.Field.HANDLERS).fields();
                 it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String path = "$.input.runtime.handlers." + entry.getKey();
                if (!entry.getKey().startsWith("/")) {
                    fail(path, "handler key must be an absolute Root pointer");
                }
                requireObject(entry.getValue(), path);
                closed(entry.getValue(), path, SCRIPTED_HANDLER);
                if (entry.getValue().has(
                        ContractsFixtureConstants.Field.RESULT)) {
                    validateScriptedResult(
                            entry.getValue().get(
                                    ContractsFixtureConstants.Field.RESULT),
                            path + ".result");
                }
                optionalText(
                        entry.getValue(),
                        path,
                        ContractsFixtureConstants.Field.FAIL);
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
        if (runtime.has("generalizationSubtypeContracts")) {
            requireObject(
                    runtime.get("generalizationSubtypeContracts"),
                    "$.input.runtime.generalizationSubtypeContracts");
            if (!runtime.has("generalizationCandidates")) {
                fail(
                        "$.input.runtime.generalizationSubtypeContracts",
                        "requires generalizationCandidates");
            }
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
                optionalText(request, path, ContractsFixtureConstants.Field.REASON);
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
        if (result.has(ContractsFixtureConstants.Field.PATCHES)) {
            requireArray(
                    result.get(ContractsFixtureConstants.Field.PATCHES),
                    path + ".patches");
        }
        if (result.has(ContractsFixtureConstants.Field.EVENTS)) {
            requireArray(
                    result.get(ContractsFixtureConstants.Field.EVENTS),
                    path + ".events");
        }
        optionalText(result, path, ContractsFixtureConstants.Field.FAIL);
        if (result.has(ContractsFixtureConstants.Field.RUNTIME_COUNTERS)) {
            requireObject(
                    result.get(
                            ContractsFixtureConstants.Field.RUNTIME_COUNTERS),
                    path + ".runtimeCounters");
            for (Iterator<Map.Entry<String, JsonNode>> it =
                 result.get(
                         ContractsFixtureConstants.Field.RUNTIME_COUNTERS)
                         .fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                requireNonNegative(entry.getValue(), path + ".runtimeCounters." + entry.getKey());
            }
        }
    }

    private void validateFeeder(JsonNode feeder) {
        requireObject(feeder, "$.input.feeder");
        closed(feeder, "$.input.feeder", FEEDER);
        requireFields(feeder, "$.input.feeder",
                "managedRootRevision",
                "indexedRootRevision",
                ContractsFixtureConstants.Field.EVENT_ORDER_KEY,
                ContractsFixtureConstants.Field.DELIVERY_SNAPSHOT);
        optionalNonNegativeInteger(feeder, "$.input.feeder", "managedRootRevision");
        optionalNonNegativeInteger(feeder, "$.input.feeder", "indexedRootRevision");
        optionalNonNegativeInteger(feeder, "$.input.feeder", "evaluatedRevision");
        optionalNonNegativeInteger(feeder, "$.input.feeder", "sameFailureCount");
        validateOrderKey(
                feeder.get(ContractsFixtureConstants.Field.EVENT_ORDER_KEY),
                "$.input.feeder.eventOrderKey");
        requireArray(
                feeder.get(ContractsFixtureConstants.Field.DELIVERY_SNAPSHOT),
                "$.input.feeder.deliverySnapshot");
        int index = 0;
        for (JsonNode hint : feeder.get(
                ContractsFixtureConstants.Field.DELIVERY_SNAPSHOT)) {
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
        requireFields(
                hint,
                path,
                ContractsFixtureConstants.Field.SCOPE_PATH,
                ContractsFixtureConstants.Field.CHANNEL_KEY);
        String scope = requireText(
                hint, path, ContractsFixtureConstants.Field.SCOPE_PATH);
        if (!scope.startsWith("/")) {
            fail(path + ".scopePath", "must be an absolute runtime pointer");
        }
        requireText(
                hint, path, ContractsFixtureConstants.Field.CHANNEL_KEY);
        optionalNonNegativeInteger(
                hint, path, ContractsFixtureConstants.Field.ORDER);
        if (hint.has(
                ContractsFixtureConstants.Field.ACTIVATION_START_EXCLUSIVE)) {
            validateOrderKey(hint.get(
                            ContractsFixtureConstants.Field
                                    .ACTIVATION_START_EXCLUSIVE),
                    path + ".activationStartExclusive");
        }
    }

    private void validateListOperation(JsonNode operation, String path) {
        requireObject(operation, path);
        closed(operation, path, LIST_OPERATION);
        requireFields(
                operation,
                path,
                ContractsFixtureConstants.Field.OP,
                ContractsFixtureConstants.Field.SIZE);
        requireMember(
                requireText(
                        operation, path, ContractsFixtureConstants.Field.OP),
                path + ".op",
                set(
                        ContractsFixtureConstants.ListOperation.APPEND,
                        ContractsFixtureConstants.ListOperation.REPLACE));
        requireNonNegative(
                operation.get(ContractsFixtureConstants.Field.SIZE),
                path + ".size");
        optionalNonNegativeInteger(
                operation, path, ContractsFixtureConstants.Field.DELTA);
        optionalNonNegativeInteger(
                operation, path, ContractsFixtureConstants.Field.INDEX);
    }

    private void validateExpected(JsonNode expected) {
        closed(expected, "$.expected", EXPECTED);
        if (expected.size() == 0) {
            fail("$.expected", "at least one assertion or exact gas outcome is required");
        }
        if (expected.has(ContractsFixtureConstants.Field.ASSERTIONS)) {
            requireArray(
                    expected.get(ContractsFixtureConstants.Field.ASSERTIONS),
                    "$.expected.assertions");
            if (expected.get(
                    ContractsFixtureConstants.Field.ASSERTIONS).size() == 0) {
                fail("$.expected.assertions", "must not be empty");
            }
            int index = 0;
            for (JsonNode assertion
                    : expected.get(
                            ContractsFixtureConstants.Field.ASSERTIONS)) {
                validateAssertion(assertion, "$.expected.assertions[" + index++ + "]");
            }
        }
        if (expected.has(ContractsFixtureConstants.Field.TRACE)) {
            requireArray(
                    expected.get(ContractsFixtureConstants.Field.TRACE),
                    "$.expected.trace");
        }
        for (String field : Arrays.asList(
                ContractsFixtureConstants.Field.TOTAL_GAS,
                ContractsFixtureConstants.Field.LIST_FOLD_STEP_RECOMPUTED,
                ContractsFixtureConstants.Field.TEXT_BLOCK_EXAMINED,
                ContractsFixtureConstants.Field.VALIDATION_PROOF_REUSED,
                ContractsFixtureConstants.Field.DIRECT_IDENTITY_HASH_BLOCK,
                ContractsFixtureConstants.Field.INTEGER_LIMB_OPERATION)) {
            optionalNonNegativeInteger(expected, "$.expected", field);
        }
        optionalBoolean(
                expected,
                "$.expected",
                ContractsFixtureConstants.Field.FAILED_CHARGE_ABSENT);
        if (expected.has(ContractsFixtureConstants.Field.ADMITTED)) {
            JsonNode admitted = expected.get(
                    ContractsFixtureConstants.Field.ADMITTED);
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
        requireFields(
                assertion,
                path,
                ContractsFixtureConstants.Field.ACTUAL,
                ContractsFixtureConstants.Field.OP);
        requireText(
                assertion, path, ContractsFixtureConstants.Field.ACTUAL);
        String op = requireText(
                assertion,
                path,
                ContractsFixtureConstants.Field.OP);
        requireMember(op, path + ".op", ASSERTION_OPERATORS);
        optionalText(
                assertion, path, ContractsFixtureConstants.Field.VARIANT);
        optionalBoolean(
                assertion, path, ContractsFixtureConstants.Field.ORDERED);
        if (ContractsFixtureConstants.AssertionOperator.EQUALS_PROJECTION
                .equals(op)) {
            requireFields(
                    assertion,
                    path,
                    ContractsFixtureConstants.Field.EXPECTED_PROJECTION);
            requireText(
                    assertion,
                    path,
                    ContractsFixtureConstants.Field.EXPECTED_PROJECTION);
            if (assertion.has(
                    ContractsFixtureConstants.Field.EXPECTED)) {
                fail(path + ".expected", "equalsProjection must not also declare expected");
            }
        } else if (ContractsFixtureConstants.AssertionOperator.ABSENT
                .equals(op)
                || ContractsFixtureConstants.AssertionOperator.PRESENT
                .equals(op)
                || ContractsFixtureConstants.AssertionOperator
                .SAME_ACROSS_VARIANTS.equals(op)) {
            if (assertion.has(ContractsFixtureConstants.Field.EXPECTED)
                    || assertion.has(
                            ContractsFixtureConstants.Field
                                    .EXPECTED_PROJECTION)) {
                fail(path, op + " does not accept an expected value");
            }
        } else {
            requireFields(
                    assertion,
                    path,
                    ContractsFixtureConstants.Field.EXPECTED);
            if (assertion.has(
                    ContractsFixtureConstants.Field.EXPECTED_PROJECTION)) {
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
