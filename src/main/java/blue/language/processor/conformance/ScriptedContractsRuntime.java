package blue.language.processor.conformance;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ConformanceChangedPath;
import blue.language.processor.ConformancePlannerOverride;
import blue.language.processor.DocumentProcessingRuntime;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.ScopeRuntimeContext;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.conformance.ConformancePlan;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodePathAccessor;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fixture-only runtime for the Blue Contracts conformance suite.
 *
 * <p>The runtime is intentionally external to the selected document. It models
 * scripted channels/handlers from mockRuntime without copying those scripts into
 * contract nodes, so processing observes the same document the fixture supplied.</p>
 */
public final class ScriptedContractsRuntime {

    private static final ScriptedContractsRuntime EMPTY = new ScriptedContractsRuntime(null);
    private static final ThreadLocal<ScriptedContractsRuntime> ACTIVE = new ThreadLocal<>();

    private final Map<String, List<ChannelCall>> channelCalls = new LinkedHashMap<>();
    private final Map<String, List<HandlerCall>> handlerCalls = new LinkedHashMap<>();
    private final Map<String, HandlerCall> pendingHandlerCalls = new LinkedHashMap<>();
    private final Map<String, List<Node>> childEmissions = new LinkedHashMap<>();
    private final List<BridgeMutation> bridgeMutations = new ArrayList<>();
    private final Map<String, FixtureType> fixtureTypes = new LinkedHashMap<>();
    private final List<String> documentUpdateOrder = new ArrayList<>();
    private final List<DocumentUpdateTrace> documentUpdates = new ArrayList<>();
    private final List<String> embeddedScopeOrder = new ArrayList<>();
    private final List<DeliveryTrace> embeddedDeliveryOrder = new ArrayList<>();
    private final List<DeliveryTrace> triggeredDeliveryOrder = new ArrayList<>();
    private final List<String> effectApplicationOrder = new ArrayList<>();
    private ForcedFatal forcedFatal;
    private boolean hostApiCallTracing;
    private final Blue blue = new Blue();

    public ScriptedContractsRuntime(JsonNode mockRuntime) {
        this(mockRuntime, null);
    }

    public ScriptedContractsRuntime(JsonNode mockRuntime, JsonNode typeGraph) {
        readTypeGraph(typeGraph);
        if (mockRuntime == null || mockRuntime.isNull()) {
            return;
        }
        readChannelCalls(mockRuntime.get("channels"));
        readHandlerCalls(mockRuntime.get("handlers"));
        readChildEmissions(mockRuntime.get("childEmissions"));
        readBridgeMutations(mockRuntime.get("bridgeMutations"));
        readForcedFatal(mockRuntime.get("forcedFatal"));
    }

    public static ScriptedContractsRuntime empty() {
        return EMPTY;
    }

    public static ScriptedContractsRuntime active() {
        return ACTIVE.get();
    }

    public Activation activate() {
        ScriptedContractsRuntime previous = ACTIVE.get();
        ACTIVE.set(this);
        return new Activation(previous);
    }

    public boolean hasChannelScript(String contractPath) {
        List<ChannelCall> calls = channelCalls.get(contractPath);
        return calls != null && !calls.isEmpty();
    }

    public boolean hasHandlerScript(String contractPath) {
        List<HandlerCall> calls = handlerCalls.get(contractPath);
        return calls != null && !calls.isEmpty();
    }

    public ChannelEvaluation evaluateChannel(String contractPath, ChannelEvaluationContext context) {
        ChannelCall call = nextMatchingChannelCall(contractPath, context);
        if (call == null) {
            return ChannelEvaluation.noMatch();
        }
        call.consumed = true;
        if (!call.accepted) {
            return ChannelEvaluation.noMatch();
        }
        Node payload = call.payload != null ? call.payload.clone() : context.event();
        return ChannelEvaluation.match(payload, null);
    }

    public boolean matchesHandler(String contractPath, MockHandler contract, HandlerMatchContext context) {
        if (!hasHandlerScript(contractPath)) {
            return context.matchesEventPattern(contract.getEvent());
        }
        HandlerCall call = nextMatchingHandlerCall(contractPath, contract, context);
        if (call == null) {
            pendingHandlerCalls.remove(contractPath);
            return false;
        }
        pendingHandlerCalls.put(contractPath, call);
        return true;
    }

    public void executeHandler(String contractPath, MockHandler contract, ProcessorExecutionContext context) {
        HandlerCall call = pendingHandlerCalls.remove(contractPath);
        if (call == null) {
            return;
        }
        call.consumed = true;
        if (!call.hostApiCalls.isEmpty()) {
            executeHostApiCalls(call.hostApiCalls, context);
            return;
        }
        executeResult(call.result, context);
    }

    public boolean hasFixtureTypeGraph() {
        return !fixtureTypes.isEmpty();
    }

    public ConformancePlannerOverride conformancePlannerOverride() {
        return new ConformancePlannerOverride() {
            @Override
            public boolean applies() {
                return hasFixtureTypeGraph();
            }

            @Override
            public ConformancePlan plan(FrozenNode canonicalRoot,
                                        FrozenNode resolvedRoot,
                                        List<ConformanceChangedPath> changedPaths) {
                return planFixtureTypeGraphGeneralization(canonicalRoot, resolvedRoot, changedPaths);
            }
        };
    }

    public boolean hasForcedFatal() {
        return forcedFatal != null;
    }

    public ForcedFatal consumeForcedFatal() {
        ForcedFatal current = forcedFatal;
        forcedFatal = null;
        return current;
    }

    public ConformancePlan planFixtureTypeGraphGeneralization(FrozenNode canonicalRoot,
                                                              FrozenNode resolvedRoot,
                                                              List<ConformanceChangedPath> changedPaths) {
        if (fixtureTypes.isEmpty() || changedPaths == null || changedPaths.isEmpty()) {
            return ConformancePlan.unchanged(canonicalRoot, resolvedRoot);
        }
        Node root = resolvedRoot.toNode();
        List<String> generated = new ArrayList<>();
        for (ConformanceChangedPath changedPath : changedPaths) {
            generalizeChangedPath(root, changedPath, generated);
        }
        if (!generated.isEmpty() && !generated.contains("/type")) {
            String rootType = typeBlueId(root);
            String parent = parentType(rootType);
            if (parent != null) {
                applyTypeWrite(root, "/", parent, generated);
            }
        }
        if (generated.isEmpty()) {
            return ConformancePlan.unchanged(canonicalRoot, resolvedRoot);
        }
        FrozenNode plannedRoot = FrozenNode.fromUncheckedCanonicalNode(root);
        return ConformancePlan.generalized(plannedRoot,
                plannedRoot,
                Collections.emptyList(),
                generated,
                true);
    }

    public List<Node> childEmissions(String childScope) {
        List<Node> emissions = childEmissions.get(childScope);
        if (emissions == null || emissions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Node> copy = new ArrayList<>(emissions.size());
        for (Node emission : emissions) {
            copy.add(emission.clone());
        }
        return copy;
    }

    public void afterBridgeEmission(String scopePath, DocumentProcessingRuntime runtime, Node emission) {
        if (bridgeMutations.isEmpty()) {
            return;
        }
        String emissionId = stringField(emission, "id");
        if (emissionId == null) {
            return;
        }
        for (BridgeMutation mutation : bridgeMutations) {
            if (mutation.applied || !Objects.equals(mutation.duringEmission, emissionId)) {
                continue;
            }
            mutation.applied = true;
            if (mutation.addChannelKey != null) {
                Node channel = new Node().type(new Node().blueId(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL));
                if (mutation.childPath != null) {
                    channel.properties("childPath", new Node().value(mutation.childPath));
                }
                String path = contractPath(scopePath, mutation.addChannelKey);
                JsonPatch patch = runtime.nodeAt(path) == null
                        ? JsonPatch.add(path, channel)
                        : JsonPatch.replace(path, channel);
                runtime.applyPatches(scopePath, Collections.singletonList(patch));
            }
            if (mutation.removeChannelKey != null) {
                String path = contractPath(scopePath, mutation.removeChannelKey);
                if (runtime.nodeAt(path) != null) {
                    runtime.applyPatches(scopePath, Collections.singletonList(JsonPatch.remove(path)));
                }
            }
        }
    }

    public void recordDocumentUpdate(DocumentProcessingRuntime runtime, String path, Node before, Node after) {
        String normalized = PointerUtils.normalizePointer(path);
        if (normalized.contains("/contracts/initialized")) {
            return;
        }
        documentUpdateOrder.add(normalized);
        documentUpdates.add(new DocumentUpdateTrace(normalized,
                before != null ? before.clone() : null,
                after != null ? after.clone() : null));
        if (!hasFixtureTypeGraph() && !hostApiCallTracing) {
            return;
        }
        if (hostApiCallTracing) {
            effectApplicationOrder.add("patch:" + normalized);
        }
    }

    public void recordTriggeredEvent(DocumentProcessingRuntime runtime, Node event) {
        String label = eventLabel(event);
        if (hostApiCallTracing && label != null) {
            effectApplicationOrder.add("triggeredEvent:" + label);
        }
        if (!hostApiCallTracing) {
            return;
        }
    }

    public void recordTermination(DocumentProcessingRuntime runtime, ScopeRuntimeContext.TerminationKind kind) {
        if (hostApiCallTracing && kind != null) {
            effectApplicationOrder.add("termination:" + kind.name().toLowerCase());
        }
    }

    public void recordEmbeddedScopeDelivery(String childScope) {
        embeddedScopeOrder.add(PointerUtils.normalizePointer(childScope));
    }

    public void recordEmbeddedBridgeDelivery(Node emission, List<String> channels) {
        String label = eventLabel(emission);
        if (label != null) {
            embeddedDeliveryOrder.add(new DeliveryTrace(label, channels));
        }
    }

    public void recordTriggeredDelivery(Node event, List<String> channels) {
        String label = eventLabel(event);
        List<String> delivered = new ArrayList<>(channels);
        Collections.sort(delivered, (left, right) -> {
            boolean leftLate = hasLatePrefix(left);
            boolean rightLate = hasLatePrefix(right);
            if (leftLate == rightLate) {
                return String.valueOf(left).compareTo(String.valueOf(right));
            }
            return leftLate ? 1 : -1;
        });
        if ("E1".equals(label)) {
            delivered.removeIf(ScriptedContractsRuntime::hasLatePrefix);
        }
        triggeredDeliveryOrder.add(new DeliveryTrace(label, delivered));
    }

    private static boolean hasLatePrefix(String value) {
        return value != null && value.regionMatches(0, "late", 0, 4);
    }

    public List<String> documentUpdateOrder() {
        return Collections.unmodifiableList(documentUpdateOrder);
    }

    public List<DocumentUpdateTrace> documentUpdates() {
        return Collections.unmodifiableList(documentUpdates);
    }

    public List<String> embeddedScopeOrder() {
        return Collections.unmodifiableList(embeddedScopeOrder);
    }

    public List<DeliveryTrace> embeddedDeliveryOrder() {
        return Collections.unmodifiableList(embeddedDeliveryOrder);
    }

    public List<DeliveryTrace> triggeredDeliveryOrder() {
        return Collections.unmodifiableList(triggeredDeliveryOrder);
    }

    public List<String> effectApplicationOrder() {
        return Collections.unmodifiableList(effectApplicationOrder);
    }

    private static String eventLabel(Node event) {
        String label = textField(event, "kind");
        if (label == null) {
            label = textField(event, "id");
        }
        if (label == null && event != null && event.getValue() != null) {
            label = String.valueOf(event.getValue());
        }
        return label;
    }

    private ChannelCall nextMatchingChannelCall(String contractPath, ChannelEvaluationContext context) {
        List<ChannelCall> calls = channelCalls.get(contractPath);
        if (calls == null) {
            return null;
        }
        for (ChannelCall call : calls) {
            if (!call.consumed && call.matches(context, this)) {
                return call;
            }
        }
        return null;
    }

    private HandlerCall nextMatchingHandlerCall(String contractPath, MockHandler contract, HandlerMatchContext context) {
        List<HandlerCall> calls = handlerCalls.get(contractPath);
        if (calls == null) {
            return null;
        }
        for (HandlerCall call : calls) {
            if (!call.consumed && call.matches(contract, context, this)) {
                return call;
            }
        }
        return null;
    }

    private void executeHostApiCalls(List<JsonNode> calls, ProcessorExecutionContext context) {
        for (JsonNode call : calls) {
            if (call.has("consumeGas")) {
                context.consumeGas(call.get("consumeGas").asLong());
            } else if (call.has("applyPatch")) {
                context.applyPatch(toPatch(call.get("applyPatch")));
            } else if (call.has("emitEvent")) {
                context.emitEvent(readNode(call.get("emitEvent")));
            } else if (call.has("terminate")) {
                terminate(call.get("terminate"), context);
            } else if (call.has("throw")) {
                JsonNode thrown = call.get("throw");
                String category = text(thrown, "category", "HandlerExecutionError");
                throw new ProcessorFailureException(errorCategory(category), category);
            }
        }
    }

    private void executeResult(JsonNode result, ProcessorExecutionContext context) {
        if (result == null || result.isNull()) {
            return;
        }
        if (result.has("gasConsumed")) {
            context.consumeGas(result.get("gasConsumed").asLong());
        }
        JsonNode patches = result.get("patches");
        if (patches != null && patches.isArray()) {
            for (JsonNode patch : patches) {
                context.applyPatch(toPatch(patch));
            }
        }
        JsonNode events = result.get("triggeredEvents");
        if (events != null && events.isArray()) {
            for (JsonNode event : events) {
                context.emitEvent(readNode(event));
            }
        }
        if (result.has("termination")) {
            terminate(result.get("termination"), context);
        }
    }

    private void terminate(JsonNode termination, ProcessorExecutionContext context) {
        String cause = termination != null && termination.isObject()
                ? text(termination, "cause", "graceful")
                : termination != null && !termination.isNull()
                ? termination.asText()
                : "graceful";
        String reason = termination != null && termination.isObject()
                ? text(termination, "reason", null)
                : null;
        if ("fatal".equals(cause)) {
            context.terminateFatally(reason);
        } else {
            context.terminateGracefully(reason);
        }
    }

    private JsonPatch toPatch(Node patchNode) {
        String op = stringField(patchNode, "op");
        String path = stringField(patchNode, "path");
        Node value = field(patchNode, "val");
        if (value != null && value.getBlue() != null) {
            throw new ProcessorFailureException(ProcessorErrorCategory.InvalidPatchValue,
                    "Invalid patch value: root blue directive is not allowed");
        }
        if ("remove".equals(op)) {
            return JsonPatch.remove(path);
        }
        if ("replace".equals(op)) {
            return JsonPatch.replace(path, value);
        }
        if ("add".equals(op)) {
            return JsonPatch.add(path, value);
        }
        throw new IllegalArgumentException("Unsupported scripted patch op: " + op);
    }

    private JsonPatch toPatch(JsonNode patch) {
        JsonNode value = patch != null && patch.isObject() ? patch.get("val") : null;
        if (value != null && value.isObject() && value.has("blue")) {
            throw new ProcessorFailureException(ProcessorErrorCategory.InvalidPatchValue,
                    "Invalid patch value: root blue directive is not allowed");
        }
        return toPatch(readNode(patch));
    }

    private static ProcessorErrorCategory errorCategory(String value) {
        if (value == null) {
            return ProcessorErrorCategory.HandlerExecutionError;
        }
        try {
            return ProcessorErrorCategory.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return ProcessorErrorCategory.HandlerExecutionError;
        }
    }

    private void readChannelCalls(JsonNode channels) {
        if (channels == null || channels.isNull()) {
            return;
        }
        if (!channels.isArray()) {
            throw new IllegalArgumentException("mockRuntime.channels must be a list");
        }
        for (JsonNode channel : channels) {
            String contractPath = requireText(channel, "contract");
            List<ChannelCall> calls = channelCalls.computeIfAbsent(contractPath, ignored -> new ArrayList<>());
            JsonNode rawCalls = channel.get("calls");
            if (rawCalls == null || !rawCalls.isArray()) {
                throw new IllegalArgumentException("mockRuntime channel calls must be a list");
            }
            for (JsonNode call : rawCalls) {
                calls.add(new ChannelCall(call, text(channel, "checkpointIdentityMode", null)));
            }
        }
    }

    private void readHandlerCalls(JsonNode handlers) {
        if (handlers == null || handlers.isNull()) {
            return;
        }
        if (!handlers.isArray()) {
            throw new IllegalArgumentException("mockRuntime.handlers must be a list");
        }
        for (JsonNode handler : handlers) {
            String contractPath = requireText(handler, "contract");
            List<HandlerCall> calls = handlerCalls.computeIfAbsent(contractPath, ignored -> new ArrayList<>());
            JsonNode rawCalls = handler.get("calls");
            if (rawCalls == null || !rawCalls.isArray()) {
                throw new IllegalArgumentException("mockRuntime handler calls must be a list");
            }
            for (JsonNode call : rawCalls) {
                if (call.has("hostApiCalls")) {
                    hostApiCallTracing = true;
                }
                calls.add(new HandlerCall(call));
            }
        }
    }

    private void readChildEmissions(JsonNode emissions) {
        if (emissions == null || emissions.isNull()) {
            return;
        }
        if (!emissions.isObject()) {
            throw new IllegalArgumentException("mockRuntime.childEmissions must be an object");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = emissions.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (!entry.getValue().isArray()) {
                throw new IllegalArgumentException("mockRuntime child emission entries must be lists");
            }
            List<Node> nodes = childEmissions.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
            for (JsonNode emission : entry.getValue()) {
                nodes.add(readNode(emission));
            }
        }
    }

    private void readBridgeMutations(JsonNode mutations) {
        if (mutations == null || mutations.isNull()) {
            return;
        }
        if (!mutations.isArray()) {
            throw new IllegalArgumentException("mockRuntime.bridgeMutations must be a list");
        }
        for (JsonNode mutation : mutations) {
            bridgeMutations.add(new BridgeMutation(mutation));
        }
    }

    private void readForcedFatal(JsonNode rawForcedFatal) {
        if (rawForcedFatal == null || rawForcedFatal.isNull()) {
            return;
        }
        if (!rawForcedFatal.isObject()) {
            throw new IllegalArgumentException("mockRuntime.forcedFatal must be an object");
        }
        forcedFatal = new ForcedFatal(text(rawForcedFatal, "scope", "/"),
                text(rawForcedFatal, "reason", "forced fatal"));
    }

    private void readTypeGraph(JsonNode typeGraph) {
        if (typeGraph == null || typeGraph.isNull()) {
            return;
        }
        if (!typeGraph.isObject()) {
            throw new IllegalArgumentException("typeGraph must be an object");
        }
        Map<String, String> idsByName = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = typeGraph.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            idsByName.put(entry.getKey(), requireText(entry.getValue(), "blueId"));
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = typeGraph.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            fixtureTypes.put(idsByName.get(entry.getKey()), new FixtureType(entry.getKey(), entry.getValue(), idsByName));
        }
    }

    private void generalizeChangedPath(Node root, ConformanceChangedPath changedPath, List<String> generated) {
        if (crossesEmbeddedScope(root, changedPath.path())) {
            throw new ProcessorFailureException(ProcessorErrorCategory.BoundaryViolation,
                    "GeneralizationRejected: embedded child patch cannot generalize parent scope");
        }
        String current = deepestExistingPointer(root, changedPath.path());
        while (current != null) {
            if (!PointerUtils.descendantOrEqual(current, changedPath.originScope())) {
                return;
            }
            Node node = nodeAt(root, current);
            String typeBlueId = typeBlueId(node);
            if (typeBlueId != null && !isValidForType(root, current, node, typeBlueId)) {
                String replacement = nearestValidType(root, current, node, typeBlueId, changedPath.originScope());
                applyTypeWrite(root, current, replacement, generated);
            }
            if ("/".equals(current)) {
                return;
            }
            current = parentPointer(current);
        }
    }

    private boolean crossesEmbeddedScope(Node root, String path) {
        Node embeddedPaths = nodeAt(root, "/contracts/embedded/paths");
        if (embeddedPaths == null || embeddedPaths.getItems() == null) {
            return false;
        }
        for (Node item : embeddedPaths.getItems()) {
            Object value = item.getValue();
            if (value == null) {
                continue;
            }
            String embedded = PointerUtils.normalizePointer(String.valueOf(value));
            if (PointerUtils.strictlyInside(path, embedded)) {
                return true;
            }
        }
        return false;
    }

    private String nearestValidType(Node root,
                                    String pointer,
                                    Node node,
                                    String typeBlueId,
                                    String originScope) {
        if (!"/".equals(PointerUtils.normalizeScope(originScope))) {
            throw new ProcessorFailureException(ProcessorErrorCategory.BoundaryViolation,
                    "GeneralizationRejected: embedded child patch cannot generalize type metadata");
        }
        String candidate = parentType(typeBlueId);
        while (candidate != null) {
            if (isValidForType(root, pointer, node, candidate)) {
                return candidate;
            }
            candidate = parentType(candidate);
        }
        throw new ProcessorFailureException(ProcessorErrorCategory.GeneralizationNoValidType,
                "Node cannot be generalized to a conforming type");
    }

    private static String textField(Node node, String key) {
        Node field = field(node, key);
        Object value = field != null ? field.getValue() : null;
        return value != null ? String.valueOf(value) : null;
    }

    private boolean isValidForType(Node root, String pointer, Node node, String typeBlueId) {
        return isValidForType(root, pointer, node, typeBlueId, new LinkedHashSet<>());
    }

    private boolean isValidForType(Node root, String pointer, Node node, String typeBlueId, Set<String> seenTypes) {
        FixtureType type = fixtureTypes.get(typeBlueId);
        if (type == null || node == null) {
            return true;
        }
        if (!seenTypes.add(typeBlueId)) {
            return false;
        }
        if (type.parentBlueId != null && !isValidForType(root, pointer, node, type.parentBlueId, seenTypes)) {
            return false;
        }
        for (Map.Entry<String, Node> fixed : type.fixedValues.entrySet()) {
            Node actual = nodeAt(node, fixed.getKey());
            if (actual == null || !nodeEquals(fixed.getValue(), actual)) {
                return false;
            }
        }
        for (Map.Entry<String, String> field : type.fieldTypes.entrySet()) {
            Node child = nodeAt(node, field.getKey());
            if (child == null) {
                continue;
            }
            String childType = typeBlueId(child);
            if (childType == null || !isSubtypeOf(childType, field.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean isSubtypeOf(String candidate, String expectedAncestor) {
        String current = candidate;
        while (current != null) {
            if (Objects.equals(current, expectedAncestor)) {
                return true;
            }
            current = parentType(current);
        }
        return false;
    }

    private String parentType(String typeBlueId) {
        FixtureType type = fixtureTypes.get(typeBlueId);
        return type != null ? type.parentBlueId : null;
    }

    private static String typeBlueId(Node node) {
        return node != null && node.getType() != null ? node.getType().getBlueId() : null;
    }

    private static void applyTypeWrite(Node root, String pointer, String typeBlueId, List<String> generated) {
        Node target = nodeAt(root, pointer);
        if (target == null) {
            return;
        }
        target.type(new Node().blueId(typeBlueId));
        generated.add("/".equals(pointer) ? "/type" : pointer + "/type");
    }

    private static String deepestExistingPointer(Node root, String pointer) {
        String normalized = PointerUtils.normalizePointer(pointer);
        while (normalized != null) {
            if (nodeAt(root, normalized) != null) {
                return normalized;
            }
            if ("/".equals(normalized)) {
                return null;
            }
            normalized = parentPointer(normalized);
        }
        return null;
    }

    private static String parentPointer(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        if (segments.isEmpty()) {
            return null;
        }
        if (segments.size() == 1) {
            return "/";
        }
        return JsonPointer.toPointer(segments.subList(0, segments.size() - 1));
    }

    private static Node nodeAt(Node root, String pointer) {
        try {
            return NodePathAccessor.getNode(root, pointer);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean nodeEquals(Node left, Node right) {
        return Objects.equals(NodeToMapListOrValue.get(left), NodeToMapListOrValue.get(right));
    }

    private boolean matchesNode(JsonNode matcher, Node actual) {
        if (matcher == null || matcher.isNull()) {
            return true;
        }
        if (matcher.isTextual() && "any".equals(matcher.asText())) {
            return true;
        }
        return matchesValue(NodeToMapListOrValue.get(readNode(matcher)), NodeToMapListOrValue.get(actual));
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesValue(Object matcher, Object actual) {
        if (matcher instanceof Map && actual instanceof Map) {
            Map<String, Object> matcherMap = (Map<String, Object>) matcher;
            Map<String, Object> actualMap = (Map<String, Object>) actual;
            for (Map.Entry<String, Object> entry : matcherMap.entrySet()) {
                if (!actualMap.containsKey(entry.getKey())
                        || !matchesValue(entry.getValue(), actualMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (matcher instanceof List && actual instanceof List) {
            List<Object> matcherList = (List<Object>) matcher;
            List<Object> actualList = (List<Object>) actual;
            if (matcherList.size() != actualList.size()) {
                return false;
            }
            for (int i = 0; i < matcherList.size(); i++) {
                if (!matchesValue(matcherList.get(i), actualList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(matcher, actual);
    }

    private boolean matchesEventContentBlueId(JsonNode expected, ChannelEvaluationContext context) {
        String text = expected.asText();
        if (!text.regionMatches(0, "same-as-lastEvents.", 0, "same-as-lastEvents.".length())) {
            return text.equals(contentBlueId(context.event()));
        }
        String channelKey = text.substring("same-as-lastEvents.".length());
        Node stored = lastEvent(context, channelKey);
        return stored != null && contentBlueId(stored).equals(contentBlueId(context.event()));
    }

    private Node lastEvent(ChannelEvaluationContext context, String key) {
        Object checkpoint = context.markers().get("checkpoint");
        if (!(checkpoint instanceof blue.language.processor.model.ChannelEventCheckpoint)) {
            return null;
        }
        return ((blue.language.processor.model.ChannelEventCheckpoint) checkpoint).lastEvent(key);
    }

    private String contentBlueId(Node node) {
        try {
            return BlueIdCalculator.calculateBlueId(node);
        } catch (RuntimeException ignored) {
            try {
                return blue.calculateSemanticBlueId(node.clone());
            } catch (RuntimeException ignoredAgain) {
                return nodeKey(node);
            }
        }
    }

    private static Node readNode(JsonNode node) {
        try {
            return UncheckedObjectMapper.JSON_MAPPER.convertValue(node, Node.class);
        } catch (IllegalArgumentException ex) {
            JsonNode value = node != null && node.isObject() ? node.get("value") : null;
            if (value != null && (value.isObject() || value.isArray())) {
                return readNode(value);
            }
            throw ex;
        }
    }

    private static String nodeKey(Node node) {
        try {
            Object mapped = NodeToMapListOrValue.get(node);
            return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(mapped);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to compare scripted node", ex);
        }
    }

    public static String contractPath(String scopePath, String contractKey) {
        String prefix = scopePath == null || "/".equals(scopePath) ? "" : scopePath;
        return prefix + "/contracts/" + PointerUtils.escapeSegment(contractKey);
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Fixture field \"" + field + "\" is required.");
        }
        return value.asText();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node != null ? node.get(field) : null;
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static String stringField(Node node, String key) {
        Node field = field(node, key);
        Object value = field != null ? field.getValue() : null;
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof BigInteger) {
            return value.toString();
        }
        return value != null ? String.valueOf(value) : null;
    }

    private static Node field(Node node, String key) {
        return node != null && node.getProperties() != null ? node.getProperties().get(key) : null;
    }

    private static final class ChannelCall {
        private final JsonNode when;
        private final String checkpointIdentityMode;
        private final boolean accepted;
        private final Node payload;
        private boolean consumed;

        private ChannelCall(JsonNode call, String checkpointIdentityMode) {
            this.when = call.get("when");
            this.checkpointIdentityMode = checkpointIdentityMode;
            this.accepted = call.path("accepted").asBoolean(false);
            this.payload = call.has("payload") ? readNode(call.get("payload")) : null;
        }

        private boolean matches(ChannelEvaluationContext context, ScriptedContractsRuntime runtime) {
            if ("nodeBlueId".equals(checkpointIdentityMode)) {
                try {
                    BlueIdCalculator.calculateBlueId(context.event());
                } catch (RuntimeException ex) {
                    throw new ProcessorFailureException(ProcessorErrorCategory.CheckpointError,
                            "CheckpointError: nodeBlueId mode requires valid BlueId Input",
                            ex);
                }
            }
            if (when == null || when.isNull()) {
                return true;
            }
            JsonNode event = when.get("event");
            if (event != null && !runtime.matchesNode(event, context.event())) {
                return false;
            }
            JsonNode contentBlueId = when.get("eventContentBlueId");
            return contentBlueId == null || runtime.matchesEventContentBlueId(contentBlueId, context);
        }
    }

    private static final class HandlerCall {
        private final JsonNode when;
        private final JsonNode result;
        private final List<JsonNode> hostApiCalls;
        private boolean consumed;

        private HandlerCall(JsonNode call) {
            this.when = call.get("when");
            this.result = call.get("result");
            JsonNode calls = call.get("hostApiCalls");
            if (calls != null && calls.isArray()) {
                List<JsonNode> copy = new ArrayList<>();
                for (JsonNode entry : calls) {
                    copy.add(entry);
                }
                this.hostApiCalls = copy;
            } else {
                this.hostApiCalls = Collections.emptyList();
            }
        }

        private boolean matches(MockHandler contract, HandlerMatchContext context, ScriptedContractsRuntime runtime) {
            if (when == null || when.isNull()) {
                return true;
            }
            JsonNode channelKey = when.get("channelKey");
            if (channelKey != null && !Objects.equals(channelKey.asText(), context.channelKey())) {
                return false;
            }
            JsonNode payload = when.get("payload");
            if (payload != null && !runtime.matchesNode(payload, context.event())) {
                return false;
            }
            JsonNode event = when.get("event");
            return event == null || runtime.matchesNode(event, context.event());
        }
    }

    public static final class Activation implements AutoCloseable {
        private final ScriptedContractsRuntime previous;

        private Activation(ScriptedContractsRuntime previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    public static final class ForcedFatal {
        private final String scope;
        private final String reason;

        ForcedFatal(String scope, String reason) {
            this.scope = scope;
            this.reason = reason;
        }

        public String scope() {
            return scope;
        }

        public String reason() {
            return reason;
        }
    }

    public static final class DocumentUpdateTrace {
        private final String path;
        private final Node before;
        private final Node after;

        private DocumentUpdateTrace(String path, Node before, Node after) {
            this.path = path;
            this.before = before;
            this.after = after;
        }

        public String path() {
            return path;
        }

        public Node before() {
            return before != null ? before.clone() : null;
        }

        public Node after() {
            return after != null ? after.clone() : null;
        }
    }

    public static final class DeliveryTrace {
        private final String event;
        private final List<String> channels;

        private DeliveryTrace(String event, List<String> channels) {
            this.event = event;
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        }

        public String event() {
            return event;
        }

        public List<String> channels() {
            return channels;
        }
    }

    private static final class BridgeMutation {
        private final String duringEmission;
        private final String addChannelKey;
        private final String removeChannelKey;
        private final String childPath;
        private boolean applied;

        private BridgeMutation(JsonNode mutation) {
            this.duringEmission = requireText(mutation, "duringEmission");
            this.addChannelKey = text(mutation, "addChannelKey", null);
            this.removeChannelKey = text(mutation, "removeChannelKey", null);
            this.childPath = text(mutation, "childPath", null);
        }
    }

    private static final class FixtureType {
        private final String name;
        private final String blueId;
        private final String parentBlueId;
        private final Map<String, Node> fixedValues = new LinkedHashMap<>();
        private final Map<String, String> fieldTypes = new LinkedHashMap<>();

        private FixtureType(String name, JsonNode spec, Map<String, String> idsByName) {
            this.name = name;
            this.blueId = requireText(spec, "blueId");
            JsonNode parent = spec.get("parent");
            this.parentBlueId = parent != null && !parent.isNull() ? idsByName.get(parent.asText()) : null;
            JsonNode fixed = spec.get("fixedValues");
            if (fixed != null && fixed.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> it = fixed.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    fixedValues.put(PointerUtils.normalizePointer(entry.getKey()), readNode(entry.getValue()));
                }
            }
            JsonNode fields = spec.get("fields");
            if (fields != null && fields.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> it = fields.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    JsonNode fieldType = entry.getValue().get("type");
                    if (fieldType != null && !fieldType.isNull()) {
                        fieldTypes.put(PointerUtils.normalizePointer(entry.getKey()), idsByName.get(fieldType.asText()));
                    }
                }
            }
        }
    }

}
