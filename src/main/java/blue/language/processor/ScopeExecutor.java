package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.conformance.ScriptedContractsRuntime;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Handles scope traversal, embedded processing, cascades, and lifecycle delivery.
 *
 * <p>Each {@link ProcessorEngine.Execution} owns a single instance which
 * orchestrates the five-phase algorithm for a scope. Consolidating the logic
 * here keeps {@code ProcessorEngine} primarily focused on composition.</p>
 */
final class ScopeExecutor {

    private final DocumentProcessor owner;
    private final ProcessorEngine.Execution execution;
    private final DocumentProcessingRuntime runtime;
    private final Map<String, ContractBundle> bundles;
    private final ChannelRunner channelRunner;

    ScopeExecutor(DocumentProcessor owner,
                  ProcessorEngine.Execution execution,
                  DocumentProcessingRuntime runtime,
                  Map<String, ContractBundle> bundles,
                  ChannelRunner channelRunner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.channelRunner = Objects.requireNonNull(channelRunner, "channelRunner");
    }

    void initializeScope(String scopePath, boolean chargeScopeEntry) {
        initializeScope(scopePath, chargeScopeEntry, true);
    }

    private void initializeScope(String scopePath, boolean chargeScopeEntry, boolean finalizeAfterInitialization) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        Set<String> processedEmbedded = new LinkedHashSet<>();
        ContractBundle bundle = null;
        Node preInitSnapshot = null;
        ScopeRuntimeContext scopeContext = runtime.scope(normalizedScope);
        if ("/".equals(normalizedScope)) {
            runtime.setScopeEmbeddedDepth(normalizedScope, 0);
        }
        scopeContext.clearProcessedEmbeddedPaths();

        if (chargeScopeEntry) {
            runtime.chargeScopeEntry(normalizedScope);
        }

        try {
            if (runtime.hasTerminationMarker(normalizedScope)) {
                runtime.markScopeTerminatedFromMarker(normalizedScope);
                return;
            }
        } catch (IllegalStateException ex) {
            execution.enterFatalTermination(normalizedScope,
                    null,
                    ProcessorErrorCategory.InvalidReservedMarker,
                    execution.fatalReason(ex, "Invalid terminated marker"));
            return;
        }

        while (true) {
            FrozenNode scopeNode = runtime.resolvedFrozenAt(normalizedScope);
            if (scopeNode == null) {
                return;
            }

            if (preInitSnapshot == null) {
                FrozenNode canonicalScopeNode = runtime.canonicalFrozenAt(normalizedScope);
                preInitSnapshot = (canonicalScopeNode != null ? canonicalScopeNode : scopeNode).toNode();
            }

            bundle = owner.contractLoader().load(scopeNode, normalizedScope, owner.metricsSink());
            bundles.put(normalizedScope, bundle);

            String childScope;
            try {
                childScope = nextEmbeddedChildScope(normalizedScope, bundle, processedEmbedded);
            } catch (ProcessorEngine.BoundaryViolationException | IllegalArgumentException ex) {
                execution.enterFatalTermination(normalizedScope,
                        bundle,
                        ProcessorErrorCategory.BoundaryViolation,
                        execution.fatalReason(ex, "Invalid embedded path"));
                return;
            }
            if (childScope == null) {
                break;
            }

            processedEmbedded.add(childScope);
            scopeContext.recordProcessedEmbeddedPath(childScope);
            runtime.setScopeEmbeddedDepth(childScope, runtime.scopeEmbeddedDepth(normalizedScope) + 1);
            FrozenNode childNode = runtime.resolvedFrozenAt(childScope);
            if (childNode != null) {
                if (!isObjectScope(childNode)) {
                    if (!finalizeAfterInitialization) {
                        continue;
                    }
                    initializeCurrentScopeIfNeeded(normalizedScope, bundle);
                    execution.enterFatalTermination(normalizedScope,
                            bundle,
                            ProcessorErrorCategory.BoundaryViolation,
                            "Embedded path " + childScope + " does not select an object scope");
                    return;
                }
                initializeScope(childScope, true, finalizeAfterInitialization);
            }
        }

        if (bundle == null) {
            return;
        }

        boolean initialized = runtime.hasInitializationMarker(normalizedScope);
        if (!initialized && finalizeAfterInitialization && bundle.hasCheckpoint()) {
            throw new IllegalStateException("Reserved key 'checkpoint' must not appear before initialization at scope " + normalizedScope);
        }

        if (initialized) {
            return;
        }

        runtime.chargeInitialization();
        String documentId = BlueIdCalculator.calculateUncheckedBlueId(preInitSnapshot != null ? preInitSnapshot : new Node());
        Node lifecycleEvent = ProcessorEngine.createLifecycleInitiatedEvent(documentId);
        ProcessorExecutionContext context = execution.createContext(normalizedScope, bundle, lifecycleEvent, false, true);
        deliverLifecycle(normalizedScope, bundle, lifecycleEvent, false);
        addInitializationMarker(context, documentId);
        if (finalizeAfterInitialization && !execution.isScopeInactive(normalizedScope)) {
            ContractBundle refreshed = refreshBundle(normalizedScope);
            finalizeScope(normalizedScope, refreshed);
        }
    }

    void loadBundles(String scopePath) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        if (bundles.containsKey(normalizedScope)) {
            return;
        }
        try {
            if (runtime.hasTerminationMarker(normalizedScope)) {
                bundles.put(normalizedScope, ContractBundle.empty());
                return;
            }
        } catch (IllegalStateException ex) {
            throw new MustUnderstandFailureException(ex.getMessage());
        }
        FrozenNode scopeNode = runtime.resolvedFrozenAt(normalizedScope);
        ContractBundle bundle = scopeNode != null
                ? owner.contractLoader().load(scopeNode, normalizedScope, owner.metricsSink())
                : ContractBundle.empty();
        bundles.put(normalizedScope, bundle);
        for (String embeddedPointer : bundle.embeddedPaths()) {
            String childScope = ProcessorEngine.resolvePointer(normalizedScope, embeddedPointer);
            loadBundles(childScope);
        }
    }

    void processExternalEvent(String scopePath, Node event) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        if (execution.isScopeInactive(normalizedScope)) {
            return;
        }
        if ("/".equals(normalizedScope)) {
            runtime.setScopeEmbeddedDepth(normalizedScope, 0);
        }
        runtime.chargeScopeEntry(normalizedScope);
        try {
            if (runtime.hasTerminationMarker(normalizedScope)) {
                runtime.markScopeTerminatedFromMarker(normalizedScope);
                return;
            }
        } catch (IllegalStateException ex) {
            ContractBundle bundle = bundles.get(normalizedScope);
            execution.enterFatalTermination(normalizedScope,
                    bundle,
                    ProcessorErrorCategory.InvalidReservedMarker,
                    execution.fatalReason(ex, "Invalid terminated marker"));
            return;
        }
        ContractBundle bundle = processEmbeddedChildren(normalizedScope, event);
        if (bundle == null) {
            return;
        }
        if (!runtime.hasInitializationMarker(normalizedScope)) {
            initializeScope(normalizedScope, false, false);
            if (execution.isScopeInactive(normalizedScope)) {
                return;
            }
            bundle = refreshBundle(normalizedScope);
            if (bundle == null) {
                return;
            }
        }
        long channelDiscoveryStart = System.nanoTime();
        List<ContractBundle.ChannelBinding> channels = bundle.channelsOfType(ChannelContract.class);
        owner.metricsSink().addChannelDiscoveryNanos(System.nanoTime() - channelDiscoveryStart);
        if (channels.isEmpty()) {
            finalizeScope(normalizedScope, bundle);
            return;
        }
        long externalCandidateCount = channels.stream()
                .filter(channel -> !ProcessorContractConstants.isProcessorManagedChannel(channel.contract()))
                .count();
        if (externalCandidateCount > 1) {
            runtime.addGas(1L);
        }
        for (ContractBundle.ChannelBinding channel : channels) {
            if (execution.isScopeInactive(normalizedScope)) {
                break;
            }
            if (ProcessorContractConstants.isProcessorManagedChannel(channel.contract())) {
                continue;
            }
            channelRunner.runExternalChannel(normalizedScope, bundle, channel, event);
        }
        finalizeScope(normalizedScope, bundle);
    }

    void handlePatch(String scopePath,
                     ContractBundle bundle,
                     JsonPatch patch,
                     boolean allowReservedMutation) {
        if (patch == null) {
            return;
        }
        handlePatches(scopePath,
                bundle,
                Collections.singletonList(patch),
                allowReservedMutation);
    }

    void handlePatches(String scopePath,
                       ContractBundle bundle,
                       List<JsonPatch> patches,
                       boolean allowReservedMutation) {
        if (execution.isScopeInactive(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        for (JsonPatch patch : patches) {
            if (execution.isScopeInactive(scopePath)) {
                return;
            }
            if (!allowReservedMutation) {
                runtime.chargeBoundaryCheck();
            }
            try {
                long boundaryStart = System.nanoTime();
                validatePatchBoundary(scopePath, bundle, patch);
                enforceReservedKeyWriteProtection(scopePath, patch, allowReservedMutation);
                owner.metricsSink().addPatchBoundaryNanos(System.nanoTime() - boundaryStart);
            } catch (ProcessorEngine.BoundaryViolationException ex) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        ProcessorErrorCategory.BoundaryViolation,
                        execution.fatalReason(ex, "Boundary violation"));
                return;
            } catch (ProcessorFailureException ex) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        ex.errorCategory(),
                        execution.fatalReason(ex, "Runtime fatal"));
                return;
            } catch (IllegalArgumentException ex) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        ProcessorErrorCategory.InvalidPatch,
                        execution.fatalReason(ex, "Boundary violation"));
                return;
            }
            try {
                long gasStart = System.nanoTime();
                chargePatchGas(patch);
                owner.metricsSink().addPatchGasNanos(System.nanoTime() - gasStart);
                List<DocumentProcessingRuntime.DocumentUpdateData> updates = runtime.applyPatches(scopePath,
                        Collections.singletonList(patch));
                long routingStart = System.nanoTime();
                for (DocumentProcessingRuntime.DocumentUpdateData update : updates) {
                    routeDocumentUpdateAfterPatch(scopePath, bundle, update);
                    if (execution.isScopeInactive(scopePath)) {
                        break;
                    }
                }
                owner.metricsSink().addDocumentUpdateRoutingNanos(System.nanoTime() - routingStart);
            } catch (ProcessorEngine.BoundaryViolationException ex) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        ProcessorErrorCategory.BoundaryViolation,
                        execution.fatalReason(ex, "Boundary violation"));
                return;
            } catch (MustUnderstandFailureException ex) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        ex.errorCategory(),
                        execution.fatalReason(ex, "Unsupported runtime contract"));
                return;
            } catch (ProcessorFailureException ex) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        ex.errorCategory(),
                        execution.fatalReason(ex, "Runtime fatal"));
                return;
            } catch (IllegalArgumentException | IllegalStateException ex) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        execution.fatalCategory(ex, ProcessorErrorCategory.InternalProcessorError),
                        execution.fatalReason(ex, "Runtime fatal"));
                return;
            }
        }
    }

    private void chargePatchGas(JsonPatch patch) {
        switch (patch.getOp()) {
            case ADD:
            case REPLACE:
                runtime.chargePatchAddOrReplace(patch.getVal());
                break;
            case REMOVE:
                runtime.chargePatchRemove();
                break;
            default:
                break;
        }
    }

    private void routeDocumentUpdateAfterPatch(String scopePath,
                                               ContractBundle bundle,
                                               DocumentProcessingRuntime.DocumentUpdateData data) {
        if (data == null) {
            return;
        }
        ScriptedContractsRuntime scriptedRuntime = ScriptedContractsRuntime.active();
        if (scriptedRuntime != null) {
            scriptedRuntime.recordDocumentUpdate(runtime, data.path(), data.before(), data.after());
        }
        markCutOffChildrenIfNeeded(scopePath, bundle, data);
        List<DocumentUpdateParticipant> participants = new ArrayList<>();
        for (String cascadeScope : data.cascadeScopes()) {
            if (execution.isScopeInactive(cascadeScope)) {
                continue;
            }
            ContractBundle targetBundle;
            try {
                targetBundle = refreshBundle(cascadeScope);
            } catch (MustUnderstandFailureException ex) {
                execution.enterFatalTermination(cascadeScope,
                        bundles.get(cascadeScope),
                        ex.errorCategory(),
                        execution.fatalReason(ex, "Unsupported runtime contract"));
                return;
            }
            if (targetBundle == null) {
                continue;
            }
            List<ContractBundle.ChannelBinding> matching = new ArrayList<>();
            for (ContractBundle.ChannelBinding channel : targetBundle.channelsOfType(DocumentUpdateChannel.class)) {
                DocumentUpdateChannel duc = (DocumentUpdateChannel) channel.contract();
                if (ProcessorEngine.matchesDocumentUpdate(cascadeScope, duc.getPath(), data.path())) {
                    matching.add(channel);
                }
            }
            if (matching.isEmpty()) {
                owner.metricsSink().incrementDocumentUpdateEventsSkippedNoChannel();
                continue;
            }
            participants.add(new DocumentUpdateParticipant(cascadeScope, targetBundle, matching));
        }
        runtime.chargeCascadeRouting(participants.size());
        for (DocumentUpdateParticipant participant : participants) {
            if (execution.isScopeInactive(participant.scopePath)) {
                continue;
            }
            Node updateEvent = ProcessorEngine.createDocumentUpdateEvent(data, participant.scopePath);
            owner.metricsSink().incrementDocumentUpdateEventsBuilt();
            for (ContractBundle.ChannelBinding channel : participant.channels) {
                channelRunner.runHandlers(participant.scopePath, participant.bundle, channel.key(), updateEvent, false);
                if (execution.isScopeInactive(participant.scopePath)) {
                    continue;
                }
            }
        }
    }

    void deliverLifecycle(String scopePath,
                          ContractBundle bundle,
                          Node event,
                          boolean finalizeAfter) {
        runtime.chargeLifecycleDelivery();
        execution.recordLifecycleForBridging(scopePath, event);
        if (bundle == null) {
            return;
        }
        for (ContractBundle.ChannelBinding channel : bundle.channelsOfType(LifecycleChannel.class)) {
            channelRunner.runHandlers(scopePath, bundle, channel.key(), event, true);
            if (execution.isScopeInactive(scopePath)) {
                break;
            }
        }
        if (finalizeAfter) {
            finalizeScope(scopePath, bundle);
        }
    }

    private ContractBundle processEmbeddedChildren(String scopePath, Node event) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        Set<String> processed = new LinkedHashSet<>();
        ScopeRuntimeContext scopeContext = runtime.scope(normalizedScope);
        scopeContext.clearProcessedEmbeddedPaths();
        ContractBundle bundle = refreshBundle(normalizedScope);
        while (bundle != null) {
            String childScope;
            try {
                childScope = nextEmbeddedChildScope(normalizedScope, bundle, processed);
            } catch (ProcessorEngine.BoundaryViolationException | IllegalArgumentException ex) {
                execution.enterFatalTermination(normalizedScope,
                        bundle,
                        ProcessorErrorCategory.BoundaryViolation,
                        execution.fatalReason(ex, "Invalid embedded path"));
                return null;
            }
            if (childScope == null) {
                return bundle;
            }
            processed.add(childScope);
            scopeContext.recordProcessedEmbeddedPath(childScope);
            runtime.setScopeEmbeddedDepth(childScope, runtime.scopeEmbeddedDepth(normalizedScope) + 1);
            if (execution.isScopeInactive(childScope)) {
                bundle = refreshBundle(normalizedScope);
                continue;
            }
            FrozenNode childNode = runtime.resolvedFrozenAt(childScope);
            if (childNode != null) {
                if (!isObjectScope(childNode)) {
                    if ("initialize".equals(eventKind(event))) {
                        bundle = refreshBundle(normalizedScope);
                        continue;
                    }
                    initializeCurrentScopeIfNeeded(normalizedScope, bundle);
                    execution.enterFatalTermination(normalizedScope,
                            bundle,
                            ProcessorErrorCategory.BoundaryViolation,
                            "Embedded path " + childScope + " does not select an object scope");
                    return null;
                }
                ScriptedContractsRuntime scriptedRuntime = ScriptedContractsRuntime.active();
                if (scriptedRuntime != null) {
                    scriptedRuntime.recordEmbeddedScopeDelivery(childScope);
                }
                processExternalEvent(childScope, event);
                if (scriptedRuntime != null) {
                    for (Node emission : scriptedRuntime.childEmissions(childScope)) {
                        runtime.scope(childScope).recordBridgeable(emission);
                    }
                }
            }
            bundle = refreshBundle(normalizedScope);
        }
        return null;
    }

    private ContractBundle refreshBundle(String scopePath) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        FrozenNode scopeNode = runtime.resolvedFrozenAt(normalizedScope);
        if (scopeNode == null) {
            bundles.remove(normalizedScope);
            return null;
        }
        ContractBundle refreshed = owner.contractLoader().load(scopeNode, normalizedScope, owner.metricsSink());
        bundles.put(normalizedScope, refreshed);
        return refreshed;
    }

    private String nextEmbeddedChildScope(String scopePath, ContractBundle bundle, Set<String> processed) {
        if (bundle == null) {
            return null;
        }
        Set<String> seenInBundle = new LinkedHashSet<>();
        for (String candidate : bundle.embeddedPaths()) {
            String normalizedCandidate = PointerUtils.assertValidRuntimePointer(candidate);
            String childScope = ProcessorEngine.resolvePointer(scopePath, normalizedCandidate);
            if (childScope.equals(ProcessorEngine.normalizeScope(scopePath))) {
                throw new ProcessorEngine.BoundaryViolationException("Process Embedded path '/' cannot embed its declaring scope");
            }
            if (!seenInBundle.add(childScope)) {
                throw new ProcessorEngine.BoundaryViolationException("Duplicate Process Embedded path: " + normalizedCandidate);
            }
            if (!processed.contains(childScope)) {
                return childScope;
            }
        }
        return null;
    }

    private boolean isObjectScope(FrozenNode node) {
        return node != null
                && node.getValue() == null
                && !node.hasItems()
                && !node.isReferenceOnly()
                && node.getPreviousBlueId() == null;
    }

    private String eventKind(Node event) {
        if (event == null || event.getProperties() == null) {
            return null;
        }
        Node kind = event.getProperties().get("kind");
        Object value = kind != null ? kind.getValue() : null;
        return value != null ? String.valueOf(value) : null;
    }

    private void addInitializationMarker(ProcessorExecutionContext context, String documentId) {
        Node marker = new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                .properties("documentId", new Node().value(documentId));
        String pointer = context.resolvePointer(ProcessorPointerConstants.RELATIVE_INITIALIZED);
        context.applyPatch(JsonPatch.add(pointer, marker));
        context.applyBufferedEffects();
    }

    private void initializeCurrentScopeIfNeeded(String scopePath, ContractBundle bundle) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        if (runtime.hasInitializationMarker(normalizedScope) || execution.isScopeInactive(normalizedScope)) {
            return;
        }
        FrozenNode canonicalScopeNode = runtime.canonicalFrozenAt(normalizedScope);
        String documentId = BlueIdCalculator.calculateUncheckedBlueId(
                canonicalScopeNode != null ? canonicalScopeNode.toNode() : new Node());
        runtime.chargeInitialization();
        Node lifecycleEvent = ProcessorEngine.createLifecycleInitiatedEvent(documentId);
        ProcessorExecutionContext context = execution.createContext(normalizedScope, bundle, lifecycleEvent, false, true);
        deliverLifecycle(normalizedScope, bundle, lifecycleEvent, false);
        if (!execution.isScopeInactive(normalizedScope)) {
            addInitializationMarker(context, documentId);
        }
    }

    private void finalizeScope(String scopePath, ContractBundle bundle) {
        if (bundle == null) {
            return;
        }
        if (execution.isScopeInactive(scopePath)) {
            return;
        }
        bridgeEmbeddedEmissions(scopePath, bundle);
        drainTriggeredQueue(scopePath, bundle);
    }

    private void bridgeEmbeddedEmissions(String scopePath, ContractBundle bundle) {
        if (execution.isScopeInactive(scopePath)) {
            return;
        }
        ScopeRuntimeContext parentContext = runtime.scope(scopePath);
        List<String> processedChildScopes = parentContext.processedEmbeddedPaths();
        if (processedChildScopes.isEmpty()) {
            return;
        }
        for (String childScope : processedChildScopes) {
            ScopeRuntimeContext childContext = runtime.scope(childScope);
            List<Node> emissions = childContext.drainBridgeableEvents();
            if (emissions.isEmpty()) {
                continue;
            }
            for (Node emission : emissions) {
                ContractBundle currentBundle = refreshBundle(scopePath);
                List<ContractBundle.ChannelBinding> embeddedChannels = currentBundle != null
                        ? currentBundle.channelsOfType(EmbeddedNodeChannel.class)
                        : Collections.emptyList();
                boolean charged = false;
                List<String> deliveredChannels = new ArrayList<>();
                for (ContractBundle.ChannelBinding channel : embeddedChannels) {
                    EmbeddedNodeChannel enc = (EmbeddedNodeChannel) channel.contract();
                    String configuredChild = enc.getChildPath() != null ? enc.getChildPath() : "/";
                    String resolvedChild = ProcessorEngine.resolvePointer(scopePath, configuredChild);
                    if (!resolvedChild.equals(childScope)) {
                        continue;
                    }
                    if (!charged) {
                        runtime.chargeBridge(emission);
                        charged = true;
                    }
                    deliveredChannels.add(channel.key());
                    channelRunner.runHandlers(scopePath, currentBundle, channel.key(), emission.clone(), false);
                }
                ScriptedContractsRuntime scriptedRuntime = ScriptedContractsRuntime.active();
                if (scriptedRuntime != null) {
                    scriptedRuntime.recordEmbeddedBridgeDelivery(emission, deliveredChannels);
                    scriptedRuntime.afterBridgeEmission(scopePath, runtime, emission);
                }
            }
        }
    }

    private void drainTriggeredQueue(String scopePath, ContractBundle bundle) {
        long routingStart = System.nanoTime();
        try {
            if (execution.isScopeInactive(scopePath)) {
                return;
            }
            ScopeRuntimeContext context = runtime.scope(scopePath);
            if (context.triggeredQueue().isEmpty()) {
                return;
            }
            while (!context.triggeredQueue().isEmpty()) {
                Node next = context.triggeredQueue().pollFirst();
                ContractBundle currentBundle = refreshBundle(scopePath);
                List<ContractBundle.ChannelBinding> triggeredChannels = currentBundle != null
                        ? currentBundle.channelsOfType(TriggeredEventChannel.class)
                        : Collections.emptyList();
                owner.metricsSink().incrementTriggeredEventsRouted();
                if (triggeredChannels.isEmpty()) {
                    continue;
                }
                runtime.chargeDrainEvent();
                List<String> deliveredChannels = new ArrayList<>();
                for (ContractBundle.ChannelBinding channel : triggeredChannels) {
                    if (execution.isScopeInactive(scopePath)) {
                        context.triggeredQueue().clear();
                        return;
                    }
                    deliveredChannels.add(channel.key());
                    channelRunner.runHandlers(scopePath, currentBundle, channel.key(), next.clone(), false);
                    if (execution.isScopeInactive(scopePath)) {
                        context.triggeredQueue().clear();
                        return;
                    }
                }
                ScriptedContractsRuntime scriptedRuntime = ScriptedContractsRuntime.active();
                if (scriptedRuntime != null) {
                    scriptedRuntime.recordTriggeredDelivery(next, deliveredChannels);
                }
            }
        } finally {
            owner.metricsSink().addTriggeredEventRoutingNanos(System.nanoTime() - routingStart);
        }
    }

    private void validatePatchBoundary(String scopePath, ContractBundle bundle, JsonPatch patch) {
        if (bundle == null) {
            return;
        }
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        String targetPath = PointerUtils.assertValidRuntimePointer(patch.getPath());

        if ("/".equals(targetPath)) {
            throw new ProcessorEngine.BoundaryViolationException("Patch path '/' is forbidden");
        }

        if (targetPath.equals(normalizedScope)) {
            throw new ProcessorEngine.BoundaryViolationException("Self-root mutation is forbidden at scope " + normalizedScope);
        }

        if (!"/".equals(normalizedScope)) {
            if (!PointerUtils.strictlyInside(targetPath, normalizedScope)) {
                throw new ProcessorEngine.BoundaryViolationException(
                        "Patch path " + targetPath + " is outside scope " + normalizedScope);
            }
        }

        for (String embeddedPointer : bundle.embeddedPaths()) {
            String embeddedScope = ProcessorEngine.resolvePointer(normalizedScope, embeddedPointer);
            if (PointerUtils.strictlyInside(targetPath, embeddedScope)) {
                throw new ProcessorEngine.BoundaryViolationException(
                        "Boundary violation: patch " + targetPath + " enters embedded scope " + embeddedScope);
            }
        }
    }

    private void enforceReservedKeyWriteProtection(String scopePath,
                                                   JsonPatch patch,
                                                   boolean allowReservedMutation) {
        if (allowReservedMutation) {
            return;
        }
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        String targetPath = PointerUtils.assertValidRuntimePointer(patch.getPath());
        String contractsPointer = ProcessorEngine.resolvePointer(normalizedScope, ProcessorPointerConstants.RELATIVE_CONTRACTS);
        if (targetPath.equals(contractsPointer)) {
            enforceContractsMapReservedSubtreePreservation(normalizedScope, patch);
            return;
        }
        for (String key : ProcessorContractConstants.RESERVED_CONTRACT_KEYS) {
            String reservedPointer = ProcessorEngine.resolvePointer(normalizedScope, ProcessorPointerConstants.relativeContractsEntry(key));
            if (PointerUtils.descendantOrEqual(targetPath, reservedPointer)) {
                if (ProcessorContractConstants.KEY_EMBEDDED.equals(key)) {
                    String embeddedPathsPointer = ProcessorEngine.resolvePointer(normalizedScope,
                            ProcessorPointerConstants.RELATIVE_EMBEDDED + "/paths");
                    if (PointerUtils.descendantOrEqual(targetPath, embeddedPathsPointer)) {
                        return;
                    }
                }
                throw new ProcessorFailureException(ProcessorErrorCategory.ReservedKeyWrite,
                        "Reserved key '" + key + "' is write-protected at " + reservedPointer);
            }
        }
    }

    private void enforceContractsMapReservedSubtreePreservation(String scopePath, JsonPatch patch) {
        if (patch.getOp() == JsonPatch.Op.REMOVE) {
            for (String key : ProcessorContractConstants.RESERVED_CONTRACT_KEYS) {
                String reservedPointer = ProcessorEngine.resolvePointer(scopePath, ProcessorPointerConstants.relativeContractsEntry(key));
                if (runtime.canonicalNodeAt(reservedPointer) != null) {
                    throw new ProcessorFailureException(ProcessorErrorCategory.ReservedKeyWrite,
                            "Replacing /contracts must preserve reserved key '" + key + "'");
                }
            }
            return;
        }
        Node replacement = patch.getVal();
        for (String key : ProcessorContractConstants.RESERVED_CONTRACT_KEYS) {
            String reservedPointer = ProcessorEngine.resolvePointer(scopePath, ProcessorPointerConstants.relativeContractsEntry(key));
            Node existing = runtime.canonicalNodeAt(reservedPointer);
            if (existing == null) {
                continue;
            }
            Node proposed = replacement != null && replacement.getProperties() != null
                    ? replacement.getProperties().get(key)
                    : null;
            if (!semanticallyEqual(existing, proposed)) {
                throw new ProcessorFailureException(ProcessorErrorCategory.ReservedKeyWrite,
                        "Replacing /contracts must preserve reserved key '" + key + "'");
            }
        }
    }

    private boolean semanticallyEqual(Node left, Node right) {
        if (left == null || right == null) {
            return left == right;
        }
        return BlueIdCalculator.calculateUncheckedBlueId(left)
                .equals(BlueIdCalculator.calculateUncheckedBlueId(right));
    }

    private void markCutOffChildrenIfNeeded(String scopePath,
                                            ContractBundle bundle,
                                            DocumentProcessingRuntime.DocumentUpdateData data) {
        if (bundle == null || bundle.embeddedPaths().isEmpty()) {
            return;
        }
        String changedPath = ProcessorEngine.normalizePointer(data.path());
        for (String embeddedPointer : bundle.embeddedPaths()) {
            String childScope = ProcessorEngine.resolvePointer(scopePath, embeddedPointer);
            if (!changedPath.equals(childScope)) {
                continue;
            }
            JsonPatch.Op op = data.op();
            if (op == JsonPatch.Op.REMOVE || op == JsonPatch.Op.REPLACE) {
                execution.markCutOff(childScope);
            }
        }
    }

    private static final class DocumentUpdateParticipant {
        private final String scopePath;
        private final ContractBundle bundle;
        private final List<ContractBundle.ChannelBinding> channels;

        private DocumentUpdateParticipant(String scopePath,
                                          ContractBundle bundle,
                                          List<ContractBundle.ChannelBinding> channels) {
            this.scopePath = scopePath;
            this.bundle = bundle;
            this.channels = channels;
        }
    }
}
