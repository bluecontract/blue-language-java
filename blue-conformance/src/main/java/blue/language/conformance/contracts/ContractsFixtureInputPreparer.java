package blue.language.conformance.contracts;

import static blue.language.conformance.contracts.ContractsFixtureExecutionEngine.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionExtractor.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionSupport.*;
import static blue.language.conformance.contracts.ContractsFixtureScriptedEnvironment.*;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.api.BlueContractsConformanceReport;
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
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
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


/** Builds immutable fixture inputs, providers, checkpoints, and delivery plans. */
abstract class ContractsFixtureInputPreparer extends ContractsFixtureProjectionSupport {

    abstract void applyBuilders(ObjectNode root, JsonNode builders);

    abstract void installRuntimeContracts(
            ObjectNode root,
            JsonNode runtime,
            JsonNode feeder);

    abstract void applyVariant(ObjectNode root, JsonNode variant);

    PreparedInput prepare(JsonNode input,
                                  JsonNode variant,
                                  Node previousRoot,
                                  boolean requiresExecutionEvidence,
                                  boolean preinitializeInternalCycle) {
        String rootForm = variant != null
                ? variant.path(ContractsFixtureConstants.Field.ROOT_FORM).asText("inline")
                : "inline";
        String cacheMode = variant != null
                ? variant.path(ContractsFixtureConstants.Field.CACHE).asText("cold")
                : "cold";
        String batchingMode = variant != null
                ? variant.path(ContractsFixtureConstants.Field.BATCHING).asText("unbatched")
                : "unbatched";
        ObjectNode declaredRoot =
                requireObject(
                        input.get(ContractsFixtureConstants.Field.ROOT),
                        "input.root").deepCopy();
        applyBuilders(declaredRoot, input.path(ContractsFixtureConstants.Field.BUILDERS));
        promoteMixedFixtureScalarToObject(declaredRoot);
        Map<String, Node> providerNodes = verifyProviderNodes(
                input.path(ContractsFixtureConstants.Field.PROVIDER));
        if (preinitializeInternalCycle) {
            installExactPreinitializedMarker(
                    declaredRoot, providerNodes);
        }
        installRuntimeContracts(
                declaredRoot,
                input.path(ContractsFixtureConstants.Field.RUNTIME),
                input.path(ContractsFixtureConstants.Field.FEEDER));
        FixtureGeneralization generalization =
                FixtureGeneralization.create(
                        declaredRoot, input.path(ContractsFixtureConstants.Field.RUNTIME));
        if (generalization != null) {
            for (Map.Entry<String, Node> entry :
                    generalization.nodesByBlueId.entrySet()) {
                putDerivedProviderNode(
                        providerNodes,
                        entry.getKey(),
                        entry.getValue());
            }
        }
        ObjectNode rootJson = declaredRoot;
        if (previousRoot != null) {
            rootJson = (ObjectNode) UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                    NodeWireForm.get(previousRoot));
            materializeRetryContracts(
                    rootJson, declaredRoot, providerNodes);
        }
        if (variant != null) {
            applyVariant(rootJson, variant);
        }
        Node event = readNode(input.get(ContractsFixtureConstants.Field.EVENT));
        Node checkpointSubjectOverride =
                variant != null && variant.has("checkpointSubject")
                        ? rawCheckpointSubject(
                        variant.get("checkpointSubject"))
                        : null;

        FixtureSourceIdentityResolver.Identity eventIdentity =
                sourceIdentity(event, providerNodes, false);
        String eventBlueId = eventIdentity.blueId();
        Node canonicalEvent = eventIdentity.canonicalInput();
        FixtureSourceIdentityResolver.Identity checkpointSubjectIdentity =
                checkpointSubjectOverride != null
                        ? sourceIdentity(
                                checkpointSubjectOverride,
                                providerNodes,
                                false)
                        : null;
        JsonNode feeder = input.path(ContractsFixtureConstants.Field.FEEDER);
        List<DerivedDelivery> deliveries = deriveDeliveries(
                rootJson, input.path(ContractsFixtureConstants.Field.EVENT), feeder.path(ContractsFixtureConstants.Field.DELIVERY_SNAPSHOT),
                eventBlueId,
                canonicalEvent,
                checkpointSubjectIdentity != null
                        ? checkpointSubjectIdentity.blueId()
                        : null,
                checkpointSubjectIdentity != null
                        ? checkpointSubjectIdentity.canonicalInput()
                        : null,
                providerNodes,
                !requiresExecutionEvidence);
        normalizeDeclaredCheckpointDomains(
                rootJson,
                deliveries);
        if (variant != null
                && (checkpointSubjectOverride != null
                || (previousRoot != null
                && variant.path(ContractsFixtureConstants.Field.SAME_EVENT).asBoolean(false)))) {
            seedVariantCheckpoints(rootJson, deliveries);
        }
        for (DerivedDelivery delivery : deliveries) {
            putDerivedProviderNode(
                    providerNodes,
                    delivery.checkpointDomainBlueId,
                    delivery.checkpointDomainNode);
            if (!delivery.checkpointSubjectNode.isReferenceOnly()) {
                putDerivedProviderNode(
                        providerNodes,
                        delivery.snapshot.checkpointSubjectBlueId(),
                        delivery.checkpointSubjectNode);
            }
        }
        Node materializedRoot = readNode(rootJson);
        FixtureSourceIdentityResolver.Identity rootIdentity =
                sourceIdentity(materializedRoot, providerNodes, true);
        String inlineRootBlueId = rootIdentity.blueId();
        String rootBlueId = inlineRootBlueId;
        Node exactProviderRoot = rootIdentity.canonicalInput();
        if (referenceBackedRootForm(rootForm)) {
            rootBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            exactProviderRoot);
            if (!inlineRootBlueId.equals(rootBlueId)) {
                throw new IllegalStateException(
                        "Preprocessing changed the exact Root identity "
                                + "between inline and reference forms");
            }
        }
        if (!"inline".equals(rootForm)) {
            putDerivedProviderNode(
                    providerNodes, rootBlueId, exactProviderRoot);
        }
        Node root = referenceBackedRootForm(rootForm)
                ? new Node().blueId(rootBlueId)
                : materializedRoot;

        long managed = requiredLong(feeder, "managedRootRevision");
        long indexed = requiredLong(feeder, "indexedRootRevision");
        if (variant != null && variant.has(ContractsFixtureConstants.Field.ROOT_REVISION)) {
            managed = variant.get(ContractsFixtureConstants.Field.ROOT_REVISION).asLong();
            indexed = managed;
        }
        ExternalOrderKey eventOrderKey =
                externalOrderKey(feeder.path(ContractsFixtureConstants.Field.EVENT_ORDER_KEY));
        ObjectNode retainedIntervalRoot = rootJson;
        boolean includeUnhintedIntervals = !requiresExecutionEvidence;
        if (generalization != null) {
            /*
             * Generalization fixtures may declare an effective Channel or
             * Process Embedded contract only on their synthetic most-specific
             * type.  The retained index represents that complete effective
             * pre-transition surface, whereas delivery preselection remains
             * bound to the authored delivery hints above.
             */
            retainedIntervalRoot = effectiveIntervalRoot(
                    rootJson, generalization);
            includeUnhintedIntervals = true;
        }
        List<SubscriptionDelta.Entry> activeSubscriptionIntervals =
                deriveActiveSubscriptionIntervals(
                        retainedIntervalRoot,
                        feeder.path(ContractsFixtureConstants.Field.DELIVERY_SNAPSHOT),
                        providerNodes,
                        includeUnhintedIntervals);
        VerifiedExecutionEvidence builtEvidence = null;
        ExternalDeliveryPlan builtPlan = null;
        if (requiresExecutionEvidence) {
            VerifiedExecutionEvidence.Builder evidence =
                    VerifiedExecutionEvidence.builder(rootBlueId, eventBlueId)
                            .revisions(managed, indexed)
                            .runtimeRegistryIdentity(
                                    BlueContractsConformanceReport
                                            .CONTRACTS_REGISTRY_PACKAGE_IDENTITY)
                            .eventOrderKey(eventOrderKey)
                            .activeSubscriptionIntervals(
                                    activeSubscriptionIntervals);
            for (DerivedDelivery delivery : deliveries) {
                evidence.delivery(delivery.snapshot);
            }
            for (String blueId : providerNodes.keySet()) {
                evidence.availableExactNode(blueId);
            }
            String unavailableAt =
                    input.path(ContractsFixtureConstants.Field.PROVIDER).path(
                            "transientUnavailableAt").asText(null);
            if (unavailableAt != null) {
                evidence.requiredExactNode(
                        requiredSelectedBodyBlueId(
                                rootJson, deliveries, unavailableAt));
            }
            builtEvidence = evidence.build();
            if (!inlineRootBlueId.equals(
                    builtEvidence.rootBlueId())) {
                throw new IllegalStateException(
                        "Execution evidence Root identity diverged "
                                + "between inline and reference forms");
            }
            ExternalDeliveryPlan.Builder plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(managed, indexed)
                            .eventOrderKey(eventOrderKey)
                            .activeSubscriptionIntervals(
                                    activeSubscriptionIntervals)
                            .exactRuntimeState();
            for (ExternalDeliverySnapshot delivery :
                    builtEvidence.deliveries()) {
                plan.delivery(delivery);
            }
            for (String blueId :
                    builtEvidence.availableExactNodeBlueIds()) {
                plan.availableExactNode(blueId);
            }
            for (String blueId :
                    builtEvidence.requiredExactNodeBlueIds()) {
                plan.requiredExactNode(blueId);
            }
            builtPlan = plan.build();
        }
        return new PreparedInput(
                rootJson,
                root,
                event,
                input.get(ContractsFixtureConstants.Field.RUNTIME),
                providerNodes,
                deliveries,
                builtEvidence,
                builtPlan,
                generalization,
                checkpointSubjectOverride,
                rootForm,
                cacheMode,
                batchingMode);
    }

    ObjectNode effectiveIntervalRoot(
            ObjectNode sourceRoot,
            FixtureGeneralization generalization) {
        ObjectNode result = sourceRoot.deepCopy();
        ObjectNode contracts = contractsObject(result);
        ObjectNode inherited = generalization.subtypeContracts;
        if (inherited == null) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = inherited.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!contracts.has(entry.getKey())) {
                contracts.set(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return result;
    }

    static void seedVariantCheckpoints(
            ObjectNode root,
            List<DerivedDelivery> deliveries) {
        for (DerivedDelivery delivery : deliveries) {
            JsonNode scopeValue =
                    jsonAt(root, delivery.snapshot.scopePath());
            if (!(scopeValue instanceof ObjectNode)) {
                throw new IllegalArgumentException(
                        "Checkpoint variant selected a missing scope "
                                + delivery.snapshot.scopePath());
            }
            ObjectNode contracts =
                    contractsObject((ObjectNode) scopeValue);
            ObjectNode checkpoint;
            if (contracts.has(
                    ProcessorContractConstants.KEY_CHECKPOINT)) {
                checkpoint = requireObject(
                        contracts.get(
                                ProcessorContractConstants.KEY_CHECKPOINT),
                        "variant checkpoint");
            } else {
                checkpoint = contracts.putObject(
                        ProcessorContractConstants.KEY_CHECKPOINT);
                checkpoint.putObject(BlueLanguageConstants.OBJECT_TYPE).put(
                        BlueLanguageConstants.OBJECT_BLUE_ID,
                        registryId("ChannelEventCheckpoint"));
            }
            ObjectNode entries =
                    objectField(
                            checkpoint,
                            ProcessorContractConstants.KEY_ENTRIES,
                            true);
            ObjectNode stored =
                    entries.putObject(
                            delivery.snapshot.channelKey());
            stored.putObject("domain").put(
                    BlueLanguageConstants.OBJECT_BLUE_ID,
                    delivery.snapshot.checkpointDomainBlueId());
            stored.putObject("subject").put(
                    BlueLanguageConstants.OBJECT_BLUE_ID,
                    delivery.snapshot.checkpointSubjectBlueId());
        }
    }

    static void normalizeDeclaredCheckpointDomains(
            ObjectNode root,
            List<DerivedDelivery> deliveries) {
        for (DerivedDelivery delivery : deliveries) {
            JsonNode scope =
                    jsonAt(
                            root,
                            delivery.snapshot
                                    .scopePath());
            if (scope == null || !scope.isObject()) {
                continue;
            }
            JsonNode contracts = scope.get(
                    ProcessorContractConstants.KEY_CONTRACTS);
            JsonNode channel = contracts != null
                    ? contracts.get(
                    delivery.snapshot.channelKey())
                    : null;
            String discriminator = channel != null
                    ? channel.path(
                    "checkpointDomain").asText(null)
                    : null;
            JsonNode entries = contracts != null
                    ? contracts.path(
                            ProcessorContractConstants.KEY_CHECKPOINT)
                    .path(ProcessorContractConstants.KEY_ENTRIES)
                    : null;
            JsonNode stored = entries != null
                    ? entries.get(
                    delivery.snapshot.channelKey())
                    : null;
            JsonNode domain = stored != null
                    ? stored.get("domain")
                    : null;
            if (stored instanceof ObjectNode
                    && domain != null
                    && domain.isTextual()
                    && domain.asText().equals(
                    discriminator)) {
                ((ObjectNode) stored)
                        .putObject("domain")
                        .put(
                                BlueLanguageConstants.OBJECT_BLUE_ID,
                                delivery
                                        .checkpointDomainBlueId);
            }
        }
    }

    /**
     * A committed canonical Root may collapse an unchanged direct contract to
     * its exact BlueId. A same-event retry retains the original exact fixture
     * content as provider materialization; expanding that equivalent form is
     * necessary both for canonical preselection and for the fresh processor's
     * provider cache.
     */
    final void materializeRetryContracts(
            JsonNode current,
            JsonNode declared,
            Map<String, Node> providerNodes) {
        if (current == null || declared == null
                || !current.isObject() || !declared.isObject()) {
            return;
        }
        ObjectNode currentObject = (ObjectNode) current;
        JsonNode currentContracts = currentObject.get(
                ProcessorContractConstants.KEY_CONTRACTS);
        JsonNode declaredContracts = declared.get(
                ProcessorContractConstants.KEY_CONTRACTS);
        if (isPureReference(currentContracts)
                && declaredContracts != null
                && declaredContracts.isObject()) {
            currentObject.set(
                    ProcessorContractConstants.KEY_CONTRACTS,
                    declaredContracts.deepCopy());
            currentContracts = currentObject.get(
                    ProcessorContractConstants.KEY_CONTRACTS);
        }
        if (currentContracts != null && currentContracts.isObject()
                && declaredContracts != null && declaredContracts.isObject()) {
            List<String> keys = new ArrayList<>();
            declaredContracts.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = currentContracts.get(key);
                JsonNode exact = declaredContracts.get(key);
                if (value == null) {
                    ((ObjectNode) currentContracts).set(
                            key, exact.deepCopy());
                    continue;
                }
                if (matchesResolvedMaterialization(value, exact)) {
                    ((ObjectNode) currentContracts).set(
                            key, exact.deepCopy());
                    continue;
                }
                if (!isPureReference(value)) {
                    continue;
                }
                String reference = value.path(BlueLanguageConstants.OBJECT_BLUE_ID).asText();
                if (reference.equals(
                        fixtureContractSourceSnapshot(
                                readNode(exact), providerNodes).blueId())) {
                    ((ObjectNode) currentContracts).set(
                            key, exact.deepCopy());
                }
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields =
                currentObject.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (ProcessorContractConstants.KEY_CONTRACTS.equals(
                    entry.getKey())) {
                continue;
            }
            JsonNode declaredChild = declared.get(entry.getKey());
            if (entry.getValue().isObject()
                    && declaredChild != null
                    && declaredChild.isObject()) {
                materializeRetryContracts(
                        entry.getValue(), declaredChild, providerNodes);
            }
        }
    }

    static boolean isPureReference(JsonNode value) {
        return value != null
                && value.isObject()
                && value.size() == 1
                && value.path(BlueLanguageConstants.OBJECT_BLUE_ID).isTextual();
    }

    static boolean matchesResolvedMaterialization(
            JsonNode actual,
            JsonNode declared) {
        if (actual == null || declared == null) {
            return actual == declared;
        }
        if (actual.equals(declared)) {
            return true;
        }
        if (declared.isValueNode()) {
            JsonNode resolvedValue =
                    actual.isObject() ? actual.get(BlueLanguageConstants.OBJECT_VALUE) : null;
            return resolvedValue != null
                    && matchesResolvedMaterialization(
                    resolvedValue, declared);
        }
        if (declared.isArray()) {
            JsonNode actualItems = actual.isArray()
                    ? actual
                    : actual.isObject()
                    ? actual.get(BlueLanguageConstants.OBJECT_ITEMS)
                    : null;
            if (actualItems == null
                    || !actualItems.isArray()
                    || actualItems.size() != declared.size()) {
                return false;
            }
            for (int index = 0; index < declared.size(); index++) {
                if (!matchesResolvedMaterialization(
                        actualItems.get(index),
                        declared.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (!declared.isObject()
                || !actual.isObject()
                || actual.size() != declared.size()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields =
                declared.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!matchesResolvedMaterialization(
                    actual.get(field.getKey()),
                    field.getValue())) {
                return false;
            }
        }
        return true;
    }

    static void putDerivedProviderNode(
            Map<String, Node> providerNodes,
            String blueId,
            Node exactNode) {
        if (!blueId.equals(DirectBlueIdCalculator.calculateBlueId(exactNode))) {
            throw new IllegalArgumentException(
                    "Derived provider content does not match " + blueId);
        }
        Node previous = providerNodes.put(blueId, exactNode.clone());
        if (previous != null
                && !semanticEquals(
                normalizeNode(previous), normalizeNode(exactNode))) {
            throw new IllegalArgumentException(
                    "Conflicting exact provider content for " + blueId);
        }
    }

    static Node checkpointDomainNode(
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            ExternalChannelDependencySnapshot dependencies,
            String runtimeDiscriminator) {
        Node domain = new Node()
                .properties("contractsVersion",
                        new Node().value("1.0"))
                .properties("effectiveTypeBlueId",
                        new Node().value(effectiveTypeBlueId));
        List<Node> contributions = new ArrayList<>();
        for (String blueId : sourceContributionNodeBlueIds) {
            contributions.add(new Node().value(blueId));
        }
        domain.properties("sourceContributionNodeBlueIds",
                new Node().items(contributions));
        if (dependencies != null
                && !dependencies
                .deterministicDependencyNodeBlueIds()
                .isEmpty()) {
            List<Node> dependencyItems =
                    new ArrayList<>();
            for (String blueId : dependencies
                    .deterministicDependencyNodeBlueIds()) {
                dependencyItems.add(
                        new Node().value(blueId));
            }
            domain.properties(
                    "deterministicDependencyNodeBlueIds",
                    new Node().items(dependencyItems));
        }
        if (runtimeDiscriminator != null
                && !runtimeDiscriminator.isEmpty()) {
            domain.properties("runtimeDiscriminator",
                    new Node().value(runtimeDiscriminator));
        }
        return domain;
    }

    ExternalChannelDependencySnapshot
    fixtureChannelDependencies(
            ObjectNode scope,
            String ownerKey,
            JsonNode ownerContract,
            Map<String, Node> providerNodes) {
        String mode =
                ownerContract.path(
                        ContractsFixtureConstants.DependencyField.MODE)
                        .asText(
                                ContractsFixtureConstants.DependencyMode
                                        .NONE);
        if (ContractsFixtureConstants.DependencyMode.NONE.equals(mode)
                || mode.isEmpty()) {
            return ExternalChannelDependencySnapshot.none();
        }
        JsonNode contracts = scope.get(
                ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null || !contracts.isObject()) {
            throw new IllegalArgumentException(
                    "Channel dependency declaration has no same-scope "
                            + "contract map at " + ownerKey);
        }
        if (ContractsFixtureConstants.DependencyMode.EXACT.equals(mode)) {
            String dependencyKey =
                    ownerContract.path(
                            ContractsFixtureConstants.DependencyField
                                    .CHANNEL_KEY)
                            .asText(null);
            ExternalChannelDependencySnapshot.ChannelEntry
                    dependency =
                    fixtureChannelEntry(
                            dependencyKey,
                            contracts.get(dependencyKey),
                            providerNodes);
            if (dependency == null) {
                throw new IllegalArgumentException(
                        "Exact Channel dependency is missing or not a "
                                + "Channel at " + ownerKey + ": "
                                + dependencyKey);
            }
            return new ExternalChannelDependencySnapshot(
                    Collections.<String>emptyList(),
                    Collections
                            .<ExternalChannelDependencySnapshot.Entry>
                                    emptyList(),
                    Collections
                            .<ExternalChannelDependencySnapshot.TypeFamily>
                                    emptyList(),
                    false,
                    Collections.singletonList(dependency),
                    false,
                    Collections.<String>emptyList());
        }
        if (!ContractsFixtureConstants.DependencyMode.CATALOG.equals(mode)) {
            throw new IllegalArgumentException(
                    "Unsupported dependencyMode at "
                            + ownerKey + ": " + mode);
        }

        List<String> rawKeys = new ArrayList<>();
        contracts.fieldNames().forEachRemaining(key -> {
            if (!ProcessorContractConstants.KEY_INITIALIZED.equals(key)
                    && !ProcessorContractConstants.KEY_TERMINATED.equals(key)
                    && !ProcessorContractConstants.KEY_CHECKPOINT.equals(key)) {
                rawKeys.add(key);
            }
        });
        rawKeys.sort(
                ExternalOrderKey
                        ::compareTextCodePoints);
        List<ExternalChannelDependencySnapshot.ChannelEntry>
                channels = new ArrayList<>();
        for (String rawKey : rawKeys) {
            ExternalChannelDependencySnapshot.ChannelEntry
                    channel =
                    fixtureChannelEntry(
                            rawKey,
                            contracts.get(rawKey),
                            providerNodes);
            if (channel != null) {
                channels.add(channel);
            }
        }
        channels.sort((left, right) -> {
            int order = Integer.compare(
                    left.order(),
                    right.order());
            if (order != 0) {
                return order;
            }
            int key = ExternalOrderKey
                    .compareTextCodePoints(
                            left.channelKey(),
                            right.channelKey());
            return key != 0
                    ? key
                    : ExternalOrderKey
                    .compareTextCodePoints(
                            left.effectiveTypeBlueId(),
                            right.effectiveTypeBlueId());
        });
        return new ExternalChannelDependencySnapshot(
                Collections.<String>emptyList(),
                Collections
                        .<ExternalChannelDependencySnapshot.Entry>
                                emptyList(),
                Collections
                        .<ExternalChannelDependencySnapshot.TypeFamily>
                                emptyList(),
                false,
                channels,
                true,
                rawKeys);
    }

    static boolean hasVector(
            JsonNode fixture,
            String vector) {
        for (JsonNode declared : fixture.path(
                ContractsFixtureConstants.Field.VECTORS)) {
            if (vector.equals(declared.asText())) {
                return true;
            }
        }
        return false;
    }

    final void installExactPreinitializedMarker(
            ObjectNode root,
            Map<String, Node> providerNodes) {
        ObjectNode contracts = objectField(
                root, ProcessorContractConstants.KEY_CONTRACTS, true);
        if (contracts.has(
                ProcessorContractConstants.KEY_INITIALIZED)) {
            return;
        }
        String preInitializationBlueId =
                sourceIdentity(
                        readNode(root), providerNodes, true).blueId();
        ObjectNode initialized =
                contracts.putObject(
                        ProcessorContractConstants
                                .KEY_INITIALIZED);
        initialized.putObject(BlueLanguageConstants.OBJECT_TYPE)
                .put(BlueLanguageConstants.OBJECT_BLUE_ID,
                        RuntimeBlueIds
                                .PROCESSING_INITIALIZED_MARKER);
        initialized.putObject("document")
                .put(BlueLanguageConstants.OBJECT_BLUE_ID, preInitializationBlueId);
    }

    ExternalChannelDependencySnapshot.ChannelEntry
    fixtureChannelEntry(
            String key,
            JsonNode contract,
            Map<String, Node> providerNodes) {
        if (key == null
                || contract == null
                || !contract.isObject()) {
            return null;
        }
        Node exactContract = readNode(contract);
        if (deferUnknownContractCapabilityToRuntime(exactContract)) {
            return null;
        }
        ResolvedSnapshot contractSnapshot =
                fixtureContractSourceSnapshot(
                        exactContract, providerNodes);
        Node effectiveContract = contractSnapshot.resolvedRoot();
        Node effectiveType = effectiveContract.getType();
        if (effectiveType == null) {
            return null;
        }
        String typeBlueId = contractSnapshot.canonicalTypeIdentities()
                .requireCanonicalTypeBlueId(effectiveType);
        String role;
        if (registry.isSubtype(
                typeBlueId,
                registryId("ExternalChannel"))) {
            role = EffectiveContractSnapshotConstants
                    .Role.EXTERNAL_CHANNEL;
        } else if (registry.isSubtype(
                typeBlueId,
                registryId("Channel"))) {
            role = EffectiveContractSnapshotConstants
                    .Role.PROCESSOR_CHANNEL;
        } else {
            return null;
        }
        String contribution = contractSnapshot.blueId();
        restoreExactSourceHeaderFields(
                contractSnapshot.canonicalRoot(),
                effectiveContract,
                registry.exactSourceFieldsByType().get(typeBlueId));
        Node header = new Node().type(
                new Node().blueId(typeBlueId));
        List<String> executableBodyFields =
                registry.executableBodyFieldsByType().get(typeBlueId);
        Set<String> executable = executableBodyFields != null
                ? new LinkedHashSet<>(executableBodyFields)
                : Collections.<String>emptySet();
        if (effectiveContract.getProperties() != null) {
            List<String> names =
                    new ArrayList<>(
                            effectiveContract
                                    .getProperties()
                                    .keySet());
            names.sort(
                    ExternalOrderKey
                            ::compareTextCodePoints);
            for (String name : names) {
                if (!executable.contains(name)) {
                    header.properties(
                            name,
                            effectiveContract
                                    .getProperties()
                                    .get(name)
                                    .clone());
                }
            }
        }
        List<String> deterministicDependencies =
                new ArrayList<>();
        if ((registry.isSubtype(
                typeBlueId,
                registryId("TriggeredEventChannel"))
                || registry.isSubtype(
                typeBlueId,
                registryId("EmbeddedNodeChannel")))
                && effectiveContract.getProperties() != null
                && effectiveContract.getProperties()
                .containsKey(ContractsFixtureConstants.Field.EVENT)) {
            Node event =
                    effectiveContract.getProperties()
                            .get(ContractsFixtureConstants.Field.EVENT);
            deterministicDependencies.add(
                    sourceIdentity(event, providerNodes, false).blueId());
        }
        FixtureResolvedSourceProjection.project(
                header,
                contractSnapshot.canonicalTypeIdentities());
        NodeToBlueIdInput.stripResolvedBlueIdMetadata(header);
        String sourceHeaderIdentity = FrozenNode.fromNode(header).blueId();
        return new ExternalChannelDependencySnapshot.ChannelEntry(
                key,
                contract.path(ContractsFixtureConstants.Field.ORDER).asInt(0),
                typeBlueId,
                role,
                Collections.singletonList(
                        contribution),
                deterministicDependencies,
                sourceHeaderIdentity);
    }

    private static void restoreExactSourceHeaderFields(
            Node exactContract,
            Node effectiveContract,
            List<String> exactSourceFields) {
        if (exactSourceFields == null || exactSourceFields.isEmpty()) {
            return;
        }
        Map<String, Node> exactProperties =
                exactContract.getProperties() != null
                        ? exactContract.getProperties()
                        : Collections.<String, Node>emptyMap();
        Map<String, Node> effectiveProperties =
                effectiveContract.getProperties() != null
                        ? new LinkedHashMap<>(
                        effectiveContract.getProperties())
                        : new LinkedHashMap<String, Node>();
        for (String field : exactSourceFields) {
            Node exact = exactProperties.get(field);
            if (exact != null) {
                effectiveProperties.put(field, exact.clone());
            } else {
                effectiveProperties.remove(field);
            }
        }
        effectiveContract.properties(effectiveProperties);
    }

    static JsonNode firstNonRootDeliveryHint(
            JsonNode feeder) {
        JsonNode hint = firstNonRootDeliveryHintOrNull(feeder);
        if (hint == null) {
            throw new IllegalArgumentException(
                    "A selected non-root delivery is required");
        }
        return hint;
    }

    static JsonNode firstNonRootDeliveryHintOrNull(
            JsonNode feeder) {
        JsonNode hints = feeder != null
                ? feeder.path(ContractsFixtureConstants.Field.DELIVERY_SNAPSHOT)
                : null;
        if (hints == null || !hints.isArray()) {
            return null;
        }
        for (JsonNode hint : hints) {
            if (!"/".equals(
                    hint.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText())) {
                return hint;
            }
        }
        return null;
    }

    List<String> directRootChildScopePaths(
            ObjectNode root) {
        List<String> result = new ArrayList<>();
        for (ScopeValue scope : enumerateDeclaredScopes(root)) {
            if (scopeDepth(scope.path) == 1) {
                result.add(scope.path);
            }
        }
        return result;
    }

    static boolean selectedChildCanProduceUpdate(
            ObjectNode root,
            JsonNode runtime,
            JsonNode selectedChild) {
        String scopePath =
                selectedChild.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText();
        String channelKey =
                selectedChild.path(ContractsFixtureConstants.Field.CHANNEL_KEY).asText();
        JsonNode scope = jsonAt(root, scopePath);
        JsonNode contracts = scope == null
                ? null
                : scope.get(ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null || !contracts.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> entries =
                contracts.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode handler = entry.getValue();
            if (!MockTypeBlueIds.MOCK_HANDLER.equals(
                    handler.path(BlueLanguageConstants.OBJECT_TYPE).path(
                            BlueLanguageConstants.OBJECT_BLUE_ID).asText(null))
                    || !channelKey.equals(
                    handler.path("channel").asText(null))) {
                continue;
            }
            JsonNode result = scriptedHandlerResult(
                    runtime,
                    scopePath,
                    entry.getKey(),
                    handler);
            if (nonEmptyResultList(result, ContractsFixtureConstants.Field.PATCHES)) {
                return true;
            }
        }
        return false;
    }

    static JsonNode scriptedHandlerResult(
            JsonNode runtime,
            String scopePath,
            String handlerKey,
            JsonNode handler) {
        JsonNode script = runtime.path(ContractsFixtureConstants.Field.HANDLERS).get(
                ScriptedContractsRuntime.contractPath(
                        scopePath, handlerKey));
        return script != null && script.has(ContractsFixtureConstants.Field.RESULT)
                ? script.get(ContractsFixtureConstants.Field.RESULT)
                : handler.get(ContractsFixtureConstants.Field.RESULT);
    }

    static boolean nonEmptyResultList(
            JsonNode result,
            String field) {
        JsonNode value = result != null
                ? result.get(field)
                : null;
        if (value != null && value.isObject()) {
            value = value.get(BlueLanguageConstants.OBJECT_ITEMS);
        }
        return value != null
                && value.isArray()
                && value.size() > 0;
    }

}
