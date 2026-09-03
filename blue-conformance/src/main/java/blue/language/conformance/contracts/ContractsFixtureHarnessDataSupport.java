package blue.language.conformance.contracts;

import static blue.language.conformance.contracts.ContractsFixtureExecutionEngine.*;
import static blue.language.conformance.contracts.ContractsFixtureFeederEnvironment.*;
import static blue.language.conformance.contracts.ContractsFixtureInputPreparer.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionExtractor.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionSupport.*;
import static blue.language.conformance.contracts.ContractsFixtureScriptedEnvironment.*;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.registry.BootstrapProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifiedNodeProvider;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasScheduleConstants;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ProcessAttemptResult;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessingTraceConstants;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedCollectionEventChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.InitializationMarker;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.model.NodeWireForm;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared immutable resources, low-level JSON support, and fixture values. */
abstract class ContractsFixtureHarnessDataSupport {

    static final String FIXTURE_INIT_CHANNEL =
            "_fixture_init_channel";
    static final String FIXTURE_INIT_HANDLER =
            "_fixture_init_handler";
    static final String FIXTURE_ABSENT_CHILD_PATH =
            "/_fixture_absent_child";
    static final String FIXTURE_EMBEDDED_CHANNEL =
            "_fixture_embedded_channel";
    static final String FIXTURE_FORWARD_HANDLER =
            "_fixture_forward_handler";
    static final String FIXTURE_CHILD_EMITTER_HANDLER =
            "_fixture_child_emitter_handler";
    static final String FIXTURE_TRIGGERED_CHANNEL =
            "_fixture_triggered_channel";
    static final String FIXTURE_NESTED_HANDLER =
            "_fixture_nested_handler";
    static final String FIXTURE_UPDATE_CHANNEL =
            "_fixture_update_channel";
    static final String FIXTURE_CASCADE_HANDLER =
            "_fixture_cascade_handler";
    static final String FIXTURE_LIFECYCLE_CHANNEL =
            "_fixture_lifecycle_channel";
    static final String FIXTURE_LIFECYCLE_HANDLER =
            "_fixture_lifecycle_handler";
    static final String FIXTURE_VALUE_FIELD =
            "_fixture_value";
    static final String FIXTURE_LIST_FIELD =
            "_fixture_list";

    static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());
    static final String CONTRACTS_REGISTRY_ROOT =
            "registry/blue-contracts-1.0/";
    static final String LANGUAGE_REGISTRY_ROOT =
            "registry/blue-language-1.0/";
    static final String CONFORMANCE_OPERATION_RESOURCE =
            "blue-contracts-closure-1.0/registry/ScriptedOperation.blue";

    final ClosedContractsFixtureValidator validator =
            new ClosedContractsFixtureValidator();
    final ContractsProjectionCatalog projectionCatalog =
            new ContractsProjectionCatalog();
    final ContractsAssertionEvaluator assertions =
            new ContractsAssertionEvaluator();
    final ContractsGasSchedule gasSchedule =
            new ContractsGasSchedule();
    final RegistryEnvironment registry = RegistryEnvironment.load();

    static Node readNode(JsonNode value) {
        if (value == null) {
            throw new IllegalArgumentException("Blue value is required");
        }
        return UncheckedObjectMapper.JSON_MAPPER.convertValue(value, Node.class);
    }

    static boolean hasAuthoredObjectField(JsonNode value) {
        Iterator<String> fields = value.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!isReservedBlueField(field)) {
                return true;
            }
        }
        return false;
    }

    static boolean isReservedBlueField(String field) {
        return BlueLanguageConstants.OBJECT_NAME.equals(field)
                || BlueLanguageConstants.OBJECT_DESCRIPTION.equals(field)
                || BlueLanguageConstants.OBJECT_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_KEY_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_MERGE_POLICY.equals(field)
                || BlueLanguageConstants.OBJECT_VALUE.equals(field)
                || BlueLanguageConstants.OBJECT_BLUE_ID.equals(field)
                || BlueLanguageConstants.OBJECT_ITEMS.equals(field)
                || BlueLanguageConstants.OBJECT_BLUE.equals(field)
                || BlueLanguageConstants.LIST_CONTROL_PREVIOUS.equals(field)
                || BlueLanguageConstants.LIST_CONTROL_POS.equals(field)
                || BlueLanguageConstants.OBJECT_SCHEMA.equals(field)
                || ProcessorContractConstants.KEY_CONTRACTS.equals(field);
    }

    /**
     * Variant checkpoint subjects are exact fixture-channel outputs, not
     * authored document fields. Preserve the raw scalar Blue value instead of
     * applying mapper type inference.
     */
    static Node rawCheckpointSubject(JsonNode value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(
                    "checkpointSubject must be exact BlueId Input");
        }
        if (value.isValueNode()) {
            return new Node().value(
                    UncheckedObjectMapper.JSON_MAPPER.convertValue(
                            value, Object.class));
        }
        return readNode(value);
    }

    static ObjectNode requireObject(JsonNode value, String path) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return (ObjectNode) value;
    }

    static long requiredLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.asLong() < 0L) {
            throw new IllegalArgumentException(
                    field + " must be a non-negative long");
        }
        return value.asLong();
    }

    static int exactInt(JsonNode value, String path) {
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.asInt() < 0) {
            throw new IllegalArgumentException(
                    path + " must be a non-negative int");
        }
        return value.asInt();
    }

    @SuppressWarnings("unchecked")
    static boolean semanticEquals(Object left, Object right) {
        left = ContractsConformanceProjection.normalize(left);
        right = ContractsConformanceProjection.normalize(right);
        if (left instanceof Number && right instanceof Number) {
            return new java.math.BigDecimal(left.toString()).compareTo(
                    new java.math.BigDecimal(right.toString())) == 0;
        }
        if (left instanceof Map && right instanceof Map) {
            Map<String, Object> l = (Map<String, Object>) left;
            Map<String, Object> r = (Map<String, Object>) right;
            if (!l.keySet().equals(r.keySet())) {
                return false;
            }
            for (String key : l.keySet()) {
                if (!semanticEquals(l.get(key), r.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<Object> l = (List<Object>) left;
            List<Object> r = (List<Object>) right;
            if (l.size() != r.size()) {
                return false;
            }
            for (int index = 0; index < l.size(); index++) {
                if (!semanticEquals(l.get(index), r.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    static void setPointer(ObjectNode root,
                                   String pointer,
                                   JsonNode value) {
        List<String> segments = pointerSegments(pointer);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(
                    "Builder target cannot replace the Root");
        }
        ObjectNode current = root;
        for (int index = 0; index < segments.size() - 1; index++) {
            String segment = segments.get(index);
            JsonNode child = current.get(segment);
            if (child == null) {
                child = current.putObject(segment);
            }
            if (!child.isObject()) {
                throw new IllegalArgumentException(
                        "Builder target crosses a non-object at " + segment);
            }
            current = (ObjectNode) child;
        }
        current.set(segments.get(segments.size() - 1), value.deepCopy());
    }

    static JsonNode jsonAt(JsonNode root, String pointer) {
        JsonNode current = root;
        for (String segment : pointerSegments(pointer)) {
            if (current == null) {
                return null;
            }
            if (current.isObject()) {
                current = current.get(segment);
            } else if (current.isArray()) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException invalid) {
                    return null;
                }
                current = index >= 0 && index < current.size()
                        ? current.get(index)
                        : null;
            } else {
                return null;
            }
        }
        return current;
    }

    static List<String> pointerSegments(String pointer) {
        if (pointer == null || pointer.isEmpty() || "/".equals(pointer)) {
            return Collections.emptyList();
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException(
                    "RFC 6901 pointer must start with '/': " + pointer);
        }
        List<String> result = new ArrayList<>();
        String[] raw = pointer.substring(1).split("/", -1);
        for (String segment : raw) {
            result.add(unescapePointer(segment));
        }
        return result;
    }

    static String unescapePointer(String segment) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < segment.length(); index++) {
            char c = segment.charAt(index);
            if (c != '~') {
                result.append(c);
                continue;
            }
            if (index + 1 >= segment.length()) {
                throw new IllegalArgumentException(
                        "Malformed RFC 6901 escape");
            }
            char escape = segment.charAt(++index);
            if (escape == '0') {
                result.append('~');
            } else if (escape == '1') {
                result.append('/');
            } else {
                throw new IllegalArgumentException(
                        "Malformed RFC 6901 escape ~" + escape);
            }
        }
        return result.toString();
    }

    static ObjectNode objectAt(ObjectNode root,
                                       String pointer,
                                       boolean create) {
        JsonNode existing = jsonAt(root, pointer);
        if (existing != null) {
            if (!existing.isObject()) {
                throw new IllegalArgumentException(
                        pointer + " is not an object");
            }
            return (ObjectNode) existing;
        }
        if (!create) {
            return null;
        }
        ObjectNode created =
                UncheckedObjectMapper.JSON_MAPPER.createObjectNode();
        setPointer(root, pointer, created);
        return (ObjectNode) jsonAt(root, pointer);
    }

    static ObjectNode objectField(ObjectNode parent,
                                          String field,
                                          boolean create) {
        JsonNode value = parent.get(field);
        if (value == null && create) {
            return parent.putObject(field);
        }
        if (value == null) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " is not an object");
        }
        return (ObjectNode) value;
    }

    static ArrayNode arrayField(ObjectNode parent,
                                        String field,
                                        boolean create) {
        JsonNode value = parent.get(field);
        if (value == null && create) {
            return parent.putArray(field);
        }
        if (value == null) {
            return null;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(field + " is not a list");
        }
        return (ArrayNode) value;
    }

    static ObjectNode firstScriptedHandler(ObjectNode contracts) {
        Iterator<JsonNode> values = contracts.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (value.isObject()
                    && MockTypeBlueIds.MOCK_HANDLER.equals(
                    value.path(BlueLanguageConstants.OBJECT_TYPE).path(BlueLanguageConstants.OBJECT_BLUE_ID).asText(null))) {
                return (ObjectNode) value;
            }
        }
        return null;
    }

    interface ObjectVisitor {
        void visit(ObjectNode value);
    }

    static void visit(JsonNode node, ObjectVisitor visitor) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            visitor.visit((ObjectNode) node);
            node.elements().forEachRemaining(child -> visit(child, visitor));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> visit(child, visitor));
        }
    }

    static String registryId(String key) {
        return RegistryEnvironment.load().idByKey.get(key);
    }

    final FixtureSourceIdentityResolver.Identity sourceIdentity(
            Node source,
            Map<String, Node> providerNodes,
            boolean includeTypeContracts) {
        NodeProvider provider = sourceIdentityProvider(providerNodes);
        BlueLanguage canonicalizer = sourceIdentityLanguage(provider);
        try {
            return FixtureSourceIdentityResolver.resolve(
                    canonicalizer,
                    source,
                    registry.exactSourceFieldsByType(),
                    registry.executableBodyFieldsByType(),
                    includeTypeContracts);
        } finally {
            canonicalizer.close();
        }
    }

    final ResolvedSnapshot fixtureContractSourceSnapshot(
            Node exactContract,
            Map<String, Node> providerNodes) {
        NodeProvider provider = sourceIdentityProvider(providerNodes);
        BlueLanguage canonicalizer = sourceIdentityLanguage(provider);
        try {
            return FixtureSourceIdentityResolver.resolveContractSnapshot(
                    canonicalizer,
                    exactContract,
                    registry.exactSourceFieldsByType(),
                    registry.executableBodyFieldsByType());
        } finally {
            canonicalizer.close();
        }
    }

    final boolean deferUnknownContractCapabilityToRuntime(Node contract) {
        Node declaredType = contract != null ? contract.getType() : null;
        return declaredType != null
                && declaredType.isReferenceOnly()
                && !registry.isSubtype(
                        declaredType.getBlueId(), RuntimeBlueIds.CONTRACT);
    }

    final Set<String> opaqueUnknownContractPaths(Node source) {
        Set<String> result = new LinkedHashSet<>();
        collectOpaqueUnknownContractPaths(
                source,
                JsonPointer.ROOT,
                result,
                new IdentityHashMap<Node, Boolean>());
        return result;
    }

    private void collectOpaqueUnknownContractPaths(
            Node source,
            String path,
            Set<String> result,
            IdentityHashMap<Node, Boolean> active) {
        if (source == null
                || source.isReferenceOnly()
                || active.put(source, Boolean.TRUE) != null) {
            return;
        }
        try {
            Node contracts = source.getContracts();
            if (contracts != null
                    && !contracts.isReferenceOnly()
                    && contracts.getProperties() != null) {
                for (Map.Entry<String, Node> entry
                        : contracts.getProperties().entrySet()) {
                    if (deferUnknownContractCapabilityToRuntime(
                            entry.getValue())) {
                        result.add(JsonPointer.append(
                                JsonPointer.append(
                                        path,
                                        ProcessorContractConstants
                                                .KEY_CONTRACTS),
                                entry.getKey()));
                    }
                }
            }
            if (source.getProperties() != null) {
                for (Map.Entry<String, Node> entry
                        : source.getProperties().entrySet()) {
                    collectOpaqueUnknownContractPaths(
                            entry.getValue(),
                            JsonPointer.append(path, entry.getKey()),
                            result,
                            active);
                }
            }
            if (source.getItems() != null) {
                for (int index = 0; index < source.getItems().size(); index++) {
                    collectOpaqueUnknownContractPaths(
                            source.getItems().get(index),
                            JsonPointer.append(path, Integer.toString(index)),
                            result,
                            active);
                }
            }
        } finally {
            active.remove(source);
        }
    }

    private NodeProvider sourceIdentityProvider(
            Map<String, Node> providerNodes) {
        Map<String, Node> exactNodes =
                new LinkedHashMap<>(registry.nodesByBlueId);
        exactNodes.putAll(Objects.requireNonNull(
                providerNodes, "providerNodes"));
        NodeProvider fixtureProvider = blueId -> {
            Node exact = exactNodes.get(blueId);
            return exact == null
                    ? null
                    : Collections.singletonList(exact.clone());
        };
        return new SequentialNodeProvider(
                BootstrapProvider.INSTANCE,
                new VerifiedNodeProvider(
                        BlueRuntimeTypeRegistry.getDefault()
                                .asProcessorSnapshotProvider()),
                fixtureProvider);
    }

    private static BlueLanguage sourceIdentityLanguage(
            NodeProvider processorLanguageProvider) {
        return BlueLanguage.builder()
                .nodeProvider(processorLanguageProvider)
                .cachePolicy(BlueCachePolicy.boundedDefaults())
                .build();
    }

    static final class RegistryEnvironment {
        final Map<String, Node> nodesByBlueId;
        final Map<String, String> idByKey;
        final String runtimeRegistryIdentity;

        private RegistryEnvironment(Map<String, Node> nodesByBlueId,
                                    Map<String, String> idByKey,
                                    String runtimeRegistryIdentity) {
            this.nodesByBlueId =
                    Collections.unmodifiableMap(new LinkedHashMap<>(nodesByBlueId));
            this.idByKey =
                    Collections.unmodifiableMap(new LinkedHashMap<>(idByKey));
            this.runtimeRegistryIdentity = Objects.requireNonNull(
                    runtimeRegistryIdentity, "runtimeRegistryIdentity");
        }

        static RegistryEnvironment load() {
            return DefaultHolder.INSTANCE;
        }

        static RegistryEnvironment load(Path packageRoot) {
            Path root = Objects.requireNonNull(
                    packageRoot, "packageRoot").toAbsolutePath().normalize();
            if (!packageRoot.isAbsolute() || !Files.isDirectory(root)) {
                throw new IllegalArgumentException(
                        "Contracts package root must be an existing absolute directory");
            }
            JsonNode release = readYaml(root.resolve("release-manifest.yaml"));
            JsonNode contractsRegistry = release.path("contractsRegistry");
            String registryIdentity = contractsRegistry.path(
                    "packageIdentity").asText(null);
            if (registryIdentity == null || registryIdentity.isEmpty()) {
                throw new IllegalArgumentException(
                        "Candidate release manifest has no Contracts registry identity");
            }
            Path manifestPath = root.resolve(
                    contractsRegistry.path("path").asText("registry/manifest.yaml"))
                    .normalize();
            if (!manifestPath.startsWith(root)) {
                throw new IllegalArgumentException(
                        "Candidate registry manifest escapes the package root");
            }
            Map<String, Node> nodes = new LinkedHashMap<>();
            Map<String, String> keys = new LinkedHashMap<>();
            loadRegistry(manifestPath, nodes, keys);
            loadConformanceOperation(nodes, keys);
            if (!MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL.equals(
                    keys.get("ScriptedExternalChannel"))
                    || !MockTypeBlueIds.MOCK_HANDLER.equals(
                    keys.get("ScriptedHandler"))
                    || !MockTypeBlueIds.MOCK_OPERATION.equals(
                    keys.get("ScriptedOperation"))) {
                throw new IllegalStateException(
                        "Candidate fixture runtime registry identity mismatch");
            }
            return new RegistryEnvironment(
                    nodes, keys, registryIdentity);
        }

        Node require(String blueId) {
            Node value = nodesByBlueId.get(blueId);
            if (value == null) {
                throw new IllegalStateException(
                        "Registry has no exact node " + blueId);
            }
            return value.clone();
        }

        Map<String, List<String>> exactSourceFieldsByType() {
            Map<String, List<String>> fields = new LinkedHashMap<>();
            fields.put(
                    RuntimeBlueIds.CHANNEL,
                    exactSourceFields(
                            ChannelContract.class,
                            Collections.<String>emptyList()));
            fields.put(
                    RuntimeBlueIds.EXTERNAL_CHANNEL,
                    exactSourceFields(
                            ChannelContract.class,
                            Collections.<String>emptyList()));
            fields.put(
                    RuntimeBlueIds.HANDLER,
                    exactSourceFields(
                            HandlerContract.class,
                            Collections.<String>emptyList()));
            fields.put(
                    RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL,
                    exactSourceFields(
                            DocumentUpdateChannel.class,
                            Collections.<String>emptyList()));
            fields.put(
                    RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL,
                    exactSourceFields(
                            TriggeredEventChannel.class,
                            Collections.<String>emptyList()));
            fields.put(
                    RuntimeBlueIds.EMBEDDED_COLLECTION_EVENT_CHANNEL,
                    exactSourceFields(
                            EmbeddedCollectionEventChannel.class,
                            Collections.<String>emptyList()));
            fields.put(
                    RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                    exactSourceFields(
                            EmbeddedNodeChannel.class,
                            Collections.<String>emptyList()));
            fields.put(
                    RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL,
                    exactSourceFields(
                            LifecycleChannel.class,
                            Collections.<String>emptyList()));
            fields.put(
                    RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER,
                    exactSourceFields(
                            InitializationMarker.class,
                            Collections.<String>emptyList()));
            fields.put(
                    MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
                    exactSourceFields(
                            MockExternalChannel.Value.class,
                            Collections.<String>emptyList()));
            fields.put(
                    MockTypeBlueIds.MOCK_HANDLER,
                    exactSourceFields(
                            MockHandler.Value.class,
                            Collections.singletonList(
                                    ContractsFixtureConstants.Field.RESULT)));
            fields.put(
                    MockTypeBlueIds.MOCK_OPERATION,
                    exactSourceFields(
                            MockOperation.Value.class,
                            Collections.singletonList(
                                    ContractsFixtureConstants.Field.RESULT)));
            return inheritedContractFields(fields);
        }

        Map<String, List<String>> executableBodyFieldsByType() {
            Map<String, List<String>> fields = new LinkedHashMap<>();
            fields.put(
                    MockTypeBlueIds.MOCK_HANDLER,
                    Collections.singletonList(
                            ContractsFixtureConstants.Field.RESULT));
            fields.put(
                    MockTypeBlueIds.MOCK_OPERATION,
                    Collections.singletonList(
                            ContractsFixtureConstants.Field.RESULT));
            return inheritedContractFields(fields);
        }

        private Map<String, List<String>> inheritedContractFields(
                Map<String, List<String>> fields) {
            Map<String, List<String>> complete = new LinkedHashMap<>();
            for (String typeBlueId : nodesByBlueId.keySet()) {
                if (!isSubtype(typeBlueId, RuntimeBlueIds.CONTRACT)) {
                    continue;
                }
                Set<String> inherited = new LinkedHashSet<>();
                Set<String> visited = new LinkedHashSet<>();
                String current = typeBlueId;
                while (current != null && visited.add(current)) {
                    List<String> direct = fields.get(current);
                    if (direct != null) {
                        inherited.addAll(direct);
                    }
                    Node type = nodesByBlueId.get(current);
                    current = type != null && type.getType() != null
                            ? type.getType().getBlueId()
                            : null;
                }
                if (current != null) {
                    throw new IllegalStateException(
                            "Cyclic Contracts registry type ancestry at "
                                    + current);
                }
                List<String> ordered = new ArrayList<>(inherited);
                ordered.sort(ExternalOrderKey::compareTextCodePoints);
                complete.put(
                        typeBlueId,
                        Collections.unmodifiableList(ordered));
            }
            return Collections.unmodifiableMap(complete);
        }

        private static List<String> exactSourceFields(
                Class<? extends Contract> contractType,
                List<String> executableBodyFields) {
            Set<String> fields = new LinkedHashSet<>();
            Class<?> current = contractType;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())
                            && !field.isSynthetic()
                            && Node.class.isAssignableFrom(field.getType())) {
                        fields.add(jsonPropertyName(field));
                    }
                }
                current = current.getSuperclass();
            }
            List<String> nodeFields = new ArrayList<>(fields);
            nodeFields.sort(ExternalOrderKey::compareTextCodePoints);
            fields.clear();
            fields.addAll(nodeFields);
            fields.addAll(executableBodyFields);
            return Collections.unmodifiableList(new ArrayList<>(fields));
        }

        private static String jsonPropertyName(Field field) {
            JsonProperty property = field.getAnnotation(JsonProperty.class);
            if (property != null
                    && property.value() != null
                    && !property.value().isEmpty()
                    && !JsonProperty.USE_DEFAULT_NAME.equals(
                            property.value())) {
                return property.value();
            }
            return field.getName();
        }

        boolean isSubtype(String candidate, String parent) {
            if (candidate == null || parent == null) {
                return false;
            }
            Set<String> visited = new LinkedHashSet<>();
            String current = candidate;
            while (current != null && visited.add(current)) {
                if (parent.equals(current)) {
                    return true;
                }
                Node node = nodesByBlueId.get(current);
                current = node != null && node.getType() != null
                        ? node.getType().getBlueId()
                        : null;
            }
            return false;
        }

        private static RegistryEnvironment loadInternal() {
            Map<String, Node> nodes = new LinkedHashMap<>();
            Map<String, String> keys = new LinkedHashMap<>();
            loadRegistry(CONTRACTS_REGISTRY_ROOT, nodes, keys);
            loadRegistry(LANGUAGE_REGISTRY_ROOT, nodes, keys);
            loadConformanceOperation(nodes, keys);
            if (!MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL.equals(
                    keys.get("ScriptedExternalChannel"))
                    || !MockTypeBlueIds.MOCK_HANDLER.equals(
                    keys.get("ScriptedHandler"))
                    || !MockTypeBlueIds.MOCK_OPERATION.equals(
                    keys.get("ScriptedOperation"))) {
                throw new IllegalStateException(
                        "Fixture runtime registry identity mismatch");
            }
            return new RegistryEnvironment(
                    nodes,
                    keys,
                    BlueContractsConformanceReport
                            .CONTRACTS_REGISTRY_PACKAGE_IDENTITY);
        }

        private static void loadConformanceOperation(
                Map<String, Node> nodes,
                Map<String, String> keys) {
            /*
             * This adapter is owned and package-inventoried by the
             * conformance harness.  It is deliberately loaded beside the
             * frozen Contracts runtime registry instead of changing that
             * production registry's package identity.
             */
            Node node = readNode(readYaml(CONFORMANCE_OPERATION_RESOURCE));
            String calculated = DirectBlueIdCalculator.calculateBlueId(node);
            if (!MockTypeBlueIds.MOCK_OPERATION.equals(calculated)) {
                throw new IllegalStateException(
                        "Conformance Operation registry identity mismatch");
            }
            Node previous = nodes.put(calculated, node);
            if (previous != null
                    && !semanticEquals(
                            normalizeNode(previous), normalizeNode(node))) {
                throw new IllegalStateException(
                        "Conformance Operation BlueId collision");
            }
            String previousKey = keys.put("ScriptedOperation", calculated);
            if (previousKey != null && !previousKey.equals(calculated)) {
                throw new IllegalStateException(
                        "Conformance Operation registry key collision");
            }
        }

        /** Defers classpath registry/runtime construction from candidate loads. */
        private static final class DefaultHolder {
            private static final RegistryEnvironment INSTANCE = loadInternal();
        }

        private static void loadRegistry(String root,
                                         Map<String, Node> nodes,
                                         Map<String, String> keys) {
            JsonNode manifest = readYaml(root + "manifest.yaml");
            JsonNode entries = manifest.get("entries");
            if (entries == null || !entries.isArray()) {
                throw new IllegalStateException(
                        "Registry manifest has no entries: " + root);
            }
            for (JsonNode entry : entries) {
                String key = entry.path("key").asText();
                String blueId = entry.path(BlueLanguageConstants.OBJECT_BLUE_ID).asText();
                String path = entry.path("path").asText();
                Node node = readNode(readYaml(root + path));
                String calculated = DirectBlueIdCalculator.calculateBlueId(node);
                if (!blueId.equals(calculated)) {
                    throw new IllegalStateException(
                            "Registry node identity mismatch for "
                                    + root + path);
                }
                Node duplicate = nodes.put(blueId, node);
                if (duplicate != null
                        && !semanticEquals(
                        normalizeNode(duplicate), normalizeNode(node))) {
                    throw new IllegalStateException(
                            "Registry BlueId collision for " + blueId);
                }
                keys.put(key, blueId);
            }
        }

        private static void loadRegistry(Path manifestPath,
                                         Map<String, Node> nodes,
                                         Map<String, String> keys) {
            JsonNode manifest = readYaml(manifestPath);
            JsonNode entries = manifest.get("entries");
            if (entries == null || !entries.isArray()) {
                throw new IllegalStateException(
                        "Registry manifest has no entries: " + manifestPath);
            }
            Path registryRoot = manifestPath.getParent();
            for (JsonNode entry : entries) {
                String key = entry.path("key").asText();
                String blueId = entry.path(
                        BlueLanguageConstants.OBJECT_BLUE_ID).asText();
                Path nodePath = registryRoot.resolve(
                        entry.path("path").asText()).normalize();
                if (!nodePath.startsWith(registryRoot)) {
                    throw new IllegalStateException(
                            "Registry entry escapes the candidate package: " + key);
                }
                Node node = readNode(readYaml(nodePath));
                String calculated = DirectBlueIdCalculator.calculateBlueId(node);
                if (!blueId.equals(calculated)) {
                    throw new IllegalStateException(
                            "Registry node identity mismatch for " + nodePath);
                }
                Node duplicate = nodes.put(blueId, node);
                if (duplicate != null
                        && !semanticEquals(
                        normalizeNode(duplicate), normalizeNode(node))) {
                    throw new IllegalStateException(
                            "Registry BlueId collision for " + blueId);
                }
                String previous = keys.put(key, blueId);
                if (previous != null && !previous.equals(blueId)) {
                    throw new IllegalStateException(
                            "Duplicate registry key with different BlueIds: " + key);
                }
            }
        }
    }

    static JsonNode readYaml(String resource) {
        try (InputStream input = ContractsFixtureHarness.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing Contracts harness resource " + resource);
            }
            return YAML.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read Contracts harness resource " + resource,
                    exception);
        }
    }

    static JsonNode readYaml(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return YAML.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read Contracts harness file " + path,
                    exception);
        }
    }

    static final class ScopeValue {
        final String path;
        final ObjectNode value;

        ScopeValue(String path, ObjectNode value) {
            this.path = path;
            this.value = value;
        }
    }

    static final class DerivedDelivery {
        final ExternalDeliverySnapshot snapshot;
        final String checkpointDomainBlueId;
        final Node checkpointDomainNode;
        final Node checkpointSubjectNode;

        DerivedDelivery(ExternalDeliverySnapshot snapshot,
                        String checkpointDomainBlueId,
                        Node checkpointDomainNode,
                        Node checkpointSubjectNode) {
            this.snapshot = snapshot;
            this.checkpointDomainBlueId = checkpointDomainBlueId;
            this.checkpointDomainNode = checkpointDomainNode.clone();
            this.checkpointSubjectNode = checkpointSubjectNode.clone();
        }
    }

    static final class FixtureGeneralization {
        final List<String> candidates;
        final Map<String, String> blueIdByCandidate;
        final Map<String, Node> nodesByBlueId;
        final Set<String> subtypeContractKeys;
        final ObjectNode subtypeContracts;

        private FixtureGeneralization(
                List<String> candidates,
                Map<String, String> blueIdByCandidate,
                Map<String, Node> nodesByBlueId,
                Set<String> subtypeContractKeys,
                ObjectNode subtypeContracts) {
            this.candidates = Collections.unmodifiableList(
                    new ArrayList<>(candidates));
            this.blueIdByCandidate = Collections.unmodifiableMap(
                    new LinkedHashMap<>(blueIdByCandidate));
            this.nodesByBlueId = Collections.unmodifiableMap(
                    new LinkedHashMap<>(nodesByBlueId));
            this.subtypeContractKeys = Collections.unmodifiableSet(
                    new LinkedHashSet<>(subtypeContractKeys));
            this.subtypeContracts = subtypeContracts != null
                    ? subtypeContracts.deepCopy()
                    : null;
        }

        static FixtureGeneralization create(
                ObjectNode root,
                JsonNode runtime) {
            JsonNode declared = runtime != null
                    ? runtime.get("generalizationCandidates")
                    : null;
            if (declared == null) {
                return null;
            }
            List<String> candidates = new ArrayList<>();
            for (JsonNode candidate : declared) {
                candidates.add(candidate.asText());
            }
            if (candidates.isEmpty()
                    || new LinkedHashSet<String>(candidates).size()
                    != candidates.size()) {
                throw new IllegalArgumentException(
                        "Generalization controls require a non-empty "
                                + "duplicate-free ancestor chain");
            }
            String validCandidate =
                    runtime.path("validCandidate").asText(null);
            if (validCandidate != null
                    && !candidates.contains(validCandidate)) {
                throw new IllegalArgumentException(
                        "validCandidate must belong to the declared "
                                + "ancestor chain");
            }
            int firstValidCandidateIndex = validCandidate == null
                    ? 0
                    : candidates.indexOf(validCandidate);
            Object fixedValidatingValue = null;
            if (firstValidCandidateIndex > 0) {
                JsonNode authoredValue = root.get(
                        BlueLanguageConstants.OBJECT_VALUE);
                if (authoredValue == null
                        || !authoredValue.isValueNode()
                        || authoredValue.isNull()) {
                    throw new IllegalArgumentException(
                            "validCandidate requires an exact initial scalar "
                                    + "Root value");
                }
                fixedValidatingValue = readNode(authoredValue).getRawValue();
            }
            if (root.has(BlueLanguageConstants.OBJECT_TYPE)) {
                throw new IllegalArgumentException(
                        "Generalization fixture root already declares a type");
            }

            Map<String, String> blueIds = new LinkedHashMap<>();
            Map<String, Node> nodes = new LinkedHashMap<>();
            Node subtypeContracts = null;
            ObjectNode authoredSubtypeContracts = null;
            Set<String> subtypeContractKeys = new LinkedHashSet<>();
            JsonNode declaredSubtypeContracts = runtime.get(
                    "generalizationSubtypeContracts");
            if (declaredSubtypeContracts != null) {
                if (!declaredSubtypeContracts.isObject()) {
                    throw new IllegalArgumentException(
                            "generalizationSubtypeContracts must be an object");
                }
                authoredSubtypeContracts =
                        ((ObjectNode) declaredSubtypeContracts).deepCopy();
                subtypeContracts = readNode(declaredSubtypeContracts);
                Map<String, Node> properties =
                        subtypeContracts.getProperties();
                if (properties == null || properties.isEmpty()) {
                    throw new IllegalArgumentException(
                            "generalizationSubtypeContracts must declare "
                                    + "at least one contract key");
                }
                subtypeContractKeys.addAll(properties.keySet());
            }
            BasicNodeProvider generatedTypes = new BasicNodeProvider();
            String parentBlueId = null;
            for (int index = candidates.size() - 1;
                 index >= 0;
                 index--) {
                String typeName = "Fixture Generalization "
                        + candidates.get(index);
                Node typeNode = parentBlueId == null
                        ? new Node().name(typeName)
                        : new Node().name(typeName)
                        .type(new Node().blueId(parentBlueId));
                if (index == 0 && subtypeContracts != null) {
                    typeNode.contracts(subtypeContracts.clone());
                }
                if (index == firstValidCandidateIndex - 1) {
                    typeNode.value(fixedValidatingValue);
                }
                generatedTypes.addSingleNodes(typeNode);
                String blueId = generatedTypes.getBlueIdByName(typeName);
                List<Node> exact = generatedTypes.fetchByBlueId(blueId);
                if (exact == null || exact.size() != 1) {
                    throw new IllegalStateException(
                            "Generated generalization type is unavailable: "
                                    + typeName);
                }
                blueIds.put(candidates.get(index), blueId);
                Node exactContent = exact.get(0).clone().blueId(null);
                if (!blueId.equals(
                        DirectBlueIdCalculator.calculateBlueId(
                                exactContent))) {
                    throw new IllegalStateException(
                            "Generated generalization type content does not "
                                    + "match its retained identity: "
                                    + typeName);
                }
                nodes.put(blueId, exactContent);
                parentBlueId = blueId;
            }
            Map<String, String> orderedBlueIds =
                    new LinkedHashMap<>();
            for (String candidate : candidates) {
                orderedBlueIds.put(
                        candidate, blueIds.get(candidate));
            }
            root.putObject(BlueLanguageConstants.OBJECT_TYPE).put(
                    BlueLanguageConstants.OBJECT_BLUE_ID,
                    orderedBlueIds.get(candidates.get(0)));
            return new FixtureGeneralization(
                    candidates,
                    orderedBlueIds,
                    nodes,
                    subtypeContractKeys,
                    authoredSubtypeContracts);
        }

        FixtureGeneralizationPlanner newPlanner() {
            return new FixtureGeneralizationPlanner(this);
        }
    }

    static final class FixtureGeneralizationPlanner {
        private final FixtureGeneralization definition;
        private final List<String> tested = new ArrayList<>();
        private String selected;

        private FixtureGeneralizationPlanner(
                FixtureGeneralization definition) {
            this.definition = definition;
        }

        void observe(Node committedDocument) {
            if (selected != null) {
                return;
            }
            String selectedBlueId = selectedTypeBlueId(committedDocument);
            for (String candidate : definition.candidates) {
                tested.add(candidate);
                if (definition.blueIdByCandidate.get(candidate).equals(
                        selectedBlueId)) {
                    selected = candidate;
                    return;
                }
            }
            throw new IllegalStateException(
                    "Released conformance engine selected a type outside "
                            + "the declared exact ancestor chain");
        }

        private static String selectedTypeBlueId(Node root) {
            Node type = root != null ? root.getType() : null;
            return type != null && type.getBlueId() != null
                    ? type.getBlueId()
                    : "";
        }

        List<String> tested() {
            return Collections.unmodifiableList(
                    new ArrayList<>(tested));
        }

        String selected() {
            return selected;
        }
    }

    static final class PreparedInput {
        final ObjectNode rootJson;
        final Node root;
        final Node event;
        final JsonNode runtimeControls;
        final Map<String, Node> providerNodes;
        final List<DerivedDelivery> derivedDeliveries;
        final VerifiedExecutionEvidence evidence;
        final ExternalDeliveryPlan deliveryPlan;
        final FixtureGeneralization generalization;
        final Node checkpointSubjectOverride;
        final String rootForm;
        final String cacheMode;
        final String batchingMode;

        PreparedInput(ObjectNode rootJson,
                      Node root,
                      Node event,
                      JsonNode runtimeControls,
                      Map<String, Node> providerNodes,
                      List<DerivedDelivery> derivedDeliveries,
                      VerifiedExecutionEvidence evidence,
                      ExternalDeliveryPlan deliveryPlan,
                      FixtureGeneralization generalization,
                      Node checkpointSubjectOverride,
                      String rootForm,
                      String cacheMode,
                      String batchingMode) {
            this.rootJson = rootJson.deepCopy();
            this.root = root;
            this.event = event;
            this.runtimeControls = runtimeControls != null
                    ? runtimeControls.deepCopy()
                    : null;
            this.providerNodes =
                    Collections.unmodifiableMap(new LinkedHashMap<>(providerNodes));
            this.derivedDeliveries = derivedDeliveries;
            this.evidence = evidence;
            this.deliveryPlan = deliveryPlan;
            this.generalization = generalization;
            this.checkpointSubjectOverride =
                    checkpointSubjectOverride != null
                            ? checkpointSubjectOverride.clone()
                            : null;
            this.rootForm = rootForm;
            this.cacheMode = cacheMode;
            this.batchingMode = batchingMode;
        }

        boolean snapshotRootForm() {
            return ContractsFixtureHarness.snapshotRootForm(rootForm);
        }

        boolean referenceBackedRootForm() {
            return ContractsFixtureHarness.referenceBackedRootForm(
                    rootForm);
        }
    }

    static final class ProcessorBundle implements AutoCloseable {
        final DocumentProcessor processor;
        final ScriptedContractsRuntime runtime;
        final FixtureGeneralizationPlanner generalization;
        final BlueLanguageRuntime language;
        final ConformanceEngine conformanceEngine;
        final FixturePhysicalProvider provider;

        ProcessorBundle(DocumentProcessor processor,
                        ScriptedContractsRuntime runtime,
                        FixtureGeneralizationPlanner generalization,
                        BlueLanguageRuntime language,
                        ConformanceEngine conformanceEngine,
                        FixturePhysicalProvider provider) {
            this.processor = processor;
            this.runtime = runtime;
            this.generalization = generalization;
            this.language = language;
            this.conformanceEngine = conformanceEngine;
            this.provider = provider;
        }

        @Override
        public void close() {
            try {
                processor.close();
            } finally {
                try {
                    conformanceEngine.close();
                } finally {
                    language.close();
                }
            }
        }
    }

    /**
     * Physical fixture provider used to make warm/cold and
     * batched/unbatched variants real preparation strategies. None of these
     * counters are exposed through semantic projections or gas traces.
     */
    static final class FixturePhysicalProvider
            implements NodeProvider {
        private final Map<String, Node> backing = new LinkedHashMap<>();
        private final Map<String, Node> cache = new LinkedHashMap<>();
        private final String cacheMode;
        private final String batchingMode;
        private final Set<String> transientlyUnavailable;
        private final Set<String> requestedBlueIds = new LinkedHashSet<>();
        private final int initialCacheEntries;
        private long requests;
        private long backendLoads;
        private int largestBackendLoad;

        FixturePhysicalProvider(Map<String, Node> nodes,
                                String cacheMode,
                                String batchingMode) {
            this(nodes, cacheMode, batchingMode,
                    Collections.<String>emptySet());
        }

        FixturePhysicalProvider(Map<String, Node> nodes,
                                String cacheMode,
                                String batchingMode,
                                Set<String> transientlyUnavailable) {
            if (!"cold".equals(cacheMode)
                    && !"warm".equals(cacheMode)) {
                throw new IllegalArgumentException(
                        "Unsupported fixture cache mode: " + cacheMode);
            }
            if (!"unbatched".equals(batchingMode)
                    && !"batched".equals(batchingMode)) {
                throw new IllegalArgumentException(
                        "Unsupported fixture batching mode: "
                                + batchingMode);
            }
            this.cacheMode = cacheMode;
            this.batchingMode = batchingMode;
            this.transientlyUnavailable = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(Objects.requireNonNull(
                            transientlyUnavailable,
                            "transientlyUnavailable")));
            for (Map.Entry<String, Node> entry : nodes.entrySet()) {
                backing.put(entry.getKey(), entry.getValue().clone());
            }
            if ("warm".equals(cacheMode)) {
                copyAll(backing, cache);
            }
            this.initialCacheEntries = cache.size();
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            requestedBlueIds.add(blueId);
            requests++;
            Node cached = cache.get(blueId);
            if (cached != null) {
                return Collections.singletonList(cached.clone());
            }
            if ("batched".equals(batchingMode)) {
                backendLoads++;
                largestBackendLoad =
                        Math.max(largestBackendLoad, backing.size());
                copyAll(backing, cache);
            } else {
                backendLoads++;
                Node exact = backing.get(blueId);
                if (exact != null) {
                    cache.put(blueId, exact.clone());
                    largestBackendLoad =
                            Math.max(largestBackendLoad, 1);
                }
            }
            Node loaded = cache.get(blueId);
            return loaded == null
                    ? null
                    : Collections.singletonList(loaded.clone());
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            if (transientlyUnavailable.contains(blueId)) {
                requestedBlueIds.add(blueId);
                requests++;
                backendLoads++;
                largestBackendLoad = Math.max(largestBackendLoad, 1);
                return NodeProviderResult.unavailable(
                        "Fixture exact node is transiently unavailable");
            }
            return NodeProvider.super.fetchResultByBlueId(blueId);
        }

        void verifyPreparation() {
            if ("cold".equals(cacheMode)
                    && initialCacheEntries != 0) {
                throw new AssertionError(
                        "Cold provider began with cached content");
            }
            if ("warm".equals(cacheMode)
                    && initialCacheEntries != backing.size()) {
                throw new AssertionError(
                        "Warm provider did not preload exact content");
            }
            if ("unbatched".equals(batchingMode)
                    && largestBackendLoad > 1) {
                throw new AssertionError(
                        "Unbatched provider performed a bulk load");
            }
            if ("batched".equals(batchingMode)
                    && backendLoads > 0
                    && largestBackendLoad != backing.size()) {
                throw new AssertionError(
                        "Batched provider did not load one physical batch");
            }
            if (requests > 0
                    && "cold".equals(cacheMode)
                    && backendLoads == 0) {
                throw new AssertionError(
                        "Cold provider request bypassed physical storage");
            }
        }

        void verifyExpectedLoads(Set<String> expectedBlueIds) {
            Set<String> missing = new LinkedHashSet<String>(
                    Objects.requireNonNull(expectedBlueIds,
                            "expectedBlueIds"));
            missing.removeAll(requestedBlueIds);
            if (!missing.isEmpty()) {
                throw new AssertionError(
                        "Fixture provider did not load expected exact nodes: "
                                + missing);
            }
        }

        private static void copyAll(Map<String, Node> source,
                                    Map<String, Node> target) {
            for (Map.Entry<String, Node> entry : source.entrySet()) {
                target.put(entry.getKey(), entry.getValue().clone());
            }
        }
    }

    static final class ProcessExecution {
        final DocumentProcessingResult result;
        final ProcessingConformanceTrace trace;
        final PlatformCommitCompanion platformCommitCompanion;
        final FixtureGeneralizationPlanner generalization;

        ProcessExecution(DocumentProcessingResult result,
                         ProcessingConformanceTrace trace,
                         PlatformCommitCompanion platformCommitCompanion,
                         FixtureGeneralizationPlanner generalization) {
            this.result = result;
            this.trace = trace;
            this.platformCommitCompanion =
                    platformCommitCompanion;
            this.generalization = generalization;
        }
    }

}
