package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic implementation of the closed Contracts 1.0 fixture runtime.
 *
 * <p>Only controls declared by {@code fixture-schema.yaml} are consumed. A
 * scripted result is reachable exclusively through an ordinary selected
 * {@link MockHandler}; the runtime never writes a processor result or committed
 * document directly.</p>
 */
public final class ScriptedContractsRuntime {

    private static final String SCRIPTED_RESULT_APPLIED =
            "scriptedResultApplied";
    private static final String TEXT_BLOCK_CONSTRUCTED =
            "textBlockConstructed";
    private static final long CONFORMANCE_RUNTIME_COUNTER_WEIGHT = 1L;
    private static final long TEXT_BLOCK_CONSTRUCTED_WEIGHT =
            GasSchedule.contracts10()
                    .weight("semantic", TEXT_BLOCK_CONSTRUCTED);
    private static final long TEXT_BLOCK_CODE_POINTS =
            GasSchedule.contracts10()
                    .formulaParameter("textBlockCodePoints");

    private static final ScriptedContractsRuntime EMPTY =
            new ScriptedContractsRuntime(null);

    private final JsonNode controls;
    private final Map<String, JsonNode> handlerScripts = new LinkedHashMap<>();
    private boolean terminationIssued;
    private boolean nestedEnqueueStarted;
    private boolean cascadeMutationApplied;
    private int cascadeUpdateIndex;

    public ScriptedContractsRuntime(JsonNode runtimeControls) {
        this.controls = runtimeControls != null && runtimeControls.isObject()
                ? runtimeControls.deepCopy()
                : null;
        if (controls == null) {
            return;
        }
        JsonNode handlers = controls.get("handlers");
        if (handlers != null && handlers.isObject()) {
            handlers.fields().forEachRemaining(entry ->
                    handlerScripts.put(
                            normalizeContractPath(entry.getKey()),
                            entry.getValue().deepCopy()));
        }
    }

    public static ScriptedContractsRuntime empty() {
        return EMPTY;
    }

    public boolean hasHandlerScript(String contractPath) {
        return handlerScripts.containsKey(normalizeContractPath(contractPath));
    }

    public boolean matchesHandler(String contractPath,
                                  MockHandler contract,
                                  HandlerMatchContext context) {
        return context.matchesEventPattern(contract.getEvent());
    }

    public void executeHandler(String contractPath,
                               MockHandler contract,
                               ProcessorExecutionContext context) {
        JsonNode script = handlerScripts.get(normalizeContractPath(contractPath));
        if (script == null) {
            return;
        }
        String fail = text(script, "fail");
        if (fail != null) {
            context.throwFatal("Scripted Handler failed: " + fail);
        }
        executeResult(script.get("result"), context);
        executeInstalledControl(context);
        applyFirstTerminationRequest(context);
    }

    /**
     * Executes a result declared directly by a selected Scripted Handler.
     */
    public void executeDeclaredResult(Node result,
                                      ProcessorExecutionContext context) {
        if (result != null) {
            JsonNode encoded = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                    NodeToMapListOrValue.get(result));
            if (!isDefinitionOnlyResult(encoded)) {
                executeResult(encoded, context);
            }
        }
        executeInstalledControl(context);
        applyFirstTerminationRequest(context);
    }

    /**
     * Executes only behavior reached through the ordinary fixture contracts
     * installed by {@link ContractsFixtureHarness}. No control is a core hook:
     * if the corresponding Handler is not selected, none of this runs.
     */
    private void executeInstalledControl(ProcessorExecutionContext context) {
        if (controls == null) {
            return;
        }
        String key = context.contractKey();
        if (Boolean.getBoolean("blue.contracts.debugHandlers")) {
            System.err.println("fixture handler " + key);
        }
        if ("_fixture_init_handler".equals(key)) {
            if (hasEventType(
                    context,
                    RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED)) {
                JsonNode patches = listItems(
                        controls.get("initializationPatches"));
                if (patches != null) {
                    for (JsonNode patch : patches) {
                        context.applyPatch(toPatch(patch));
                    }
                }
            }
            return;
        }
        if ("_fixture_child_emitter_handler".equals(key)) {
            JsonNode emissions = listItems(
                    controls.get("childEmissions"));
            if (emissions != null) {
                for (JsonNode emission : emissions) {
                    context.emitEvent(readNode(emission));
                }
            }
            return;
        }
        if (key != null
                && key.startsWith("_fixture_forward_handler")) {
            context.emitEvent(context.event());
            return;
        }
        if ("_fixture_nested_handler".equals(key)) {
            emitNextNestedEvent(context);
            return;
        }
        if ("_fixture_cascade_handler".equals(key)) {
            applyCascadeMutation(context);
            return;
        }
        if ("_fixture_lifecycle_handler".equals(key)) {
            applyCascadeMutation(context);
            return;
        }
        if (controls.has("nestedEnqueues")
                && !nestedEnqueueStarted
                && (key == null || !key.startsWith("_fixture_"))) {
            long count = nonNegativeLong(
                    controls.get("nestedEnqueues"), "nestedEnqueues");
            nestedEnqueueStarted = true;
            if (count > 0L) {
                context.emitEvent(nestedEvent(1L));
            }
        }
    }

    private void emitNextNestedEvent(ProcessorExecutionContext context) {
        long limit = nonNegativeLong(
                controls.get("nestedEnqueues"), "nestedEnqueues");
        long current = scalarLong(property(context.event(), "fixtureSequence"));
        if (current > 0L && current < limit) {
            context.emitEvent(nestedEvent(current + 1L));
        }
    }

    private void applyCascadeMutation(ProcessorExecutionContext context) {
        JsonNode mutation = controls.get("cascadeMutation");
        if (mutation == null || !mutation.isObject()
                || cascadeMutationApplied) {
            return;
        }
        int target = mutation.has("afterPatchIndex")
                ? mutation.get("afterPatchIndex").asInt()
                : 0;
        String replaceScope = text(mutation, "replaceScope");
        if (mutation.path(
                "sourceCutOffDuringUpdate").asBoolean(false)) {
            String sourceScope = scalarText(
                    property(context.event(), "sourceScopePath"));
            if (sourceScope == null) {
                return;
            }
            if (replaceScope == null) {
                replaceScope = sourceScope;
            } else if (!replaceScope.equals(sourceScope)) {
                return;
            }
        }
        if (cascadeUpdateIndex++ < target) {
            return;
        }
        if (replaceScope == null || "/".equals(replaceScope)) {
            return;
        }
        cascadeMutationApplied = true;
        context.applyPatch(JsonPatch.replace(
                replaceScope, replacementScope(1L)));
        if (mutation.path("thenReaddSamePath").asBoolean(false)) {
            context.applyPatch(JsonPatch.replace(
                    replaceScope, replacementScope(2L)));
        }
    }

    private static Node nestedEvent(long sequence) {
        return new Node()
                .properties("id",
                        new Node().value("nested-" + sequence))
                .properties("fixtureSequence",
                        new Node().value(BigInteger.valueOf(sequence)));
    }

    private static Node replacementScope(long generation) {
        return new Node().properties(
                "fixtureGeneration",
                new Node().value(BigInteger.valueOf(generation)));
    }

    private void executeResult(JsonNode result,
                               ProcessorExecutionContext context) {
        if (result == null || result.isNull()) {
            return;
        }

        JsonNode runtimeCounters = result.get("runtimeCounters");
        Map<String, Long> weights = new LinkedHashMap<>();
        weights.put(
                SCRIPTED_RESULT_APPLIED,
                CONFORMANCE_RUNTIME_COUNTER_WEIGHT);
        if (runtimeCounters != null && runtimeCounters.isObject()) {
            runtimeCounters.fieldNames().forEachRemaining(
                    name -> weights.put(
                            name,
                            CONFORMANCE_RUNTIME_COUNTER_WEIGHT));
        }
        if (hasConstructedText(result.get("events"))) {
            weights.put(
                    TEXT_BLOCK_CONSTRUCTED,
                    TEXT_BLOCK_CONSTRUCTED_WEIGHT);
        }

        GasMeter.ChildGasLedger ledger =
                context.newRuntimeGasLedger("runtime", weights);
        String fail = text(result, "fail");
        try {
            ledger.charge(SCRIPTED_RESULT_APPLIED, 1L);

            if (fail == null
                    && runtimeCounters != null
                    && runtimeCounters.isObject()) {
                runtimeCounters.fields().forEachRemaining(entry ->
                        ledger.charge(
                                entry.getKey(),
                                nonNegativeLong(
                                        entry.getValue(),
                                        "runtimeCounters." + entry.getKey())));
            }
            if (fail == null) {
                JsonNode patches = listItems(result.get("patches"));
                if (patches != null) {
                    for (JsonNode patch : patches) {
                        context.applyPatch(toPatch(patch));
                    }
                }
                JsonNode events = listItems(result.get("events"));
                if (events != null) {
                    for (JsonNode event : events) {
                        context.emitEvent(
                                expandConstructedText(readNode(event), ledger));
                    }
                }
                JsonNode termination = result.get("termination");
                if (termination != null && !termination.isNull()) {
                    applyTermination(termination, context);
                }
            }
        } finally {
            context.submitRuntimeGasLedger(ledger);
        }
        if (fail != null) {
            context.throwFatal("Scripted Handler failed: " + fail);
        }
    }

    private void applyFirstTerminationRequest(ProcessorExecutionContext context) {
        if (terminationIssued || controls == null) {
            return;
        }
        JsonNode requests = controls.get("terminationRequests");
        if (requests == null || !requests.isArray() || requests.size() == 0) {
            return;
        }
        terminationIssued = true;
        applyTermination(requests.get(0), context);
    }

    private static void applyTermination(JsonNode termination,
                                         ProcessorExecutionContext context) {
        if (termination.isObject()) {
            String cause = text(termination, "cause");
            String reason = text(termination, "reason");
            context.terminate(cause != null ? cause : "completed", reason);
            return;
        }
        context.terminate("completed", termination.asText(null));
    }

    private static JsonPatch toPatch(JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw new IllegalArgumentException("Scripted patch must be an object");
        }
        String op = text(patch, "op");
        String path = text(patch, "path");
        if (op == null || path == null) {
            throw new IllegalArgumentException(
                    "Scripted patch requires op and path");
        }
        if ("remove".equals(op)) {
            return JsonPatch.remove(path);
        }
        JsonNode rawValue = patch.get("val");
        if (rawValue == null) {
            throw new IllegalArgumentException(
                    "Scripted add/replace patch requires val");
        }
        Node value = readNode(rawValue);
        if ("add".equals(op)) {
            return JsonPatch.add(path, value);
        }
        if ("replace".equals(op)) {
            return JsonPatch.replace(path, value);
        }
        throw new IllegalArgumentException("Unsupported scripted patch op: " + op);
    }

    private static boolean hasConstructedText(JsonNode events) {
        JsonNode items = listItems(events);
        if (items == null) {
            return false;
        }
        for (JsonNode event : items) {
            if (event != null
                    && event.isObject()
                    && event.has("constructedText")) {
                return true;
            }
        }
        return false;
    }

    private static Node expandConstructedText(
            Node event,
            GasMeter.ChildGasLedger ledger) {
        Node constructed = property(event, "constructedText");
        if (constructed == null) {
            return event;
        }
        String unit = scalarText(property(constructed, "repeat"));
        long count = scalarLong(property(constructed, "count"));
        if (unit == null || unit.codePointCount(0, unit.length()) != 1 || count < 0L) {
            throw new IllegalArgumentException(
                    "constructedText requires one code point and a non-negative count");
        }
        ledger.charge(
                TEXT_BLOCK_CONSTRUCTED,
                textBlocks(count));
        StringBuilder text = new StringBuilder();
        for (long index = 0L; index < count; index++) {
            text.append(unit);
        }
        Node expanded = event.clone();
        expanded.getProperties().remove("constructedText");
        expanded.properties("text", new Node().value(text.toString()));
        return expanded;
    }

    private static long textBlocks(long codePointCount) {
        return codePointCount == 0L
                ? 0L
                : 1L + ((codePointCount - 1L) / TEXT_BLOCK_CODE_POINTS);
    }

    public static String contractPath(String scopePath, String contractKey) {
        String scope = PointerUtils.normalizePointer(scopePath);
        String escaped = contractKey == null ? "" : contractKey
                .replace("~", "~0")
                .replace("/", "~1");
        return "/".equals(scope)
                ? "/contracts/" + escaped
                : scope + "/contracts/" + escaped;
    }

    private static String normalizeContractPath(String path) {
        return PointerUtils.normalizePointer(path);
    }

    private static Node readNode(JsonNode value) {
        return UncheckedObjectMapper.JSON_MAPPER.convertValue(value, Node.class);
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object != null ? object.get(field) : null;
        value = scalarValue(value);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static long nonNegativeLong(JsonNode value, String path) {
        value = scalarValue(value);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.asLong() < 0L) {
            throw new IllegalArgumentException(path + " must be a non-negative long");
        }
        return value.asLong();
    }

    private static JsonNode listItems(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isArray()) {
            return value;
        }
        JsonNode items = value.isObject() ? value.get("items") : null;
        return items != null && items.isArray() ? items : null;
    }

    private static JsonNode scalarValue(JsonNode value) {
        if (value != null && value.isObject()) {
            JsonNode scalar = value.get("value");
            if (scalar != null) {
                return scalar;
            }
        }
        return value;
    }

    private static boolean isDefinitionOnlyResult(JsonNode result) {
        JsonNode type = result != null ? result.get("type") : null;
        if (type == null
                || !type.isObject()
                || type.path("blueId").isTextual()) {
            return false;
        }
        return listItems(result.get("patches")) == null
                && listItems(result.get("events")) == null
                && text(result, "fail") == null
                && result.get("runtimeCounters") == null
                && !hasConcreteTermination(
                result.get("termination"));
    }

    private static boolean hasConcreteTermination(JsonNode termination) {
        JsonNode scalar = scalarValue(termination);
        if (scalar != termination) {
            return scalar != null && !scalar.isNull();
        }
        return termination != null
                && termination.isObject()
                && (text(termination, "cause") != null
                || text(termination, "reason") != null);
    }

    private static Node property(Node node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private static boolean hasEventType(
            ProcessorExecutionContext context,
            String blueId) {
        Node event = context.event();
        return event != null
                && event.getType() != null
                && blueId.equals(event.getType().getBlueId());
    }

    private static String scalarText(Node node) {
        return node != null && node.getValue() instanceof String
                ? (String) node.getValue()
                : null;
    }

    private static long scalarLong(Node node) {
        Object value = node != null ? node.getValue() : null;
        if (value instanceof BigInteger) {
            return ((BigInteger) value).longValueExact();
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return -1L;
    }

}
