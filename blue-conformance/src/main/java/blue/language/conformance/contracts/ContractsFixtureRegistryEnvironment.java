package blue.language.conformance.contracts;

import static blue.language.conformance.contracts.ContractsFixtureHarnessDataSupport.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionSupport.normalizeNode;

import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.ExternalOrderKey;
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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads and validates the exact registry environment used by Contracts
 * conformance fixtures.
 */
final class ContractsFixtureRegistryEnvironment {

    final Map<String, Node> nodesByBlueId;
    final Map<String, String> idByKey;
    final String runtimeRegistryIdentity;

    private ContractsFixtureRegistryEnvironment(
            Map<String, Node> nodesByBlueId,
            Map<String, String> idByKey,
            String runtimeRegistryIdentity) {
        this.nodesByBlueId =
                Collections.unmodifiableMap(new LinkedHashMap<>(nodesByBlueId));
        this.idByKey =
                Collections.unmodifiableMap(new LinkedHashMap<>(idByKey));
        this.runtimeRegistryIdentity = Objects.requireNonNull(
                runtimeRegistryIdentity, "runtimeRegistryIdentity");
    }

    static ContractsFixtureRegistryEnvironment load() {
        return DefaultHolder.INSTANCE;
    }

    static ContractsFixtureRegistryEnvironment load(Path packageRoot) {
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
        return new ContractsFixtureRegistryEnvironment(
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

    private static ContractsFixtureRegistryEnvironment loadInternal() {
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
        return new ContractsFixtureRegistryEnvironment(
                nodes,
                keys,
                BlueContractsConformanceReport
                        .CONTRACTS_REGISTRY_PACKAGE_IDENTITY);
    }

    private static void loadConformanceOperation(
            Map<String, Node> nodes,
            Map<String, String> keys) {
        /*
         * This adapter is owned and package-inventoried by the conformance
         * harness. It is deliberately loaded beside the frozen Contracts
         * runtime registry instead of changing that registry's identity.
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
        private static final ContractsFixtureRegistryEnvironment INSTANCE =
                loadInternal();
    }

    private static void loadRegistry(
            String root,
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
            String blueId = entry.path(
                    BlueLanguageConstants.OBJECT_BLUE_ID).asText();
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

    private static void loadRegistry(
            Path manifestPath,
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
