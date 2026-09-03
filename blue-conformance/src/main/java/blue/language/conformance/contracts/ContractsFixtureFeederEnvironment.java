package blue.language.conformance.contracts;

import static blue.language.conformance.contracts.ContractsFixtureExecutionEngine.*;
import static blue.language.conformance.contracts.ContractsFixtureInputPreparer.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionExtractor.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionSupport.*;
import static blue.language.conformance.contracts.ContractsFixtureScriptedEnvironment.*;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.provider.NodeProvider;
import blue.language.registry.BootstrapProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifiedNodeProvider;
import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ConformanceChangedPath;
import blue.language.processor.ConformancePlannerOverride;
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
import blue.language.processor.registry.RuntimeBlueIds;
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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


/** Derives exact feeder evidence, intervals, and embedded scope occurrences. */
abstract class ContractsFixtureFeederEnvironment extends ContractsFixtureHarnessDataSupport {

    abstract ExternalChannelDependencySnapshot fixtureChannelDependencies(
            ObjectNode scope,
            String ownerKey,
            JsonNode ownerContract,
            Map<String, Node> providerNodes);

    Map<String, Node> verifyProviderNodes(JsonNode provider) {
        Map<String, Node> result = new LinkedHashMap<>();
        JsonNode nodes = provider.get("nodes");
        if (nodes == null) {
            return result;
        }
        nodes.fields().forEachRemaining(entry -> {
            Node node = readNode(entry.getValue());
            String actual = DirectBlueIdCalculator.calculateBlueId(node);
            if (!entry.getKey().equals(actual)) {
                throw new IllegalArgumentException(
                        "Provider node identity mismatch: expected "
                                + entry.getKey() + " but calculated " + actual);
            }
            result.put(entry.getKey(), node);
        });
        return result;
    }

    List<DerivedDelivery> deriveDeliveries(
            ObjectNode root,
            JsonNode event,
            JsonNode hints,
            String eventBlueId,
            Node canonicalEvent,
            String checkpointSubjectOverrideBlueId,
            Node canonicalCheckpointSubjectOverride,
            Map<String, Node> providerNodes,
            boolean includeUnhintedCandidates) {
        /*
         * PROCESS admission owns the top-level cyclic-member diagnostic.
         * Such an event has no independently inspectable body, so feeder
         * preparation must not attempt to derive a subscription key first.
         * BlueId calculation has already validated the exact event identity.
         */
        if (BlueIds.hasCyclicMemberSeparator(eventBlueId)) {
            return Collections.emptyList();
        }
        String subscriptionKey = event.path(
                ProcessorContractConstants.KEY_SUBSCRIPTION_KEY).asText(null);
        if (subscriptionKey == null) {
            throw new IllegalArgumentException(
                    "Fixture event requires subscriptionKey");
        }
        Map<String, JsonNode> hintByOccurrence = new LinkedHashMap<>();
        Map<String, Integer> assertedOrderByOccurrence =
                new LinkedHashMap<>();
        for (JsonNode hint : hints) {
            String occurrence = occurrence(
                    hint.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText(),
                    hint.path(ContractsFixtureConstants.Field.CHANNEL_KEY).asText());
            if (hintByOccurrence.put(occurrence, hint) != null) {
                throw new IllegalArgumentException(
                        "Duplicate delivery hint " + occurrence);
            }
            if (hint.has(ContractsFixtureConstants.Field.ORDER)) {
                assertedOrderByOccurrence.put(
                        occurrence,
                        hint.get(ContractsFixtureConstants.Field.ORDER).asInt());
            }
        }

        List<ScopeValue> scopes = enumerateDeclaredScopes(
                root, providerNodes);
        List<DerivedDelivery> result = new ArrayList<>();
        for (ScopeValue scope : scopes) {
            JsonNode contracts = scope.value.get(
                    ProcessorContractConstants.KEY_CONTRACTS);
            if (contracts == null || !contracts.isObject()
                    || contracts.has(
                    ProcessorContractConstants.KEY_TERMINATED)) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = contracts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode contract = materializeFixtureObject(
                        entry.getValue(), providerNodes);
                if (contract == null) {
                    continue;
                }
                Node contractNode = readNode(contract);
                if (deferUnknownContractCapabilityToRuntime(contractNode)) {
                    continue;
                }
                ResolvedSnapshot contractSnapshot =
                        fixtureContractSourceSnapshot(
                                contractNode, providerNodes);
                Node effectiveType = contractSnapshot
                        .resolvedRoot()
                        .getType();
                String typeBlueId = effectiveType != null
                        ? contractSnapshot.canonicalTypeIdentities()
                                .requireCanonicalTypeBlueId(effectiveType)
                        : null;
                if (!registry.isSubtype(typeBlueId, registryId("ExternalChannel"))) {
                    continue;
                }
                if (!subscriptionKey.equals(
                        contract.path(
                                ProcessorContractConstants
                                        .KEY_SUBSCRIPTION_KEY)
                                .asText(null))) {
                    continue;
                }
                String key = occurrence(scope.path, entry.getKey());
                JsonNode hint = hintByOccurrence.remove(key);
                if (hint == null && !includeUnhintedCandidates) {
                    continue;
                }
                int order = contract.path(ContractsFixtureConstants.Field.ORDER).asInt(0);
                String contribution = contractSnapshot.blueId();
                String domain = contract.path("checkpointDomain").asText(null);
                if (domain == null) {
                    throw new IllegalArgumentException(
                            "External Channel has no checkpointDomain at " + key);
                }
                List<String> contributions =
                        Collections.singletonList(contribution);
                ExternalChannelDependencySnapshot dependencies =
                        fixtureChannelDependencies(
                                scope.value,
                                entry.getKey(),
                                contract,
                                providerNodes);
                Node domainNode = checkpointDomainNode(
                        typeBlueId,
                        contributions,
                        dependencies,
                        domain);
                String domainBlueId =
                        DirectBlueIdCalculator.calculateBlueId(domainNode);
                String canonicalDomainBlueId = CheckpointDomain.derive(
                        typeBlueId,
                        contributions,
                        dependencies,
                        domain);
                if (!domainBlueId.equals(canonicalDomainBlueId)) {
                    throw new IllegalStateException(
                            "Checkpoint domain derivation drift");
                }
                String subjectBlueId = eventBlueId;
                Node subjectNode = canonicalEvent.clone();
                if (canonicalCheckpointSubjectOverride != null) {
                    subjectNode = canonicalCheckpointSubjectOverride.clone();
                    subjectBlueId = Objects.requireNonNull(
                            checkpointSubjectOverrideBlueId,
                            "checkpointSubjectOverrideBlueId");
                }
                ExternalDeliverySnapshot.Builder snapshot =
                        ExternalDeliverySnapshot.builder(scope.path, entry.getKey())
                                .order(order)
                                .sourceContribution(contribution)
                                .effectiveTypeBlueId(typeBlueId)
                                .subscriptionKey(subscriptionKey)
                                .checkpointDomainBlueId(domainBlueId)
                                .checkpointSubjectBlueId(subjectBlueId);
                if (hint != null && hint.has(ContractsFixtureConstants.Field.ACTIVATION_START_EXCLUSIVE)) {
                    snapshot.activationStartExclusive(
                            externalOrderKey(
                                    hint.get(ContractsFixtureConstants.Field.ACTIVATION_START_EXCLUSIVE)));
                }
                result.add(new DerivedDelivery(
                        snapshot.build(),
                        domainBlueId,
                        domainNode,
                        subjectNode));
            }
        }
        result.sort(Comparator
                .comparingInt((DerivedDelivery value) ->
                        scopeDepth(value.snapshot.scopePath()))
                .reversed()
                .thenComparing(value -> value.snapshot.scopePath())
                .thenComparingInt(value -> value.snapshot.order())
                .thenComparing(value -> value.snapshot.channelKey()));
        validateDeliveryHintOrders(
                result,
                assertedOrderByOccurrence);
        if (!hintByOccurrence.isEmpty()) {
            throw new IllegalArgumentException(
                    "Delivery hint is not derivable from the exact Root: "
                            + hintByOccurrence.keySet());
        }
        List<String> derivedKeys = new ArrayList<>();
        for (DerivedDelivery delivery : result) {
            derivedKeys.add(occurrence(
                    delivery.snapshot.scopePath(),
                    delivery.snapshot.channelKey()));
        }
        List<String> hintedKeys = new ArrayList<>();
        for (JsonNode hint : hints) {
            hintedKeys.add(occurrence(
                    hint.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText(),
                    hint.path(ContractsFixtureConstants.Field.CHANNEL_KEY).asText()));
        }
        /*
         * platform/canonicalPreselection deliberately exercises an omission
         * and is classified by executePlatform. Every other hint set must be
         * the complete canonical preselection.
         */
        if (!hintedKeys.equals(derivedKeys)) {
            // The caller distinguishes the declared platform omission.
            if (hints.size() != 0) {
                throw new IllegalArgumentException(
                        "Delivery hints are not the complete canonical order: "
                                + hintedKeys + " != " + derivedKeys);
            }
        }
        return Collections.unmodifiableList(result);
    }

    void validateDeliveryHintOrders(
            List<DerivedDelivery> deliveries,
            Map<String, Integer> assertedOrderByOccurrence) {
        for (int index = 0; index < deliveries.size(); index++) {
            ExternalDeliverySnapshot snapshot =
                    deliveries.get(index).snapshot;
            String key = occurrence(
                    snapshot.scopePath(),
                    snapshot.channelKey());
            Integer asserted = assertedOrderByOccurrence.get(key);
            if (asserted == null
                    || asserted.intValue() == snapshot.order()) {
                continue;
            }

            /*
             * The final multi-source routing fixtures encode tied effective
             * channel orders as stable tie ordinals (0, 1, ...). Keep the
             * derived ExternalDelivery.order exact, but accept that redundant
             * compact-hint spelling only when it proves the same canonical
             * key order within one scope/order tie. Arbitrary mismatches still
             * fail closed.
             */
            int first = index;
            while (first > 0
                    && sameDeliveryOrderTie(
                    deliveries.get(first - 1).snapshot,
                    snapshot)) {
                first--;
            }
            int last = index;
            while (last + 1 < deliveries.size()
                    && sameDeliveryOrderTie(
                    deliveries.get(last + 1).snapshot,
                    snapshot)) {
                last++;
            }
            int tieRank = index - first;
            boolean stableTieOrdinal =
                    last > first
                            && asserted.intValue()
                            == snapshot.order() + tieRank;
            if (!stableTieOrdinal) {
                throw new IllegalArgumentException(
                        "Delivery hint order mismatch at " + key);
            }
        }
    }

    boolean sameDeliveryOrderTie(
            ExternalDeliverySnapshot left,
            ExternalDeliverySnapshot right) {
        return left.order() == right.order()
                && left.scopePath().equals(right.scopePath());
    }

    /**
     * Builds the complete retained active index surface independently of the
     * current event's canonical preselection. The fixture platform treats
     * admission revision zero as the activation revision of the supplied
     * authoritative Root.
     */
    List<SubscriptionDelta.Entry>
    deriveActiveSubscriptionIntervals(
            ObjectNode root,
            JsonNode deliveryHints,
            Map<String, Node> providerNodes,
            boolean includeUnhintedCandidates) {
        Map<String, ExternalOrderKey> starts =
                new LinkedHashMap<>();
        Set<String> retainedOccurrences = new LinkedHashSet<>();
        for (JsonNode hint : deliveryHints) {
            retainedOccurrences.add(occurrence(
                    hint.path(ContractsFixtureConstants.Field.SCOPE_PATH)
                            .asText(),
                    hint.path(ContractsFixtureConstants.Field.CHANNEL_KEY)
                            .asText()));
            if (hint.has(ContractsFixtureConstants.Field.ACTIVATION_START_EXCLUSIVE)) {
                starts.put(
                        occurrence(
                                hint.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText(),
                                hint.path(ContractsFixtureConstants.Field.CHANNEL_KEY).asText()),
                        externalOrderKey(
                                hint.get(ContractsFixtureConstants.Field.ACTIVATION_START_EXCLUSIVE)));
            }
        }
        List<SubscriptionDelta.Entry> result =
                new ArrayList<>();
        for (ScopeValue scope : enumerateDeclaredScopes(
                root, providerNodes)) {
            JsonNode contracts = scope.value.get(
                    ProcessorContractConstants.KEY_CONTRACTS);
            if (contracts == null || !contracts.isObject()
                    || contracts.has(
                        ProcessorContractConstants.KEY_TERMINATED)) {
                continue;
            }
            if (!includeUnhintedCandidates) {
                retainCheckpointedOccurrences(
                        scope.path, contracts, retainedOccurrences);
            }
            Iterator<Map.Entry<String, JsonNode>> fields =
                    contracts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry =
                        fields.next();
                String occurrence = occurrence(
                        scope.path, entry.getKey());
                if (!includeUnhintedCandidates
                        && !retainedOccurrences.contains(occurrence)) {
                    continue;
                }
                JsonNode contract = materializeFixtureObject(
                        entry.getValue(), providerNodes);
                if (contract == null) {
                    continue;
                }
                Node contractNode = readNode(contract);
                if (deferUnknownContractCapabilityToRuntime(contractNode)) {
                    continue;
                }
                ResolvedSnapshot contractSnapshot =
                        fixtureContractSourceSnapshot(
                                contractNode, providerNodes);
                Node effectiveType = contractSnapshot
                        .resolvedRoot()
                        .getType();
                String typeBlueId = effectiveType != null
                        ? contractSnapshot.canonicalTypeIdentities()
                                .requireCanonicalTypeBlueId(effectiveType)
                        : null;
                if (!registry.isSubtype(
                        typeBlueId,
                        registryId("ExternalChannel"))) {
                    continue;
                }
                List<String> subscriptionKeys =
                        new ArrayList<>();
                JsonNode plural =
                        contract.get(
                                ProcessorContractConstants
                                        .KEY_SUBSCRIPTION_KEYS);
                if (plural != null && plural.isArray()) {
                    for (JsonNode key : plural) {
                        if (!key.isTextual()
                                || key.asText().isEmpty()) {
                            throw new IllegalArgumentException(
                                    "Invalid retained subscription key at "
                                            + scope.path + "/"
                                            + entry.getKey());
                        }
                        subscriptionKeys.add(key.asText());
                    }
                } else {
                    String singular =
                            contract.path(
                                    ProcessorContractConstants
                                            .KEY_SUBSCRIPTION_KEY)
                                    .asText(null);
                    if (singular != null
                            && !singular.isEmpty()) {
                        subscriptionKeys.add(singular);
                    }
                }
                if (subscriptionKeys.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Active External Channel has no subscription "
                                    + "keys at " + scope.path + "/"
                                    + entry.getKey());
                }
                String contribution = contractSnapshot.blueId();
                String discriminator =
                        contract.path("checkpointDomain")
                                .asText(null);
                if (discriminator == null) {
                    throw new IllegalArgumentException(
                            "Active External Channel has no checkpoint "
                                    + "domain at " + scope.path + "/"
                                    + entry.getKey());
                }
                ExternalChannelDependencySnapshot dependencies =
                        fixtureChannelDependencies(
                                scope.value,
                                entry.getKey(),
                                contract,
                                providerNodes);
                String domain = CheckpointDomain.derive(
                        typeBlueId,
                        Collections.singletonList(contribution),
                        dependencies,
                        discriminator);
                result.add(new SubscriptionDelta.Entry(
                        scope.path,
                        entry.getKey(),
                        typeBlueId,
                        Collections.singletonList(contribution),
                        contract.path(ContractsFixtureConstants.Field.ORDER).asInt(0),
                        subscriptionKeys,
                        domain,
                        dependencies,
                        0L,
                        starts.get(occurrence),
                        null));
            }
        }
        return Collections.unmodifiableList(result);
    }

    List<ScopeValue> enumerateDeclaredScopes(ObjectNode root) {
        return enumerateDeclaredScopes(
                root, Collections.<String, Node>emptyMap());
    }

    List<ScopeValue> enumerateDeclaredScopes(
            ObjectNode root,
            Map<String, Node> providerNodes) {
        List<ScopeValue> result = new ArrayList<>();
        Set<String> visitedIds = new LinkedHashSet<>();
        enumerateDeclaredScopes(
                "/", root, result, visitedIds, providerNodes);
        return result;
    }

    /**
     * Adds source occurrences proven active by persisted checkpoint state.
     * Process fixtures use compact delivery hints for current preselection;
     * a non-selected prior source remains part of the retained interval
     * surface when its checkpoint entry proves an earlier activation.
     */
    static void retainCheckpointedOccurrences(
            String scopePath,
            JsonNode contracts,
            Set<String> retainedOccurrences) {
        JsonNode entries = contracts.path(
                ProcessorContractConstants.KEY_CHECKPOINT).path(
                ProcessorContractConstants.KEY_ENTRIES);
        if (!entries.isObject()) {
            return;
        }
        Iterator<String> keys = entries.fieldNames();
        while (keys.hasNext()) {
            retainedOccurrences.add(occurrence(scopePath, keys.next()));
        }
    }

    void enumerateDeclaredScopes(String path,
                                         ObjectNode scope,
                                         List<ScopeValue> result,
                                         Set<String> ancestry,
                                         Map<String, Node> providerNodes) {
        result.add(new ScopeValue(path, scope));
        String identity = DirectBlueIdCalculator.calculateBlueId(readNode(scope));
        if (!ancestry.add(identity)) {
            throw new IllegalArgumentException(
                    "Embedded scope ancestry cycle at " + path);
        }
        JsonNode embedded = scope
                .path(ProcessorContractConstants.KEY_CONTRACTS)
                .path(ProcessorContractConstants.KEY_EMBEDDED);
        JsonNode paths = embedded.path(
                ProcessorContractConstants.KEY_PATHS);
        if (paths.isArray()) {
            for (JsonNode declared : paths) {
                String childPath = resolveScope(path, declared.asText());
                JsonNode child = jsonAt(result.get(0).value, childPath);
                if (child == null || child.isMissingNode() || child.isNull()) {
                    continue;
                }
                if (!child.isObject()) {
                    throw new IllegalArgumentException(
                            "Embedded scope is not an object at " + childPath);
                }
                enumerateDeclaredScopes(
                        childPath,
                        (ObjectNode) child,
                        result,
                        new LinkedHashSet<>(ancestry),
                        providerNodes);
            }
        }

        JsonNode collectionPaths = embedded.path(
                ProcessorContractConstants.KEY_COLLECTION_PATHS);
        if (!collectionPaths.isArray()) {
            return;
        }
        for (JsonNode declared : collectionPaths) {
            String collectionPath = resolveScope(
                    path,
                    declared.asText());
            JsonNode collection = jsonAt(
                    result.get(0).value,
                    collectionPath);
            if (collection == null || !collection.isObject()) {
                // Runtime subscription-surface validation owns malformed,
                // absent, list, and scalar collection-target diagnostics.
                continue;
            }
            List<String> memberKeys = new ArrayList<>();
            collection.fieldNames().forEachRemaining(memberKeys::add);
            memberKeys.removeIf(
                    ContractsFixtureHarness::isReservedBlueField);
            memberKeys.sort(ExternalOrderKey::compareTextCodePoints);
            for (String memberKey : memberKeys) {
                JsonNode member = materializeFixtureObject(
                        collection.get(memberKey), providerNodes);
                if (member == null) {
                    // The processor must report the precise invalid-surface
                    // diagnostic; the feeder must not invent a scope here.
                    continue;
                }
                String childPath = JsonPointer.append(
                        collectionPath,
                        memberKey);
                enumerateDeclaredScopes(
                        childPath,
                        (ObjectNode) member,
                        result,
                        new LinkedHashSet<>(ancestry),
                        providerNodes);
            }
        }
    }

    static ObjectNode materializeFixtureObject(
            JsonNode candidate,
            Map<String, Node> providerNodes) {
        if (candidate == null || !candidate.isObject()) {
            return null;
        }
        ObjectNode object = (ObjectNode) candidate;
        if (object.size() != 1
                || !object.path(BlueLanguageConstants.OBJECT_BLUE_ID)
                .isTextual()) {
            return object;
        }
        Node exact = providerNodes.get(
                object.path(BlueLanguageConstants.OBJECT_BLUE_ID).asText());
        return exact != null
                ? compactFixtureObject(exact)
                : null;
    }

    /**
     * Projects an exact provider node in fixture authoring form. SIMPLE wire
     * form is required for nested contract scalar fields, but a scalar-bearing
     * scope would otherwise collapse to the scalar and discard its contracts.
     * Detaching only the root payload preserves both parts without changing
     * the verified provider node used by the processor.
     */
    static ObjectNode compactFixtureObject(Node exact) {
        Node container = exact.clone();
        Object scalar = container.getValue();
        List<Node> items = container.getItems();
        if (scalar != null) {
            container.value(null);
        }
        if (items != null) {
            container.items((List<Node>) null);
        }
        ObjectNode result = (ObjectNode) UncheckedObjectMapper.JSON_MAPPER
                .valueToTree(NodeWireForm.get(
                        container, NodeWireForm.Strategy.SIMPLE));
        if (scalar != null) {
            result.set(
                    BlueLanguageConstants.OBJECT_VALUE,
                    UncheckedObjectMapper.JSON_MAPPER.valueToTree(scalar));
        }
        if (items != null) {
            List<Object> wireItems = new ArrayList<>();
            for (Node item : items) {
                wireItems.add(NodeWireForm.get(
                        item, NodeWireForm.Strategy.SIMPLE));
            }
            result.set(
                    BlueLanguageConstants.OBJECT_ITEMS,
                    UncheckedObjectMapper.JSON_MAPPER.valueToTree(wireItems));
        }
        return result;
    }

}
