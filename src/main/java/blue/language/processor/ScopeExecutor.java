package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.utils.BlueIdCalculator;

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
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        Set<String> processedEmbedded = new LinkedHashSet<>();
        ContractBundle bundle = null;
        Node preInitSnapshot = null;

        if (chargeScopeEntry) {
            runtime.chargeScopeEntry(normalizedScope);
        }

        while (true) {
            Node scopeNode = ProcessorEngine.nodeAt(runtime.document(), normalizedScope);
            if (scopeNode == null) {
                return;
            }

            if (preInitSnapshot == null) {
                preInitSnapshot = scopeNode.clone();
            }

            bundle = owner.contractLoader().load(scopeNode, normalizedScope);
            bundles.put(normalizedScope, bundle);

            String nextEmbedded = null;
            for (String candidate : bundle.embeddedPaths()) {
                if (!processedEmbedded.contains(candidate)) {
                    nextEmbedded = candidate;
                    break;
                }
            }

            if (nextEmbedded == null) {
                break;
            }

            processedEmbedded.add(nextEmbedded);
            String childScope = ProcessorEngine.resolvePointer(normalizedScope, nextEmbedded);
            Node childNode = ProcessorEngine.nodeAt(runtime.document(), childScope);
            if (childNode != null) {
                initializeScope(childScope, true);
            }
        }

        if (bundle == null) {
            return;
        }

        boolean initialized = ProcessorEngine.hasInitializationMarker(runtime.document(), normalizedScope);
        if (!initialized && bundle.hasCheckpoint()) {
            throw new IllegalStateException("Reserved key 'checkpoint' must not appear before initialization at scope " + normalizedScope);
        }

        if (initialized) {
            return;
        }

        runtime.chargeInitialization();
        String documentId = BlueIdCalculator.calculateBlueId(preInitSnapshot != null ? preInitSnapshot : new Node());
        Node lifecycleEvent = ProcessorEngine.createLifecycleInitiatedEvent(documentId);
        ProcessorExecutionContext context = execution.createContext(normalizedScope, bundle, lifecycleEvent, false, true);
        deliverLifecycle(normalizedScope, bundle, lifecycleEvent, true);
        addInitializationMarker(context, documentId);
    }

    void loadBundles(String scopePath) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        if (bundles.containsKey(normalizedScope)) {
            return;
        }
        Node scopeNode = ProcessorEngine.nodeAt(runtime.document(), normalizedScope);
        ContractBundle bundle = scopeNode != null
                ? owner.contractLoader().load(scopeNode, normalizedScope)
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
        runtime.chargeScopeEntry(normalizedScope);
        ContractBundle bundle = processEmbeddedChildren(normalizedScope, event);
        if (bundle == null) {
            return;
        }
        List<ContractBundle.ChannelBinding> channels = bundle.channelsOfType(ChannelContract.class);
        if (channels.isEmpty()) {
            finalizeScope(normalizedScope, bundle);
            return;
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
        if (execution.isScopeInactive(scopePath)) {
            return;
        }
        runtime.chargeBoundaryCheck();
        try {
            validatePatchBoundary(scopePath, bundle, patch);
            enforceReservedKeyWriteProtection(scopePath, patch, allowReservedMutation);
        } catch (ProcessorEngine.BoundaryViolationException ex) {
            execution.enterFatalTermination(scopePath, bundle, execution.fatalReason(ex, "Boundary violation"));
            return;
        }
        try {
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
            DocumentProcessingRuntime.DocumentUpdateData data = runtime.applyPatch(scopePath, patch);
            if (data == null) {
                return;
            }
            markCutOffChildrenIfNeeded(scopePath, bundle, data);
            runtime.chargeCascadeRouting(data.cascadeScopes().size());
            for (String cascadeScope : data.cascadeScopes()) {
                ContractBundle targetBundle = bundles.get(cascadeScope);
                if (targetBundle == null) {
                    continue;
                }
                if (execution.isScopeInactive(cascadeScope)) {
                    continue;
                }
                Node updateEvent = ProcessorEngine.createDocumentUpdateEvent(data, cascadeScope);
                for (ContractBundle.ChannelBinding channel : targetBundle.channelsOfType(DocumentUpdateChannel.class)) {
                    DocumentUpdateChannel duc = (DocumentUpdateChannel) channel.contract();
                    if (!ProcessorEngine.matchesDocumentUpdate(cascadeScope, duc.getPath(), data.path())) {
                        continue;
                    }
                    channelRunner.runHandlers(cascadeScope, targetBundle, channel.key(), updateEvent, false);
                    if (execution.isScopeInactive(cascadeScope)) {
                        break;
                    }
                }
            }
        } catch (ProcessorEngine.BoundaryViolationException ex) {
            execution.enterFatalTermination(scopePath, bundle, execution.fatalReason(ex, "Boundary violation"));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            execution.enterFatalTermination(scopePath, bundle, execution.fatalReason(ex, "Runtime fatal"));
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
        ContractBundle bundle = refreshBundle(normalizedScope);
        while (bundle != null) {
            String next = nextEmbeddedPath(bundle, processed);
            if (next == null) {
                return bundle;
            }
            processed.add(next);
            String childScope = ProcessorEngine.resolvePointer(normalizedScope, next);
            if (childScope.equals(normalizedScope)) {
                bundle = refreshBundle(normalizedScope);
                continue;
            }
            if (execution.isScopeInactive(childScope)) {
                bundle = refreshBundle(normalizedScope);
                continue;
            }
            Node childNode = ProcessorEngine.nodeAt(runtime.document(), childScope);
            if (childNode != null) {
                initializeScope(childScope, false);
                processExternalEvent(childScope, event);
            }
            bundle = refreshBundle(normalizedScope);
        }
        return null;
    }

    private ContractBundle refreshBundle(String scopePath) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        Node scopeNode = ProcessorEngine.nodeAt(runtime.document(), normalizedScope);
        if (scopeNode == null) {
            bundles.remove(normalizedScope);
            return null;
        }
        ContractBundle refreshed = owner.contractLoader().load(scopeNode, normalizedScope);
        bundles.put(normalizedScope, refreshed);
        return refreshed;
    }

    private String nextEmbeddedPath(ContractBundle bundle, Set<String> processed) {
        if (bundle == null) {
            return null;
        }
        for (String candidate : bundle.embeddedPaths()) {
            if (!processed.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void addInitializationMarker(ProcessorExecutionContext context, String documentId) {
        Node marker = new Node()
                .type(new Node().blueId("InitializationMarker"))
                .properties("documentId", new Node().value(documentId));
        String pointer = context.resolvePointer(ProcessorPointerConstants.RELATIVE_INITIALIZED);
        context.applyPatch(JsonPatch.add(pointer, marker));
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
        if (bundle.embeddedPaths().isEmpty()) {
            return;
        }
        List<ContractBundle.ChannelBinding> embeddedChannels = bundle.channelsOfType(EmbeddedNodeChannel.class);
        for (String embeddedPointer : bundle.embeddedPaths()) {
            String childScope = ProcessorEngine.resolvePointer(scopePath, embeddedPointer);
            ScopeRuntimeContext childContext = runtime.scope(childScope);
            List<Node> emissions = childContext.drainBridgeableEvents();
            if (emissions.isEmpty()) {
                continue;
            }
            if (embeddedChannels.isEmpty()) {
                continue;
            }
            for (Node emission : emissions) {
                boolean charged = false;
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
                    channelRunner.runHandlers(scopePath, bundle, channel.key(), emission.clone(), false);
                }
            }
        }
    }

    private void drainTriggeredQueue(String scopePath, ContractBundle bundle) {
        if (execution.isScopeInactive(scopePath)) {
            return;
        }
        ScopeRuntimeContext context = runtime.scope(scopePath);
        if (context.triggeredQueue().isEmpty()) {
            return;
        }
        List<ContractBundle.ChannelBinding> triggeredChannels = bundle.channelsOfType(TriggeredEventChannel.class);
        if (triggeredChannels.isEmpty()) {
            context.triggeredQueue().clear();
            return;
        }
        while (!context.triggeredQueue().isEmpty()) {
            Node next = context.triggeredQueue().pollFirst();
            runtime.chargeDrainEvent();
            for (ContractBundle.ChannelBinding channel : triggeredChannels) {
                if (execution.isScopeInactive(scopePath)) {
                    context.triggeredQueue().clear();
                    return;
                }
                channelRunner.runHandlers(scopePath, bundle, channel.key(), next.clone(), false);
                if (execution.isScopeInactive(scopePath)) {
                    context.triggeredQueue().clear();
                    return;
                }
            }
        }
    }

    private void validatePatchBoundary(String scopePath, ContractBundle bundle, JsonPatch patch) {
        if (bundle == null) {
            return;
        }
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        String targetPath = ProcessorEngine.normalizePointer(patch.getPath());

        if (targetPath.equals(normalizedScope)) {
            throw new ProcessorEngine.BoundaryViolationException("Self-root mutation is forbidden at scope " + normalizedScope);
        }

        if (!"/".equals(normalizedScope)) {
            if (!targetPath.startsWith(normalizedScope + "/")) {
                throw new ProcessorEngine.BoundaryViolationException(
                        "Patch path " + targetPath + " is outside scope " + normalizedScope);
            }
        }

        for (String embeddedPointer : bundle.embeddedPaths()) {
            String embeddedScope = ProcessorEngine.resolvePointer(normalizedScope, embeddedPointer);
            if (targetPath.startsWith(embeddedScope + "/")) {
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
        String targetPath = ProcessorEngine.normalizePointer(patch.getPath());
        for (String key : ProcessorContractConstants.RESERVED_CONTRACT_KEYS) {
            String reservedPointer = ProcessorEngine.resolvePointer(normalizedScope, ProcessorPointerConstants.relativeContractsEntry(key));
            if (targetPath.equals(reservedPointer) || targetPath.startsWith(reservedPointer + "/")) {
                throw new ProcessorEngine.BoundaryViolationException(
                        "Reserved key '" + key + "' is write-protected at " + reservedPointer);
            }
        }
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
}
