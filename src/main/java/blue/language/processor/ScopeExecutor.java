package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;

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
    private boolean drainingInternalEvents;
    private boolean internalEventDrainRequested;
    private int internalEventDrainDeferralDepth;

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
            execution.abortRuntimeFailure(normalizedScope,
                    null,
                    ProcessorErrorCategory.InvalidReservedMarker,
                    execution.fatalReason(ex, "Invalid terminated marker"));
            return;
        }

        while (true) {
            ProcessingMetricsSink metrics = owner.metricsSink();
            long resolvedStart = System.nanoTime();
            FrozenNode scopeNode;
            try {
                scopeNode = runtime.resolvedFrozenAt(normalizedScope);
            } finally {
                metrics.addBundleScopeResolvedLookupNanos(System.nanoTime() - resolvedStart);
            }
            if (scopeNode == null) {
                return;
            }

            bundle = loadBundle(
                    scopeNode,
                    normalizedScope,
                    metrics);
            bundles.put(normalizedScope, bundle);

            String childScope;
            try {
                childScope = nextEmbeddedChildScope(normalizedScope, bundle, processedEmbedded);
            } catch (ProcessorEngine.BoundaryViolationException | IllegalArgumentException ex) {
                execution.abortRuntimeFailure(normalizedScope,
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
            runtime.attachScopeOccurrence(
                    normalizedScope,
                    childScope);
            runtime.setScopeEmbeddedDepth(childScope, runtime.scopeEmbeddedDepth(normalizedScope) + 1);
            FrozenNode selectedChildNode = runtime.selectedFrozenAt(childScope);
            FrozenNode childNode = runtime.resolvedFrozenAt(childScope);
            if (childNode != null) {
                if (!isObjectScope(selectedChildNode) || !isObjectScope(childNode)) {
                    execution.abortRuntimeFailure(normalizedScope,
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

        runtime.chargeInitialization(normalizedScope);
        String documentId;
        try {
            documentId = runtime.calculatePreInitializationScopeContentBlueId(
                    normalizedScope, owner.scopeIdentitySnapshotManager());
        } catch (RuntimeException ex) {
            execution.abortRuntimeFailure(normalizedScope,
                    bundle,
                    ScopeIdentityErrorMapper.from(ex),
                    execution.fatalReason(
                            ex,
                            "Exact scope identity calculation failed"));
            return;
        }
        Node lifecycleEvent = ProcessorEngine.createLifecycleInitiatedEvent(documentId);
        deliverLifecycle(normalizedScope, bundle, lifecycleEvent, false);
        if (finalizeAfterInitialization && !execution.shouldStopScopeWork(normalizedScope)) {
            drainInternalEvents();
        }
        if (!execution.shouldStopScopeWork(normalizedScope)) {
            addInitializationMarker(normalizedScope, documentId);
        }
    }

    /**
     * Executes one externally preselected occurrence without recursively
     * discovering unrelated channels or implicitly initializing the scope.
     */
    void processEvidenceDelivery(String scopePath,
                                 String channelKey,
                                 Node event) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return;
        }
        try {
            if (runtime.hasTerminationMarker(normalizedScope)) {
                runtime.markScopeTerminatedFromMarker(normalizedScope);
                return;
            }
        } catch (IllegalStateException ex) {
            execution.abortRuntimeFailure(
                    normalizedScope,
                    bundles.get(normalizedScope),
                    ProcessorErrorCategory.InvalidReservedMarker,
                    execution.fatalReason(ex, "Invalid terminated marker"));
            return;
        }
        ContractBundle bundle = bundles.get(normalizedScope);
        if (bundle == null) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery scope was not preflighted: "
                            + normalizedScope);
        }
        if (bundle == null) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery scope disappeared: " + normalizedScope);
        }
        ContractBundle.ChannelBinding channel =
                bundle.channelBinding(channelKey);
        if (channel == null
                || ProcessorContractConstants.isProcessorManagedChannel(
                channel.contract())) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery occurrence is not executable at "
                            + normalizedScope + "/" + channelKey);
        }
        channelRunner.runExternalChannel(
                normalizedScope, bundle, channel, event);
        drainInternalEvents();
        channelRunner.persistPendingCheckpoints(normalizedScope);
    }

    ContractBundle externalClassificationBundle(
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded) {
        String normalizedScope =
                ProcessorEngine.normalizeScope(scopePath);
        FrozenNode selected =
                execution.classificationSelectedAt(normalizedScope);
        FrozenNode resolved =
                execution.classificationResolvedAt(normalizedScope);
        if (!isValidParticipatingScope(
                normalizedScope, selected)
                || !isValidParticipatingScope(
                normalizedScope, resolved)) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery scope is absent or not an object: "
                            + normalizedScope);
        }
        return owner.contractLoader().loadExternalClassification(
                selected,
                resolved,
                normalizedScope,
                channelKey,
                includeProcessEmbedded,
                owner.metricsSink(),
                execution.contractRecognitionMeter(),
                includeProcessEmbedded
                        ? "structural-route-header"
                        : "external-channel-header");
    }

    ChannelRunner.ExternalClassification classifyEvidenceDelivery(
            String scopePath,
            String channelKey,
            Node event,
            ContractBundle classificationBundle) {
        String normalizedScope =
                ProcessorEngine.normalizeScope(scopePath);
        ContractBundle.ChannelBinding channel =
                classificationBundle != null
                        ? classificationBundle.channelBinding(
                        channelKey)
                        : null;
        if (channel == null
                || ProcessorContractConstants
                .isProcessorManagedChannel(
                        channel.contract())) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery occurrence is not executable at "
                            + normalizedScope + "/" + channelKey);
        }
        return channelRunner.classifyExternalChannel(
                normalizedScope,
                classificationBundle,
                channel,
                event);
    }

    void processClassifiedEvidenceDelivery(
            ChannelRunner.ExternalClassification classification) {
        if (classification == null
                || !classification.acceptedNew()) {
            return;
        }
        String normalizedScope =
                ProcessorEngine.normalizeScope(
                        classification.scopePath());
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return;
        }
        ContractBundle bundle = bundles.get(normalizedScope);
        if (bundle == null) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery scope was not preflighted: "
                            + normalizedScope);
        }
        ContractBundle.ChannelBinding channel =
                bundle.channelBinding(
                        classification.channelKey());
        if (channel == null
                || ProcessorContractConstants
                .isProcessorManagedChannel(
                        channel.contract())) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery occurrence changed before execution at "
                            + normalizedScope + "/"
                            + classification.channelKey());
        }
        channelRunner.runClassifiedExternalChannel(
                classification);
        drainInternalEvents();
        channelRunner.persistPendingCheckpoints(
                normalizedScope);
    }

    ContractBundle preflightEvidenceScope(String scopePath) {
        return preflightEvidenceScope(scopePath, true);
    }

    ContractBundle preflightEvidenceScopeAfterSelectedHeaders(
            String scopePath) {
        return preflightEvidenceScope(scopePath, false);
    }

    private ContractBundle preflightEvidenceScope(
            String scopePath,
            boolean preflightSelectedHeaders) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        FrozenNode selected = runtime.selectedFrozenAt(normalizedScope);
        try {
            /*
             * Classify directly present headers before effective resolution.
             * Otherwise an unknown direct type with no provider body is
             * misreported as malformed evidence rather than must-understand.
             */
            if (preflightSelectedHeaders) {
                owner.contractLoader().preflightSelectedContractHeaders(
                        selected);
            }
            FrozenNode resolved =
                    runtime.resolvedFrozenAt(normalizedScope);
            if (!isValidParticipatingScope(
                    normalizedScope, selected)
                    || !isValidParticipatingScope(
                    normalizedScope, resolved)) {
                throw new InvalidExecutionEvidenceException(
                        "Participating scope is absent or not an object: "
                                + normalizedScope);
            }
            if (runtime.hasTerminationMarker(normalizedScope)) {
                throw new InvalidExecutionEvidenceException(
                        "Participating scope is directly terminated: "
                                + normalizedScope);
            }
            return refreshBundle(normalizedScope, false);
        } catch (InvalidExecutionEvidenceException exception) {
            throw exception;
        } catch (MustUnderstandFailureException exception) {
            /*
             * The feeder identifies the participating closure; support for
             * every effective contract in that closure is a processor
             * capability question, not malformed feeder evidence.
             */
            throw exception;
        } catch (RuntimeException exception) {
            ProcessorErrorCategory providerCategory =
                    ScopeIdentityErrorMapper.from(exception);
            if (providerCategory
                    == ProcessorErrorCategory.ProviderUnavailable
                    || providerCategory
                    == ProcessorErrorCategory.ProviderBlueIdMismatch) {
                throw exception;
            }
            throw new InvalidExecutionEvidenceException(
                    "Participating scope preflight failed at "
                            + normalizedScope + ": "
                            + ProcessorEngine.deterministicMessage(
                            exception, "unsupported contract"));
        }
    }

    void preflightSelectedHeaders(String scopePath) {
        String normalizedScope =
                ProcessorEngine.normalizeScope(scopePath);
        owner.contractLoader().preflightSelectedContractHeaders(
                runtime.selectedFrozenAt(normalizedScope));
    }

    ContractBundle initializeEvidenceScope(String scopePath) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return null;
        }
        if (runtime.hasTerminationMarker(normalizedScope)) {
            runtime.markScopeTerminatedFromMarker(normalizedScope);
            return null;
        }
        ContractBundle bundle = bundles.get(normalizedScope);
        if (bundle == null) {
            bundle = preflightEvidenceScope(normalizedScope);
        }
        if (runtime.hasInitializationMarker(normalizedScope)) {
            return bundle;
        }
        runtime.chargeInitialization(normalizedScope);
        String documentId = runtime.calculatePreInitializationScopeContentBlueId(
                normalizedScope, owner.scopeIdentitySnapshotManager());
        Node lifecycleEvent =
                ProcessorEngine.createLifecycleInitiatedEvent(documentId);
        deliverLifecycle(normalizedScope, bundle, lifecycleEvent, false);
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return null;
        }
        drainInternalEvents();
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return null;
        }
        addInitializationMarker(normalizedScope, documentId);
        return refreshBundle(normalizedScope);
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
        handlePatches(scopePath, bundle, patches, allowReservedMutation, null);
    }

    void handlePatches(String scopePath,
                       ContractBundle bundle,
                       List<JsonPatch> patches,
                       boolean allowReservedMutation,
                       WorkingDocument.Preview preview) {
        handlePatchInputs(scopePath,
                bundle,
                PatchInput.mutableList(patches),
                allowReservedMutation,
                preview);
    }

    void handlePatchInputs(String scopePath,
                           ContractBundle bundle,
                           List<PatchInput> patches,
                           boolean allowReservedMutation,
                           WorkingDocument.Preview preview) {
        if (execution.shouldStopScopeWork(scopePath)) {
            return;
        }
        if (patches == null || patches.isEmpty()) {
            return;
        }
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     runtime.preparePatchInputSequence(scopePath, patches, preview)) {
            for (int patchIndex = 0; patchIndex < sequence.size(); patchIndex++) {
                PatchInput patch = sequence.patchInputForValidation(patchIndex);
                if (execution.shouldStopScopeWork(scopePath)) {
                    return;
                }
                if (!allowReservedMutation) {
                    runtime.chargeBoundaryCheck();
                }
                try {
                    long boundaryStart = System.nanoTime();
                    validatePatchBoundary(scopePath, bundle, patch);
                    enforceReservedKeyWriteProtection(scopePath, patch, allowReservedMutation);
                    preflightDirectContractMutation(scopePath, patch);
                    owner.metricsSink().addPatchBoundaryNanos(System.nanoTime() - boundaryStart);
                } catch (ProcessorEngine.BoundaryViolationException ex) {
                    execution.abortRuntimeFailure(scopePath,
                            bundle,
                            ProcessorErrorCategory.BoundaryViolation,
                            execution.fatalReason(ex, "Boundary violation"));
                    return;
                } catch (ProcessorFailureException ex) {
                    execution.abortRuntimeFailure(scopePath,
                            bundle,
                            ex.errorCategory(),
                            execution.fatalReason(ex, "Runtime fatal"));
                    return;
                } catch (IllegalArgumentException ex) {
                    execution.abortRuntimeFailure(scopePath,
                            bundle,
                            ProcessorErrorCategory.InvalidPatch,
                            execution.fatalReason(ex, "Boundary violation"));
                    return;
                }
                try {
                    long gasStart = System.nanoTime();
                    runtime.recordPatchSemanticDemands(
                            patch.authoredPath());
                    chargePatchGas(patch);
                    owner.metricsSink().addPatchGasNanos(System.nanoTime() - gasStart);
                    List<DocumentProcessingRuntime.DocumentUpdateData> updates =
                            sequence.applyNext(patchIndex);
                    long routingStart = System.nanoTime();
                    for (DocumentProcessingRuntime.DocumentUpdateData update : updates) {
                        routeDocumentUpdateAfterPatch(scopePath, bundle, update);
                        if (execution.shouldStopScopeWork(scopePath)) {
                            return;
                        }
                    }
                    owner.metricsSink().addDocumentUpdateRoutingNanos(System.nanoTime() - routingStart);
                } catch (ProcessorEngine.BoundaryViolationException ex) {
                    execution.abortRuntimeFailure(scopePath,
                            bundle,
                            ProcessorErrorCategory.BoundaryViolation,
                            execution.fatalReason(ex, "Boundary violation"));
                    return;
                } catch (MustUnderstandFailureException ex) {
                    execution.abortRuntimeFailure(scopePath,
                            bundle,
                            ex.errorCategory(),
                            execution.fatalReason(ex, "Unsupported runtime contract"));
                    return;
                } catch (ProcessorFailureException ex) {
                    execution.abortRuntimeFailure(scopePath,
                            bundle,
                            ex.errorCategory(),
                            execution.fatalReason(ex, "Runtime fatal"));
                    return;
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    execution.abortRuntimeFailure(scopePath,
                            bundle,
                            execution.fatalCategory(ex, ProcessorErrorCategory.InternalProcessorError),
                            execution.fatalReason(ex, "Runtime fatal"));
                    return;
                }
            }
        } catch (GasLimitExceededException
                 | PortableLimitExceededException
                 | SubscriptionSurfaceInvalidException ex) {
            throw ex;
        } catch (RunTerminationException ex) {
            // Root-scope fatal termination is the processor's control-flow signal.
            // Do not reinterpret it as a snapshot-publication failure.
            throw ex;
        } catch (RuntimeException ex) {
            execution.abortRuntimeFailure(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.InternalProcessorError),
                    execution.fatalReason(ex, "Snapshot publication failed"));
        }
    }

    private void chargePatchGas(PatchInput patch) {
        switch (patch.op()) {
            case ADD:
            case REPLACE:
                if (patch.isFrozen()) {
                    runtime.chargeFrozenPatchAddOrReplace(
                            patch.frozenAuthoredCanonicalSizeBytes());
                } else {
                    runtime.chargePatchAddOrReplace(patch.mutableValue());
                }
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
        /*
         * Freeze the participating scope chain before any cascade handler can
         * replace or cut off its source. Object-path ancestors that were never
         * activated through Process Embedded are not receiving scopes.
         */
        List<String> receivingChain =
                freezeDocumentUpdateReceivingChain(data);
        for (String cascadeScope : receivingChain) {
            java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("op", data.op().name().toLowerCase());
            details.put("beforePresent", data.beforePresent());
            details.put("afterPresent", data.afterPresent());
            details.put("sourceScopePath", data.originScope());
            runtime.recordTrace(ProcessingTraceRecord.Kind.DOCUMENT_UPDATE,
                    cascadeScope,
                    null,
                    data.path(),
                    details,
                    null);
        }
        markCutOffChildrenIfNeeded(scopePath, bundle, data);
        List<DocumentUpdateParticipant> participants = new ArrayList<>();
        for (String cascadeScope : receivingChain) {
            if (execution.shouldStopScopeWork(cascadeScope)) {
                continue;
            }
            ContractBundle targetBundle;
            try {
                targetBundle = refreshBundle(cascadeScope);
            } catch (MustUnderstandFailureException ex) {
                if (affectsEmbeddedSubscriptionSurface(
                        cascadeScope, data.path())) {
                    throw new SubscriptionSurfaceInvalidException(
                            execution.fatalReason(
                                    ex,
                                    "Invalid changed Process Embedded surface"),
                            cascadeScope,
                            ProcessorContractConstants.KEY_EMBEDDED);
                }
                execution.abortRuntimeFailure(cascadeScope,
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
            if (execution.shouldStopScopeWork(participant.scopePath)) {
                continue;
            }
            Node updateEvent = ProcessorEngine.createDocumentUpdateEvent(data, participant.scopePath);
            owner.metricsSink().incrementDocumentUpdateEventsBuilt();
            for (ContractBundle.ChannelBinding channel : participant.channels) {
                channelRunner.runHandlers(
                        participant.scopePath,
                        participant.bundle,
                        channel.key(),
                        updateEvent,
                        true);
                if (execution.shouldStopScopeWork(participant.scopePath)) {
                    continue;
                }
            }
        }
    }

    private boolean affectsEmbeddedSubscriptionSurface(
            String scopePath,
            String changedPath) {
        String embeddedPaths = ProcessorEngine.resolvePointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_EMBEDDED
                        + "/paths");
        String normalizedChange =
                PointerUtils.normalizePointer(changedPath);
        return PointerUtils.descendantOrEqual(
                normalizedChange, embeddedPaths)
                || PointerUtils.descendantOrEqual(
                embeddedPaths, normalizedChange);
    }

    private List<String> freezeDocumentUpdateReceivingChain(
            DocumentProcessingRuntime.DocumentUpdateData data) {
        List<String> result = new ArrayList<>();
        String origin =
                ProcessorEngine.normalizeScope(data.originScope());
        for (String candidate : data.cascadeScopes()) {
            String normalized =
                    ProcessorEngine.normalizeScope(candidate);
            boolean isEndpoint = normalized.equals(origin)
                    || "/".equals(normalized);
            if (!isEndpoint && !bundles.containsKey(normalized)) {
                continue;
            }
            /*
             * A lifecycle Handler result is applied while its scope is
             * terminating. Its patches still own their complete synchronous
             * Document Update cascade; only new ordinary Triggered/Embedded
             * deliveries are excluded during termination.
             */
            if (!execution.shouldStopScopeWork(normalized)) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableList(result);
    }

    void deliverLifecycle(String scopePath,
                          ContractBundle bundle,
                          Node event,
                          boolean finalizeAfter) {
        beginInternalEventDrainDeferral();
        try {
            runtime.chargeLifecycleDelivery();
            runtime.recordTrace(ProcessingTraceRecord.Kind.LIFECYCLE,
                    scopePath,
                    null,
                    null,
                    Collections.emptyMap(),
                    event);
            if (bundle == null) {
                return;
            }
            for (ContractBundle.ChannelBinding channel
                    : bundle.channelsOfType(
                    LifecycleChannel.class)) {
                channelRunner.runHandlers(
                        scopePath,
                        bundle,
                        channel.key(),
                        event,
                        true);
                if (execution.shouldStopScopeWork(
                        scopePath)) {
                    break;
                }
            }
        } finally {
            endInternalEventDrainDeferral();
        }
    }

    void deliverTerminationLifecycle(String scopePath,
                                     ContractBundle bundle,
                                     Node event) {
        deliverLifecycle(scopePath, bundle, event, false);
    }

    private ContractBundle refreshBundle(String scopePath) {
        return refreshBundle(scopePath, true);
    }

    private ContractBundle refreshBundle(
            String scopePath,
            boolean preflightSelectedHeaders) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        ProcessingMetricsSink metrics = owner.metricsSink();
        metrics.incrementBundleScopeRefreshes();
        long resolvedStart = System.nanoTime();
        FrozenNode selectedScope = selectedScopeAt(normalizedScope);
        if (preflightSelectedHeaders) {
            owner.contractLoader().preflightSelectedContractHeaders(
                    selectedScope);
        }
        FrozenNode scopeNode;
        try {
            scopeNode = runtime.resolvedFrozenAt(normalizedScope);
        } finally {
            metrics.addBundleScopeResolvedLookupNanos(System.nanoTime() - resolvedStart);
        }
        if (scopeNode == null) {
            bundles.remove(normalizedScope);
            return null;
        }
        ContractBundle refreshed = loadBundle(scopeNode, normalizedScope, metrics);
        bundles.put(normalizedScope, refreshed);
        return refreshed;
    }

    private ContractBundle loadBundle(FrozenNode scopeNode, String normalizedScope, ProcessingMetricsSink metrics) {
        long loadStart = System.nanoTime();
        try {
            FrozenNode selectedScope =
                    selectedScopeAt(normalizedScope);
            FrozenNode recognitionScope = runtime.contractRecognitionScope(
                    selectedScope, scopeNode);
            ContractBundle loaded = owner.contractLoader().load(
                    selectedScope,
                    recognitionScope,
                    normalizedScope,
                    metrics,
                    execution.contractRecognitionMeter(),
                    "participating-contract-header");
            for (EffectiveContractSnapshot snapshot
                    : loaded.effectiveContractSnapshots()) {
                runtime.recordContractSnapshot(snapshot);
            }
            return loaded;
        } finally {
            metrics.addBundleScopeContractLoadNanos(System.nanoTime() - loadStart);
        }
    }

    private FrozenNode selectedScopeAt(String normalizedScope) {
        return runtime.selectedFrozenAt(normalizedScope);
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
                && !node.isReferenceOnly();
    }

    private boolean isValidParticipatingScope(
            String scopePath,
            FrozenNode node) {
        if (node == null || node.isReferenceOnly()) {
            return false;
        }
        return "/".equals(
                ProcessorEngine.normalizeScope(scopePath))
                || isObjectScope(node);
    }

    private void addInitializationMarker(String scopePath, String documentId) {
        FrozenNode marker = ProcessorMarkerFactory.initialized(documentId);
        String pointer = ProcessorEngine.resolvePointer(
                scopePath, ProcessorPointerConstants.RELATIVE_INITIALIZED);
        /*
         * Processor-owned initialization state is a Direct Write. Contracts
         * 1.0 §9.3/C-INIT-05 requires no Document Update for this marker.
         */
        runtime.chargeProcessorMarkerWritten("initialization-marker");
        runtime.directWrite(pointer, marker.toNode());
        runtime.recordTrace(ProcessingTraceRecord.Kind.MARKER_WRITE,
                scopePath,
                ProcessorContractConstants.KEY_INITIALIZED,
                pointer);
    }

    void cleanupCheckpointState() {
        List<String> scopes = new ArrayList<>(bundles.keySet());
        Collections.sort(scopes,
                (left, right) -> {
                    int depth = Integer.compare(
                            JsonPointer.split(right).size(),
                            JsonPointer.split(left).size());
                    return depth != 0
                            ? depth
                            : ExternalOrderKey.compareTextCodePoints(left, right);
                });
        for (String scopePath : scopes) {
            if (execution.shouldStopScopeWork(scopePath)) {
                continue;
            }
            ContractBundle bundle = refreshBundle(scopePath);
            if (bundle != null) {
                channelRunner.cleanupInactiveCheckpoints(scopePath, bundle);
            }
        }
    }

    void requestInternalEventDrain() {
        if (drainingInternalEvents) {
            return;
        }
        internalEventDrainRequested = true;
        if (internalEventDrainDeferralDepth == 0) {
            drainInternalEvents();
        }
    }

    void drainInternalEvents() {
        if (drainingInternalEvents) {
            return;
        }
        if (internalEventDrainDeferralDepth > 0) {
            internalEventDrainRequested = true;
            return;
        }
        internalEventDrainRequested = false;
        boolean quiescent = false;
        drainingInternalEvents = true;
        try {
            while (runtime.hasPendingEventOccurrences()
                    && !execution.hasFailure()
                    && !rootIsCutOff()) {
                EventOccurrence occurrence =
                        runtime.pollEventOccurrence();
                if (occurrence == null) {
                    break;
                }
                runtime.chargeDrainEvent();
                Map<String, Object> details =
                        new java.util.LinkedHashMap<>();
                details.put("drainOwner",
                        "invocation-event-fifo");
                details.put("sourceScopePath",
                        occurrence.source().scopePath());
                runtime.recordTrace(
                        ProcessingTraceRecord.Kind.EVENT_DEQUEUED,
                        occurrence.source().scopePath(),
                        occurrence.emittingContractKey(),
                        null,
                        details,
                        occurrence.event());

                if (occurrence.sourceMode()
                        == EventOccurrence.SourceMode.TRIGGERED
                        && execution.canDeliverOccurrenceLocally(
                        occurrence.source())) {
                    deliverTriggeredOccurrence(occurrence);
                }
                for (ScopeRuntimeContext ancestor
                        : occurrence.frozenAncestors()) {
                    if (execution.canDeliverOccurrenceLocally(
                            ancestor)) {
                        deliverEmbeddedOccurrence(
                                ancestor, occurrence);
                    }
                    if (execution.rootIsTerminated()) {
                        break;
                    }
                }
            }
            quiescent = !runtime.hasPendingEventOccurrences()
                    && !execution.hasFailure();
        } finally {
            drainingInternalEvents = false;
        }
        if (quiescent) {
            execution.completePendingTerminations();
        }
    }

    private void beginInternalEventDrainDeferral() {
        internalEventDrainDeferralDepth++;
    }

    private void endInternalEventDrainDeferral() {
        if (internalEventDrainDeferralDepth <= 0) {
            throw new IllegalStateException(
                    "Internal event drain deferral underflow");
        }
        internalEventDrainDeferralDepth--;
        if (internalEventDrainDeferralDepth == 0
                && internalEventDrainRequested
                && !drainingInternalEvents) {
            drainInternalEvents();
        }
    }

    private boolean rootIsCutOff() {
        ScopeRuntimeContext root =
                runtime.existingScope("/");
        return root != null && root.isCutOff();
    }

    private void deliverTriggeredOccurrence(
            EventOccurrence occurrence) {
        long routingStart = System.nanoTime();
        try {
            String sourcePath =
                    occurrence.source().scopePath();
            ContractBundle currentBundle =
                    refreshBundle(sourcePath);
            List<ContractBundle.ChannelBinding> channels =
                    currentBundle != null
                            ? currentBundle.channelsOfType(
                            TriggeredEventChannel.class)
                            : Collections.emptyList();
            owner.metricsSink()
                    .incrementTriggeredEventsRouted();
            for (ContractBundle.ChannelBinding channel
                    : channels) {
                if (!execution.canDeliverOccurrenceLocally(
                        occurrence.source())) {
                    return;
                }
                TriggeredEventChannel triggered =
                        (TriggeredEventChannel)
                                channel.contract();
                if (!matchesEventPattern(
                        occurrence, triggered.getEvent())) {
                    continue;
                }
                runtime.chargeTriggeredDelivery();
                Map<String, Object> details =
                        new java.util.LinkedHashMap<>();
                details.put("mode", "triggered");
                details.put("sourceScopePath",
                        sourcePath);
                runtime.recordTrace(
                        ProcessingTraceRecord.Kind.EVENT_DELIVERED,
                        sourcePath,
                        channel.key(),
                        null,
                        details,
                        occurrence.event());
                channelRunner.runHandlers(
                        sourcePath,
                        currentBundle,
                        channel.key(),
                        occurrence.event());
            }
        } finally {
            owner.metricsSink()
                    .addTriggeredEventRoutingNanos(
                            System.nanoTime()
                                    - routingStart);
        }
    }

    private void deliverEmbeddedOccurrence(
            ScopeRuntimeContext receivingAncestor,
            EventOccurrence occurrence) {
        String receivingPath =
                receivingAncestor.scopePath();
        String sourcePath =
                ProcessorEngine.relativizePointer(
                        receivingPath,
                        occurrence.source().scopePath());
        Node wrapper = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds
                                .EMBEDDED_EVENT_DELIVERY))
                .properties(
                        "sourcePath",
                        new Node().value(sourcePath))
                .properties(
                        "event",
                        new Node().blueId(
                                occurrence.eventBlueId()));
        ContractBundle currentBundle =
                refreshBundle(receivingPath);
        List<ContractBundle.ChannelBinding> channels =
                currentBundle != null
                        ? currentBundle.channelsOfType(
                        EmbeddedNodeChannel.class)
                        : Collections.emptyList();
        for (ContractBundle.ChannelBinding channel
                : channels) {
            if (!execution.canDeliverOccurrenceLocally(
                    receivingAncestor)) {
                return;
            }
            EmbeddedNodeChannel embedded =
                    (EmbeddedNodeChannel)
                            channel.contract();
            if (!matchesSourcePath(
                    receivingPath,
                    occurrence.source().scopePath(),
                    embedded)
                    || !matchesEventPattern(
                    occurrence, embedded.getEvent())) {
                continue;
            }
            runtime.chargeBridge(wrapper);
            Map<String, Object> details =
                    new java.util.LinkedHashMap<>();
            details.put("mode", "embedded");
            details.put("sourceScopePath",
                    occurrence.source().scopePath());
            details.put("sourcePath", sourcePath);
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.EVENT_DELIVERED,
                    receivingPath,
                    channel.key(),
                    null,
                    details,
                    wrapper);
            channelRunner.runHandlers(
                    receivingPath,
                    currentBundle,
                    channel.key(),
                    wrapper.clone());
        }
    }

    private boolean matchesSourcePath(
            String receivingPath,
            String absoluteSourcePath,
            EmbeddedNodeChannel channel) {
        String configured = channel.getSourcePath();
        if (configured == null) {
            configured = channel.getChildPath();
        }
        return configured == null
                || ProcessorEngine.resolvePointer(
                receivingPath, configured)
                .equals(absoluteSourcePath);
    }

    private boolean matchesEventPattern(
            EventOccurrence occurrence,
            Node pattern) {
        return pattern == null
                || owner.matchingService().matches(
                occurrence.frozenEvent(),
                FrozenNode.fromResolvedNode(pattern));
    }

    private void validatePatchBoundary(String scopePath, ContractBundle bundle, PatchInput patch) {
        if (bundle == null) {
            return;
        }
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        String targetPath = PointerUtils.assertValidRuntimePointer(patch.authoredPath());

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
            if (PointerUtils.strictlyInside(embeddedScope, targetPath)) {
                throw new ProcessorEngine.BoundaryViolationException(
                        "Boundary violation: patch " + targetPath
                                + " is a strict ancestor of embedded scope "
                                + embeddedScope);
            }
        }
    }

    private void preflightDirectContractMutation(
            String scopePath,
            PatchInput patch) {
        if (patch.op() != JsonPatch.Op.ADD
                && patch.op() != JsonPatch.Op.REPLACE) {
            return;
        }
        String contractsPointer = ProcessorEngine.resolvePointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_CONTRACTS);
        List<String> contractsSegments =
                JsonPointer.split(contractsPointer);
        List<String> targetSegments =
                JsonPointer.split(patch.authoredPath());
        FrozenNode value = patch.frozenValue();
        if (value == null && patch.mutableValue() != null) {
            value = FrozenNode.fromResolvedNode(
                    patch.mutableValue());
        }
        if (value == null) {
            return;
        }
        if (targetSegments.equals(contractsSegments)) {
            if (value.getProperties() == null) {
                return;
            }
            for (Map.Entry<String, FrozenNode> entry
                    : value.getProperties().entrySet()) {
                if (!ProcessorContractConstants
                        .RESERVED_CONTRACT_KEYS.contains(
                        entry.getKey())) {
                    owner.contractLoader()
                            .preflightDirectContractHeader(
                            entry.getKey(), entry.getValue());
                }
            }
            return;
        }
        if (targetSegments.size() == contractsSegments.size() + 1
                && targetSegments.subList(
                0, contractsSegments.size()).equals(
                contractsSegments)) {
            String key =
                    targetSegments.get(contractsSegments.size());
            if (!ProcessorContractConstants.RESERVED_CONTRACT_KEYS
                    .contains(key)) {
                owner.contractLoader().preflightDirectContractHeader(
                        key, value);
            }
            return;
        }
        if (targetSegments.size() == contractsSegments.size() + 2
                && targetSegments.subList(
                0, contractsSegments.size()).equals(
                contractsSegments)
                && "type".equals(targetSegments.get(
                targetSegments.size() - 1))) {
            String key =
                    targetSegments.get(contractsSegments.size());
            if (!ProcessorContractConstants.RESERVED_CONTRACT_KEYS
                    .contains(key)) {
                owner.contractLoader().preflightDirectContractHeader(
                        key,
                        FrozenNode.fromResolvedNode(
                                new Node().type(value.toNode())));
            }
        }
    }

    private void enforceReservedKeyWriteProtection(String scopePath,
                                                   PatchInput patch,
                                                   boolean allowReservedMutation) {
        if (allowReservedMutation) {
            return;
        }
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        String targetPath = PointerUtils.assertValidRuntimePointer(patch.authoredPath());
        enforceInlineTypeProtectedStateMutation(
                normalizedScope, targetPath, patch);
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

    private void enforceInlineTypeProtectedStateMutation(
            String scopePath,
            String targetPath,
            PatchInput patch) {
        if ((patch.op() != JsonPatch.Op.ADD
                && patch.op() != JsonPatch.Op.REPLACE)
                || !targetPath.equals(ProcessorEngine.resolvePointer(
                scopePath, "/type"))) {
            return;
        }
        Node authoredContracts = patch.mutableValue() != null
                ? patch.mutableValue().getContracts()
                : null;
        FrozenNode frozenContracts = patch.frozenValue() != null
                ? patch.frozenValue().getContracts()
                : null;
        for (String protectedKey : java.util.Arrays.asList(
                ProcessorContractConstants.KEY_INITIALIZED,
                ProcessorContractConstants.KEY_TERMINATED,
                ProcessorContractConstants.KEY_CHECKPOINT,
                ProcessorContractConstants.KEY_EMBEDDED,
                "generalization")) {
            boolean present = authoredContracts != null
                    && authoredContracts.getProperties() != null
                    && authoredContracts.getProperties().containsKey(
                    protectedKey);
            if (!present) {
                present = frozenContracts != null
                        && frozenContracts.getProperties() != null
                        && frozenContracts.getProperties().containsKey(
                        protectedKey);
            }
            if (present) {
                throw new ProcessorFailureException(
                        ProcessorErrorCategory
                                .ProtectedProcessorStateMutation,
                        "Application type patch contributes protected "
                                + "processor state at "
                                + targetPath + "/contracts/"
                                + JsonPointer.escape(protectedKey));
            }
        }
    }

    private void enforceContractsMapReservedSubtreePreservation(String scopePath, PatchInput patch) {
        if (patch.op() == JsonPatch.Op.REMOVE) {
            for (String key : ProcessorContractConstants.RESERVED_CONTRACT_KEYS) {
                String reservedPointer = ProcessorEngine.resolvePointer(scopePath, ProcessorPointerConstants.relativeContractsEntry(key));
                if (runtime.selectedFrozenAt(reservedPointer) != null) {
                    throw new ProcessorFailureException(ProcessorErrorCategory.ReservedKeyWrite,
                            "Replacing /contracts must preserve reserved key '" + key + "'");
                }
            }
            return;
        }
        Node replacement = patch.mutableValue();
        FrozenNode frozenReplacement = patch.frozenValue();
        for (String key : ProcessorContractConstants.RESERVED_CONTRACT_KEYS) {
            String reservedPointer = ProcessorEngine.resolvePointer(scopePath, ProcessorPointerConstants.relativeContractsEntry(key));
            boolean equal;
            if (patch.isFrozen()) {
                FrozenNode existing = runtime.selectedFrozenAt(
                        reservedPointer);
                if (existing == null) {
                    continue;
                }
                FrozenNode proposed = frozenReplacement != null
                        ? frozenReplacement.property(key)
                        : null;
                equal = semanticallyEqual(existing, proposed);
            } else {
                FrozenNode selected = runtime.selectedFrozenAt(
                        reservedPointer);
                Node existing = selected != null
                        ? selected.toNode()
                        : null;
                if (existing == null) {
                    continue;
                }
                Node proposed = replacement != null && replacement.getProperties() != null
                        ? replacement.getProperties().get(key)
                        : null;
                equal = semanticallyEqual(existing, proposed);
            }
            if (!equal) {
                throw new ProcessorFailureException(ProcessorErrorCategory.ReservedKeyWrite,
                        "Replacing /contracts must preserve reserved key '" + key + "'");
            }
        }
    }

    private boolean semanticallyEqual(FrozenNode left, FrozenNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        // Reserved runtime subtrees can arrive through different construction
        // modes; compare their authored form so preservation checks remain
        // representation-insensitive.
        return BlueIdCalculator.calculateUncheckedBlueId(left.toNode())
                .equals(BlueIdCalculator.calculateUncheckedBlueId(right.toNode()));
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
                if (op == JsonPatch.Op.REPLACE
                        && data.beforePresent()
                        && data.afterPresent()
                        && semanticallyEqual(data.before(), data.after())) {
                    continue;
                }
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
